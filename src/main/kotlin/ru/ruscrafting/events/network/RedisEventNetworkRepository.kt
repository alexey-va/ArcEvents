package ru.ruscrafting.events.network

import com.google.gson.Gson
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisPresenceDirectory
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.RedisReplyRejection
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.redis.network.ArcTaskRedisRequestTimeoutScheduler
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashConsumeResult
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.EventMode
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RedisEventNetworkRepository(
    private val redis: RedisOperations,
    gson: Gson = Gson(),
) {
    private data class ReservedCandidate(
        val queued: QueueEntry,
        val reserved: QueueEntry,
    )

    private val queueCodec = codec(gson, QueueEntry::class.java, QUEUE_FIELDS, QUEUE_REQUIRED_FIELDS, QueueEntry::validated)
    private val nodeCodec = codec(gson, HostNode::class.java, NODE_FIELDS, NODE_REQUIRED_FIELDS, HostNode::validated)
    private val statsCodec = codec(gson, PlayerEventStats::class.java, STATS_FIELDS, STATS_REQUIRED_FIELDS, PlayerEventStats::validated)
    private val messageCodec = codec(
        gson,
        EventNetworkMessage::class.java,
        MESSAGE_FIELDS,
        MESSAGE_REQUIRED_FIELDS,
        EventNetworkMessage::validated,
    )
    private val queue = RedisHashUpdater(redis, QUEUE_KEY, queueCodec, MAX_CAS_ATTEMPTS)
    private val stats = RedisHashUpdater(redis, STATS_KEY, statsCodec, MAX_CAS_ATTEMPTS)

    fun openMessages(
        originAllowed: (String) -> Boolean,
        replyAllowed: (request: EventNetworkMessage, reply: EventNetworkMessage, origin: String) -> Boolean,
        timeoutScheduler: RedisRequestTimeoutScheduler = ArcTaskRedisRequestTimeoutScheduler,
        onReplyRejected: (RedisReplyRejection) -> Unit = {},
        listener: (EventNetworkMessage, String) -> Unit,
    ): RedisRequestReplyChannel<EventNetworkMessage> = RedisRequestReplyChannel(
        redis = redis,
        channel = EVENT_CHANNEL,
        codec = messageCodec,
        originAllowed = originAllowed,
        requestId = EventNetworkMessage::eventId,
        replyTo = EventNetworkMessage::replyTo,
        replyAllowed = replyAllowed,
        timeoutMillis = START_REQUEST_TIMEOUT_MS,
        maxPending = MAX_PENDING_STARTS,
        timeoutScheduler = timeoutScheduler,
        replay = RedisReplayPolicy(EventNetworkMessage::eventId, MESSAGE_DEDUPLICATION_MS, MAX_SEEN_MESSAGES),
        onMessage = listener,
        onReplyRejected = onReplyRejected,
    )

    fun openNodes(
        originAllowed: (String) -> Boolean,
        entryAllowed: (HostNode) -> Boolean,
        leaseMillis: Long,
        clockMillis: () -> Long = System::currentTimeMillis,
    ): RedisPresenceDirectory<HostNode> = RedisPresenceDirectory(
        redis = redis,
        hashKey = NODES_KEY,
        codec = nodeCodec,
        entryId = HostNode::serverId,
        origin = HostNode::serverId,
        observedAtMillis = HostNode::heartbeatAtMs,
        originAllowed = originAllowed,
        entryAllowed = entryAllowed,
        leaseMillis = leaseMillis,
        maxEntries = MAX_NETWORK_NODES,
        clockMillis = clockMillis,
    )

    fun joinQueue(
        playerId: UUID,
        playerName: String,
        originServer: String,
        nowMs: Long,
        lifetimeMs: Long,
    ): CompletableFuture<QueueJoinResult> {
        val field = playerId.toString()
        return queue.update(field) { current ->
            if (current != null && current.expiresAtMs >= nowMs) {
                val refreshed = if (
                    current.state == QueueState.QUEUED &&
                    (current.playerName != playerName || current.originServer != originServer)
                ) current.copy(playerName = playerName, originServer = originServer).validated()
                else current
                RedisHashDecision.Write(refreshed)
            } else {
                RedisHashDecision.Write(
                    QueueEntry(
                        playerId = field,
                        playerName = playerName,
                        originServer = originServer,
                        joinedAtMs = nowMs,
                        expiresAtMs = nowMs + lifetimeMs,
                    ).validated(),
                )
            }
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> {
                    val after = requireNotNull(result.after)
                    if (result.before == null || requireNotNull(result.before).expiresAtMs < nowMs) QueueJoinResult.Joined(after)
                    else QueueJoinResult.Existing(after)
                }
                is RedisHashUpdateResult.Unchanged -> QueueJoinResult.Existing(result.current)
                is RedisHashUpdateResult.Rejected -> QueueJoinResult.Contended
                is RedisHashUpdateResult.Contended -> QueueJoinResult.Contended
            }
        }
    }

    fun leaveQueue(playerId: UUID, nowMs: Long): CompletableFuture<QueueLeaveResult> =
        queue.update(playerId.toString()) { current ->
            when {
                current == null -> RedisHashDecision.Reject
                current.expiresAtMs < nowMs -> RedisHashDecision.Delete
                current.state != QueueState.QUEUED -> RedisHashDecision.Write(current)
                else -> RedisHashDecision.Delete
            }
        }.thenApply { result ->
            when (result) {
                is RedisHashUpdateResult.Changed -> {
                    val before = requireNotNull(result.before)
                    if (before.expiresAtMs < nowMs) QueueLeaveResult.Missing else QueueLeaveResult.Left
                }
                is RedisHashUpdateResult.Unchanged -> QueueLeaveResult.Reserved(result.current)
                is RedisHashUpdateResult.Rejected -> result.current?.let(QueueLeaveResult::Reserved) ?: QueueLeaveResult.Missing
                is RedisHashUpdateResult.Contended -> QueueLeaveResult.Contended
            }
        }

    fun loadQueue(nowMs: Long): CompletableFuture<List<QueueEntry>> = redis.loadMap(QUEUE_KEY).thenApply { values ->
        values.values.map(queueCodec::decode)
            .filter { it.expiresAtMs >= nowMs }
            .sortedWith(compareBy<QueueEntry> { it.joinedAtMs }.thenBy { it.playerId })
    }

    fun loadQueuedCount(nowMs: Long): CompletableFuture<Int> =
        loadQueue(nowMs).thenApply { entries -> entries.count { it.state == QueueState.QUEUED } }

    fun loadQueueEntry(playerId: UUID): CompletableFuture<QueueEntry?> =
        redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenApply { values -> values.firstOrNull()?.let(queueCodec::decode) }

    fun reserve(
        matchId: UUID,
        destinationServer: String,
        minimum: Int,
        maximum: Int,
        nowMs: Long,
        reservationMs: Long,
        requiredOwnerId: UUID? = null,
        requiredOwnerOrigin: String? = null,
        mode: String = EventMode.TTT.id,
    ): CompletableFuture<ReservationBatch?> = loadQueue(nowMs).thenCompose { entries ->
        require(EventMode.fromId(mode) != null) { "Unsupported event mode" }
        val candidates = entries.filter { it.state == QueueState.QUEUED }.take(maximum)
        val owner = candidates.firstOrNull()
        if (requiredOwnerId != null && (
                owner == null || owner.playerId != requiredOwnerId.toString() ||
                    requiredOwnerOrigin != null && owner.originServer != requiredOwnerOrigin
                )
        ) {
            return@thenCompose CompletableFuture.completedFuture(null)
        }
        if (candidates.size < minimum) CompletableFuture.completedFuture(null)
        else reserveCandidates(
            matchId,
            destinationServer,
            candidates,
            minimum,
            nowMs,
            reservationMs,
            requiredOwnerId,
            requiredOwnerOrigin,
            mode,
            0,
            emptyList(),
        )
    }

    fun claimReservation(playerId: UUID, currentServer: String, nowMs: Long): CompletableFuture<QueueEntry?> =
        queue.update(playerId.toString()) { entry ->
            when (entry?.state) {
                QueueState.RESERVED -> if (entry.destinationServer == currentServer && entry.expiresAtMs >= nowMs) {
                    RedisHashDecision.Write(entry.copy(state = QueueState.ARRIVED, expiresAtMs = Long.MAX_VALUE).validated())
                } else RedisHashDecision.Reject
                QueueState.ARRIVED -> if (entry.destinationServer == currentServer) RedisHashDecision.Write(entry) else RedisHashDecision.Reject
                QueueState.MATCHED, QueueState.RETURN_PENDING -> if (
                    currentServer == entry.originServer || currentServer == entry.destinationServer
                ) RedisHashDecision.Write(entry) else RedisHashDecision.Reject
                QueueState.QUEUED, null -> RedisHashDecision.Reject
            }
        }.thenApply { it.acceptedValue() }

    fun releaseReservation(matchId: UUID, playerIds: Collection<UUID>? = null): CompletableFuture<List<QueueEntry>> =
        redis.loadMap(QUEUE_KEY).thenCompose { values ->
            val selectedIds = playerIds?.map(UUID::toString)?.toSet()
            val matching = values.values.map(queueCodec::decode).filter { entry ->
                entry.matchId == matchId.toString() && entry.state in ROUTED_STATES &&
                    (selectedIds == null || entry.playerId in selectedIds)
            }
            val changes = matching.map { entry -> markMatchReturnPending(UUID.fromString(entry.playerId), matchId) }
            CompletableFuture.allOf(*changes.toTypedArray()).thenApply { changes.mapNotNull(CompletableFuture<QueueEntry?>::join) }
        }

    fun completeReservation(matchId: UUID, playerIds: Collection<UUID>): CompletableFuture<Int> {
        val completions = playerIds.distinct().map { playerId -> completeArrival(matchId, playerId) }
        return CompletableFuture.allOf(*completions.toTypedArray()).thenApply { completions.count { it.join() } }
    }

    fun markReturnPending(entry: QueueEntry): CompletableFuture<QueueEntry?> =
        markReturnPending(UUID.fromString(entry.playerId), UUID.fromString(requireNotNull(entry.matchId)))

    fun prepareRecoveredReturn(playerId: UUID, matchId: UUID): CompletableFuture<QueueEntry?> =
        markReturnPending(playerId, matchId)

    fun acknowledgeReturn(playerId: UUID, matchId: UUID): CompletableFuture<Boolean> =
        queue.update(playerId.toString()) { entry ->
            when {
                entry == null -> RedisHashDecision.Reject
                entry.state == QueueState.RETURN_PENDING && entry.matchId == matchId.toString() -> RedisHashDecision.Delete
                else -> RedisHashDecision.Reject
            }
        }.thenApply { result ->
            result is RedisHashUpdateResult.Changed || result is RedisHashUpdateResult.Rejected && result.current == null
        }

    fun cleanup(nowMs: Long): CompletableFuture<Int> = redis.loadMap(QUEUE_KEY).thenCompose { values ->
        val expired = values.entries.mapNotNull { (field, raw) ->
            field.takeIf { queueCodec.decode(raw).expiresAtMs < nowMs }
        }.take(MAX_CLEANUP)
        val removals = expired.map { field -> queue.consume(field) { it.expiresAtMs < nowMs } }
        CompletableFuture.allOf(*removals.toTypedArray()).thenApply {
            removals.count { it.join() is RedisHashConsumeResult.Consumed }
        }
    }

    fun loadStats(playerId: UUID): CompletableFuture<PlayerEventStats> =
        redis.loadMapEntries(STATS_KEY, playerId.toString()).thenApply { values ->
            values.firstOrNull()?.let(statsCodec::decode) ?: PlayerEventStats()
        }

    fun updateStats(
        playerId: UUID,
        transform: (PlayerEventStats) -> PlayerEventStats,
    ): CompletableFuture<StatsUpdate> = stats.update(playerId.toString()) { current ->
        RedisHashDecision.Write(transform(current ?: PlayerEventStats()).validated())
    }.thenApply { result ->
        when (result) {
            is RedisHashUpdateResult.Changed -> StatsUpdate(result.before ?: PlayerEventStats(), requireNotNull(result.after))
            is RedisHashUpdateResult.Unchanged -> StatsUpdate(result.current, result.current)
            is RedisHashUpdateResult.Rejected -> error("Statistics update was unexpectedly rejected")
            is RedisHashUpdateResult.Contended -> error("Statistics update remained contended after ${result.attempts} attempts")
        }
    }

    private fun reserveCandidates(
        matchId: UUID,
        destinationServer: String,
        candidates: List<QueueEntry>,
        minimum: Int,
        nowMs: Long,
        reservationMs: Long,
        requiredOwnerId: UUID?,
        requiredOwnerOrigin: String?,
        mode: String,
        index: Int,
        reserved: List<ReservedCandidate>,
    ): CompletableFuture<ReservationBatch?> {
        if (index >= candidates.size) {
            if (reserved.size >= minimum) {
                return CompletableFuture.completedFuture(ReservationBatch(matchId, reserved.map(ReservedCandidate::reserved), mode = requireNotNull(EventMode.fromId(mode))))
            }
            return rollbackReservation(matchId, reserved).thenApply { null }
        }
        val candidate = candidates[index]
        return queue.update(candidate.playerId) { current ->
            val ownerMatches = index != 0 || requiredOwnerId == null || current != null &&
                current.playerId == requiredOwnerId.toString() &&
                (requiredOwnerOrigin == null || current.originServer == requiredOwnerOrigin)
            if (current == candidate && ownerMatches && current.state == QueueState.QUEUED && current.expiresAtMs >= nowMs) {
                RedisHashDecision.Write(
                    current.copy(
                        state = QueueState.RESERVED,
                        expiresAtMs = nowMs + reservationMs,
                        matchId = matchId.toString(),
                        destinationServer = destinationServer,
                        mode = mode,
                    ).validated(),
                )
            } else RedisHashDecision.Reject
        }.thenCompose { result ->
            val after = (result as? RedisHashUpdateResult.Changed)?.after
            if (requiredOwnerId != null && index == 0 && after == null) {
                return@thenCompose rollbackReservation(matchId, reserved).thenApply { null }
            }
            reserveCandidates(
                matchId,
                destinationServer,
                candidates,
                minimum,
                nowMs,
                reservationMs,
                requiredOwnerId,
                requiredOwnerOrigin,
                mode,
                index + 1,
                if (after == null) reserved else reserved + ReservedCandidate(candidate, after),
            )
        }
    }

    private fun rollbackReservation(matchId: UUID, entries: List<ReservedCandidate>): CompletableFuture<Unit> {
        val updates = entries.map { mutation ->
            queue.update(mutation.reserved.playerId) { current ->
                if (current == mutation.reserved && current.matchId == matchId.toString()) {
                    RedisHashDecision.Write(mutation.queued)
                } else RedisHashDecision.Reject
            }
        }
        return CompletableFuture.allOf(*updates.toTypedArray()).thenApply {
            updates.forEach { update ->
                when (val result = update.join()) {
                    is RedisHashUpdateResult.Changed -> Unit
                    is RedisHashUpdateResult.Unchanged -> Unit
                    is RedisHashUpdateResult.Rejected -> error("ArcEvents reservation rollback was rejected")
                    is RedisHashUpdateResult.Contended -> error(
                        "ArcEvents reservation rollback remained contended after ${result.attempts} attempts",
                    )
                }
            }
        }
    }

    private fun completeArrival(matchId: UUID, playerId: UUID): CompletableFuture<Boolean> =
        queue.update(playerId.toString()) { entry ->
            when {
                entry == null || entry.matchId != matchId.toString() -> RedisHashDecision.Reject
                entry.state == QueueState.MATCHED -> RedisHashDecision.Write(entry)
                entry.state == QueueState.ARRIVED -> RedisHashDecision.Write(entry.copy(state = QueueState.MATCHED).validated())
                else -> RedisHashDecision.Reject
            }
        }.thenApply { result -> result is RedisHashUpdateResult.Changed || result is RedisHashUpdateResult.Unchanged }

    private fun markReturnPending(playerId: UUID, matchId: UUID): CompletableFuture<QueueEntry?> =
        queue.update(playerId.toString()) { entry ->
            when {
                entry == null || entry.matchId != matchId.toString() -> RedisHashDecision.Reject
                entry.state == QueueState.RETURN_PENDING -> RedisHashDecision.Write(entry)
                entry.state in setOf(QueueState.ARRIVED, QueueState.MATCHED) -> RedisHashDecision.Write(
                    entry.copy(state = QueueState.RETURN_PENDING, expiresAtMs = Long.MAX_VALUE).validated(),
                )
                else -> RedisHashDecision.Reject
            }
        }.thenApply { it.acceptedValue() }

    private fun markMatchReturnPending(playerId: UUID, matchId: UUID): CompletableFuture<QueueEntry?> =
        queue.update(playerId.toString()) { entry ->
            when {
                entry == null || entry.matchId != matchId.toString() || entry.state !in ROUTED_STATES -> RedisHashDecision.Reject
                entry.state == QueueState.RETURN_PENDING -> RedisHashDecision.Write(entry)
                else -> RedisHashDecision.Write(
                    entry.copy(state = QueueState.RETURN_PENDING, expiresAtMs = Long.MAX_VALUE).validated(),
                )
            }
        }.thenApply { it.acceptedValue() }

    private fun RedisHashUpdateResult<QueueEntry>.acceptedValue(): QueueEntry? = when (this) {
        is RedisHashUpdateResult.Changed -> after
        is RedisHashUpdateResult.Unchanged -> current
        is RedisHashUpdateResult.Rejected, is RedisHashUpdateResult.Contended -> null
    }

    companion object {
        /** v2 isolates owner-aware queue reservations from legacy nodes during a coordinated restart. */
        const val QUEUE_KEY = "arc:events:v2:queue"
        /** v2 isolates owner/arena-aware advertisements from legacy nodes during a coordinated restart. */
        const val NODES_KEY = "arc:events:v2:nodes"
        /** Statistics remain wire-compatible with v1 and deliberately retain player history. */
        const val STATS_KEY = "arc:events:v1:stats"
        const val EVENT_CHANNEL = "arc:events:v2:events"
        private const val MAX_JSON_CHARS = 64_000
        private const val MAX_CAS_ATTEMPTS = 12
        private const val MAX_CLEANUP = 256
        private const val MESSAGE_DEDUPLICATION_MS = 15L * 60L * 1_000L
        private const val MAX_SEEN_MESSAGES = 20_000
        private const val START_REQUEST_TIMEOUT_MS = 12_000L
        private const val MAX_PENDING_STARTS = 32
        private const val MAX_NETWORK_NODES = 1_024
        private val QUEUE_FIELDS = setOf(
            "playerId", "playerName", "originServer", "mode", "state", "joinedAtMs", "expiresAtMs", "matchId",
            "destinationServer",
        )
        private val QUEUE_REQUIRED_FIELDS = QUEUE_FIELDS - setOf("matchId", "destinationServer")
        private val NODE_FIELDS = setOf(
            "serverId", "mode", "available", "arenaReady", "phase", "matchId", "queueSize", "capacity", "heartbeatAtMs",
            "arenaIds", "supportedModes",
        )
        private val NODE_REQUIRED_FIELDS = NODE_FIELDS - setOf("phase", "matchId", "arenaIds", "supportedModes")
        private val STATS_FIELDS = setOf(
            "revision", "lastMatchId", "matches", "wins", "traitorWins", "innocentWins", "kills", "deaths", "karma",
        )
        private val STATS_REQUIRED_FIELDS = STATS_FIELDS - "lastMatchId"
        private val MESSAGE_FIELDS = setOf(
            "eventId", "signal", "occurredAtMs", "matchId", "playerId", "destinationServer", "queueSize", "winner",
            "endReason", "replyTo", "startResult", "requesterId", "preferredArenaId", "adminBypass", "mode",
        )
        private val MESSAGE_REQUIRED_FIELDS = setOf("eventId", "signal", "occurredAtMs")
        private val ROUTED_STATES = setOf(
            QueueState.RESERVED,
            QueueState.ARRIVED,
            QueueState.MATCHED,
            QueueState.RETURN_PENDING,
        )

        private fun <T : Any> codec(
            gson: Gson,
            type: Class<T>,
            fields: Set<String>,
            requiredFields: Set<String>,
            validate: (T) -> Unit,
        ): BoundedJsonCodec<T> = BoundedJsonCodec(
            gson = gson,
            type = type,
            rootContract = JsonObjectContract(fields, requiredFields),
            bounds = JsonResourceBounds(
                maxCharacters = MAX_JSON_CHARS,
                maxDepth = 12,
                maxContainerEntries = 10_000,
                maxTotalNodes = 100_000,
                maxStringCharacters = 4_096,
            ),
            validate = validate,
        )
    }
}
