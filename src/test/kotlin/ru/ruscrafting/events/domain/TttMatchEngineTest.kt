package ru.ruscrafting.events.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.util.UUID

class TttMatchEngineTest : StringSpec({
    val allocation = RoleAllocationSettings(
        traitorPlayerRatio = 4,
        detectiveMinimumPlayers = 6,
        traitorCredits = 2,
        detectiveCredits = 1,
    )

    "role allocation is deterministic and scales traitors and detectives" {
        listOf(
            4 to (1 to 0),
            6 to (1 to 1),
            12 to (3 to 2),
            16 to (4 to 2),
        ).forEach { (size, expected) ->
            val first = TttRoleAllocator.allocate(players(size), allocation, 42)
            val second = TttRoleAllocator.allocate(players(size), allocation, 42)
            first shouldBe second
            first.values.count { it.role == TttRole.TRAITOR } shouldBe expected.first
            first.values.count { it.role == TttRole.DETECTIVE } shouldBe expected.second
            first.values.count { it.role == TttRole.INNOCENT } shouldBe size - expected.first - expected.second
        }
    }

    "match follows reserved preparation countdown active and restoration states" {
        val engine = TttMatchEngine(4, 16, 60_000)
        val created = engine.create(UUID.randomUUID(), players(6), allocation, 99, 1_000)
        created.phase shouldBe MatchPhase.RESERVED
        created.participants.values.all { it.status == ParticipantStatus.RESERVED } shouldBe true

        val preparing = engine.prepare(created)
        preparing.phase shouldBe MatchPhase.PREPARING
        val countdown = engine.countdown(preparing)
        countdown.phase shouldBe MatchPhase.COUNTDOWN
        countdown.participants.values.all { it.status == ParticipantStatus.ALIVE } shouldBe true
        val active = engine.activate(countdown, 2_000)
        active.phase shouldBe MatchPhase.ACTIVE
        active.deadlineMs shouldBe 62_000

        val cancelled = engine.cancel(active, MatchEndReason.ADMIN)
        cancelled.phase shouldBe MatchPhase.CANCELLED
        val restoring = engine.restoring(cancelled)
        restoring.phase shouldBe MatchPhase.RESTORING
        val restored = restoring.participants.keys.fold(restoring) { state, playerId -> engine.restored(state, playerId) }
        restored.phase shouldBe MatchPhase.COMPLETED
        restored.participants.values.all { it.status == ParticipantStatus.RESTORED } shouldBe true
    }

    "eliminating the final traitor ends the round for innocents" {
        val active = activeMatch(6, allocation)
        val traitor = active.participants.values.single { it.role == TttRole.TRAITOR }
        val killer = active.participants.values.first { it.role == TttRole.INNOCENT }
        val (finished, outcome) = TttMatchEngine(4, 16, 60_000).eliminate(active, traitor.playerId, killer.playerId)
        outcome shouldBe MatchOutcome.Finished(TttTeam.INNOCENTS, MatchEndReason.ELIMINATION)
        finished.phase shouldBe MatchPhase.RESOLVING
        finished.winner shouldBe TttTeam.INNOCENTS
        finished.participant(killer.playerId)?.kills shouldBe 1
    }

    "traitors win as soon as they reach parity" {
        val engine = TttMatchEngine(4, 16, 60_000)
        var active = activeMatch(4, allocation)
        val traitor = active.participants.values.single { it.role == TttRole.TRAITOR }
        val victims = active.participants.values.filter { it.role != TttRole.TRAITOR }
        val first = engine.eliminate(active, victims[0].playerId, traitor.playerId)
        first.second shouldBe MatchOutcome.Continue
        active = first.first
        val second = engine.eliminate(active, victims[1].playerId, traitor.playerId)
        second.second shouldBe MatchOutcome.Finished(TttTeam.TRAITORS, MatchEndReason.ELIMINATION)
    }

    "friendly eliminations are tracked and reduce karma" {
        val active = activeMatch(6, allocation)
        val teammates = active.participants.values.filter { it.role.team == TttTeam.INNOCENTS }.take(2)
        val (changed, _) = TttMatchEngine(4, 16, 60_000).eliminate(active, teammates[0].playerId, teammates[1].playerId)
        val killer = changed.participant(teammates[1].playerId)!!
        killer.kills shouldBe 1
        killer.friendlyKills shouldBe 1
        PlayerEventStats().record(UUID.randomUUID(), killer, null).karma shouldBe 900
    }

    "round timeout belongs to the innocent team" {
        val engine = TttMatchEngine(4, 16, 60_000)
        val active = activeMatch(6, allocation)
        val deadline = requireNotNull(active.deadlineMs)
        engine.tick(active, deadline - 1).second shouldBe MatchOutcome.Continue
        val (finished, outcome) = engine.tick(active, deadline)
        outcome shouldBe MatchOutcome.Finished(TttTeam.INNOCENTS, MatchEndReason.TIMEOUT)
        finished.phase shouldBe MatchPhase.RESOLVING
    }

    "one player cannot be allocated twice" {
        val duplicate = players(4).toMutableList().also { it[3] = it[0] }
        shouldThrow<IllegalArgumentException> { TttRoleAllocator.allocate(duplicate, allocation, 1) }
    }

    "statistics record exactly one result" {
        val participant = TttParticipant(
            UUID.randomUUID(), "Tester", "parkour", TttRole.TRAITOR, ParticipantStatus.DEAD,
            credits = 0, kills = 3, deaths = 1,
        )
        val matchId = UUID.randomUUID()
        val updated = PlayerEventStats().record(matchId, participant, TttTeam.TRAITORS)
        updated.matches shouldBe 1
        updated.wins shouldBe 1
        updated.traitorWins shouldBe 1
        updated.innocentWins shouldBe 0
        updated.kills shouldBe 3
        updated.deaths shouldBe 1
        updated.lastMatchId shouldBe matchId.toString()
        updated.record(matchId, participant, TttTeam.TRAITORS) shouldBe updated
    }
}) {
    companion object {
        private fun players(count: Int): List<QueuedPlayer> = (1..count).map { index ->
            QueuedPlayer(
                UUID.nameUUIDFromBytes("player-$index".toByteArray()),
                "Player$index",
                if (index % 2 == 0) "spawn" else "survival",
                index.toLong(),
            )
        }

        private fun activeMatch(count: Int, allocation: RoleAllocationSettings): TttMatch {
            val engine = TttMatchEngine(4, 16, 60_000)
            return engine.activate(engine.countdown(engine.prepare(
                engine.create(UUID.nameUUIDFromBytes("match-$count".toByteArray()), players(count), allocation, 42, 1_000),
            )), 2_000)
        }
    }
}
