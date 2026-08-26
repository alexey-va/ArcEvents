package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.util.UUID

class TttMatchRuntimeTest : StringSpec({
    val players = (1..4).map { index ->
        QueuedPlayer(UUID.nameUUIDFromBytes("runtime-$index".toByteArray()), "Player$index", "spawn", index.toLong())
    }
    val allocation = RoleAllocationSettings(4, 6, 2, 1)

    "preparation expires after exactly the configured number of ticks" {
        val runtime = TttMatchRuntime({ TttMatchEngine(4, 12, 300_000L) }, clock = { 10_000L })
        val matchId = UUID.randomUUID()
        runtime.prepare(runtime.create(matchId, players, allocation, 7L), seconds = 3)

        runtime.phaseSecondsRemaining() shouldBe 3
        runtime.tickPhase() shouldBe PhaseCountdownTick(2, false)
        runtime.tickPhase() shouldBe PhaseCountdownTick(1, false)
        runtime.tickPhase() shouldBe PhaseCountdownTick(0, true)
    }

    "runtime owns a complete match lifecycle and clears transient state on release" {
        var now = 10_000L
        val runtime = TttMatchRuntime({ TttMatchEngine(4, 12, 300_000L) }, clock = { now })
        val matchId = UUID.randomUUID()
        runtime.prepare(runtime.create(matchId, players, allocation, 7L), seconds = 1)
        runtime.beginCountdown(seconds = 1)
        runtime.activate()

        runtime.matchId shouldBe matchId
        runtime.phase shouldBe MatchPhase.ACTIVE
        runtime.cancel(MatchEndReason.ADMIN)
        runtime.beginRestoring()
        runtime.release()

        runtime.current shouldBe null
        runtime.diagnostics() shouldBe EventRuntimeDiagnostics(null, null, 0, mapOf("combat" to 0))
    }

    "successful recovery is recorded even when a disconnected player returns mid-round" {
        val runtime = TttMatchRuntime({ TttMatchEngine(4, 12, 300_000L) }, clock = { 10_000L })
        val matchId = UUID.randomUUID()
        runtime.prepare(runtime.create(matchId, players, allocation, 7L), seconds = 1)
        runtime.beginCountdown(seconds = 1)
        runtime.activate()
        val playerId = runtime.current!!.participants.values.first { it.role != TttRole.TRAITOR }.playerId
        runtime.disconnect(playerId)

        runtime.markRecoveryApplied(playerId).participant(playerId)?.status shouldBe ParticipantStatus.RESTORED
        runtime.phase shouldBe MatchPhase.ACTIVE
    }

    "runtime refuses to silently release a live match" {
        val runtime = TttMatchRuntime({ TttMatchEngine(4, 12, 300_000L) }, clock = { 10_000L })
        runtime.prepare(runtime.create(UUID.randomUUID(), players, allocation, 7L), seconds = 1)

        shouldThrow<IllegalArgumentException> { runtime.release() }
    }

    "bounded combat history keeps globally monotonic sequence numbers" {
        val runtime = TttMatchRuntime({ TttMatchEngine(4, 12, 300_000L) }, clock = { 10_000L })
        runtime.prepare(runtime.create(UUID.randomUUID(), players, allocation, 7L), seconds = 1)
        runtime.beginCountdown(seconds = 1)
        runtime.activate()
        val attacker = runtime.current!!.participants.values.first()
        val victim = runtime.current!!.participants.values.first { it.playerId != attacker.playerId }

        repeat(513) {
            val sequence = runtime.nextCombatSequence()
            runtime.recordDamage(
                CombatRecord(
                    sequence = sequence,
                    occurredAtMs = 10_000L + sequence,
                    attackerId = attacker.playerId,
                    attackerName = attacker.playerName,
                    victimId = victim.playerId,
                    victimName = victim.playerName,
                    weapon = "test",
                    finalDamage = 0.1,
                    friendly = attacker.role.team == victim.role.team,
                    headshot = false,
                    lethal = false,
                ),
                victim.playerId,
                attacker.playerId,
                0.1,
            )
        }

        runtime.combatRecords().size shouldBe 512
        runtime.combatRecords().first().sequence shouldBe 2
        runtime.combatRecords().last().sequence shouldBe 513
    }
})
