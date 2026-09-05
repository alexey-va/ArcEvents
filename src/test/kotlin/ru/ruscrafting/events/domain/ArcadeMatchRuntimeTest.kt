package ru.ruscrafting.events.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.util.UUID

class ArcadeMatchRuntimeTest : StringSpec({
    class Clock(var now: Long = 0) { fun read() = now }
    fun players(count: Int) = (1..count).map { QueuedPlayer(UUID.nameUUIDFromBytes("arcade-$it".toByteArray()), "P$it", "spawn", it.toLong()) }

    "mode ids round trip" {
        EventMode.fromId("GUNGAME") shouldBe EventMode.GUN_GAME
        EventMode.fromId("unknown") shouldBe null
    }

    "rules reject inverted and negative bounds" {
        shouldThrow<IllegalArgumentException> { ArcadeRules(minimumPlayers = 5, maximumPlayers = 4).validated() }
        shouldThrow<IllegalArgumentException> { ArcadeRules(roundSeconds = -1).validated() }
    }

    "tick crosses preparation and countdown by authoritative deadlines" {
        val c = Clock(); val runtime = ArcadeMatchRuntime(c::read)
        runtime.start(UUID.randomUUID(), EventMode.GUN_GAME, players(3), ArcadeRules(preparationSeconds = 2, countdownSeconds = 3))
        c.now = 1_999; runtime.tick().phase shouldBe MatchPhase.PREPARING
        c.now = 2_000; runtime.tick().phase shouldBe MatchPhase.COUNTDOWN
        c.now = 5_000; runtime.tick().phase shouldBe MatchPhase.ACTIVE
        runtime.current!!.participants.values.all { it.status == ParticipantStatus.ALIVE } shouldBe true
    }

    "gun game advances firearms and requires the final knife" {
        val c = Clock(); val runtime = ArcadeMatchRuntime(c::read)
        runtime.start(UUID.randomUUID(), EventMode.GUN_GAME, players(3), ArcadeRules(2, 16, 0, 0, 30, spawnProtectionSeconds = 0))
        runtime.tick(); val ids = runtime.current!!.participants.keys.toList(); val killer = ids[0]; val victim = ids[1]
        runtime.eliminate(victim, killer)
        runtime.current!!.participants.getValue(killer).stage shouldBe 1
        c.now = 10_000
        repeat(FirearmId.entries.size - 1) {
            c.now += 3_000
            runtime.respawn(victim)
            c.now += 3_000
            runtime.eliminate(victim, killer)
        }
        c.now += 3_000
        runtime.respawn(victim)
        c.now += 3_000
        runtime.eliminate(victim, killer, knifeKill = false)
        runtime.phase shouldBe MatchPhase.ACTIVE
        c.now += 3_000
        runtime.respawn(victim)
        c.now += 3_000
        runtime.eliminate(victim, killer, knifeKill = true)
        runtime.phase shouldBe MatchPhase.RESOLVING
        runtime.current!!.winners shouldContainExactlyInAnyOrder listOf(killer)
    }

    "protected players cannot be eliminated and respawn is due only once" {
        val c = Clock(); val runtime = ArcadeMatchRuntime(c::read)
        runtime.start(UUID.randomUUID(), EventMode.GUN_GAME, players(3), ArcadeRules(2, 16, 0, 0, 30, respawnSeconds = 3, spawnProtectionSeconds = 2)); runtime.tick()
        val ids = runtime.current!!.participants.keys.toList()
        c.now = 3_000
        runtime.eliminate(ids[1], ids[0])
        c.now = 6_000
        runtime.respawn(ids[1])
        runtime.eliminate(ids[1], ids[0]).participants.getValue(ids[1]).status shouldBe ParticipantStatus.ALIVE
        c.now = 8_000
        runtime.eliminate(ids[1], ids[0]).participants.getValue(ids[1]).deaths shouldBe 2
    }

    "active disconnect below two resolves the match" {
        val runtime = ArcadeMatchRuntime { 0L }
        runtime.start(UUID.randomUUID(), EventMode.GUN_GAME, players(3), ArcadeRules(2, 16, 0, 0, 30))
        runtime.tick()
        runtime.disconnect(runtime.current!!.participants.keys.first()); runtime.phase shouldBe MatchPhase.ACTIVE
        runtime.disconnect(runtime.current!!.participants.keys.first { runtime.current!!.participants.getValue(it).status != ParticipantStatus.DISCONNECTED })
        runtime.phase shouldBe MatchPhase.CANCELLED
    }

    "disaster cycles score living players and reset dead players" {
        val c = Clock(); val runtime = ArcadeMatchRuntime(c::read)
        runtime.start(UUID.randomUUID(), EventMode.DISASTERS, players(3), ArcadeRules(2, 16, 0, 0, 30, disasterRounds = 2)); runtime.tick()
        c.now = 31_000
        runtime.tick().phase shouldBe MatchPhase.ACTIVE
        runtime.beginDisaster()
        val ids = runtime.current!!.participants.keys.toList()
        runtime.eliminate(ids[1], null)
        runtime.disconnect(ids[2])
        runtime.completeDisaster()
        runtime.current!!.disasterRound shouldBe 1
        runtime.current!!.participants.getValue(ids[0]).score shouldBe 1
        runtime.current!!.participants.getValue(ids[1]).status shouldBe ParticipantStatus.RESERVED
        runtime.beginDisaster(); runtime.completeDisaster(); runtime.phase shouldBe MatchPhase.RESOLVING
        runtime.current!!.winners shouldContainExactlyInAnyOrder listOf(ids[0])
    }

    "gun game timeout ranks a dead respawning player" {
        val c = Clock()
        val runtime = ArcadeMatchRuntime(c::read)
        runtime.start(UUID.randomUUID(), EventMode.GUN_GAME, players(3), ArcadeRules(2, 16, 0, 0, 30, spawnProtectionSeconds = 0))
        runtime.tick()
        val ids = runtime.current!!.participants.keys.toList()
        runtime.eliminate(ids[1], ids[0])
        runtime.eliminate(ids[2], ids[0])
        c.now = 30_000
        runtime.tick().phase shouldBe MatchPhase.RESOLVING
        runtime.current!!.winners shouldContainExactlyInAnyOrder listOf(ids[0])
    }

    "recovery marking is idempotent and release is gated" {
        val runtime = ArcadeMatchRuntime { 0L }
        runtime.start(UUID.randomUUID(), EventMode.GUN_GAME, players(3), ArcadeRules(2, 16, 0, 0, 30))
        runtime.cancel(MatchEndReason.ADMIN)
        runtime.beginRestoring()
        val id = runtime.current!!.participants.keys.first(); runtime.markRecoveryApplied(id); runtime.markRecoveryApplied(id)
        runtime.current!!.participants.keys.filter { it != id }.forEach(runtime::markRecoveryApplied)
        runtime.release(); runtime.current shouldBe null

        val active = ArcadeMatchRuntime { 0L }
        active.start(UUID.randomUUID(), EventMode.GUN_GAME, players(3), ArcadeRules(2, 16, 0, 0, 30))
        shouldThrow<IllegalArgumentException> { active.release() }
    }

    "arcade stats replay is idempotent and leaves TTT fields untouched" {
        val matchId = UUID.randomUUID()
        val participant = ArcadePlayer(UUID.randomUUID(), "P", "spawn", kills = 3, deaths = 2)
        val initial = PlayerEventStats(matches = 9, wins = 9, karma = 777, traitorWins = 4, innocentWins = 5)
        val recorded = initial.recordArcade(matchId, participant, won = true)
        recorded.matches shouldBe 10
        recorded.wins shouldBe 10
        recorded.kills shouldBe 3
        recorded.deaths shouldBe 2
        recorded.karma shouldBe 777
        recorded.traitorWins shouldBe 4
        recorded.innocentWins shouldBe 5
        recorded.recordArcade(matchId, participant, won = true) shouldBe recorded
    }
})
