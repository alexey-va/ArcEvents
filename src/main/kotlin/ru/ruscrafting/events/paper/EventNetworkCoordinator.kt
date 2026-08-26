package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.arc.network.BackendServerId
import ru.arc.network.LeasedNetworkDirectory
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.redis.RedisManager
import ru.arc.redis.safety.OriginBoundRedisBus
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
    @Volatile
    private var nodeDirectoryLeaseMillis: Long = settings().network.heartbeatStaleSeconds * 1_000L
    @Volatile
    private var nodeDirectory = newNodeDirectory(nodeDirectoryLeaseMillis)
    private var eventBus: OriginBoundRedisBus<EventNetworkMessage>? = null
    private val tasks = mutableListOf<ScheduledTask>()
    private val statistics = ConcurrentHashMap<UUID, PlayerEventStats>()
    private val pendingStarts = ConcurrentHashMap<String, CompletableFuture<ReservationStartResult>>()
    private val pendingStartTimeouts = ConcurrentHashMap<String, ScheduledTask>()
    private val startInFlight = AtomicBoolean(false)
    @Volatile
    private var started = false

    fun start() {
        check(!started)
        eventBus = repository.register(
            originAllowed = { origin -> settings().let { origin != it.serverId && origin in it.network.allowedOrigins } },
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
                    is QueueJoinResult.Joined -> sendJoinedMessage(player, current)
                    is QueueJoinResult.Existing -> player.sendMessage(locale.render("queue.already", player))
                    QueueJoinResult.Contended -> player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.contended", player))))
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
                player.sendMessage(locale.render("queue.joined", player, mapOf(
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
                    player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.network", player))))
                    return@runSync
                }
                when (result) {
                    QueueLeaveResult.Left -> {
                        player.sendMessage(locale.render("queue.left", player))
                        refresh()
                    }
                    QueueLeaveResult.Missing -> player.sendMessage(locale.render("queue.not-queued", player))
                    is QueueLeaveResult.Reserved -> player.sendMessage(locale.render("queue.leave-reserved", player))
                    QueueLeaveResult.Contended -> player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.contended", player))))
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
        if (pendingStarts.size >= MAX_PENDING_STARTS) {
            return CompletableFuture.completedFuture(ReservationStartResult.NETWORK_FAILURE)
        }
        val request = EventNetworkMessage.create(
            signal = EventNetworkSignal.START_REQUEST,
            destinationServer = current.hostServer,
        )
        val result = CompletableFuture<ReservationStartResult>()
        pendingStarts[request.eventId] = result
        pendingStartTimeouts[request.eventId] = Tasks.scheduler.runLater(START_REQUEST_TIMEOUT_TICKS) {
            pendingStartTimeouts.remove(request.eventId)
            pendingStarts.remove(request.eventId)?.complete(ReservationStartResult.NETWORK_FAILURE)
        }
        return runCatching {
            repository.publish(request)
            debug.event("start_requested", "origin" to current.serverId, "host" to current.hostServer, "request" to request.eventId)
            result
        }.getOrElse { failure ->
            pendingStartTimeouts.remove(request.eventId)?.cancel()
            pendingStarts.remove(request.eventId)
            plugin.logger.log(Level.WARNING, "ArcEvents start request could not be published", failure)
            CompletableFuture.completedFuture(ReservationStartResult.NETWORK_FAILURE)
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
                    repository.publish(message)
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
            player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.return-transfer", player))))
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
                EventNetworkSignal.START_RESULT -> handleStartResult(message, origin)
                EventNetworkSignal.NODE_PROBE -> repository.publish(
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
                repository.publish(EventNetworkMessage.create(
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

    private fun handleStartResult(message: EventNetworkMessage, origin: String) {
        val current = settings()
        if (origin != current.hostServer || message.destinationServer != current.serverId) return
        val requestId = message.replyTo ?: return
        val result = message.startResult?.let { runCatching { ReservationStartResult.valueOf(it) }.getOrNull() } ?: return
        pendingStartTimeouts.remove(requestId)?.cancel()
        pendingStarts.remove(requestId)?.complete(result)
        debug.event("start_result_received", "host" to origin, "request" to requestId, "result" to result)
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
                player.sendMessage(locale.render("queue.reserved", player))
                if (settings().serverId == destination) {
                    handleJoin(player)
                } else if (settings().network.transferOnReservation && !transferSent(player, destination)) {
                    player.sendMessage(locale.render("command.failed", player, mapOf("reason" to locale.render("reason.transfer", player))))
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
                    player.sendMessage(locale.render("queue.returned", player))
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
            player.sendMessage(locale.render("queue.returned", player))
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
                repository.publish(EventNetworkMessage.create(EventNetworkSignal.QUEUE_CHANGED, queueSize = queueSize))
            }
        }
        refreshNodes()
    }

    private fun refreshNodes() {
        val current = settings()
        val leaseMillis = current.network.heartbeatStaleSeconds * 1_000L
        val directory = directoryFor(leaseMillis)
        repository.loadNodes().whenComplete { loaded, failure ->
            if (failure != null || loaded == null) return@whenComplete
            val now = clock()
            loaded.asSequence()
                .filter { it.heartbeatAtMs <= now + FUTURE_SKEW_MS }
                .forEach { node ->
                    directory.observe(
                        key = node.serverId,
                        origin = node.serverId,
                        value = node,
                        observedAtMillis = node.heartbeatAtMs,
                    )
                }
            nodes = directory.snapshot().map { it.value }.sortedBy(HostNode::serverId)
        }
    }

    @Synchronized
    private fun directoryFor(leaseMillis: Long): LeasedNetworkDirectory<String, HostNode> {
        if (leaseMillis != nodeDirectoryLeaseMillis) {
            nodeDirectoryLeaseMillis = leaseMillis
            nodeDirectory = newNodeDirectory(leaseMillis)
        }
        return nodeDirectory
    }

    private fun newNodeDirectory(leaseMillis: Long): LeasedNetworkDirectory<String, HostNode> =
        LeasedNetworkDirectory(leaseMillis = leaseMillis, maxEntries = MAX_NETWORK_NODES, clock = clock)

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
        eventBus?.close()
        eventBus = null
        nodeDirectory.clear()
        nodes = emptyList()
        pendingStartTimeouts.values.forEach(ScheduledTask::cancel)
        pendingStartTimeouts.clear()
        pendingStarts.values.forEach { it.complete(ReservationStartResult.NETWORK_FAILURE) }
        pendingStarts.clear()
        startInFlight.set(false)
    }

    companion object {
        private const val MESSAGE_MAX_AGE_MS = 15 * 60 * 1_000L
        private const val FUTURE_SKEW_MS = 60_000L
        private const val MAX_PENDING_STARTS = 32
        private const val START_REQUEST_TIMEOUT_TICKS = 12L * 20L
        private const val START_MESSAGE_MAX_AGE_MS = 10_000L
        private const val START_FUTURE_SKEW_MS = 2_000L
        private const val MAX_NETWORK_NODES = 1_024
        private val START_SIGNALS = setOf(EventNetworkSignal.START_REQUEST, EventNetworkSignal.START_RESULT)
    }
}
