package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.io.DataInputStream
import java.io.ByteArrayInputStream
import java.util.UUID

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

    "role shop stays hidden before activation and after elimination" {
        val playerId = UUID.randomUUID()
        val traitor = TttParticipant(playerId, "Tester", "spawn", TttRole.TRAITOR, ParticipantStatus.RESERVED, 2)
        fun match(phase: MatchPhase, participant: TttParticipant) = TttMatch(
            UUID.randomUUID(), 0, phase, mapOf(playerId to participant), 1,
        )

        shopAccessible(match(MatchPhase.PREPARING, traitor), traitor) shouldBe false
        shopAccessible(match(MatchPhase.ACTIVE, traitor), traitor) shouldBe false
        val alive = traitor.copy(status = ParticipantStatus.ALIVE)
        shopAccessible(match(MatchPhase.ACTIVE, alive), alive) shouldBe true
        val dead = traitor.copy(status = ParticipantStatus.DEAD)
        shopAccessible(match(MatchPhase.ACTIVE, dead), dead) shouldBe false
    }
})
