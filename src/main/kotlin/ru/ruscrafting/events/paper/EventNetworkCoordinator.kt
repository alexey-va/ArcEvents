package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisManager
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.network.EventNetworkMessage
import ru.ruscrafting.events.network.EventNetworkSignal
import ru.ruscrafting.events.network.HostNode
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.QueueJoinResult
import ru.ruscrafting.events.network.QueueLeaveResult
import ru.ruscrafting.events.network.RedisEventNetworkRepository
import ru.ruscrafting.events.network.ReservationBatch
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

enum class ReservationStartResult {
    STARTED,
    HOST_ONLY,
    ARENA_UNAVAILABLE,
    BUSY,
    INSUFFICIENT_PLAYERS,
    NETWORK_FAILURE,
}

class EventNetworkCoordinator(
    private val plugin: Plugin,
    private val settings: () -> ArcEventsConfig,
    private val locale: ArcEventsLocale,
    private val repository: RedisEventNetworkRepository,
    private val redis: RedisManager,
    private val transfer: BackendTransfer,
    private val debug: ArcEventsDebug,
    private val matchState: () -> Pair<UUID?, MatchPhase?>,
    private val arenaReady: () -> Boolean,
    private val onReservation: (ReservationBatch) -> Boolean,
    private val onArrival: (QueueEntry) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    @Volatile
    var queueSize: Int = 0
        private set
    @Volatile
    private var nodes: List<HostNode> = emptyList()
    private var listener: ChannelListener? = null
    private val tasks = mutableListOf<ScheduledTask>()
    private val seen = ConcurrentHashMap<String, Long>()
    private val statistics = ConcurrentHashMap<UUID, PlayerEventStats>()
    @Volatile
    private var started = false

    fun start() {
        check(!started)
        listener = repository.register(::receive)
        started = true
        refresh()
        heartbeat()
        val heartbeatTicks = settings().network.heartbeatSeconds * 20L
        tasks += Tasks.scheduler.runTimer(heartbeatTicks, heartbeatTicks) { maintain() }
        redis.init()
        debug.event("network_started", "server" to settings().serverId, "mode" to settings().nodeMode)
    }

    fun join(player: Player) {
        val current = settings()
        if (!hostAvailable()) {
            player.sendMessage(locale.render("queue.unavailable", player))
            return
        }
        repository.joinQueue(
            player.uniqueId,
            player.name,
            current.serverId,
            clock(),
            current.network.queueEntrySeconds * 1_000L,
        ).whenComplete { result, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "ArcEvents queue join failed", failure)
                    player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.network", player))))
                    return@runSync
                }
                when (result) {
                    is QueueJoinResult.Joined -> {
                        refresh()
                        player.sendMessage(locale.render("queue.joined", player, mapOf(
                            "queue" to locale.text((queueSize + 1).coerceAtMost(current.ttt.maximumPlayers)),
                            "minimum" to locale.text(current.ttt.minimumPlayers),
                        )))
                    }
                    is QueueJoinResult.Existing -> player.sendMessage(locale.render("queue.already", player))
                    QueueJoinResult.Contended -> player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.contended", player))))
                    null -> Unit
                }
            }
        }
    }

    fun leave(player: Player) {
        repository.leaveQueue(player.uniqueId, clock()).whenComplete { result, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null) {
                    player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.network", player))))
                    return@runSync
                }
                when (result) {
                    QueueLeaveResult.Left -> {
                        player.sendMessage(locale.render("queue.left", player))
                        refresh()
                    }
                    QueueLeaveResult.Missing -> player.sendMessage(locale.render("queue.not-queued", player))
                    is QueueLeaveResult.Reserved -> player.sendMessage(locale.render("queue.already", player))
                    QueueLeaveResult.Contended -> player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.contended", player))))
                    null -> Unit
                }
            }
        }
    }

    fun reserveNow(): CompletableFuture<ReservationStartResult> {
        val current = settings()
        if (current.nodeMode != NodeMode.HOST) return CompletableFuture.completedFuture(ReservationStartResult.HOST_ONLY)
        if (!arenaReady()) return CompletableFuture.completedFuture(ReservationStartResult.ARENA_UNAVAILABLE)
        if (matchState().first != null) return CompletableFuture.completedFuture(ReservationStartResult.BUSY)
        val result = CompletableFuture<ReservationStartResult>()
        val matchId = UUID.randomUUID()
        repository.reserve(
            matchId,
            current.serverId,
            current.ttt.minimumPlayers,
            current.ttt.maximumPlayers,
            clock(),
            current.network.reservationSeconds * 1_000L,
        ).whenComplete { batch, failure ->
            Tasks.scheduler.runSync {
                if (!started) {
                    result.complete(ReservationStartResult.NETWORK_FAILURE)
                    return@runSync
                }
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "ArcEvents roster reservation failed", failure)
                    result.complete(ReservationStartResult.NETWORK_FAILURE)
                    return@runSync
                }
                if (batch == null) {
                    result.complete(ReservationStartResult.INSUFFICIENT_PLAYERS)
                    return@runSync
                }
                if (!onReservation(batch)) {
                    releaseReservation(batch.matchId)
                    result.complete(ReservationStartResult.BUSY)
                    return@runSync
                }
                batch.entries.forEach { entry ->
                    val message = EventNetworkMessage.create(
                        signal = EventNetworkSignal.ROUTE_PLAYER,
                        matchId = batch.matchId,
                        playerId = UUID.fromString(entry.playerId),
                        destinationServer = current.serverId,
                    )
                    repository.publish(message)
                    route(message)
                }
                refresh()
                result.complete(ReservationStartResult.STARTED)
            }
        }
        return result
    }

    fun handleJoin(player: Player) {
        loadStats(player.uniqueId)
        val current = settings()
        if (current.nodeMode != NodeMode.HOST) return
        repository.claimReservation(player.uniqueId, current.serverId, clock()).whenComplete { entry, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "ArcEvents reservation claim failed", failure)
                    return@runSync
                }
                entry?.let(onArrival)
            }
        }
    }

    fun stats(playerId: UUID): PlayerEventStats = statistics[playerId] ?: PlayerEventStats()

    fun loadStats(playerId: UUID) {
        repository.loadStats(playerId).whenComplete { value, failure ->
            if (failure == null && value != null) statistics[playerId] = value
        }
    }

    fun recordStats(playerId: UUID, transform: (PlayerEventStats) -> PlayerEventStats) {
        repository.updateStats(playerId, transform).whenComplete { update, failure ->
            if (failure == null && update != null) statistics[playerId] = update.after
            else if (failure != null) plugin.logger.log(Level.WARNING, "ArcEvents statistics update failed for $playerId", failure)
        }
    }

    fun announceStarted(matchId: UUID) = repository.publish(
        EventNetworkMessage.create(EventNetworkSignal.MATCH_STARTED, matchId = matchId),
    )

    fun announceEnded(matchId: UUID, winner: TttTeam?, reason: MatchEndReason) = repository.publish(
        EventNetworkMessage.create(EventNetworkSignal.MATCH_ENDED, matchId = matchId, winner = winner, endReason = reason),
    )

    fun releaseReservation(matchId: UUID) {
        val current = settings()
        repository.releaseReservation(matchId, clock(), current.network.queueEntrySeconds * 1_000L)
    }

    fun returnPlayer(player: Player, originServer: String): Boolean {
        val current = settings()
        if (!current.network.returnToOrigin || originServer == current.serverId) return true
        if (originServer !in current.network.allowedOrigins || !transfer.connect(player, originServer)) {
            player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.return-transfer", player))))
            return false
        }
        return true
    }

    fun hostAvailable(): Boolean {
        val current = settings()
        if (current.nodeMode == NodeMode.HOST && arenaReady() && matchState().first == null) return true
        return nodes.any { it.serverId == current.hostServer && it.available }
    }

    fun nodeSnapshot(): List<HostNode> = nodes

    private fun receive(message: EventNetworkMessage, origin: String) {
        if (!started) return
        val current = settings()
        val now = clock()
        if (origin !in current.network.allowedOrigins || origin == current.serverId) return
        if (message.occurredAtMs < now - MESSAGE_MAX_AGE_MS || message.occurredAtMs > now + FUTURE_SKEW_MS) return
        if (seen.size >= MAX_SEEN_MESSAGES || seen.putIfAbsent(message.eventId, now) != null) return
        Tasks.scheduler.runSync {
            if (!started) return@runSync
            when (message.signal) {
                EventNetworkSignal.QUEUE_CHANGED -> {
                    queueSize = message.queueSize ?: queueSize
                    refreshNodes()
                }
                EventNetworkSignal.ROUTE_PLAYER -> route(message)
                EventNetworkSignal.NODE_PROBE -> repository.publish(
                    EventNetworkMessage.create(EventNetworkSignal.NODE_ACK, replyTo = message.eventId),
                )
                EventNetworkSignal.NODE_ACK -> debug.event("node_ack", "origin" to origin, "reply" to message.replyTo)
                EventNetworkSignal.MATCH_STARTED, EventNetworkSignal.MATCH_ENDED -> refreshNodes()
            }
        }
    }

    private fun route(message: EventNetworkMessage) {
        val playerId = message.playerId?.let(UUID::fromString) ?: return
        val player = plugin.server.getPlayer(playerId) ?: return
        val destination = message.destinationServer ?: return
        player.sendMessage(locale.render("queue.reserved", player))
        if (settings().serverId == destination) {
            handleJoin(player)
        } else if (settings().network.transferOnReservation) {
            if (!transfer.connect(player, destination)) {
                player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.transfer", player))))
            }
        }
    }

    private fun maintain() {
        if (!started) return
        val now = clock()
        seen.entries.removeIf { it.value < now - MESSAGE_MAX_AGE_MS }
        repository.cleanup(now)
        if (!redis.isSubscriptionActive()) redis.init()
        refresh()
        heartbeat()
    }

    private fun refresh() {
        repository.loadQueue(clock()).whenComplete { queue, failure ->
            if (failure == null && queue != null) {
                queueSize = queue.count { it.state == ru.ruscrafting.events.network.QueueState.QUEUED }
                repository.publish(EventNetworkMessage.create(EventNetworkSignal.QUEUE_CHANGED, queueSize = queueSize))
            }
        }
        refreshNodes()
    }

    private fun refreshNodes() {
        val current = settings()
        repository.loadNodes(clock(), current.network.heartbeatStaleSeconds * 1_000L).whenComplete { loaded, failure ->
            if (failure == null && loaded != null) nodes = loaded
        }
    }

    private fun heartbeat() {
        val current = settings()
        val (matchId, phase) = matchState()
        val arenaReady = arenaReady()
        repository.saveNode(
            HostNode(
                serverId = current.serverId,
                mode = current.nodeMode.name,
                available = current.nodeMode == NodeMode.HOST && arenaReady && matchId == null,
                arenaReady = arenaReady,
                phase = phase,
                matchId = matchId?.toString(),
                queueSize = queueSize,
                capacity = if (current.nodeMode == NodeMode.HOST) current.ttt.maximumPlayers else 0,
                heartbeatAtMs = clock(),
            ),
        )
    }

    override fun close() {
        started = false
        tasks.forEach(ScheduledTask::cancel)
        tasks.clear()
        listener?.let(repository::unregister)
        listener = null
        seen.clear()
    }

    companion object {
        private const val MESSAGE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val FUTURE_SKEW_MS = 60_000L
        private const val MAX_SEEN_MESSAGES = 4_096
    }
}
