package ru.ruscrafting.events.paper

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.network.BackendTransfer
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.network.HostNode
import ru.ruscrafting.events.network.RedisEventNetworkRepository
import ru.ruscrafting.events.network.ReservationBatch
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EventNetworkCoordinatorMockBukkitTest : FunSpec({
    test("host heartbeat and every mode reservation use their own player limits") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                fixture.heartbeat().apply {
                    capacity shouldBe 16
                    supportedModes shouldContainExactlyInAnyOrder EventMode.entries.map(EventMode::id)
                }
            }

            EventMode.entries.forEach { mode ->
                EventNetworkCoordinatorFixture().use { fixture ->
                    fixture.start()
                    fixture.queueFourPlayers()

                    fixture.await(fixture.coordinator.reserveNow(mode = mode)) shouldBe ReservationStartResult.STARTED
                    fixture.reservations.single().apply {
                        this.mode shouldBe mode
                        entries.map { it.mode }.distinct() shouldBe listOf(mode.id)
                    }
                }
            }
        }
    }
})

private class EventNetworkCoordinatorFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcEventsNetworkCoordinatorTest")
    private val dataRoot = Files.createTempDirectory("arcevents-network-coordinator-")
    private val nowMs = 1_787_730_000_000L
    private val redisStore = InMemoryRedis(ServerIdentity { "parkour" })
    private val repository = RedisEventNetworkRepository(redisStore)
    val reservations = mutableListOf<ReservationBatch>()
    private val settings: ArcEventsConfig
    private val locale: ArcEventsLocale
    val coordinator: EventNetworkCoordinator

    init {
        PaperArcRuntime.installScheduling(plugin)
        ConfigManager.clear()
        ArcEventsConfig.mergeMissing(dataRoot)
        val configPath = dataRoot.resolve("config.yml")
        Files.writeString(configPath, Files.readString(configPath).replace("node-mode: RELAY", "node-mode: HOST"))
        copyResource("lang/ru.yml")
        copyResource("lang/en.yml")
        settings = ArcEventsConfig.inspect(dataRoot)
        locale = ArcEventsLocale(dataRoot) { settings }
        coordinator = EventNetworkCoordinator(
            plugin = plugin,
            settings = { settings },
            locale = locale,
            repository = repository,
            redis = mockk<RedisManager>(relaxed = true),
            transfer = mockk<BackendTransfer>(relaxed = true),
            debug = ArcEventsDebug({ true }) {},
            matchState = { null to null },
            arenaReady = { true },
            readyArenaIds = { listOf("test", "disasters") },
            onReservation = { batch -> reservations += batch; true },
            onArrival = { true },
            clock = { nowMs },
        )
    }

    fun start() = coordinator.start()

    fun heartbeat(): HostNode = redisStore.loadMap(RedisEventNetworkRepository.NODES_KEY).get(5, TimeUnit.SECONDS)
        .getValue(settings.serverId)
        .let { Gson().fromJson(it, HostNode::class.java).validated() }

    fun queueFourPlayers() {
        (1..4).forEach { index ->
            repository.joinQueue(
                UUID.nameUUIDFromBytes("coordinator-$index".toByteArray()),
                "Player$index",
                settings.serverId,
                nowMs + index,
                60_000L,
            ).get(5, TimeUnit.SECONDS)
        }
    }

    fun <T> await(future: CompletableFuture<T>): T {
        repeat(100) {
            paper.performTicks(1)
            if (future.isDone) return future.get(5, TimeUnit.SECONDS)
            Thread.sleep(2L)
        }
        error("Timed out waiting for coordinator callback")
    }

    override fun close() {
        coordinator.close()
        Tasks.reset()
        ConfigManager.clear()
        paper.close()
        check(dataRoot.toFile().deleteRecursively()) { "Failed to delete $dataRoot" }
    }

    private fun copyResource(name: String) {
        val target = dataRoot.resolve(name)
        Files.createDirectories(requireNotNull(target.parent))
        requireNotNull(EventNetworkCoordinatorMockBukkitTest::class.java.getResourceAsStream("/$name")).use { source ->
            Files.copy(source, target)
        }
    }
}
