package ru.ruscrafting.events.network

import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.QueuedPlayer
import ru.ruscrafting.events.domain.TttTeam
import java.util.UUID

enum class QueueState { QUEUED, RESERVED }

data class QueueEntry(
    val playerId: String,
    val playerName: String,
    val originServer: String,
    val mode: String = "ttt",
    val state: QueueState = QueueState.QUEUED,
    val joinedAtMs: Long,
    val expiresAtMs: Long,
    val matchId: String? = null,
    val destinationServer: String? = null,
) {
    fun validated(): QueueEntry = apply {
        require(UUID.fromString(playerId).toString() == playerId) { "Invalid queue player id" }
        require(playerName.matches(PLAYER_NAME)) { "Invalid queue player name" }
        require(originServer.matches(SERVER_ID)) { "Invalid queue origin" }
        require(mode == "ttt") { "Unsupported event mode" }
        require(joinedAtMs > 0 && expiresAtMs > joinedAtMs && expiresAtMs - joinedAtMs <= MAX_QUEUE_MS)
        when (state) {
            QueueState.QUEUED -> require(matchId == null && destinationServer == null)
            QueueState.RESERVED -> {
                require(UUID.fromString(requireNotNull(matchId)).toString() == matchId)
                require(requireNotNull(destinationServer).matches(SERVER_ID))
            }
        }
    }

    fun queuedPlayer(): QueuedPlayer = QueuedPlayer(UUID.fromString(playerId), playerName, originServer, joinedAtMs)

    companion object {
        private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")
        private val SERVER_ID = Regex("[a-z0-9_-]{1,32}")
        private const val MAX_QUEUE_MS = 60 * 60 * 1000L
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
) {
    fun validated(): HostNode = apply {
        require(serverId.matches(Regex("[a-z0-9_-]{1,32}")))
        require(mode in setOf("RELAY", "HOST"))
        require(queueSize in 0..10_000 && capacity in 0..32)
        require(heartbeatAtMs > 0)
        matchId?.let { require(UUID.fromString(it).toString() == it) }
        if (available) require(mode == "HOST" && arenaReady && phase == null)
    }
}

enum class EventNetworkSignal {
    QUEUE_CHANGED,
    ROUTE_PLAYER,
    MATCH_STARTED,
    MATCH_ENDED,
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
) {
    fun validated(): EventNetworkMessage = apply {
        require(UUID.fromString(eventId).toString() == eventId)
        require(occurredAtMs > 0)
        matchId?.let { require(UUID.fromString(it).toString() == it) }
        playerId?.let { require(UUID.fromString(it).toString() == it) }
        destinationServer?.let { require(it.matches(Regex("[a-z0-9_-]{1,32}"))) }
        queueSize?.let { require(it in 0..10_000) }
        when (signal) {
            EventNetworkSignal.QUEUE_CHANGED -> require(queueSize != null)
            EventNetworkSignal.ROUTE_PLAYER -> require(matchId != null && playerId != null && destinationServer != null)
            EventNetworkSignal.MATCH_STARTED -> require(matchId != null)
            EventNetworkSignal.MATCH_ENDED -> require(matchId != null && endReason != null)
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
        ).validated()
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
)

data class StatsUpdate(val before: PlayerEventStats, val after: PlayerEventStats)
