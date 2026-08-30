package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.redis.RedisManager
import ru.arc.redis.network.RedisPresenceDirectory
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.network.EventNetworkMessage
import ru.ruscrafting.events.network.EventNetworkSignal
import ru.ruscrafting.events.network.EventRoutePolicy
import ru.ruscrafting.events.network.HostNode
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.QueueJoinResult
import ru.ruscrafting.events.network.QueueLeaveResult
import ru.ruscrafting.events.network.QueueState
import ru.ruscrafting.events.network.RedisEventNetworkRepository
import ru.ruscrafting.events.network.ReservationBatch
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

enum class ReservationStartResult {
    STARTED,
    ARENA_UNAVAILABLE,
    BUSY,
    INSUFFICIENT_PLAYERS,
    RECOVERY_PENDING,
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
    private val onArrival: (QueueEntry) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    @Volatile
    var queueSize: Int = 0
        private set
    @Volatile
    private var nodes: List<HostNode> = emptyList()
    private var nodePresence: RedisPresenceDirectory<HostNode>? = null
    private var nodePresenceLeaseMillis: Long = 0L
    private var eventChannel: RedisRequestReplyChannel<EventNetworkMessage>? = null
    private val tasks = mutableListOf<ScheduledTask>()
    private val statistics = ConcurrentHashMap<UUID, PlayerEventStats>()
    private val startInFlight = AtomicBoolean(false)
    @Volatile
    private var started = false

