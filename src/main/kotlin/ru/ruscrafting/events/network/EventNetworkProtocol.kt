package ru.ruscrafting.events.network

import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.QueuedPlayer
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.domain.EventMode
import java.util.UUID

enum class QueueState { QUEUED, RESERVED, ARRIVED, MATCHED, RETURN_PENDING }

data class QueueEntry(
    val playerId: String,
    val playerName: String,
    val originServer: String,
    val mode: String = EventMode.TTT.id,
    val state: QueueState = QueueState.QUEUED,
    val joinedAtMs: Long,
    val expiresAtMs: Long,
    val matchId: String? = null,
    val destinationServer: String? = null,
) {
    fun validated(): QueueEntry = apply {
        require(UUID.fromString(playerId).toString() == playerId) { "Invalid queue player id" }
        NetworkPlayerName.of(playerName)
        BackendServerId.of(originServer)
        require(EventMode.fromId(mode ?: EventMode.TTT.id) != null) { "Unsupported event mode" }
        require(joinedAtMs > 0 && expiresAtMs > joinedAtMs)
        when (state) {
            QueueState.QUEUED -> {
                require(expiresAtMs - joinedAtMs <= MAX_QUEUE_MS)
                require(matchId == null && destinationServer == null)
            }
            QueueState.RESERVED -> {
                require(expiresAtMs - joinedAtMs <= MAX_RESERVED_LIFETIME_MS)
                require(UUID.fromString(requireNotNull(matchId)).toString() == matchId)
                BackendServerId.of(requireNotNull(destinationServer))
            }
            QueueState.ARRIVED, QueueState.MATCHED, QueueState.RETURN_PENDING -> {
                require(expiresAtMs == Long.MAX_VALUE)
                require(UUID.fromString(requireNotNull(matchId)).toString() == matchId)
                BackendServerId.of(requireNotNull(destinationServer))
            }
        }
    }

    fun queuedPlayer(): QueuedPlayer = QueuedPlayer(UUID.fromString(playerId), playerName, originServer, joinedAtMs)

    companion object {
        private const val MAX_QUEUE_MS = 60 * 60 * 1000L
        private const val MAX_RESERVATION_MS = 5 * 60 * 1000L
        private const val MAX_RESERVED_LIFETIME_MS = MAX_QUEUE_MS + MAX_RESERVATION_MS
    }
}

data class HostNode(
    val serverId: String,
    val mode: String,
    val available: Boolean,
    val arenaReady: Boolean,
    val phase: MatchPhase?,
    val matchId: String?,
    val queueSize: Int,
    val capacity: Int,
    val heartbeatAtMs: Long,
    val arenaIds: List<String> = emptyList(),
    val supportedModes: List<String> = listOf(EventMode.TTT.id),
) {
    fun supports(mode: EventMode): Boolean = (supportedModes ?: listOf(EventMode.TTT.id)).contains(mode.id)

    fun validated(): HostNode = apply {
        BackendServerId.of(serverId)
        require(mode in setOf("RELAY", "HOST"))
        require(queueSize in 0..10_000 && capacity in 0..32)
        require(heartbeatAtMs > 0)
        require(arenaIds.size <= 16 && arenaIds.distinct().size == arenaIds.size)
        require(arenaIds.all { it.matches(Regex("[a-z0-9_-]{1,32}")) })
        val modes = supportedModes ?: listOf(EventMode.TTT.id)
        require(modes.isNotEmpty() && modes.size <= 8 && modes.distinct().size == modes.size)
        require(modes.all { EventMode.fromId(it) != null })
        matchId?.let { require(UUID.fromString(it).toString() == it) }
        if (available) require(mode == "HOST" && arenaReady && phase == null)
    }
}

enum class EventNetworkSignal {
    QUEUE_CHANGED,
    ROUTE_PLAYER,
    RETURN_PLAYER,
    MATCH_STARTED,
    MATCH_ENDED,
    START_REQUEST,
    START_RESULT,
    NODE_PROBE,
    NODE_ACK,
}

