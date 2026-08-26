package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class EventRecoveryGateTest : StringSpec({
    val currentPlayer = UUID.randomUUID()
    val oldMatchPlayer = UUID.randomUUID()

    "only current online recovery state can block session release" {
        EventRecoveryGate.blocked(
            onlinePlayers = setOf(currentPlayer),
            pendingPlayers = emptySet(),
            recoveredPlayers = setOf(currentPlayer),
        ) shouldBe false

        EventRecoveryGate.blocked(
            onlinePlayers = setOf(currentPlayer),
            pendingPlayers = setOf(oldMatchPlayer),
            recoveredPlayers = setOf(currentPlayer),
        ) shouldBe false
    }

    "online pending or missing recovery proof blocks fail closed" {
        EventRecoveryGate.blocked(setOf(currentPlayer), setOf(currentPlayer), emptySet()) shouldBe true
        EventRecoveryGate.blocked(setOf(currentPlayer), emptySet(), emptySet()) shouldBe true
        EventRecoveryGate.blocked(emptySet(), setOf(currentPlayer), emptySet()) shouldBe false
    }
})
