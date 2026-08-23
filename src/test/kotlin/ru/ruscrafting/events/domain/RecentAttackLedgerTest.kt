package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class RecentAttackLedgerTest : StringSpec({
    "recent environment death keeps bounded attacker attribution" {
        var now = 1_000L
        val ledger = RecentAttackLedger(ttlMs = 10_000L) { now }
        val match = UUID.randomUUID()
        val victim = UUID.randomUUID()
        val attacker = UUID.randomUUID()

        ledger.record(match, victim, attacker)
        now = 10_999L

        ledger.consume(match, victim) shouldBe attacker
        ledger.consume(match, victim) shouldBe null
    }

    "stale or previous-match attribution is rejected" {
        var now = 1_000L
        val ledger = RecentAttackLedger(ttlMs = 10_000L) { now }
        val firstMatch = UUID.randomUUID()
        val secondMatch = UUID.randomUUID()
        val victim = UUID.randomUUID()
        val attacker = UUID.randomUUID()

        ledger.record(firstMatch, victim, attacker)
        ledger.consume(secondMatch, victim) shouldBe null

        ledger.record(secondMatch, victim, attacker)
        now = 11_001L
        ledger.consume(secondMatch, victim) shouldBe null
    }
})
