package ru.ruscrafting.events.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
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
        repository.leaveQueue(player, 2_000).join() shouldBe QueueLeaveResult.Left
        repository.leaveQueue(player, 2_000).join() shouldBe QueueLeaveResult.Missing
    }

    "host reserves the oldest bounded roster and claims it only at its destination" {
        val repository = RedisEventNetworkRepository(InMemoryRedis(ServerIdentity { "parkour" }))
        (1..6).forEach { index ->
            repository.joinQueue(uuid(index), "Player$index", if (index % 2 == 0) "spawn" else "survival", index * 1_000L, 120_000).join()
        }
        val matchId = uuid(100)
        val batch = repository.reserve(matchId, "parkour", 4, 5, 10_000, 30_000).join()!!
        batch.entries shouldHavePlayerIds (1..5).map(::uuid)
        repository.leaveQueue(uuid(1), 11_000).join()::class shouldBe QueueLeaveResult.Reserved::class
        repository.claimReservation(uuid(1), "spawn", 11_000).join() shouldBe null
        repository.claimReservation(uuid(1), "parkour", 11_000).join()?.playerId shouldBe uuid(1).toString()
        repository.claimReservation(uuid(1), "parkour", 11_001).join() shouldBe null
        repository.loadQueue(11_000).join().count { it.state == QueueState.QUEUED } shouldBe 1
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
        val update = repository.updateStats(uuid(9)) { it.record(participant, TttTeam.TRAITORS) }.join()
        update.before.matches shouldBe 0
        update.after.matches shouldBe 1
        repository.loadStats(uuid(9)).join() shouldBe update.after
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
