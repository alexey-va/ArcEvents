package ru.ruscrafting.events.network

import com.google.gson.Gson
import com.google.gson.JsonParseException
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.events.domain.PlayerEventStats
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RedisEventNetworkRepository(
    private val redis: RedisOperations,
    private val gson: Gson = Gson(),
) {
    fun register(listener: (EventNetworkMessage, String) -> Unit): ChannelListener {
        val channelListener = ChannelListener { _, raw, origin -> decodeMessage(raw)?.let { listener(it, origin) } }
        redis.registerChannelUnique(EVENT_CHANNEL, channelListener)
        return channelListener
    }

    fun unregister(listener: ChannelListener) = redis.unregisterChannel(EVENT_CHANNEL, listener)

    fun publish(message: EventNetworkMessage) {
        redis.publish(EVENT_CHANNEL, encode(message.validated()))
    }

    fun saveNode(node: HostNode): CompletableFuture<*> =
        redis.saveMapEntries(NODES_KEY, node.serverId, encode(node.validated()))

    fun loadNodes(nowMs: Long, staleAfterMs: Long): CompletableFuture<List<HostNode>> =
        redis.loadMap(NODES_KEY).thenApply { values ->
            values.values.mapNotNull { raw -> runCatching { decodeNode(raw) }.getOrNull() }
                .filter { it.heartbeatAtMs >= nowMs - staleAfterMs && it.heartbeatAtMs <= nowMs + FUTURE_SKEW_MS }
                .sortedBy(HostNode::serverId)
        }

    fun joinQueue(
        playerId: UUID,
        playerName: String,
        originServer: String,
        nowMs: Long,
        lifetimeMs: Long,
    ): CompletableFuture<QueueJoinResult> = joinAttempt(playerId, playerName, originServer, nowMs, lifetimeMs, 0)

    fun leaveQueue(playerId: UUID, nowMs: Long): CompletableFuture<QueueLeaveResult> = leaveAttempt(playerId, nowMs, 0)

    fun loadQueue(nowMs: Long): CompletableFuture<List<QueueEntry>> = redis.loadMap(QUEUE_KEY).thenApply { values ->
        values.values.mapNotNull { raw -> runCatching { decodeQueue(raw) }.getOrNull() }
            .filter { it.expiresAtMs >= nowMs }
            .sortedWith(compareBy<QueueEntry> { it.joinedAtMs }.thenBy { it.playerId })
    }

    fun loadQueuedCount(nowMs: Long): CompletableFuture<Int> =
        loadQueue(nowMs).thenApply { queue -> queue.count { it.state == QueueState.QUEUED } }

    fun loadQueueEntry(playerId: UUID): CompletableFuture<QueueEntry?> =
        redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenApply { values ->
            values.firstOrNull()?.let { raw -> runCatching { decodeQueue(raw) }.getOrNull() }
        }

    fun reserve(
        matchId: UUID,
        destinationServer: String,
        minimum: Int,
        maximum: Int,
        nowMs: Long,
        reservationMs: Long,
    ): CompletableFuture<ReservationBatch?> = loadQueue(nowMs).thenCompose { queue ->
        val candidates = queue.filter { it.state == QueueState.QUEUED }.take(maximum)
        if (candidates.size < minimum) return@thenCompose CompletableFuture.completedFuture(null)
        reserveCandidates(matchId, destinationServer, candidates, minimum, nowMs, reservationMs, 0, emptyList())
    }

    fun claimReservation(
        playerId: UUID,
        currentServer: String,
        nowMs: Long,
    ): CompletableFuture<QueueEntry?> = claimReservationAttempt(playerId, currentServer, nowMs, 0)

    fun releaseReservation(
        matchId: UUID,
        playerIds: Collection<UUID>? = null,
    ): CompletableFuture<List<QueueEntry>> =
        redis.loadMap(QUEUE_KEY).thenCompose { values ->
            val selectedIds = playerIds?.map(UUID::toString)?.toSet()
            val matchingPlayerIds = values.mapNotNull { (_, raw) ->
                val entry = runCatching { decodeQueue(raw) }.getOrNull() ?: return@mapNotNull null
                if (entry.matchId == matchId.toString() && entry.state in ROUTED_STATES &&
                    (selectedIds == null || entry.playerId in selectedIds)
                ) UUID.fromString(entry.playerId) else null
            }
            val changes = matchingPlayerIds.map { playerId -> markMatchReturnPendingAttempt(playerId, matchId, 0) }
            CompletableFuture.allOf(*changes.toTypedArray()).thenApply { changes.mapNotNull(CompletableFuture<QueueEntry?>::join) }
        }

    fun completeReservation(matchId: UUID, playerIds: Collection<UUID>): CompletableFuture<Int> {
        val completions = playerIds.distinct().map { playerId -> completeArrivalAttempt(matchId, playerId, 0) }
        return CompletableFuture.allOf(*completions.toTypedArray()).thenApply { completions.count { it.join() } }
    }

    fun markReturnPending(entry: QueueEntry): CompletableFuture<QueueEntry?> =
        markReturnPendingAttempt(UUID.fromString(entry.playerId), UUID.fromString(requireNotNull(entry.matchId)), 0)

    fun prepareRecoveredReturn(playerId: UUID, matchId: UUID): CompletableFuture<QueueEntry?> =
        markReturnPendingAttempt(playerId, matchId, 0)

    fun acknowledgeReturn(playerId: UUID, matchId: UUID): CompletableFuture<Boolean> =
        acknowledgeReturnAttempt(playerId, matchId, 0)

    private fun claimReservationAttempt(
        playerId: UUID,
        currentServer: String,
        nowMs: Long,
        attempt: Int,
    ): CompletableFuture<QueueEntry?> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(null)
        return redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(null)
            val entry = runCatching { decodeQueue(raw) }.getOrNull()
                ?: return@thenCompose redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, null).thenApply { null }
            when (entry.state) {
                QueueState.QUEUED -> CompletableFuture.completedFuture(null)
                QueueState.RESERVED -> {
                    if (entry.destinationServer != currentServer || entry.expiresAtMs < nowMs) {
                        CompletableFuture.completedFuture(null)
                    } else {
                        val arrived = entry.copy(state = QueueState.ARRIVED, expiresAtMs = Long.MAX_VALUE).validated()
                        redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, encode(arrived)).thenCompose { changed ->
                            if (changed) CompletableFuture.completedFuture(arrived)
                            else claimReservationAttempt(playerId, currentServer, nowMs, attempt + 1)
                        }
                    }
                }
                QueueState.ARRIVED -> CompletableFuture.completedFuture(entry.takeIf { it.destinationServer == currentServer })
                QueueState.MATCHED, QueueState.RETURN_PENDING -> CompletableFuture.completedFuture(entry.takeIf {
                    currentServer == it.originServer || currentServer == it.destinationServer
                })
            }
        }
    }

    fun cleanup(nowMs: Long): CompletableFuture<Int> = redis.loadMap(QUEUE_KEY).thenCompose { values ->
        val expired = values.entries.mapNotNull { (field, raw) ->
            val entry = runCatching { decodeQueue(raw) }.getOrNull()
            if (entry == null || entry.expiresAtMs < nowMs) field to raw else null
        }.take(MAX_CLEANUP)
        CompletableFuture.allOf(*expired.map { (field, raw) ->
            redis.compareAndSetMapEntry(QUEUE_KEY, field, raw, null)
        }.toTypedArray()).thenApply { expired.size }
    }

    fun loadStats(playerId: UUID): CompletableFuture<PlayerEventStats> =
        redis.loadMapEntries(STATS_KEY, playerId.toString()).thenApply { values ->
            values.firstOrNull()?.let(::decodeStats) ?: PlayerEventStats()
        }

    fun updateStats(
        playerId: UUID,
        transform: (PlayerEventStats) -> PlayerEventStats,
    ): CompletableFuture<StatsUpdate> = updateStatsAttempt(playerId, transform, 0)

    private fun joinAttempt(
        playerId: UUID,
        playerName: String,
        originServer: String,
        nowMs: Long,
        lifetimeMs: Long,
        attempt: Int,
    ): CompletableFuture<QueueJoinResult> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(QueueJoinResult.Contended)
        val field = playerId.toString()
        return redis.loadMapEntries(QUEUE_KEY, field).thenCompose { values ->
            val beforeRaw = values.firstOrNull()
            val before = beforeRaw?.let { runCatching { decodeQueue(it) }.getOrNull() }
            if (before != null && before.expiresAtMs >= nowMs) {
                if (before.state == QueueState.QUEUED &&
                    (before.playerName != playerName || before.originServer != originServer)
                ) {
                    val refreshed = before.copy(
                        playerName = playerName,
                        originServer = originServer,
                    ).validated()
                    return@thenCompose redis.compareAndSetMapEntry(
                        QUEUE_KEY,
                        field,
                        beforeRaw,
                        encode(refreshed),
                    ).thenCompose { changed ->
                        if (changed) {
                            CompletableFuture.completedFuture(QueueJoinResult.Existing(refreshed))
                        } else {
                            joinAttempt(playerId, playerName, originServer, nowMs, lifetimeMs, attempt + 1)
                        }
                    }
                }
                return@thenCompose CompletableFuture.completedFuture(QueueJoinResult.Existing(before))
            }
            val after = QueueEntry(
                playerId = field,
                playerName = playerName,
                originServer = originServer,
                joinedAtMs = nowMs,
                expiresAtMs = nowMs + lifetimeMs,
            ).validated()
            redis.compareAndSetMapEntry(QUEUE_KEY, field, beforeRaw, encode(after)).thenCompose { changed ->
                if (changed) CompletableFuture.completedFuture(QueueJoinResult.Joined(after))
                else joinAttempt(playerId, playerName, originServer, nowMs, lifetimeMs, attempt + 1)
            }
        }
    }

    private fun leaveAttempt(playerId: UUID, nowMs: Long, attempt: Int): CompletableFuture<QueueLeaveResult> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(QueueLeaveResult.Contended)
        val field = playerId.toString()
        return redis.loadMapEntries(QUEUE_KEY, field).thenCompose { values ->
            val beforeRaw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(QueueLeaveResult.Missing)
            val before = runCatching { decodeQueue(beforeRaw) }.getOrNull()
            if (before == null || before.expiresAtMs < nowMs) {
                return@thenCompose redis.compareAndSetMapEntry(QUEUE_KEY, field, beforeRaw, null).thenApply { QueueLeaveResult.Missing }
            }
            if (before.state != QueueState.QUEUED) {
                return@thenCompose CompletableFuture.completedFuture(QueueLeaveResult.Reserved(before))
            }
            redis.compareAndSetMapEntry(QUEUE_KEY, field, beforeRaw, null).thenCompose { changed ->
                if (changed) CompletableFuture.completedFuture(QueueLeaveResult.Left)
                else leaveAttempt(playerId, nowMs, attempt + 1)
            }
        }
    }

    private fun reserveCandidates(
        matchId: UUID,
        destinationServer: String,
        candidates: List<QueueEntry>,
        minimum: Int,
        nowMs: Long,
        reservationMs: Long,
        index: Int,
        reserved: List<QueueEntry>,
    ): CompletableFuture<ReservationBatch?> {
        if (index >= candidates.size) {
            if (reserved.size >= minimum) return CompletableFuture.completedFuture(ReservationBatch(matchId, reserved))
            return rollbackReservation(matchId, reserved, nowMs).thenApply { null }
        }
        val candidate = candidates[index]
        val field = candidate.playerId
        return redis.loadMapEntries(QUEUE_KEY, field).thenCompose { values ->
            val raw = values.firstOrNull()
            val current = raw?.let { runCatching { decodeQueue(it) }.getOrNull() }
            if (current == null || current != candidate || current.state != QueueState.QUEUED || current.expiresAtMs < nowMs) {
                return@thenCompose reserveCandidates(matchId, destinationServer, candidates, minimum, nowMs, reservationMs, index + 1, reserved)
            }
            val after = current.copy(
                state = QueueState.RESERVED,
                expiresAtMs = nowMs + reservationMs,
                matchId = matchId.toString(),
                destinationServer = destinationServer,
            ).validated()
            redis.compareAndSetMapEntry(QUEUE_KEY, field, raw, encode(after)).thenCompose { changed ->
                reserveCandidates(
                    matchId, destinationServer, candidates, minimum, nowMs, reservationMs, index + 1,
                    if (changed) reserved + after else reserved,
                )
            }
        }
    }

    private fun rollbackReservation(matchId: UUID, entries: List<QueueEntry>, nowMs: Long): CompletableFuture<*> =
        CompletableFuture.allOf(*entries.map { entry ->
            val reservedRaw = encode(entry)
            val queued = entry.copy(
                state = QueueState.QUEUED,
                joinedAtMs = nowMs,
                expiresAtMs = nowMs + 60_000,
                matchId = null,
                destinationServer = null,
            ).validated()
            redis.compareAndSetMapEntry(QUEUE_KEY, entry.playerId, reservedRaw, encode(queued))
        }.toTypedArray())

    private fun completeArrivalAttempt(matchId: UUID, playerId: UUID, attempt: Int): CompletableFuture<Boolean> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(false)
        return redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(false)
            val entry = runCatching { decodeQueue(raw) }.getOrNull()
                ?: return@thenCompose CompletableFuture.completedFuture(false)
            if (entry.matchId != matchId.toString()) {
                return@thenCompose CompletableFuture.completedFuture(false)
            }
            if (entry.state == QueueState.MATCHED) return@thenCompose CompletableFuture.completedFuture(true)
            if (entry.state != QueueState.ARRIVED) return@thenCompose CompletableFuture.completedFuture(false)
            val matched = entry.copy(state = QueueState.MATCHED).validated()
            redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, encode(matched)).thenCompose { changed ->
                if (changed) CompletableFuture.completedFuture(true)
                else completeArrivalAttempt(matchId, playerId, attempt + 1)
            }
        }
    }

    private fun markReturnPendingAttempt(playerId: UUID, matchId: UUID, attempt: Int): CompletableFuture<QueueEntry?> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(null)
        return redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(null)
            val entry = runCatching { decodeQueue(raw) }.getOrNull()
                ?: return@thenCompose CompletableFuture.completedFuture(null)
            if (entry.matchId != matchId.toString()) return@thenCompose CompletableFuture.completedFuture(null)
            if (entry.state == QueueState.RETURN_PENDING) return@thenCompose CompletableFuture.completedFuture(entry)
            if (entry.state !in setOf(QueueState.ARRIVED, QueueState.MATCHED)) {
                return@thenCompose CompletableFuture.completedFuture(null)
            }
            val pending = entry.copy(state = QueueState.RETURN_PENDING, expiresAtMs = Long.MAX_VALUE).validated()
            redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, encode(pending)).thenCompose { changed ->
                if (changed) CompletableFuture.completedFuture(pending)
                else markReturnPendingAttempt(playerId, matchId, attempt + 1)
            }
        }
    }

    private fun markMatchReturnPendingAttempt(playerId: UUID, matchId: UUID, attempt: Int): CompletableFuture<QueueEntry?> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(null)
        return redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(null)
            val entry = runCatching { decodeQueue(raw) }.getOrNull()
                ?: return@thenCompose CompletableFuture.completedFuture(null)
            if (entry.matchId != matchId.toString() || entry.state !in ROUTED_STATES) {
                return@thenCompose CompletableFuture.completedFuture(null)
            }
            if (entry.state == QueueState.RETURN_PENDING) return@thenCompose CompletableFuture.completedFuture(entry)
            val pending = entry.copy(state = QueueState.RETURN_PENDING, expiresAtMs = Long.MAX_VALUE).validated()
            redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, encode(pending)).thenCompose { changed ->
                if (changed) CompletableFuture.completedFuture(pending)
                else markMatchReturnPendingAttempt(playerId, matchId, attempt + 1)
            }
        }
    }

    private fun acknowledgeReturnAttempt(playerId: UUID, matchId: UUID, attempt: Int): CompletableFuture<Boolean> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(false)
        return redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(true)
            val entry = runCatching { decodeQueue(raw) }.getOrNull()
                ?: return@thenCompose CompletableFuture.completedFuture(false)
            if (entry.state != QueueState.RETURN_PENDING || entry.matchId != matchId.toString()) {
                return@thenCompose CompletableFuture.completedFuture(false)
            }
            redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, null).thenCompose { changed ->
                if (changed) CompletableFuture.completedFuture(true)
                else acknowledgeReturnAttempt(playerId, matchId, attempt + 1)
            }
        }
    }

    private fun updateStatsAttempt(
        playerId: UUID,
        transform: (PlayerEventStats) -> PlayerEventStats,
        attempt: Int,
    ): CompletableFuture<StatsUpdate> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.failedFuture(IllegalStateException("Statistics update remained contended"))
        val field = playerId.toString()
        return redis.loadMapEntries(STATS_KEY, field).thenCompose { values ->
            val beforeRaw = values.firstOrNull()
            val before = beforeRaw?.let(::decodeStats) ?: PlayerEventStats()
            val after = transform(before).validated()
            redis.compareAndSetMapEntry(STATS_KEY, field, beforeRaw, encode(after)).thenCompose { changed ->
                if (changed) CompletableFuture.completedFuture(StatsUpdate(before, after))
                else updateStatsAttempt(playerId, transform, attempt + 1)
            }
        }
    }

    private fun encode(value: Any): String = gson.toJson(value).also {
        require(it.length <= MAX_JSON_CHARS) { "ArcEvents Redis payload is too large" }
    }

    private fun decodeQueue(raw: String): QueueEntry = checked(raw) { gson.fromJson(it, QueueEntry::class.java).validated() }
    private fun decodeNode(raw: String): HostNode = checked(raw) { gson.fromJson(it, HostNode::class.java).validated() }
    private fun decodeStats(raw: String): PlayerEventStats = checked(raw) { gson.fromJson(it, PlayerEventStats::class.java).validated() }

    private fun decodeMessage(raw: String): EventNetworkMessage? = runCatching {
        checked(raw) { gson.fromJson(it, EventNetworkMessage::class.java).validated() }
    }.getOrNull()

    private fun <T> checked(raw: String, decode: (String) -> T): T {
        if (raw.length > MAX_JSON_CHARS) throw JsonParseException("ArcEvents Redis payload is too large")
        return decode(raw)
    }

    companion object {
        const val QUEUE_KEY = "arc:events:v1:queue"
        const val NODES_KEY = "arc:events:v1:nodes"
        const val STATS_KEY = "arc:events:v1:stats"
        const val EVENT_CHANNEL = "arc:events:v1:events"
        private const val MAX_JSON_CHARS = 64_000
        private const val MAX_CAS_ATTEMPTS = 12
        private const val MAX_CLEANUP = 256
        private const val FUTURE_SKEW_MS = 60_000L
        private val ROUTED_STATES = setOf(
            QueueState.RESERVED,
            QueueState.ARRIVED,
            QueueState.MATCHED,
            QueueState.RETURN_PENDING,
        )
    }
}
