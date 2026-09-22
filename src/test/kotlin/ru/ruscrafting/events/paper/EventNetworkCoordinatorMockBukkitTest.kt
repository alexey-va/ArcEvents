package ru.ruscrafting.events.paper

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.network.BackendServerId
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.network.BackendTransfer
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.network.EventNetworkMessage
import ru.ruscrafting.events.network.EventNetworkSignal
import ru.ruscrafting.events.network.HostNode
import ru.ruscrafting.events.network.QueueEntry
import ru.ruscrafting.events.network.QueueState
import ru.ruscrafting.events.network.RedisEventNetworkRepository
import ru.ruscrafting.events.network.ReservationBatch
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EventNetworkCoordinatorMockBukkitTest : FunSpec({
    test("solo start on a relay resumes an old matched route without replacing escrow ownership") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(relay = true).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("ReturningFisher")
                fixture.seedMatched(player, UUID.randomUUID())
                val before = fixture.queueEntry(player)
                every { fixture.transfer.connect(player, BackendServerId.of("parkour")) } returns BackendTransferResult.SENT

                repeat(2) {
                    fixture.await(fixture.coordinator.reserveNow(player, mode = EventMode.FISHING)) shouldBe ReservationStartResult.RECOVERY_PENDING
                }

                verify(exactly = 1) { fixture.transfer.connect(player, BackendServerId.of("parkour")) }
                fixture.queueEntry(player) shouldBe before
                fixture.reservations shouldBe emptyList()
            }
        }
    }

    test("a failed recovery transfer preserves the old route and lets the solo player retry") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(relay = true).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("RecoveryRetry")
                fixture.seedMatched(player, UUID.randomUUID())
                val before = fixture.queueEntry(player)
                every { fixture.transfer.connect(any(), any()) } returnsMany listOf(
                    BackendTransferResult.SEND_FAILED, BackendTransferResult.SENT,
                )

                fixture.await(fixture.coordinator.reserveNow(player, mode = EventMode.FISHING)) shouldBe ReservationStartResult.NETWORK_FAILURE
                fixture.queueEntry(player) shouldBe before
                fixture.await(fixture.coordinator.reserveNow(player, mode = EventMode.FISHING)) shouldBe ReservationStartResult.RECOVERY_PENDING
                fixture.queueEntry(player) shouldBe before
                verify(exactly = 2) { fixture.transfer.connect(player, BackendServerId.of("parkour")) }
                fixture.reservations shouldBe emptyList()
            }
        }
    }

    test("fishing is restricted to its dedicated arena and never starts ownerless") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()

                fixture.coordinator.selectableArenaIds(EventMode.FISHING) shouldBe listOf("fishing")
                fixture.await(fixture.coordinator.reserveNow(mode = EventMode.FISHING)) shouldBe ReservationStartResult.NOT_OWNER
            }
        }
    }

    test("host availability follows the selected mode arena") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(includeCombatArena = false).use { fixture ->
                fixture.start()

                fixture.coordinator.hostAvailable(EventMode.TTT) shouldBe false
                fixture.coordinator.hostAvailable(EventMode.GUN_GAME) shouldBe false
                fixture.coordinator.hostAvailable(EventMode.FISHING) shouldBe true
            }
        }
    }

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
                    val requester = if (mode == EventMode.FISHING) fixture.addPlayer("FishingOwner") else null
                    fixture.queueFourPlayers()

                    fixture.await(fixture.coordinator.reserveNow(requester = requester, mode = mode)) shouldBe ReservationStartResult.STARTED
                    fixture.reservations.single().apply {
                        this.mode shouldBe mode
                        if (mode == EventMode.FISHING) {
                            entries.map { it.playerId } shouldBe listOf(requester!!.uniqueId.toString())
                            fixture.queueEntry(UUID.nameUUIDFromBytes("coordinator-1".toByteArray()))?.state shouldBe QueueState.QUEUED
                        } else {
                            entries.map { it.mode }.distinct() shouldBe listOf(mode.id)
                        }
                    }
                }
            }
        }
    }

    test("recovered player is not transferred until RETURN_PENDING is durable") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("Recovered")
                fixture.coordinator.returnRecoveredPlayer(player, PlayerRecovery(UUID.randomUUID(), "spawn"))
                fixture.tick(3)

                verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
            }
        }
    }

    test("recovered player retries a failed return transfer while still online") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("RecoveredRetry")
                val matchId = UUID.randomUUID()
                fixture.seedMatched(player, matchId)
                var transferCalls = 0
                every { fixture.transfer.connect(any(), any()) } answers {
                    transferCalls++
                    if (transferCalls == 1) BackendTransferResult.SEND_FAILED else BackendTransferResult.SENT
                }

                fixture.coordinator.returnRecoveredPlayer(player, PlayerRecovery(matchId, "spawn"))
                fixture.tick(3)
                transferCalls shouldBe 1

                fixture.coordinator.reconfigure()
                fixture.tick(3)
                transferCalls shouldBe 2
                fixture.queueEntry(player)?.state shouldBe QueueState.RETURN_PENDING
            }
        }
    }

    test("reservation transfer retries while the player remains online") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("ReservationRetry")
                val matchId = UUID.randomUUID()
                fixture.seedReserved(player, matchId, "survival")
                var transferCalls = 0
                every { fixture.transfer.connect(any(), any()) } answers {
                    transferCalls++
                    if (transferCalls == 1) BackendTransferResult.SEND_FAILED else BackendTransferResult.SENT
                }

                fixture.publishRoute(matchId, player, "survival")
                fixture.tick(3)
                transferCalls shouldBe 1
                fixture.queueEntry(player)?.state shouldBe QueueState.RESERVED

                fixture.coordinator.reconfigure()
                fixture.tick(3)
                transferCalls shouldBe 2
                fixture.queueEntry(player)?.state shouldBe QueueState.RESERVED
            }
        }
    }

    test("stale reservation transfer retry is pruned before sending") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("StaleRetry")
                val matchId = UUID.randomUUID()
                fixture.seedReserved(player, matchId, "survival")
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SEND_FAILED

                fixture.publishRoute(matchId, player, "survival")
                fixture.tick(3)
                fixture.releaseReservation(player, matchId)
                fixture.queueEntry(player)?.state shouldBe QueueState.RETURN_PENDING

                fixture.coordinator.reconfigure()
                fixture.tick(3)
                verify(exactly = 1) { fixture.transfer.connect(player, BackendServerId.of("survival")) }
            }
        }
    }

    test("expired reservation does not acknowledge while outbound transfer is pending") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DelayedTransfer")
                val matchId = UUID.randomUUID()
                fixture.seedReserved(player, matchId, "survival")
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT
                fixture.publishRoute(matchId, player, "survival")
                fixture.tick(3)

                fixture.advanceTime(11L)
                fixture.coordinator.reconfigure()
                fixture.tick(5)

                fixture.queueEntry(player)?.state shouldBe QueueState.RETURN_PENDING
                verify(exactly = 1) { fixture.transfer.connect(player, BackendServerId.of("survival")) }
            }
        }
    }
})

