package ru.ruscrafting.events.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import com.google.gson.Gson
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.events.domain.PlayerEventStats
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.domain.ParticipantStatus
import java.util.UUID

class RedisEventNetworkRepositoryTest : StringSpec({
    "queue join is idempotent and leave removes only a queued entry" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val repository = RedisEventNetworkRepository(redis)
        val player = uuid(1)
        repository.joinQueue(player, "Player1", "spawn", 1_000, 60_000).join()::class shouldBe QueueJoinResult.Joined::class
        repository.joinQueue(player, "Player1", "spawn", 2_000, 60_000).join()::class shouldBe QueueJoinResult.Existing::class
        repository.loadQueue(2_000).join().map(QueueEntry::playerId) shouldBe listOf(player.toString())
        repository.loadQueueEntry(player).join()?.playerId shouldBe player.toString()
        repository.leaveQueue(player, 2_000).join() shouldBe QueueLeaveResult.Left
        repository.leaveQueue(player, 2_000).join() shouldBe QueueLeaveResult.Missing
        repository.loadQueueEntry(player).join() shouldBe null
    }

    "queued count reads shared state and excludes reservations" {
        val repository = RedisEventNetworkRepository(InMemoryRedis(ServerIdentity { "spawn" }))
        repository.joinQueue(uuid(1), "Player1", "spawn", 1_000, 60_000).join()
        repository.joinQueue(uuid(2), "Player2", "survival", 1_001, 60_000).join()

        repository.loadQueuedCount(2_000).join() shouldBe 2

        repository.reserve(uuid(9), "parkour", 1, 1, 2_001, 60_000).join()
        repository.loadQueuedCount(2_002).join() shouldBe 1
    }

    "queued player refreshes the current origin without losing FIFO position" {
        val repository = RedisEventNetworkRepository(InMemoryRedis(ServerIdentity { "spawn" }))
        val player = uuid(7)
        val joined = repository.joinQueue(player, "Player7", "survival", 1_000, 60_000).join() as QueueJoinResult.Joined

        val refreshed = repository.joinQueue(player, "Player7", "spawn", 2_000, 60_000).join() as QueueJoinResult.Existing

        refreshed.entry.originServer shouldBe "spawn"
        refreshed.entry.joinedAtMs shouldBe joined.entry.joinedAtMs
        refreshed.entry.expiresAtMs shouldBe joined.entry.expiresAtMs
        repository.loadQueue(2_000).join().single() shouldBe refreshed.entry
    }

    "reserved player keeps the committed origin when another backend repeats join" {
        val repository = RedisEventNetworkRepository(InMemoryRedis(ServerIdentity { "parkour" }))
        (1..4).forEach { repository.joinQueue(uuid(it), "Player$it", "survival", it.toLong(), 60_000).join() }
        val matchId = uuid(99)
        repository.reserve(matchId, "parkour", 4, 4, 5_000, 30_000).join()!!

        val existing = repository.joinQueue(uuid(1), "Player1", "spawn", 6_000, 60_000).join() as QueueJoinResult.Existing

        existing.entry.state shouldBe QueueState.RESERVED
        existing.entry.originServer shouldBe "survival"
        existing.entry.matchId shouldBe matchId.toString()
    }

    "host reserves the oldest bounded roster and claims arrival idempotently only at its destination" {
        val repository = RedisEventNetworkRepository(InMemoryRedis(ServerIdentity { "parkour" }))
        (1..6).forEach { index ->
            repository.joinQueue(uuid(index), "Player$index", if (index % 2 == 0) "spawn" else "survival", index * 1_000L, 120_000).join()
        }
        val matchId = uuid(100)
        val batch = repository.reserve(matchId, "parkour", 4, 5, 10_000, 30_000).join()!!
        batch.entries shouldHavePlayerIds (1..5).map(::uuid)
        repository.leaveQueue(uuid(1), 11_000).join()::class shouldBe QueueLeaveResult.Reserved::class
        repository.claimReservation(uuid(1), "spawn", 11_000).join() shouldBe null
        val arrived = repository.claimReservation(uuid(1), "parkour", 11_000).join()!!
        arrived.playerId shouldBe uuid(1).toString()
        arrived.state shouldBe QueueState.ARRIVED
        repository.claimReservation(uuid(1), "parkour", 11_001).join() shouldBe arrived
        repository.loadQueue(11_000).join().count { it.state == QueueState.QUEUED } shouldBe 1
    }

    "cancelled reservation preserves every origin until return is acknowledged" {
        val repository = RedisEventNetworkRepository(InMemoryRedis(ServerIdentity { "parkour" }))
        (1..4).forEach { index ->
            repository.joinQueue(uuid(index), "Player$index", if (index % 2 == 0) "spawn" else "survival", index * 1_000L, 120_000).join()
        }
        val matchId = uuid(101)
        repository.reserve(matchId, "parkour", 4, 4, 10_000, 30_000).join()!!
        repository.claimReservation(uuid(1), "parkour", 11_000).join()?.state shouldBe QueueState.ARRIVED

        val released = repository.releaseReservation(matchId).join()
        released.size shouldBe 4
        released.all { it.state == QueueState.RETURN_PENDING && it.expiresAtMs == Long.MAX_VALUE } shouldBe true
        repository.loadQueue(Long.MAX_VALUE - 1).join().size shouldBe 4
        repository.claimReservation(uuid(1), "survival", 99_000).join()?.state shouldBe QueueState.RETURN_PENDING
        repository.acknowledgeReturn(uuid(1), matchId).join() shouldBe true
        repository.claimReservation(uuid(1), "parkour", 99_001).join() shouldBe null
        repository.loadQueue(99_001).join().map(QueueEntry::playerId).contains(uuid(1).toString()) shouldBe false
    }

    "durable escrow handoff keeps every origin until recovered return is acknowledged" {
        val repository = RedisEventNetworkRepository(InMemoryRedis(ServerIdentity { "parkour" }))
        (1..4).forEach { repository.joinQueue(uuid(it), "Player$it", "spawn", it.toLong(), 60_000).join() }
        val matchId = uuid(102)
        repository.reserve(matchId, "parkour", 4, 4, 5_000, 30_000).join()!!
        (1..4).forEach { repository.claimReservation(uuid(it), "parkour", 6_000).join()?.state shouldBe QueueState.ARRIVED }

        repository.completeReservation(matchId, (1..4).map(::uuid)).join() shouldBe 4
        repository.loadQueue(6_001).join().all { it.state == QueueState.MATCHED } shouldBe true
        repository.claimReservation(uuid(1), "parkour", 6_001).join()?.state shouldBe QueueState.MATCHED
        repository.completeReservation(matchId, (1..4).map(::uuid)).join() shouldBe 4
        repository.releaseReservation(matchId, listOf(uuid(2))).join().single().state shouldBe QueueState.RETURN_PENDING
        repository.claimReservation(uuid(1), "parkour", 6_001).join()?.state shouldBe QueueState.MATCHED
        repository.prepareRecoveredReturn(uuid(1), matchId).join()?.state shouldBe QueueState.RETURN_PENDING
        repository.claimReservation(uuid(1), "spawn", 6_002).join()?.state shouldBe QueueState.RETURN_PENDING
        repository.acknowledgeReturn(uuid(1), matchId).join() shouldBe true
        repository.loadQueue(6_003).join().map(QueueEntry::playerId).contains(uuid(1).toString()) shouldBe false
    }

    "insufficient reservation leaves the queue intact" {
        val repository = RedisEventNetworkRepository(InMemoryRedis())
        (1..3).forEach { repository.joinQueue(uuid(it), "Player$it", "spawn", it.toLong(), 60_000).join() }
        repository.reserve(uuid(200), "parkour", 4, 16, 5_000, 30_000).join() shouldBe null
        repository.loadQueue(5_000).join().all { it.state == QueueState.QUEUED } shouldBe true
    }

    "host heartbeats expire and statistics update by CAS" {
        val repository = RedisEventNetworkRepository(InMemoryRedis())
        repository.saveNode(HostNode("parkour", "HOST", true, true, null, null, 4, 16, 10_000)).join()
        repository.loadNodes(20_000, 15_000).join().map(HostNode::serverId) shouldBe listOf("parkour")
        repository.loadNodes(30_001, 15_000).join() shouldBe emptyList()

        val participant = TttParticipant(uuid(9), "Player9", "spawn", TttRole.TRAITOR, ParticipantStatus.DEAD, 0, 2, 1)
        val matchId = uuid(900)
        val update = repository.updateStats(uuid(9)) { it.record(matchId, participant, TttTeam.TRAITORS) }.join()
        update.before.matches shouldBe 0
        update.after.matches shouldBe 1
        repository.loadStats(uuid(9)).join() shouldBe update.after
        repository.updateStats(uuid(9)) { it.record(matchId, participant, TttTeam.TRAITORS) }.join().after shouldBe update.after
    }

    "legacy statistics JSON remains compatible without an idempotency field" {
        val legacy = Gson().fromJson(
            """{"revision":3,"matches":2,"wins":1,"traitorWins":1,"innocentWins":0,"kills":4,"deaths":2,"karma":900}""",
            PlayerEventStats::class.java,
        ).validated()
        legacy.lastMatchId shouldBe null
        legacy.matches shouldBe 2
        legacy.record(uuid(901), TttParticipant(uuid(9), "Player9", "spawn", TttRole.INNOCENT, ParticipantStatus.ALIVE, 0), TttTeam.INNOCENTS)
            .matches shouldBe 3
    }

    "network listener receives validated origin metadata" {
        val redis = InMemoryRedis(ServerIdentity { "spawn" })
        val repository = RedisEventNetworkRepository(redis)
        var received: Pair<EventNetworkMessage, String>? = null
        repository.register { message, origin -> received = message to origin }
        val message = EventNetworkMessage.create(EventNetworkSignal.QUEUE_CHANGED, nowMs = 1_000, queueSize = 4)
        repository.publish(message)
        received shouldBe (message to "spawn")
    }
}) {
    companion object {
        private fun uuid(value: Int): UUID = UUID(0, value.toLong())

        private infix fun List<QueueEntry>.shouldHavePlayerIds(expected: List<UUID>) {
            map { UUID.fromString(it.playerId) } shouldBe expected
        }
    }
}
