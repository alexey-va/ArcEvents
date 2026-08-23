package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.DataInputStream
import java.io.ByteArrayInputStream

class OperatorContractTest : StringSpec({
    "backend transfer uses the bounded BungeeCord Connect contract" {
        val bytes = BungeeBackendTransfer.encodeConnect("parkour")
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readUTF() shouldBe "Connect"
            input.readUTF() shouldBe "parkour"
            input.available() shouldBe 0
        }
    }

    "QA output has a stable ordered machine-readable prefix" {
        ArcEventsDebug.qa(
            "server" to "parkour",
            "phase" to "active",
            "match" to "00000000-0000-0000-0000-000000000001",
            "queue" to 4,
        ) shouldBe "ARCEVENTS_QA server=parkour phase=active match=00000000-0000-0000-0000-000000000001 queue=4"
    }
})
