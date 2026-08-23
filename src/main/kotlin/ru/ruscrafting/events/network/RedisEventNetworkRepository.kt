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
        destinationServer: String,
        nowMs: Long,
    ): CompletableFuture<QueueEntry?> = redis.loadMapEntries(QUEUE_KEY, playerId.toString()).thenCompose { values ->
        val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(null)
        val entry = runCatching { decodeQueue(raw) }.getOrNull()
            ?: return@thenCompose redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, null).thenApply { null }
        if (entry.state != QueueState.RESERVED || entry.destinationServer != destinationServer || entry.expiresAtMs < nowMs) {
            return@thenCompose CompletableFuture.completedFuture(null)
        }
        redis.compareAndSetMapEntry(QUEUE_KEY, playerId.toString(), raw, null).thenApply { claimed -> entry.takeIf { claimed } }
    }

    fun releaseReservation(matchId: UUID, nowMs: Long, queueLifetimeMs: Long): CompletableFuture<Int> =
        redis.loadMap(QUEUE_KEY).thenCompose { values ->
            val matching = values.mapNotNull { (field, raw) ->
                val entry = runCatching { decodeQueue(raw) }.getOrNull() ?: return@mapNotNull null
                if (entry.state == QueueState.RESERVED && entry.matchId == matchId.toString()) Triple(field, raw, entry) else null
            }
            CompletableFuture.allOf(*matching.map { (field, raw, entry) ->
                val queued = entry.copy(
                    state = QueueState.QUEUED,
                    expiresAtMs = nowMs + queueLifetimeMs,
                    matchId = null,
                    destinationServer = null,
                ).validated()
                redis.compareAndSetMapEntry(QUEUE_KEY, field, raw, encode(queued))
            }.toTypedArray()).thenApply { matching.size }
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
            if (before.state == QueueState.RESERVED) {
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
                expiresAtMs = nowMs + 60_000,
                matchId = null,
                destinationServer = null,
            ).validated()
            redis.compareAndSetMapEntry(QUEUE_KEY, entry.playerId, reservedRaw, encode(queued))
        }.toTypedArray())

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
    }
}