    fun start() {
        check(!started)
        val initial = settings()
        nodePresenceLeaseMillis = initial.network.heartbeatStaleSeconds * 1_000L
        nodePresence = repository.openNodes(
            originAllowed = { origin -> settings().let { origin == it.serverId || origin in it.network.allowedOrigins } },
            entryAllowed = { node -> node.heartbeatAtMs <= clock() + FUTURE_SKEW_MS },
            leaseMillis = nodePresenceLeaseMillis,
            clockMillis = clock,
        )
        eventChannel = repository.openMessages(
            originAllowed = { origin -> settings().let { origin != it.serverId && origin in it.network.allowedOrigins } },
            replyAllowed = ::replyAllowed,
            onReplyRejected = { reason -> debug.event("network_reply_rejected", "reason" to reason) },
            listener = ::receive,
        )
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
            player.sendEventMessage(locale.render("queue.unavailable", player))
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
                    player.sendEventMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.network", player))))
                    return@runSync
                }
                when (result) {
                    is QueueJoinResult.Joined -> sendJoinedMessage(player, current)
                    is QueueJoinResult.Existing -> player.sendEventMessage(locale.render("queue.already", player))
                    QueueJoinResult.Contended -> player.sendEventMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.contended", player))))
                    null -> Unit
                }
            }
        }
    }

    private fun sendJoinedMessage(player: Player, current: ArcEventsConfig) {
        repository.loadQueuedCount(clock()).whenComplete { authoritativeSize, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.FINE, "ArcEvents queue count refresh failed after join", failure)
                }
                val displaySize = if (failure == null && authoritativeSize != null) {
                    authoritativeSize
                } else {
                    queueSize + 1
                }.coerceIn(1, current.ttt.maximumPlayers)
                player.sendEventMessage(locale.render("queue.joined", player, mapOf(
                    "queue" to locale.text(displaySize),
                    "minimum" to locale.text(current.ttt.minimumPlayers),
                )))
                refresh()
            }
        }
    }

    fun leave(player: Player) {
        repository.leaveQueue(player.uniqueId, clock()).whenComplete { result, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null) {
                    player.sendEventMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.network", player))))
                    return@runSync
                }
                when (result) {
                    QueueLeaveResult.Left -> {
                        player.sendEventMessage(locale.render("queue.left", player))
                        refresh()
                    }
                    QueueLeaveResult.Missing -> player.sendEventMessage(locale.render("queue.not-queued", player))
                    is QueueLeaveResult.Reserved -> player.sendEventMessage(locale.render("queue.leave-reserved", player))
                    QueueLeaveResult.Contended -> player.sendEventMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.contended", player))))
                    null -> Unit
                }
            }
        }
    }

    fun reserveNow(requester: Player? = null): CompletableFuture<ReservationStartResult> {
        if (requester != null) return queueRequesterThenReserve(requester)
        val current = settings()
        return if (current.nodeMode == NodeMode.HOST) reserveOnHost() else requestHostStart(current)
    }

    private fun queueRequesterThenReserve(requester: Player): CompletableFuture<ReservationStartResult> {
        val current = settings()
        if (!hostAvailable()) return CompletableFuture.completedFuture(ReservationStartResult.ARENA_UNAVAILABLE)
        val result = CompletableFuture<ReservationStartResult>()
        repository.joinQueue(
            requester.uniqueId,
            requester.name,
            current.serverId,
            clock(),
            current.network.queueEntrySeconds * 1_000L,
        ).whenComplete { joinResult, failure ->
            Tasks.scheduler.runSync {
                if (!started || failure != null || joinResult == QueueJoinResult.Contended || joinResult == null) {
                    if (failure != null) plugin.logger.log(Level.WARNING, "ArcEvents start requester queue failed", failure)
                    result.complete(ReservationStartResult.NETWORK_FAILURE)
                    return@runSync
                }
                refresh()
                reserveNow().whenComplete { outcome, reserveFailure ->
                    result.complete(if (reserveFailure == null && outcome != null) outcome else ReservationStartResult.NETWORK_FAILURE)
                }
            }
        }
        return result
    }

    private fun requestHostStart(current: ArcEventsConfig): CompletableFuture<ReservationStartResult> {
        if (!hostAvailable()) return CompletableFuture.completedFuture(ReservationStartResult.ARENA_UNAVAILABLE)
        val request = EventNetworkMessage.create(
            signal = EventNetworkSignal.START_REQUEST,
            destinationServer = current.hostServer,
        )
        debug.event("start_requested", "origin" to current.serverId, "host" to current.hostServer, "request" to request.eventId)
        val channel = eventChannel ?: return CompletableFuture.completedFuture(ReservationStartResult.NETWORK_FAILURE)
        return channel.request(request).thenApply { result ->
            when (result) {
                is RedisRequestResult.Reply -> {
                    val outcome = result.message.startResult
                        ?.let { runCatching { ReservationStartResult.valueOf(it) }.getOrNull() }
                        ?: ReservationStartResult.NETWORK_FAILURE
                    debug.event(
                        "start_result_received",
                        "host" to result.originServer,
                        "request" to request.eventId,
                        "result" to outcome,
                    )
                    outcome
                }
                is RedisRequestResult.InfrastructureFailure -> {
                    plugin.logger.log(Level.WARNING, "ArcEvents start request infrastructure failed", result.cause)
                    ReservationStartResult.NETWORK_FAILURE
                }
                else -> ReservationStartResult.NETWORK_FAILURE
            }
        }
    }

    private fun reserveOnHost(): CompletableFuture<ReservationStartResult> {
        val current = settings()
        if (current.nodeMode != NodeMode.HOST) return CompletableFuture.completedFuture(ReservationStartResult.NETWORK_FAILURE)
        if (!arenaReady()) return CompletableFuture.completedFuture(ReservationStartResult.ARENA_UNAVAILABLE)
        if (matchState().first != null) return CompletableFuture.completedFuture(ReservationStartResult.BUSY)
        if (!startInFlight.compareAndSet(false, true)) return CompletableFuture.completedFuture(ReservationStartResult.BUSY)
        val result = CompletableFuture<ReservationStartResult>()
        val matchId = UUID.randomUUID()
        val reservationFuture = runCatching { repository.reserve(
            matchId,
            current.serverId,
            current.ttt.minimumPlayers,
            current.ttt.maximumPlayers,
            clock(),
            current.network.reservationSeconds * 1_000L,
        ) }.getOrElse { failure ->
            startInFlight.set(false)
            plugin.logger.log(Level.WARNING, "ArcEvents roster reservation could not be submitted", failure)
            return CompletableFuture.completedFuture(ReservationStartResult.NETWORK_FAILURE)
        }
        reservationFuture.whenComplete { batch, failure ->
            Tasks.scheduler.runSync {
                startInFlight.set(false)
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
                    releaseReservation(batch)
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
                    publish(message)
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
        repository.claimReservation(player.uniqueId, current.serverId, clock()).whenComplete { entry, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "ArcEvents reservation claim failed", failure)
                    return@runSync
                }
                when (entry?.state) {
                    QueueState.ARRIVED -> {
                        if (current.nodeMode != NodeMode.HOST || !onArrival(entry)) recoverOrphanedArrival(player, entry)
                    }
                    QueueState.MATCHED -> recoverOrphanedArrival(player, entry)
                    QueueState.RETURN_PENDING -> finishPendingReturn(player, entry)
                    else -> Unit
                }
            }
        }
    }

    fun stats(playerId: UUID): PlayerEventStats = statistics[playerId] ?: PlayerEventStats()

    fun queueState(playerId: UUID): CompletableFuture<QueueState?> =
        repository.loadQueueEntry(playerId).thenApply { entry ->
            entry?.takeIf { it.expiresAtMs >= clock() }?.state
        }

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

    fun announceStarted(matchId: UUID) = publish(
        EventNetworkMessage.create(EventNetworkSignal.MATCH_STARTED, matchId = matchId),
    )

    fun announceEnded(matchId: UUID, winner: TttTeam?, reason: MatchEndReason) = publish(
        EventNetworkMessage.create(EventNetworkSignal.MATCH_ENDED, matchId = matchId, winner = winner, endReason = reason),
    )

    fun releaseReservation(
        batch: ReservationBatch,
        playerIds: Collection<UUID> = batch.entries.map { UUID.fromString(it.playerId) },
    ): CompletableFuture<Int> {
        val transition = repository.releaseReservation(batch.matchId, playerIds)
        transition.whenComplete { released, failure ->
            Tasks.scheduler.runSync {
                if (!started) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not preserve return routes for ${batch.matchId}", failure)
                    return@runSync
                }
                released.orEmpty().forEach { entry ->
                    val message = EventNetworkMessage.create(
                        signal = EventNetworkSignal.RETURN_PLAYER,
                        matchId = batch.matchId,
                        playerId = UUID.fromString(entry.playerId),
                        destinationServer = entry.originServer,
                    )
                    publish(message)
                    routeReturn(message)
                }
                refresh()
            }
        }
        return transition.thenApply { it.size }
    }

    fun releaseUnarrived(batch: ReservationBatch, arrivedPlayerIds: Collection<UUID>): CompletableFuture<Int> {
        val arrived = arrivedPlayerIds.toSet()
        val unarrived = batch.entries.map { UUID.fromString(it.playerId) }.filterNot(arrived::contains)
        return if (unarrived.isEmpty()) CompletableFuture.completedFuture(0) else releaseReservation(batch, unarrived)
    }

    fun completeReservation(matchId: UUID, playerIds: Collection<UUID>): CompletableFuture<Unit> {
        val expected = playerIds.distinct().size
        return repository.completeReservation(matchId, playerIds).thenApply { completed ->
            check(completed == expected) { "Completed $completed of $expected ArcEvents arrivals for $matchId" }
        }
    }

    fun returnPlayer(player: Player, originServer: String): Boolean {
        val current = settings()
        if (!current.network.returnToOrigin || originServer == current.serverId) return true
        if (originServer !in current.network.allowedOrigins || !transferSent(player, originServer)) {
            player.sendEventMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.return-transfer", player))))
            return false
        }
        return true
    }

    fun returnRecoveredPlayer(player: Player, recovery: PlayerRecovery) {
        repository.prepareRecoveredReturn(player.uniqueId, recovery.matchId).whenComplete { _, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not preserve recovered return ${player.uniqueId}", failure)
                }
                val current = settings()
                if (!current.network.returnToOrigin || recovery.returnServer == current.serverId) {
                    repository.acknowledgeReturn(player.uniqueId, recovery.matchId)
                    return@runSync
                }
                returnPlayer(player, recovery.returnServer)
            }
        }
    }

    fun hostAvailable(): Boolean {
        val current = settings()
        if (current.nodeMode == NodeMode.HOST && arenaReady() && matchState().first == null) return true
        return nodes.any { it.serverId == current.hostServer && it.available }
    }

    fun nodeSnapshot(): List<HostNode> = nodes

    private fun publish(message: EventNetworkMessage) {
        requireNotNull(eventChannel) { "ArcEvents network coordinator is not started" }.publish(message)
    }

    private fun replyAllowed(request: EventNetworkMessage, reply: EventNetworkMessage, origin: String): Boolean {
        val current = settings()
        val now = clock()
        return request.signal == EventNetworkSignal.START_REQUEST &&
            reply.signal == EventNetworkSignal.START_RESULT &&
            origin == current.hostServer &&
            origin in current.network.allowedOrigins &&
            reply.destinationServer == current.serverId &&
            reply.occurredAtMs >= now - START_MESSAGE_MAX_AGE_MS &&
            reply.occurredAtMs <= now + START_FUTURE_SKEW_MS
    }

    private fun receive(message: EventNetworkMessage, origin: String) {
        if (!started) return
        val current = settings()
        val now = clock()
        if (origin !in current.network.allowedOrigins || origin == current.serverId) return
        if (message.occurredAtMs < now - MESSAGE_MAX_AGE_MS || message.occurredAtMs > now + FUTURE_SKEW_MS) return
        if (message.signal in START_SIGNALS &&
            (message.occurredAtMs < now - START_MESSAGE_MAX_AGE_MS || message.occurredAtMs > now + START_FUTURE_SKEW_MS)
        ) return
        Tasks.scheduler.runSync {
            if (!started) return@runSync
            when (message.signal) {
                EventNetworkSignal.QUEUE_CHANGED -> {
                    queueSize = message.queueSize ?: queueSize
                    refreshNodes()
                }
                EventNetworkSignal.ROUTE_PLAYER -> route(message)
                EventNetworkSignal.RETURN_PLAYER -> routeReturn(message)
                EventNetworkSignal.START_REQUEST -> handleStartRequest(message, origin)
                EventNetworkSignal.START_RESULT -> Unit
                EventNetworkSignal.NODE_PROBE -> publish(
                    EventNetworkMessage.create(EventNetworkSignal.NODE_ACK, replyTo = message.eventId),
                )
                EventNetworkSignal.NODE_ACK -> debug.event("node_ack", "origin" to origin, "reply" to message.replyTo)
                EventNetworkSignal.MATCH_STARTED, EventNetworkSignal.MATCH_ENDED -> refreshNodes()
            }
        }
    }

    private fun handleStartRequest(message: EventNetworkMessage, origin: String) {
        val current = settings()
        if (current.nodeMode != NodeMode.HOST || message.destinationServer != current.serverId) return
        reserveOnHost().whenComplete { result, failure ->
            if (!started) return@whenComplete
            val outcome = if (failure == null && result != null) result else ReservationStartResult.NETWORK_FAILURE
            runCatching {
                publish(EventNetworkMessage.create(
                    signal = EventNetworkSignal.START_RESULT,
                    destinationServer = origin,
                    replyTo = message.eventId,
                    startResult = outcome.name,
                ))
            }.onSuccess {
                debug.event("start_request_resolved", "origin" to origin, "request" to message.eventId, "result" to outcome)
            }.onFailure { publishFailure ->
                plugin.logger.log(Level.WARNING, "ArcEvents start result could not be published", publishFailure)
            }
        }
    }

    private fun route(message: EventNetworkMessage) {
        val playerId = message.playerId?.let(UUID::fromString) ?: return
        val matchId = message.matchId?.let(UUID::fromString) ?: return
        val destination = message.destinationServer ?: return
        repository.loadQueueEntry(playerId).whenComplete { entry, failure ->
            Tasks.scheduler.runSync {
                if (!started) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "ArcEvents route authorization failed for $playerId", failure)
                    return@runSync
                }
                if (!EventRoutePolicy.toMatch(entry, playerId, matchId, destination, clock())) {
                    debug.event("route_rejected", "player" to playerId, "match" to matchId, "destination" to destination)
                    return@runSync
                }
                val player = plugin.server.getPlayer(playerId) ?: return@runSync
                player.sendEventMessage(locale.render("queue.reserved", player))
                if (settings().serverId == destination) {
                    handleJoin(player)
                } else if (settings().network.transferOnReservation && !transferSent(player, destination)) {
                    player.sendEventMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.transfer", player))))
                }
            }
        }
    }

    private fun routeReturn(message: EventNetworkMessage) {
        val playerId = message.playerId?.let(UUID::fromString) ?: return
        val matchId = message.matchId?.let(UUID::fromString) ?: return
        val origin = message.destinationServer ?: return
        repository.loadQueueEntry(playerId).whenComplete { entry, failure ->
            Tasks.scheduler.runSync {
                if (!started) return@runSync
                if (failure != null) {
                    plugin.logger.log(Level.WARNING, "ArcEvents return authorization failed for $playerId", failure)
                    return@runSync
                }
                if (!EventRoutePolicy.toOrigin(entry, playerId, matchId, origin)) {
                    debug.event("return_route_rejected", "player" to playerId, "match" to matchId, "origin" to origin)
                    return@runSync
                }
                val player = plugin.server.getPlayer(playerId) ?: return@runSync
                if (settings().serverId == origin || !settings().network.returnToOrigin) {
                    player.sendEventMessage(locale.render("queue.returned", player))
                    repository.acknowledgeReturn(playerId, matchId).whenComplete { acknowledged, acknowledgeFailure ->
                        if (acknowledgeFailure != null || acknowledged != true) {
                            plugin.logger.log(
                                Level.WARNING,
                                "ArcEvents could not acknowledge returned player $playerId for $matchId",
                                acknowledgeFailure,
                            )
                        }
                    }
                } else {
                    returnPlayer(player, origin)
                }
            }
        }
    }

    private fun recoverOrphanedArrival(player: Player, entry: QueueEntry) {
        repository.markReturnPending(entry).whenComplete { pending, failure ->
            Tasks.scheduler.runSync {
                if (!started || !player.isOnline) return@runSync
                if (failure != null || pending == null) {
                    plugin.logger.log(Level.SEVERE, "ArcEvents could not recover orphaned arrival ${entry.playerId}", failure)
                    return@runSync
                }
                finishPendingReturn(player, pending)
            }
        }
    }

    private fun finishPendingReturn(player: Player, entry: QueueEntry) {
        val matchId = UUID.fromString(requireNotNull(entry.matchId))
        if (settings().serverId == entry.originServer || !settings().network.returnToOrigin) {
            player.sendEventMessage(locale.render("queue.returned", player))
            repository.acknowledgeReturn(player.uniqueId, matchId).whenComplete { acknowledged, failure ->
                if (failure != null || acknowledged != true) {
                    plugin.logger.log(Level.WARNING, "ArcEvents could not acknowledge returned player ${player.uniqueId} for $matchId", failure)
                }
            }
        } else {
            returnPlayer(player, entry.originServer)
        }
    }

    private fun transferSent(player: Player, destination: String): Boolean =
        transfer.connect(player, BackendServerId.of(destination)) == BackendTransferResult.SENT

    private fun maintain() {
        if (!started) return
        val now = clock()
        repository.cleanup(now)
        if (!redis.isSubscriptionActive()) redis.init()
        refresh()
        heartbeat()
    }

    private fun refresh() {
        repository.loadQueue(clock()).whenComplete { queue, failure ->
            if (failure == null && queue != null) {
                queueSize = queue.count { it.state == ru.ruscrafting.events.network.QueueState.QUEUED }
                publish(EventNetworkMessage.create(EventNetworkSignal.QUEUE_CHANGED, queueSize = queueSize))
            }
        }
        refreshNodes()
    }

    private fun refreshNodes() {
        val current = settings()
        val leaseMillis = current.network.heartbeatStaleSeconds * 1_000L
        val presence = requireNotNull(nodePresence)
        if (leaseMillis != nodePresenceLeaseMillis) {
            nodePresenceLeaseMillis = leaseMillis
            presence.updateLeaseMillis(leaseMillis)
        }
        presence.refresh().whenComplete { refreshed, failure ->
            if (failure != null || refreshed == null) return@whenComplete
            nodes = refreshed.values.sortedBy(HostNode::serverId)
            if (refreshed.rejected.isNotEmpty()) {
                debug.event("node_presence_rejected", "reasons" to refreshed.rejected)
            }
        }
    }

    /** Constant-time in-memory gauge safe for runtime health sampling. */
    fun activeLeaseCount(): Int = nodePresence?.activeLeaseCount() ?: 0

    private fun heartbeat() {
        val current = settings()
        val (matchId, phase) = matchState()
        val arenaReady = arenaReady()
        requireNotNull(nodePresence).publish(
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
        eventChannel?.close()
        eventChannel = null
        nodePresence?.close()
        nodePresence = null
        nodes = emptyList()
        startInFlight.set(false)
    }

    companion object {
        private const val MESSAGE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val FUTURE_SKEW_MS = 60_000L
        private const val START_MESSAGE_MAX_AGE_MS = 10_000L
        private const val START_FUTURE_SKEW_MS = 2_000L
        private val START_SIGNALS = setOf(EventNetworkSignal.START_REQUEST, EventNetworkSignal.START_RESULT)
    }
}
