package ru.ruscrafting.events.paper

import com.google.gson.Gson
import io.kotest.assertions.throwables.shouldThrow
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

        var mutationObservedCommittedState = false
        val receipt = escrow.commitThenMutate(
            matchId,
            listOf(player),
            mapOf(player.uniqueId to "spawn"),
            1_787_730_000_000,
        ) { committed ->
            mutationObservedCommittedState = escrow.pendingCount(matchId) == 1 && committed.matchId == matchId.toString()
            "mutated"
        }
        val batch = receipt.committed
        receipt.mutation shouldBe "mutated"
        mutationObservedCommittedState shouldBe true
        batch.formatVersion shouldBe RecoveryBatch.FORMAT_VERSION
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

    test("an early arrival snapshot survives later roster completion") {
        val world = paper.server.addSimpleWorld("arrival-world")
        val first = paper.server.addPlayer("First")
        val second = paper.server.addPlayer("Second")
        first.teleport(Location(world, 1.0, 70.0, 1.0))
        second.teleport(Location(world, 2.0, 70.0, 2.0))
        first.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 3))
        second.inventory.setItem(0, ItemStack.of(Material.EMERALD, 4))
        val escrow = PlayerStateEscrow(RecoveryBatchStore(dataRoot, Gson()))
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000000125")

        escrow.commitThenMutate(matchId, listOf(first), mapOf(first.uniqueId to "spawn"), 1_787_730_000_000) {
            first.inventory.clear()
        }
        escrow.commitThenMutate(
            matchId,
            listOf(first, second),
            mapOf(first.uniqueId to "spawn", second.uniqueId to "survival"),
            1_787_730_001_000,
        ) { Unit }

        escrow.pendingCount(matchId) shouldBe 2
        escrow.recover(first) shouldBe PlayerRecovery(matchId, "spawn")
        first.inventory.getItem(0) shouldBe ItemStack.of(Material.DIAMOND, 3)
        escrow.recover(second) shouldBe PlayerRecovery(matchId, "survival")
        second.inventory.getItem(0) shouldBe ItemStack.of(Material.EMERALD, 4)
    }

    test("removed legacy recovery format fails closed") {
        val matchId = UUID.fromString("00000000-0000-0000-0000-000000000124")
        val record = dataRoot.resolve("data/recovery/$matchId.json")
        Files.createDirectories(record.parent)
        Files.writeString(
            record,
            """{"formatVersion":1,"matchId":"$matchId","createdAtMs":1787730000000,"states":[],"restoredPlayerIds":[]}""",
            StandardCharsets.UTF_8,
        )

        shouldThrow<IllegalArgumentException> { RecoveryBatchStore(dataRoot).loadAll() }
        Files.exists(record) shouldBe true
    }
})
