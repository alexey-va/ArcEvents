package ru.ruscrafting.events.paper

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class PlayerStateEscrowMockBukkitTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var dataRoot: java.nio.file.Path

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        dataRoot = Files.createTempDirectory("arcevents-escrow-test-")
    }

    afterEach {
        paper.close()
        dataRoot.toFile().deleteRecursively()
    }

    test("new recovery batches use the shared authenticated player-state envelope") {
        val world = paper.server.addSimpleWorld("escrow-world")
        val player = paper.server.addPlayer("Escrowed")
        player.teleport(Location(world, 4.0, 70.0, -8.0, 30f, 4f))
        player.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 3))
        player.foodLevel = 12
        val codec = PaperPlayerStateCodec()
        val persisted = AtomicInteger()
        val playerStates = PaperPlayerStateService(
            codec = codec,
            primaryThread = { true },
            persistPlayerData = { persisted.incrementAndGet() },
        )
        val escrow = PlayerStateEscrow(RecoveryBatchStore(dataRoot, Gson(), codec), playerStates)
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000000123")

        val batch = escrow.prepare(matchId, listOf(player), mapOf(player.uniqueId to "spawn"), 1_787_730_000_000)
        batch.formatVersion shouldBe RecoveryBatch.CURRENT_FORMAT_VERSION
        batch.snapshots shouldBe emptyList()
        batch.states.single().playerId shouldBe player.uniqueId.toString()
        escrow.pendingCount(matchId) shouldBe 1

        player.inventory.clear()
        player.foodLevel = 20
        player.teleport(Location(world, 100.0, 90.0, 100.0))

        escrow.recover(player) shouldBe PlayerRecovery(matchId, "spawn")
        player.inventory.getItem(0) shouldBe ItemStack.of(Material.DIAMOND, 3)
        player.foodLevel shouldBe 12
        player.location.x shouldBe 4.0
        persisted.get() shouldBe 1
        escrow.pendingCount(matchId) shouldBe 0
    }

    test("legacy recovery batches remain readable until their final acknowledgement") {
        val world = paper.server.addSimpleWorld("legacy-escrow-world")
        val player = paper.server.addPlayer("LegacyEscrow")
        player.teleport(Location(world, 7.0, 72.0, 9.0))
        player.inventory.setItem(0, ItemStack.of(Material.EMERALD, 2))
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000000124")
        val gson = Gson()
        val location = player.location
        val compass = player.compassTarget
        val unsigned = PlayerStateSnapshot(
            matchId = matchId.toString(),
            playerId = player.uniqueId.toString(),
            returnServer = "survival",
            checksum = "",
            capturedAtMs = 1_787_730_000_000,
            storageBase64 = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(player.inventory.storageContents)),
            armorBase64 = Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(player.inventory.armorContents)),
            offhandBase64 = null,
            cursorBase64 = null,
            selectedSlot = player.inventory.heldItemSlot,
            compassWorld = compass.world.name,
            compassX = compass.x,
            compassY = compass.y,
            compassZ = compass.z,
            world = location.world.name,
            x = location.x,
            y = location.y,
            z = location.z,
            yaw = location.yaw,
            pitch = location.pitch,
            health = player.health,
            absorption = player.absorptionAmount,
            foodLevel = player.foodLevel,
            saturation = player.saturation,
            exhaustion = player.exhaustion,
            level = player.level,
            exp = player.exp,
            totalExperience = player.totalExperience,
            gameMode = player.gameMode.name,
            allowFlight = player.allowFlight,
            flying = player.isFlying,
            flySpeed = player.flySpeed,
            walkSpeed = player.walkSpeed,
            velocityX = player.velocity.x,
            velocityY = player.velocity.y,
            velocityZ = player.velocity.z,
            fireTicks = player.fireTicks,
            fallDistance = player.fallDistance,
            remainingAir = player.remainingAir,
            noDamageTicks = player.noDamageTicks,
            freezeTicks = player.freezeTicks,
            gliding = player.isGliding,
            swimming = player.isSwimming,
            sprinting = player.isSprinting,
            potionEffects = emptyList(),
        )
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(gson.toJson(unsigned).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val legacy = RecoveryBatch(
            formatVersion = RecoveryBatch.LEGACY_FORMAT_VERSION,
            matchId = matchId.toString(),
            createdAtMs = 1_787_730_000_000,
            snapshots = listOf(unsigned.copy(checksum = checksum)),
        )
        val store = RecoveryBatchStore(dataRoot, gson)
        val record = dataRoot.resolve("data/recovery/$matchId.json")
        Files.writeString(record, gson.toJson(legacy) + "\n", StandardCharsets.UTF_8)
        val escrow = PlayerStateEscrow(store)

        escrow.pendingCount(matchId) shouldBe 1
        player.inventory.clear()
        player.teleport(Location(world, 100.0, 90.0, 100.0))

        escrow.recover(player) shouldBe PlayerRecovery(matchId, "survival")
        player.inventory.getItem(0) shouldBe ItemStack.of(Material.EMERALD, 2)
        player.location.x shouldBe 7.0
        Files.exists(record) shouldBe false
    }
})