data class EventNetworkMessage(
    val eventId: String,
    val signal: EventNetworkSignal,
    val occurredAtMs: Long,
    val matchId: String? = null,
    val playerId: String? = null,
    val destinationServer: String? = null,
    val queueSize: Int? = null,
    val winner: TttTeam? = null,
    val endReason: MatchEndReason? = null,
    val replyTo: String? = null,
    val startResult: String? = null,
    /** Player requesting a start; absent for legacy/admin requests. */
    val requesterId: String? = null,
    /** Host-local arena choice requested by the queue owner. */
    val preferredArenaId: String? = null,
    /** Set only by a trusted backend after checking arcevents.admin. */
    val adminBypass: Boolean = false,
    val mode: String = EventMode.TTT.id,
) {
    fun validated(): EventNetworkMessage = apply {
        require(UUID.fromString(eventId).toString() == eventId)
        require(occurredAtMs > 0)
        matchId?.let { require(UUID.fromString(it).toString() == it) }
        playerId?.let { require(UUID.fromString(it).toString() == it) }
        destinationServer?.let(BackendServerId::of)
        queueSize?.let { require(it in 0..10_000) }
        replyTo?.let { require(UUID.fromString(it).toString() == it) }
        requesterId?.let { require(UUID.fromString(it).toString() == it) }
        preferredArenaId?.let { require(it.matches(Regex("[a-z0-9_-]{1,32}|auto"))) }
        require(EventMode.fromId(mode ?: EventMode.TTT.id) != null) { "Unsupported event mode" }
        when (signal) {
            EventNetworkSignal.QUEUE_CHANGED -> require(queueSize != null)
            EventNetworkSignal.ROUTE_PLAYER -> require(matchId != null && playerId != null && destinationServer != null)
            EventNetworkSignal.RETURN_PLAYER -> require(matchId != null && playerId != null && destinationServer != null)
            EventNetworkSignal.MATCH_STARTED -> require(matchId != null)
            EventNetworkSignal.MATCH_ENDED -> require(matchId != null && endReason != null)
            EventNetworkSignal.START_REQUEST -> {
                require(destinationServer != null && replyTo == null && startResult == null)
                require(matchId == null && playerId == null && queueSize == null && winner == null && endReason == null)
            }
            EventNetworkSignal.START_RESULT -> {
                require(destinationServer != null && replyTo != null)
                require(startResult in START_RESULTS)
                require(matchId == null && playerId == null && queueSize == null && winner == null && endReason == null)
                require(requesterId == null && preferredArenaId == null && !adminBypass)
            }
            EventNetworkSignal.NODE_PROBE -> require(replyTo == null)
            EventNetworkSignal.NODE_ACK -> require(replyTo != null)
        }
    }

    companion object {
        fun create(
            signal: EventNetworkSignal,
            nowMs: Long = System.currentTimeMillis(),
            matchId: UUID? = null,
            playerId: UUID? = null,
            destinationServer: String? = null,
            queueSize: Int? = null,
            winner: TttTeam? = null,
            endReason: MatchEndReason? = null,
            replyTo: String? = null,
            startResult: String? = null,
            requesterId: UUID? = null,
            preferredArenaId: String? = null,
            adminBypass: Boolean = false,
            mode: String = EventMode.TTT.id,
        ): EventNetworkMessage = EventNetworkMessage(
            eventId = UUID.randomUUID().toString(),
            signal = signal,
            occurredAtMs = nowMs,
            matchId = matchId?.toString(),
            playerId = playerId?.toString(),
            destinationServer = destinationServer,
            queueSize = queueSize,
            winner = winner,
            endReason = endReason,
            replyTo = replyTo,
            startResult = startResult,
            requesterId = requesterId?.toString(),
            preferredArenaId = preferredArenaId,
            adminBypass = adminBypass,
            mode = mode,
        ).validated()

        private val START_RESULTS = setOf(
            "STARTED",
            "ARENA_UNAVAILABLE",
            "BUSY",
            "INSUFFICIENT_PLAYERS",
            "RECOVERY_PENDING",
            "NETWORK_FAILURE",
            "NOT_OWNER",
        )
    }
}

sealed interface QueueJoinResult {
    data class Joined(val entry: QueueEntry) : QueueJoinResult
    data class Existing(val entry: QueueEntry) : QueueJoinResult
    data object Contended : QueueJoinResult
}

sealed interface QueueLeaveResult {
    data object Left : QueueLeaveResult
    data object Missing : QueueLeaveResult
    data class Reserved(val entry: QueueEntry) : QueueLeaveResult
    data object Contended : QueueLeaveResult
}

data class ReservationBatch(
    val matchId: UUID,
    val entries: List<QueueEntry>,
    val requesterId: UUID? = null,
    val preferredArenaId: String? = null,
    val mode: EventMode = EventMode.TTT,
) {
    init {
        require(entries.all { (it.mode ?: EventMode.TTT.id) == mode.id }) { "Reservation entries use mixed event modes" }
    }
}

data class StatsUpdate(val before: PlayerEventStats, val after: PlayerEventStats)
