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

    "modern dialogs stay optional and fall back for old clients and item grids" {
        dialogFrontendSupported(false, MIN_DIALOG_PROTOCOL, EventsView.Main) shouldBe false
        dialogFrontendSupported(true, MIN_DIALOG_PROTOCOL - 1, EventsView.Main) shouldBe false
        dialogFrontendSupported(true, MIN_DIALOG_PROTOCOL, EventsView.Main) shouldBe true
        dialogFrontendSupported(true, MIN_DIALOG_PROTOCOL, EventsView.Help) shouldBe true
        dialogFrontendSupported(true, MIN_DIALOG_PROTOCOL, EventsView.Admin) shouldBe true
        dialogFrontendSupported(true, MIN_DIALOG_PROTOCOL, EventsView.Shop) shouldBe false
        dialogFrontendSupported(true, MIN_DIALOG_PROTOCOL, EventsView.Roster) shouldBe false
    }

    "ViaVersion original client protocol wins over the backend protocol" {
        effectiveClientProtocol(774, 769) shouldBe 769
        effectiveClientProtocol(774, 771) shouldBe 771
        effectiveClientProtocol(774, -1) shouldBe 774
        effectiveClientProtocol(774, null) shouldBe 774
    }

    "debug command catalog covers round player combat evidence equipment and GUI scenarios" {
        ArcEventsCommand.DEBUG_ACTIONS.containsAll(listOf(
            "bootstrap", "advance", "end", "timer", "role", "health", "weapon", "ammo",
            "item", "kill", "revive", "discover", "dna", "call", "loot", "menu", "cleanup",
        )) shouldBe true
        ArcEventsCommand.DEBUG_ITEMS.size shouldBe 6
        ArcEventsCommand.DEBUG_VIEWS shouldBe listOf("main", "help", "admin", "shop", "roster", "report")
        ArcEventsCommand.validOptionalInteger(null) shouldBe true
        ArcEventsCommand.validOptionalInteger("0") shouldBe true
        ArcEventsCommand.validOptionalInteger("full") shouldBe false
    }
})