private class EventNetworkCoordinatorFixture(
    private val includeCombatArena: Boolean = true,
    private val relay: Boolean = false,
) : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcEventsNetworkCoordinatorTest")
    private val dataRoot = Files.createTempDirectory("arcevents-network-coordinator-")
    private var nowMs = 1_787_730_000_000L
    private val redisStore = InMemoryRedis(ServerIdentity { "parkour" })
    private val repository = RedisEventNetworkRepository(redisStore)
    val reservations = mutableListOf<ReservationBatch>()
    val transfer = mockk<BackendTransfer>(relaxed = true)
    private val settings: ArcEventsConfig
    private val locale: ArcEventsLocale
    val coordinator: EventNetworkCoordinator

    init {
        PaperArcRuntime.installScheduling(plugin)
        ConfigManager.clear()
        ArcEventsConfig.mergeMissing(dataRoot)
        val configPath = dataRoot.resolve("config.yml")
        Files.writeString(configPath, if (relay) Files.readString(configPath).replace("server-id: parkour", "server-id: spawn")
            else Files.readString(configPath).replace("node-mode: RELAY", "node-mode: HOST"))
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
            transfer = transfer,
            debug = ArcEventsDebug({ true }) {},
            matchState = { null to null },
            arenaReady = { true },
            readyArenaIds = {
                buildList {
                    if (includeCombatArena) add("test")
                    add("fishing")
                    add("disasters")
                }
            },
            onReservation = { batch -> reservations += batch; true },
            onArrival = { true },
            clock = { nowMs },
        )
    }

    fun start() {
        if (relay) {
            repository.openNodes({ true }, { true }, 60_000L, { nowMs }).use { presence ->
                presence.publish(HostNode("parkour", "HOST", true, true, null, null, 0, 16, nowMs,
                    listOf("fishing"), listOf(EventMode.FISHING.id))).join()
            }
        }
        coordinator.start()
        tick(3)
    }

    fun addPlayer(name: String) = paper.addPlayer(name)

    fun tick(count: Int) {
        repeat(count) {
            paper.performTicks(1)
            Thread.sleep(2L)
        }
    }

    fun advanceTime(deltaMs: Long) {
        nowMs += deltaMs
    }

    fun seedMatched(player: org.bukkit.entity.Player, matchId: UUID) {
        repository.joinQueue(player.uniqueId, player.name, "spawn", nowMs, 60_000L).get(5, TimeUnit.SECONDS)
        repository.reserveForRequester(matchId, "parkour", player.uniqueId, "spawn", nowMs, 30_000L, EventMode.FISHING.id).get(5, TimeUnit.SECONDS)
        repository.claimReservation(player.uniqueId, "parkour", nowMs + 1).get(5, TimeUnit.SECONDS)
        repository.completeReservation(matchId, listOf(player.uniqueId)).get(5, TimeUnit.SECONDS)
    }

    fun seedReserved(player: org.bukkit.entity.Player, matchId: UUID, destination: String) {
        repository.joinQueue(player.uniqueId, player.name, settings.serverId, nowMs, 60_000L).get(5, TimeUnit.SECONDS)
        repository.reserve(matchId, destination, 1, 1, nowMs, 10L).get(5, TimeUnit.SECONDS)
    }

    fun publishRoute(matchId: UUID, player: org.bukkit.entity.Player, destination: String) {
        val message = EventNetworkMessage.create(
            signal = EventNetworkSignal.ROUTE_PLAYER,
            nowMs = nowMs,
            matchId = matchId,
            playerId = player.uniqueId,
            destinationServer = destination,
        )
        redisStore.simulateExternalMessage(
            RedisEventNetworkRepository.EVENT_CHANNEL,
            Gson().toJson(message),
            "spawn",
        )
    }

    fun queueEntry(player: org.bukkit.entity.Player): QueueEntry? = queueEntry(player.uniqueId)

    fun queueEntry(playerId: UUID): QueueEntry? = repository.loadQueueEntry(playerId).get(5, TimeUnit.SECONDS)

    fun releaseReservation(player: org.bukkit.entity.Player, matchId: UUID) {
        repository.releaseReservation(matchId, listOf(player.uniqueId)).get(5, TimeUnit.SECONDS)
    }

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
