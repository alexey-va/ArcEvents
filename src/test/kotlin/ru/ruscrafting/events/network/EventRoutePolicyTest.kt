package ru.ruscrafting.events.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class EventRoutePolicyTest : StringSpec({
    val playerId = UUID.randomUUID()
    val matchId = UUID.randomUUID()
    fun entry(state: QueueState, destination: String = "parkour", match: UUID = matchId) = QueueEntry(
        playerId = playerId.toString(),
        playerName = "RouteTester",
        originServer = "spawn",
        state = state,
        joinedAtMs = 1_000L,
        expiresAtMs = if (state in setOf(QueueState.QUEUED, QueueState.RESERVED)) 60_000L else Long.MAX_VALUE,
        matchId = match.takeUnless { state == QueueState.QUEUED }?.toString(),
        destinationServer = "parkour".takeUnless { state == QueueState.QUEUED },
    ).validated()

    "match route requires exact reserved player match and destination" {
        EventRoutePolicy.toMatch(entry(QueueState.RESERVED), playerId, matchId, "parkour", 10_000L) shouldBe true
        EventRoutePolicy.toMatch(entry(QueueState.MATCHED), playerId, matchId, "parkour", 10_000L) shouldBe false
        EventRoutePolicy.toMatch(entry(QueueState.RESERVED), playerId, UUID.randomUUID(), "parkour", 10_000L) shouldBe false
        EventRoutePolicy.toMatch(entry(QueueState.RESERVED), playerId, matchId, "survival", 10_000L) shouldBe false
        EventRoutePolicy.toMatch(entry(QueueState.RESERVED), playerId, matchId, "parkour", 60_001L) shouldBe false
    }

    "return route requires exact return-pending origin" {
        EventRoutePolicy.toOrigin(entry(QueueState.RETURN_PENDING), playerId, matchId, "spawn") shouldBe true
        EventRoutePolicy.toOrigin(entry(QueueState.MATCHED), playerId, matchId, "spawn") shouldBe false
        EventRoutePolicy.toOrigin(entry(QueueState.RETURN_PENDING), playerId, matchId, "survival") shouldBe false
    }
})
