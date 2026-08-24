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
})
