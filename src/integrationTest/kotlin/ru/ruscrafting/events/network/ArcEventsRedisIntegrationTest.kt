package ru.ruscrafting.events.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.redis.network.RedisRequestReplyChannel
import ru.arc.redis.network.RedisRequestResult
import ru.arc.redis.network.RedisRequestTimeoutHandle
import ru.arc.redis.network.RedisRequestTimeoutScheduler
import ru.arc.testing.containers.RedisTestService
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ArcEventsRedisIntegrationTest : StringSpec({
    "two real Redis nodes complete remote start routing and preserve return acknowledgement" {
        RedisTestService.start().use { service ->
            var spawn: RedisManager? = null
            var parkour: RedisManager? = null
            try {
                val connection = RedisConnection(service.endpoint.host, service.endpoint.port)
                spawn = RedisManager(connection, ServerIdentity { "spawn" })
                parkour = RedisManager(connection, ServerIdentity { "parkour" })
                val spawnRepository = RedisEventNetworkRepository(spawn)
                val parkourRepository = RedisEventNetworkRepository(parkour)
                val routeLatch = CountDownLatch(4)
                val routes = java.util.Collections.synchronizedList(mutableListOf<Pair<EventNetworkMessage, String>>())
                val reservedBatch = AtomicReference<ReservationBatch?>()
                val matchId = UUID(0, 100)
                ScheduledRedisTimeouts().use { timeouts ->
                    val spawnEvents = spawnRepository.openMessages(
                        originAllowed = { it == "parkour" },
                        replyAllowed = { request, reply, origin ->
                            request.signal == EventNetworkSignal.START_REQUEST &&
                                reply.signal == EventNetworkSignal.START_RESULT &&
                                origin == "parkour" && reply.destinationServer == "spawn"
                        },
                        timeoutScheduler = timeouts,
                    ) { message, origin ->
                        if (message.signal == EventNetworkSignal.ROUTE_PLAYER) {
                            routes += message to origin
                            routeLatch.countDown()
                        }
                    }
                    lateinit var parkourEvents: RedisRequestReplyChannel<EventNetworkMessage>
                    parkourEvents = parkourRepository.openMessages(
                        originAllowed = { it == "spawn" },
                        replyAllowed = { _, _, _ -> false },
                        timeoutScheduler = timeouts,
                    ) { message, origin ->
                        if (message.signal == EventNetworkSignal.START_REQUEST && message.destinationServer == "parkour") {
                            parkourRepository.reserve(matchId, "parkour", 4, 16, 2_000, 30_000).whenComplete { batch, failure ->
                                require(failure == null && batch != null)
                                reservedBatch.set(batch)
                                batch.entries.forEach { entry ->
                                    parkourEvents.publish(EventNetworkMessage.create(
                                        signal = EventNetworkSignal.ROUTE_PLAYER,
                                        nowMs = 2_001,
                                        matchId = batch.matchId,
                                        playerId = UUID.fromString(entry.playerId),
                                        destinationServer = "parkour",
                                    ))
                                }
                                parkourEvents.publish(EventNetworkMessage.create(
                                    signal = EventNetworkSignal.START_RESULT,
                                    nowMs = 2_002,
                                    destinationServer = origin,
                                    replyTo = message.eventId,
                                    startResult = "STARTED",
                                ))
                            }
                        }
                    }
                    spawn.init()
                    parkour.init()
                    waitUntil(10_000) { spawn.isSubscriptionActive() && parkour.isSubscriptionActive() }

                    val players = (1..4).map { UUID(0, it.toLong()) }
                    players.forEachIndexed { index, playerId ->
                        val origin = if (index % 2 == 0) "spawn" else "survival"
                        spawnRepository.joinQueue(playerId, "Player${index + 1}", origin, 1_000L + index, 60_000).join()
                    }
                    val request = EventNetworkMessage.create(
                        signal = EventNetworkSignal.START_REQUEST,
                        nowMs = 1_500,
                        destinationServer = "parkour",
                    )
                    val startResult = spawnEvents.request(request).get(5, TimeUnit.SECONDS)
                    routeLatch.await(5, TimeUnit.SECONDS) shouldBe true

                    val batch = reservedBatch.get()!!
                    batch.entries.size shouldBe 4
                    batch.entries.map(QueueEntry::originServer).toSet() shouldBe setOf("spawn", "survival")
                    val reply = startResult as RedisRequestResult.Reply
                    reply.message.replyTo shouldBe request.eventId
                    reply.message.destinationServer shouldBe "spawn"
                    reply.message.startResult shouldBe "STARTED"
                    reply.originServer shouldBe "parkour"
                    routes.map { it.first.playerId }.toSet() shouldBe players.map(UUID::toString).toSet()
                    routes.all { (message, origin) ->
                        message.matchId == matchId.toString() &&
                            message.destinationServer == "parkour" &&
                            origin == "parkour"
                    } shouldBe true

                    parkourRepository.claimReservation(players.first(), "spawn", 2_002).join() shouldBe null
                    val arrived = parkourRepository.claimReservation(players.first(), "parkour", 2_002).join()!!
                    arrived.matchId shouldBe matchId.toString()
                    arrived.state shouldBe QueueState.ARRIVED
                    parkourRepository.claimReservation(players.first(), "parkour", 2_003).join() shouldBe arrived

                    val released = parkourRepository.releaseReservation(matchId).join()
                    released.size shouldBe 4
                    spawnRepository.claimReservation(players.first(), "spawn", 2_004).join()?.state shouldBe
                        QueueState.RETURN_PENDING
                    spawnRepository.acknowledgeReturn(players.first(), matchId).join() shouldBe true
                    parkourRepository.claimReservation(players.first(), "parkour", 2_005).join() shouldBe null
                    spawnEvents.close()
                    parkourEvents.close()
                }
            } finally {
                spawn?.close()
                parkour?.close()
            }
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

private class ScheduledRedisTimeouts : RedisRequestTimeoutScheduler, AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    override fun schedule(delayMillis: Long, action: () -> Unit): RedisRequestTimeoutHandle {
        val future = executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS)
        return RedisRequestTimeoutHandle { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}
