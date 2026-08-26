package ru.ruscrafting.events.network

import java.util.UUID

/** Fail-closed authorization for cross-server player movement. */
object EventRoutePolicy {
    fun toMatch(entry: QueueEntry?, playerId: UUID, matchId: UUID, destinationServer: String, nowMs: Long): Boolean =
        entry?.playerId == playerId.toString() &&
            entry.matchId == matchId.toString() &&
            entry.destinationServer == destinationServer &&
            entry.state == QueueState.RESERVED &&
            entry.expiresAtMs >= nowMs

    fun toOrigin(entry: QueueEntry?, playerId: UUID, matchId: UUID, originServer: String): Boolean =
        entry?.playerId == playerId.toString() &&
            entry.matchId == matchId.toString() &&
            entry.originServer == originServer &&
            entry.state == QueueState.RETURN_PENDING
}
