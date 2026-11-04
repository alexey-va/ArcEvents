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
import ru.arc.redis.RedisOperations
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.redis.ServerIdentity
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FirearmSoundPreset
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
    test("shared firearm sound loads on startup propagates trusted invalidations and repairs missed messages") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.persistSound(FirearmSoundPreset.SHARP)
                fixture.start()
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.SHARP

                fixture.persistSound(FirearmSoundPreset.HEAVY)
                fixture.publishSoundChange("unknown")
                fixture.tick(2)
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.SHARP
                fixture.publishSoundChange("spawn")
                fixture.tick(2)
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.HEAVY

                fixture.persistSound(FirearmSoundPreset.ECHO)
                fixture.coordinator.reconfigure()
                fixture.tick(2)
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.ECHO
            }
        }
    }

    test("firearm selection waits for durable completion and reads the authoritative value after reordered acknowledgements") {
        failOnUnsupportedMockBukkitOperation {
            lateinit var controlled: ControlledFirearmSoundRedis
            EventNetworkCoordinatorFixture(repositoryRedis = { ControlledFirearmSoundRedis(it).also { value -> controlled = value } }).use { fixture ->
                fixture.start()
                controlled.delayWrites = true
                val first = fixture.coordinator.selectFirearmSound(FirearmSoundPreset.SHARP)
                controlled.awaitStoredWrite(0)
                val second = fixture.coordinator.selectFirearmSound(FirearmSoundPreset.HEAVY)
                controlled.awaitStoredWrite(1)
                first.isDone shouldBe false
                second.isDone shouldBe false
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.ORIGINAL
                controlled.writes[1].complete(Unit)
                second.join() shouldBe FirearmSoundPreset.HEAVY
                controlled.writes[0].complete(Unit)
                first.join() shouldBe FirearmSoundPreset.HEAVY
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.HEAVY

                val failed = fixture.coordinator.selectFirearmSound(FirearmSoundPreset.ECHO)
                controlled.awaitStoredWrite(2)
                controlled.writes[2].completeExceptionally(IllegalStateException("Redis write failed"))
                failed.isCompletedExceptionally shouldBe true
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.HEAVY
            }
        }
    }

    test("stale firearm refreshes and completions after close cannot overwrite the active sound") {
        failOnUnsupportedMockBukkitOperation {
            lateinit var controlled: ControlledFirearmSoundRedis
            EventNetworkCoordinatorFixture(repositoryRedis = { ControlledFirearmSoundRedis(it).also { value -> controlled = value } }).use { fixture ->
                fixture.start()
                fixture.persistSound(FirearmSoundPreset.SHARP)
                controlled.delayReads = true
                fixture.coordinator.reconfigure()
                controlled.awaitReadCount(1)
                fixture.persistSound(FirearmSoundPreset.HEAVY)
                fixture.coordinator.reconfigure()
                controlled.awaitReadCount(2)
                controlled.completeRead(1)
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.HEAVY
                controlled.completeRead(0)
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.HEAVY

                val closedSelection = fixture.coordinator.selectFirearmSound(FirearmSoundPreset.ECHO)
                controlled.awaitReadCount(3)
                fixture.coordinator.close()
                controlled.completeRead(2)
                closedSelection.isCompletedExceptionally shouldBe true
                fixture.coordinator.firearmSoundPreset() shouldBe FirearmSoundPreset.HEAVY
            }
        }
    }

    test("disposable host preserves stale match routes for the origin without redirecting a later join") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(disposable = true).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DStale")
                val matchId = UUID.randomUUID()
                fixture.seedMatched(player, matchId)
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT

                fixture.coordinator.handleJoin(player)
                fixture.tick(4)
                requireNotNull(fixture.queueEntry(player)).state shouldBe QueueState.RETURN_PENDING

                // A subsequent unrelated backend join must not replay the old return transfer.
                fixture.coordinator.handleJoin(player)
                fixture.tick(4)
                requireNotNull(fixture.queueEntry(player)).let {
                    it.matchId shouldBe matchId.toString()
                    it.originServer shouldBe "spawn"
                    it.state shouldBe QueueState.RETURN_PENDING
                }
                verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
            }
        }
    }

    test("disposable host sends an explicit current recovery return") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(disposable = true).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DReturn")
                val matchId = UUID.randomUUID()
                fixture.seedMatched(player, matchId)
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT

                fixture.coordinator.returnRecoveredPlayer(player, PlayerRecovery(matchId, "spawn"))
                fixture.tick(4)

                requireNotNull(fixture.queueEntry(player)).let {
                    it.matchId shouldBe matchId.toString()
                    it.originServer shouldBe "spawn"
                    it.state shouldBe QueueState.RETURN_PENDING
                }
                verify(exactly = 1) { fixture.transfer.connect(player, BackendServerId.of("spawn")) }
            }
        }
    }

    test("rapid disposable-host rejoin revokes a pending recovery return callback and retry") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(disposable = true).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DRapid")
                val matchId = UUID.randomUUID()
                fixture.seedMatched(player, matchId)
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT

                // Queue the async return callback, then model a new backend join before it runs.
                fixture.coordinator.returnRecoveredPlayer(player, PlayerRecovery(matchId, "spawn"))
                fixture.coordinator.handleJoin(player)
                fixture.tick(4)
                fixture.coordinator.reconfigure()
                fixture.tick(4)

                requireNotNull(fixture.queueEntry(player)).let {
                    it.matchId shouldBe matchId.toString()
                    it.originServer shouldBe "spawn"
                    it.state shouldBe QueueState.RETURN_PENDING
                }
                verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
            }
        }
    }

    test("disposable host acknowledges an exact return route when it is the origin") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(disposable = true).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DAck")
                val matchId = UUID.randomUUID()
                fixture.seedMatched(player, matchId, origin = "parkour")
                fixture.markReturnPending(player)

                fixture.coordinator.handleJoin(player)
                fixture.tick(4)

                fixture.queueEntry(player) shouldBe null
                verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
            }
        }
    }

    test("disposable host leaves an exact active match route alone") {
        failOnUnsupportedMockBukkitOperation {
            lateinit var activeRoute: (QueueEntry) -> Boolean
            EventNetworkCoordinatorFixture(
                disposable = true,
                activeMatchParticipant = { entry -> activeRoute(entry) },
            ).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DActive")
                val matchId = UUID.randomUUID()
                fixture.seedMatched(player, matchId)
                activeRoute = { entry -> entry.playerId == player.uniqueId.toString() && entry.matchId == matchId.toString() }
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT

                fixture.coordinator.handleJoin(player)
                fixture.tick(4)

                fixture.queueEntry(player)?.state shouldBe QueueState.MATCHED
                verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
            }
        }
    }

    test("disposable host converts an orphan arrival to a durable return without transferring") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(disposable = true, onArrival = { false }).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DOrphan")
                val matchId = UUID.randomUUID()
                fixture.seedReserved(player, matchId, "parkour", origin = "spawn")
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT

                fixture.coordinator.handleJoin(player)
                fixture.tick(4)

                requireNotNull(fixture.queueEntry(player)).state shouldBe QueueState.RETURN_PENDING
                verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
            }
        }
    }

    test("disposable host ignores an old return message without a current recovery attempt") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture(disposable = true).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("DOldMsg")
                val matchId = UUID.randomUUID()
                fixture.seedMatched(player, matchId)
                fixture.markReturnPending(player)
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT

                fixture.publishReturn(matchId, player, "spawn")
                fixture.tick(4)

                requireNotNull(fixture.queueEntry(player)).state shouldBe QueueState.RETURN_PENDING
                verify(exactly = 0) { fixture.transfer.connect(any(), any()) }
            }
        }
    }

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

    test("solo Zombies starts only the requester and repeated clicks keep queued peers untouched") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val peer = fixture.addPlayer("QueuedPeer")
                fixture.queuePlayer(peer)
                val peerBefore = fixture.queueEntry(peer)
                fixture.advanceTime(1)
                val requester = fixture.addPlayer("SoloZombies")

                fixture.await(fixture.coordinator.reserveZombiesSolo(requester)) shouldBe ReservationStartResult.STARTED
                fixture.await(fixture.coordinator.reserveZombiesSolo(requester)) shouldBe ReservationStartResult.RECOVERY_PENDING
                fixture.reservations.single().apply {
                    mode shouldBe EventMode.ZOMBIES
                    entries.map(QueueEntry::playerId) shouldBe listOf(requester.uniqueId.toString())
                }
                fixture.queueEntry(peer) shouldBe peerBefore
            }
        }
    }

    test("ordinary Zombies team starts retain the creator gate and consume the full roster") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val owner = fixture.addPlayer("ZombiesOwner")
                fixture.queuePlayer(owner)
                fixture.advanceTime(1)
                val teammate = fixture.addPlayer("ZombiesMember")

                fixture.await(fixture.coordinator.reserveNow(teammate, mode = EventMode.ZOMBIES)) shouldBe ReservationStartResult.NOT_OWNER
                fixture.reservations shouldBe emptyList()
                fixture.await(fixture.coordinator.reserveNow(owner, mode = EventMode.ZOMBIES)) shouldBe ReservationStartResult.STARTED
                fixture.reservations.single().entries.map(QueueEntry::playerId) shouldContainExactlyInAnyOrder
                    listOf(owner.uniqueId.toString(), teammate.uniqueId.toString())
            }
        }
    }

    test("solo requests bind the requester origin and ignore duplicate or stale messages") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val requester = fixture.addPlayer("RemoteSolo")
                val peer = fixture.addPlayer("RemotePeer")
                fixture.queuePlayer(peer, origin = "spawn")
                fixture.queuePlayer(requester, origin = "survival")
                val peerBefore = fixture.queueEntry(peer)
                fixture.publishSoloStart(requester, origin = "spawn")
                fixture.tick(5)
                fixture.reservations shouldBe emptyList()
                fixture.queueEntry(requester)?.state shouldBe QueueState.QUEUED

                fixture.publishSoloStart(requester, origin = "survival", ageMs = 10_001L)
                fixture.tick(5)
                fixture.reservations shouldBe emptyList()
                val request = fixture.publishSoloStart(requester, origin = "survival")
                fixture.tick(10)
                fixture.publishMessage(request, "survival")
                fixture.tick(5)
                fixture.reservations.size shouldBe 1
                fixture.reservations.single().entries.map(QueueEntry::playerId) shouldBe listOf(requester.uniqueId.toString())
                fixture.queueEntry(peer) shouldBe peerBefore
            }
        }
    }

    test("queue join completes with durable queued state and a matching queue control size") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("QueueJoin")
                fixture.await(fixture.coordinator.join(player)) shouldBe true
                fixture.coordinator.queueSize shouldBe 1
                fixture.await(fixture.coordinator.join(player)) shouldBe true
                fixture.coordinator.queueControl(player.uniqueId).get(5, TimeUnit.SECONDS).apply {
                    state shouldBe QueueState.QUEUED
                    ownerId shouldBe player.uniqueId
                    queueSize shouldBe 1
                }
                fixture.seedReserved(player, UUID.randomUUID(), "survival")
                fixture.await(fixture.coordinator.join(player)) shouldBe false
                fixture.reservations shouldBe emptyList()
            }
        }
    }

    test("queue join failure and a closed generation never report joined or start a session") {
        failOnUnsupportedMockBukkitOperation {
            lateinit var redis: InMemoryRedis
            EventNetworkCoordinatorFixture(repositoryRedis = { it.also { value -> redis = value } }).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("FailedQueueJoin")
                redis.failOnSave = true
                fixture.await(fixture.coordinator.join(player)) shouldBe false
                redis.failOnSave = false
                fixture.queueEntry(player) shouldBe null
                fixture.coordinator.close()
                fixture.await(fixture.coordinator.join(player)) shouldBe false
                fixture.reservations shouldBe emptyList()
            }
        }
    }

    test("a failed authoritative refresh never completes queue admission as joined") {
        failOnUnsupportedMockBukkitOperation {
            var failRefresh = false
            EventNetworkCoordinatorFixture(repositoryRedis = { delegate ->
                object : RedisOperations by delegate {
                    override fun loadMap(key: String): CompletableFuture<Map<String, String>> =
                        if (failRefresh && key == RedisEventNetworkRepository.QUEUE_KEY) {
                            CompletableFuture.failedFuture(IllegalStateException("Queue refresh failed"))
                        } else delegate.loadMap(key)
                }
            }).use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("FailedRefresh")
                failRefresh = true

                fixture.await(fixture.coordinator.join(player)) shouldBe false
                fixture.queueEntry(player)?.state shouldBe QueueState.QUEUED
                fixture.reservations shouldBe emptyList()
            }
        }
    }

    test("queue join and solo continuations cannot cross a coordinator generation") {
        failOnUnsupportedMockBukkitOperation {
            for (solo in listOf(false, true)) {
                lateinit var controlled: ControlledQueueJoinRedis
                EventNetworkCoordinatorFixture(repositoryRedis = { ControlledQueueJoinRedis(it).also { value -> controlled = value } }).use { fixture ->
                    fixture.start()
                    val player = fixture.addPlayer("GenerationJoin")
                    controlled.delayWrites = true
                    val joining = if (solo) fixture.coordinator.reserveZombiesSolo(player) else fixture.coordinator.join(player)
                    controlled.stored.get(5, TimeUnit.SECONDS)
                    joining.isDone shouldBe false
                    fixture.coordinator.close()
                    fixture.start()
                    controlled.acknowledgement.complete(true)

                    fixture.await(joining) shouldBe if (solo) ReservationStartResult.NETWORK_FAILURE else false
                    fixture.reservations shouldBe emptyList()
                }
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
                val player = fixture.addPlayer("UnavailableQueue")
                fixture.await(fixture.coordinator.join(player)) shouldBe false
                fixture.queueEntry(player) shouldBe null
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

    test("a submitted recovery return retries until the proxy actually removes the player") {
        failOnUnsupportedMockBukkitOperation {
            EventNetworkCoordinatorFixture().use { fixture ->
                fixture.start()
                val player = fixture.addPlayer("PendingReturn")
                fixture.seedMatched(player, UUID.randomUUID())
                every { fixture.transfer.connect(any(), any()) } returns BackendTransferResult.SENT

                fixture.coordinator.returnRecoveredPlayer(player, PlayerRecovery(UUID.fromString(fixture.queueEntry(player)!!.matchId), "spawn"))
                fixture.tick(3)
                fixture.coordinator.reconfigure()
                fixture.tick(3)

                verify(exactly = 2) { fixture.transfer.connect(player, BackendServerId.of("spawn")) }
                fixture.queueEntry(player)?.state shouldBe QueueState.RETURN_PENDING
                player.disconnect()
                fixture.coordinator.reconfigure()
                fixture.tick(3)
                verify(exactly = 2) { fixture.transfer.connect(player, BackendServerId.of("spawn")) }
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
    private val disposable: Boolean = false,
    private val onArrival: (QueueEntry) -> Boolean = { true },
    private val activeMatchParticipant: (QueueEntry) -> Boolean = { false },
    repositoryRedis: (InMemoryRedis) -> RedisOperations = { it },
) : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcEventsNetworkCoordinatorTest")
    private val dataRoot = Files.createTempDirectory("arcevents-network-coordinator-")
    private var nowMs = 1_787_730_000_000L
    private val redisStore = InMemoryRedis(ServerIdentity { "parkour" })
    private val repository = RedisEventNetworkRepository(repositoryRedis(redisStore))
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
        val baseConfig = if (relay) Files.readString(configPath).replace("server-id: parkour", "server-id: spawn")
            else Files.readString(configPath).replace("node-mode: RELAY", "node-mode: HOST")
        Files.writeString(configPath, if (disposable) baseConfig.replace("mode: PRESERVE", "mode: DISPOSABLE") else baseConfig)
        copyResource("lang/ru.yml")
        copyResource("lang/en.yml")
        settings = ArcEventsConfig.inspect(dataRoot)
        locale = ArcEventsLocale(dataRoot) { settings }
        coordinator = EventNetworkCoordinator(
            plugin = plugin,
            settings = { settings },
            locale = locale,
            repository = repository,
            redis = mockk<PaperRedisClient>(relaxed = true),
            transfer = transfer,
            debug = ArcEventsDebug({ true }) {},
            matchState = { null to null },
            arenaReady = { true },
            readyArenaIds = {
                buildList {
                    if (includeCombatArena) add("test")
                    add("fishing")
                    add("zombies")
                    add("disasters")
                }
            },
            onReservation = { batch -> reservations += batch; true },
            onArrival = onArrival,
            activeMatchParticipant = activeMatchParticipant,
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

    fun queuePlayer(player: org.bukkit.entity.Player, origin: String = settings.serverId) {
        repository.joinQueue(player.uniqueId, player.name, origin, nowMs, 60_000L).get(5, TimeUnit.SECONDS)
    }

    fun publishSoloStart(player: org.bukkit.entity.Player, origin: String, ageMs: Long = 0): EventNetworkMessage {
        val message = EventNetworkMessage.create(EventNetworkSignal.START_REQUEST, nowMs - ageMs,
            destinationServer = "parkour", requesterId = player.uniqueId, mode = EventMode.ZOMBIES.id, requesterOnly = true)
        publishMessage(message, origin)
        return message
    }

    fun publishMessage(message: EventNetworkMessage, origin: String) {
        redisStore.simulateExternalMessage(RedisEventNetworkRepository.EVENT_CHANNEL, Gson().toJson(message), origin)
    }

    fun tick(count: Int) {
        repeat(count) {
            paper.performTicks(1)
            Thread.sleep(2L)
        }
    }

    fun advanceTime(deltaMs: Long) {
        nowMs += deltaMs
    }

    fun persistSound(preset: FirearmSoundPreset) {
        redisStore.saveMapEntries(RedisEventNetworkRepository.SETTINGS_KEY,
            RedisEventNetworkRepository.FIREARM_SOUND_FIELD, preset.name).get(5, TimeUnit.SECONDS)
    }

    fun publishSoundChange(origin: String) {
        redisStore.simulateExternalMessage(RedisEventNetworkRepository.EVENT_CHANNEL,
            Gson().toJson(EventNetworkMessage.create(EventNetworkSignal.FIREARM_SOUND_CHANGED, nowMs)), origin)
    }

    fun seedMatched(player: org.bukkit.entity.Player, matchId: UUID, origin: String = "spawn") {
        repository.joinQueue(player.uniqueId, player.name, origin, nowMs, 60_000L).get(5, TimeUnit.SECONDS)
        repository.reserveForRequester(matchId, "parkour", player.uniqueId, origin, nowMs, 30_000L, EventMode.FISHING.id).get(5, TimeUnit.SECONDS)
        repository.claimReservation(player.uniqueId, "parkour", nowMs + 1).get(5, TimeUnit.SECONDS)
        repository.completeReservation(matchId, listOf(player.uniqueId)).get(5, TimeUnit.SECONDS)
    }

    fun seedReserved(player: org.bukkit.entity.Player, matchId: UUID, destination: String, origin: String = settings.serverId) {
        repository.joinQueue(player.uniqueId, player.name, origin, nowMs, 60_000L).get(5, TimeUnit.SECONDS)
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

    fun publishReturn(matchId: UUID, player: org.bukkit.entity.Player, origin: String) {
        val message = EventNetworkMessage.create(
            signal = EventNetworkSignal.RETURN_PLAYER,
            nowMs = nowMs,
            matchId = matchId,
            playerId = player.uniqueId,
            destinationServer = origin,
        )
        redisStore.simulateExternalMessage(
            RedisEventNetworkRepository.EVENT_CHANNEL,
            Gson().toJson(message),
            origin,
        )
    }

    fun queueEntry(player: org.bukkit.entity.Player): QueueEntry? = queueEntry(player.uniqueId)

    fun markReturnPending(player: org.bukkit.entity.Player) {
        repository.markReturnPending(requireNotNull(queueEntry(player))).get(5, TimeUnit.SECONDS)
    }

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

private class ControlledQueueJoinRedis(private val delegate: InMemoryRedis) : RedisOperations by delegate {
    var delayWrites = false
    val stored = CompletableFuture<Unit>()
    val acknowledgement = CompletableFuture<Boolean>()

    override fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean> {
        val write = delegate.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
        if (!delayWrites || key != RedisEventNetworkRepository.QUEUE_KEY) return write
        return write.thenCompose { accepted ->
            stored.complete(Unit)
            acknowledgement.thenApply { accepted }
        }
    }
}

private class ControlledFirearmSoundRedis(private val delegate: InMemoryRedis) : RedisOperations by delegate {
    var delayWrites = false
    var delayReads = false
    val writes = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<Unit>>()
    private val storedWrites = java.util.concurrent.CopyOnWriteArrayList<CompletableFuture<*>>()
    private val reads = java.util.concurrent.CopyOnWriteArrayList<Pair<List<String?>, CompletableFuture<List<String?>>>>()

    override fun saveMapEntries(key: String, vararg keyValuePairs: String?): CompletableFuture<*> {
        val persisted = delegate.saveMapEntries(key, *keyValuePairs)
        if (key != RedisEventNetworkRepository.SETTINGS_KEY) return persisted
        storedWrites += persisted
        if (!delayWrites) return persisted
        val acknowledgement = CompletableFuture<Unit>().also(writes::add)
        return persisted.thenCompose { acknowledgement }
    }

    override fun loadMapEntries(key: String, vararg mapKeys: String): CompletableFuture<List<String?>> {
        val delayed = key == RedisEventNetworkRepository.SETTINGS_KEY && delayReads
        return delegate.loadMapEntries(key, *mapKeys).thenCompose { snapshot ->
            if (delayed) {
                CompletableFuture<List<String?>>().also { reads += snapshot to it }
            } else CompletableFuture.completedFuture(snapshot)
        }
    }

    fun awaitStoredWrite(index: Int) {
        storedWrites[index].get(5, TimeUnit.SECONDS)
    }

    fun awaitReadCount(count: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (reads.size < count && System.nanoTime() < deadline) Thread.sleep(1)
        check(reads.size >= count) { "Expected $count controlled reads, received ${reads.size}" }
    }

    fun completeRead(index: Int) {
        val (snapshot, result) = reads[index]
        result.complete(snapshot)
    }
}
