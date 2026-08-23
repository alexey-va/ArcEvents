package ru.ruscrafting.events.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArcEventsRedisIntegrationTest : StringSpec({
    "two real Redis nodes exchange route messages and claim one reservation exactly once" {
        val port = ServerSocket(0).use { it.localPort }
        val directory = Files.createTempDirectory("arcevents-redis-")
        val process = ProcessBuilder(
            System.getenv("REDIS_SERVER_BIN") ?: "redis-server",
            "--bind", "127.0.0.1", "--port", port.toString(), "--save", "", "--appendonly", "no",
            "--dir", directory.toString(),
        ).redirectErrorStream(true).start()
        val outputDrain = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { _ -> } } }.apply {
            isDaemon = true
            start()
        }
        var spawn: RedisManager? = null
        var parkour: RedisManager? = null
        try {
            waitUntil(10_000) { runCatching { Socket("127.0.0.1", port).use { } }.isSuccess }
            val connection = RedisConnection("127.0.0.1", port)
            spawn = RedisManager(connection, ServerIdentity { "spawn" })
            parkour = RedisManager(connection, ServerIdentity { "parkour" })
            val spawnRepository = RedisEventNetworkRepository(spawn)
            val parkourRepository = RedisEventNetworkRepository(parkour)
            val latch = CountDownLatch(1)
            var route: Pair<EventNetworkMessage, String>? = null
            spawnRepository.register { message, origin ->
                if (message.signal == EventNetworkSignal.ROUTE_PLAYER) {
                    route = message to origin
                    latch.countDown()
                }
            }
            parkourRepository.register { _, _ -> }
            spawn.init()
            parkour.init()
            waitUntil(10_000) { spawn.isSubscriptionActive() && parkour.isSubscriptionActive() }

            val players = (1..4).map { UUID(0, it.toLong()) }
            players.forEachIndexed { index, playerId ->
                spawnRepository.joinQueue(playerId, "Player${index + 1}", "spawn", 1_000L + index, 60_000).join()
            }
            val matchId = UUID(0, 100)
            val batch = parkourRepository.reserve(matchId, "parkour", 4, 16, 2_000, 30_000).join()!!
            batch.entries.size shouldBe 4
            val message = EventNetworkMessage.create(
                EventNetworkSignal.ROUTE_PLAYER,
                nowMs = 2_001,
                matchId = matchId,
                playerId = players.first(),
                destinationServer = "parkour",
            )
            parkourRepository.publish(message)
            latch.await(5, TimeUnit.SECONDS) shouldBe true
            route shouldBe (message to "parkour")

            parkourRepository.claimReservation(players.first(), "spawn", 2_002).join() shouldBe null
            parkourRepository.claimReservation(players.first(), "parkour", 2_002).join()?.matchId shouldBe matchId.toString()
            parkourRepository.claimReservation(players.first(), "parkour", 2_003).join() shouldBe null
        } finally {
            spawn?.close()
            parkour?.close()
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            outputDrain.join(1_000)
            directory.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
            val deadline = System.nanoTime() + Duration.ofMillis(timeoutMs).toNanos()
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(25)
            }
            require(condition()) { "Timed out waiting for Redis state" }
        }
    }
}
