package ru.ruscrafting.events.network

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class EventNetworkProtocolTest : StringSpec({
    "cross-server start messages are target and correlation bound" {
        val request = EventNetworkMessage.create(
            EventNetworkSignal.START_REQUEST,
            nowMs = 1_000,
            destinationServer = "parkour",
        )
        request.destinationServer shouldBe "parkour"
        request.replyTo shouldBe null

        val result = EventNetworkMessage.create(
            EventNetworkSignal.START_RESULT,
            nowMs = 1_001,
            destinationServer = "spawn",
            replyTo = request.eventId,
            startResult = "STARTED",
        )
        result.replyTo shouldBe request.eventId
        result.startResult shouldBe "STARTED"
    }

    "cross-server start rejects untargeted and unknown outcomes" {
        shouldThrow<IllegalArgumentException> {
            EventNetworkMessage.create(EventNetworkSignal.START_REQUEST, nowMs = 1_000)
        }
        shouldThrow<IllegalArgumentException> {
            EventNetworkMessage.create(
                EventNetworkSignal.START_RESULT,
                nowMs = 1_001,
                destinationServer = "spawn",
                replyTo = UUID.randomUUID().toString(),
                startResult = "HOST_ONLY",
            )
        }
    }

    "start request preserves requester, arena choice, and admin bypass" {
        val requester = UUID.randomUUID()
        val request = EventNetworkMessage.create(
            EventNetworkSignal.START_REQUEST,
            nowMs = 1_000,
            destinationServer = "parkour",
            requesterId = requester,
            preferredArenaId = "japanese-lobby",
            adminBypass = true,
        )
        request.requesterId shouldBe requester.toString()
        request.preferredArenaId shouldBe "japanese-lobby"
        request.adminBypass shouldBe true
    }

    "start results cannot carry creator-only request fields" {
        shouldThrow<IllegalArgumentException> {
            EventNetworkMessage(
                eventId = UUID.randomUUID().toString(),
                signal = EventNetworkSignal.START_RESULT,
                occurredAtMs = 1_001,
                destinationServer = "spawn",
                replyTo = UUID.randomUUID().toString(),
                startResult = "STARTED",
                requesterId = UUID.randomUUID().toString(),
            ).validated()
        }
    }

    "reservation remains valid at the maximum queue and reservation boundary" {
        val joinedAtMs = 1_000L
        QueueEntry(
            playerId = UUID.randomUUID().toString(),
            playerName = "BoundaryPlayer",
            originServer = "spawn",
            state = QueueState.RESERVED,
            joinedAtMs = joinedAtMs,
            expiresAtMs = joinedAtMs + 3_600_000L + 300_000L,
            matchId = UUID.randomUUID().toString(),
            destinationServer = "parkour",
        ).validated().state shouldBe QueueState.RESERVED
    }
})
