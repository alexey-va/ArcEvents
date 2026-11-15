package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.bukkit.Location
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Item
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.domain.apocalypse.ApocalypseOriginalLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.paper.EventWork
import java.util.Random
import java.util.concurrent.CompletableFuture

class ApocalypseSourceMiningTest : StringSpec({
    "only an asynchronously prepared authored tungsten position with the expected current block can grant source ore" {
        SourceMiningFixture().use { f ->
            f.breakOre().isCancelled shouldBe true
            f.prepare()
            f.breakOre().isCancelled shouldBe true
            f.claims.size shouldBe 0
            val ordinary = f.world.getBlockAt(5, 64, 1).also { it.type = Material.STONE }
            BlockBreakEvent(ordinary, f.player).also(f.mining::onBreak).isCancelled shouldBe false
            f.work.runNext()
            f.ore.type = Material.STONE
            f.breakOre().isCancelled shouldBe false
            f.ore.type = Material.DEEPSLATE_IRON_ORE
            val lookalike = f.world.getBlockAt(4, 64, 1).also { it.type = Material.DEEPSLATE_IRON_ORE }
            BlockBreakEvent(lookalike, f.player).also(f.mining::onBreak).isCancelled shouldBe false
            val otherSourceOre = f.world.getBlockAt(2, 64, 1).also { it.type = Material.DEEPSLATE_IRON_ORE }
            BlockBreakEvent(otherSourceOre, f.player).also(f.mining::onBreak).isCancelled shouldBe false
            f.claims.size shouldBe 0
            f.breakOre().isCancelled shouldBe true
            f.claims shouldBe setOf("source-ore:1:64:1")
            f.ore.type shouldBe Material.DEEPSLATE_IRON_ORE
            f.drops().size shouldBe 0
            f.tickets.activeLeaseCount shouldBe 1
            f.commit()
            f.tickets.activeLeaseCount shouldBe 0
            f.ore.type shouldBe Material.AIR
            f.drops().single().itemStack.amount shouldBe 1
        }
    }

    "the journal claim precedes the native change, blocks pending mining and prevents a replaced ore from minting again" {
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.breakOre().isCancelled shouldBe true
            f.breakOre().isCancelled shouldBe true
            f.pending.size shouldBe 1
            f.commit()
            f.drops().size shouldBe 1
            f.ore.type = Material.DEEPSLATE_IRON_ORE
            f.breakOre().isCancelled shouldBe false
            f.pending.size shouldBe 0
            f.drops().size shouldBe 1
            f.drops().single().itemStack.itemMeta.persistentDataContainer
                .get(NamespacedKey(f.plugin, "apocalypse_source_item"), PersistentDataType.STRING) shouldBe "the_deeper_void:raw_tungsten"
        }
    }

    "source tool and Fortune rules preserve the ore on a denied or busy claim and do not add a Silk Touch or XP branch" {
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            for (tool in listOf(Material.AIR, Material.WOODEN_PICKAXE, Material.STONE_PICKAXE, Material.GOLDEN_PICKAXE)) {
                f.player.inventory.setItemInMainHand(ItemStack.of(tool))
                f.breakOre().isCancelled shouldBe true
            }
            f.toolHints shouldBe 4
            f.claims.size shouldBe 0
            f.active = false
            f.player.inventory.setItemInMainHand(ItemStack.of(Material.IRON_PICKAXE))
            f.breakOre().isCancelled shouldBe true
            f.claims.size shouldBe 0
            f.active = true
            f.acceptClaims = false
            f.breakOre().isCancelled shouldBe true
            f.ore.type shouldBe Material.DEEPSLATE_IRON_ORE
            f.claims.size shouldBe 0
            f.acceptClaims = true
            val pick = ItemStack.of(Material.NETHERITE_PICKAXE).also {
                it.addUnsafeEnchantment(Enchantment.FORTUNE, 3)
                it.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1)
            }
            f.player.inventory.setItemInMainHand(pick)
            val event = f.breakOre()
            event.isCancelled shouldBe true
            event.expToDrop shouldBe 0
            f.commit()
            f.drops().single().itemStack.amount shouldBe 4
            f.drops().single().itemStack.type shouldBe Material.RAW_IRON
        }
    }

    "a durable callback retains its chunk, preserves a changed block and releases its lease on close" {
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.breakOre()
            f.ore.type = Material.DIAMOND_BLOCK
            f.commit()
            f.ore.type shouldBe Material.DIAMOND_BLOCK
            f.drops().size shouldBe 0
        }
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.breakOre()
            f.tickets.activeChunkCount shouldBe 1
            f.commit()
            f.tickets.activeChunkCount shouldBe 0
            f.ore.type shouldBe Material.AIR
            f.drops().size shouldBe 1
        }
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.breakOre()
            f.mining.close()
            f.tickets.activeLeaseCount shouldBe 0
            f.commit()
            f.ore.type shouldBe Material.DEEPSLATE_IRON_ORE
            f.drops().size shouldBe 0
        }
    }

    "an ore index that finishes after unload or close cannot revive the old chunk cache" {
        SourceMiningFixture().use { f ->
            f.prepare()
            f.mining.onUnload(ChunkUnloadEvent(f.ore.chunk))
            f.work.runNext()
            f.breakOre().isCancelled shouldBe true
            f.claims.size shouldBe 0
            f.prepare()
            f.mining.close()
            f.work.runNext()
            f.breakOre().isCancelled shouldBe false
            f.claims.size shouldBe 0
        }
    }

    "hundreds of loaded chunks share one content-worker admission and close discards every queued index" {
        SourceMiningFixture().use { f ->
            for (x in 0 until 200) f.mining.prepare(f.world.getChunkAt(x, 0), f.layout)
            f.work.computeCount shouldBe 1
            f.work.pendingCount shouldBe 1
            f.mining.close()
            f.work.runNext()
            f.work.computeCount shouldBe 1
            f.work.pendingCount shouldBe 0
        }
    }

    "a player's queued source index is promoted ahead of untouched chunks without admitting a second worker" {
        SourceMiningFixture().use { f ->
            every { f.layout.sourceOresInChunk(1, 0) } returns emptyMap()
            every { f.layout.sourceOresInChunk(2, 0) } returns emptyMap()
            f.prepare()
            f.mining.prepare(f.world.getChunkAt(1, 0), f.layout)
            f.mining.prepare(f.world.getChunkAt(2, 0), f.layout)
            f.mining.prepare(f.world.getChunkAt(2, 0), f.layout, priority = true)
            f.work.computeCount shouldBe 1
            f.work.runNext()
            f.work.computeCount shouldBe 2
            f.work.pendingCount shouldBe 1
            f.work.runNext()
            f.work.runNext()
            verifyOrder {
                f.layout.sourceOresInChunk(0, 0)
                f.layout.sourceOresInChunk(2, 0)
                f.layout.sourceOresInChunk(1, 0)
            }
        }
    }

    "a failed cold index preserves possible ore and can be retried explicitly without admitting native drops" {
        SourceMiningFixture().use { f ->
            every { f.layout.sourceOresInChunk(0, 0) } throws IllegalStateException("Fixture source index failure")
            f.prepare(); f.work.runNext()
            f.breakOre().isCancelled shouldBe true
            f.claims.size shouldBe 0
            f.ore.type shouldBe Material.DEEPSLATE_IRON_ORE
            every { f.layout.sourceOresInChunk(0, 0) } returns mapOf(ApocalypsePoint(1, 64, 1) to "the_deeper_void:tungsten_ore")
            f.prepare(); f.work.runNext()
            f.breakOre().isCancelled shouldBe true
            f.claims shouldBe setOf("source-ore:1:64:1")
        }
    }

    "columbite requires a diamond tool and rolls plain iron and optional rhenium with independent Fortune bonuses" {
        SourceMiningFixture(SequenceMiningRandom(1, 4, 0, 2)).use { f ->
            f.prepare(); f.work.runNext()
            BlockBreakEvent(f.columbite, f.player).also(f.mining::onBreak).isCancelled shouldBe true
            f.toolHints shouldBe 1
            f.claims.size shouldBe 0
            f.player.inventory.setItemInMainHand(ItemStack.of(Material.DIAMOND_PICKAXE).also {
                it.addUnsafeEnchantment(Enchantment.FORTUNE, 3)
                it.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1)
            })
            val event = BlockBreakEvent(f.columbite, f.player).also(f.mining::onBreak)
            event.isCancelled shouldBe true
            event.expToDrop shouldBe 0
            f.commit()
            val drops = f.drops().map { it.itemStack }
            drops.size shouldBe 2
            drops[0] shouldBe ItemStack.of(Material.RAW_IRON, 8)
            drops[1].amount shouldBe 2
            drops[1].itemMeta.persistentDataContainer.get(NamespacedKey(f.plugin, "apocalypse_source_item"), PersistentDataType.STRING) shouldBe "the_deeper_void:rhenium"
        }
        SourceMiningFixture(SequenceMiningRandom(0, 1)).use { f ->
            f.prepare(); f.work.runNext()
            f.player.inventory.setItemInMainHand(ItemStack.of(Material.NETHERITE_PICKAXE))
            BlockBreakEvent(f.columbite, f.player).also(f.mining::onBreak).isCancelled shouldBe true
            f.commit()
            f.drops().map { it.itemStack } shouldBe listOf(ItemStack.of(Material.RAW_IRON))
        }
    }

    "a denied or failed durable claim releases the shared lease without altering the source block" {
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.acceptClaims = false
            f.breakOre().isCancelled shouldBe true
            f.tickets.activeLeaseCount shouldBe 0
            f.ore.type shouldBe Material.DEEPSLATE_IRON_ORE
            f.drops().size shouldBe 0
            f.acceptClaims = true
            f.breakOre().isCancelled shouldBe true
            f.pending.removeFirst().completeExceptionally(IllegalStateException("Fixture durable failure"))
            f.tickets.activeLeaseCount shouldBe 0
            f.ore.type shouldBe Material.DEEPSLATE_IRON_ORE
            f.drops().size shouldBe 0
        }
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.tickets.close()
            f.breakOre().isCancelled shouldBe true
            f.claims.size shouldBe 0
            f.drops().size shouldBe 0
        }
    }

    "an admitted hand tool wears once before ACK while wrong tools, denial, immediate failure and Creative never wear" {
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.breakOre()
            f.wornTools shouldBe listOf(Material.IRON_PICKAXE)
            f.breakOre()
            f.player.inventory.setItemInMainHand(ItemStack.of(Material.DIAMOND_PICKAXE))
            f.pending.removeFirst().completeExceptionally(IllegalStateException("Fixture late journal failure"))
            f.wornTools shouldBe listOf(Material.IRON_PICKAXE)
        }
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.player.inventory.setItemInMainHand(ItemStack.of(Material.STONE_PICKAXE))
            f.breakOre()
            f.wornTools shouldBe emptyList()
            f.player.inventory.setItemInMainHand(ItemStack.of(Material.IRON_PICKAXE))
            f.acceptClaims = false
            f.breakOre()
            f.wornTools shouldBe emptyList()
            f.acceptClaims = true
            f.failClaimsImmediately = true
            f.breakOre()
            f.wornTools shouldBe emptyList()
            f.claims.size shouldBe 0
        }
        SourceMiningFixture().use { f ->
            f.prepare(); f.work.runNext()
            f.player.gameMode = GameMode.CREATIVE
            f.breakOre()
            f.wornTools shouldBe emptyList()
            f.commit()
            f.wornTools shouldBe emptyList()
        }
    }

    "columbite migration repairs only original old cells and never repeats a completed chunk migration" {
        SourceMiningFixture().use { f ->
            f.columbite.type = Material.POLISHED_DEEPSLATE
            val ordinary = f.world.getBlockAt(5, 64, 1).also { it.type = Material.POLISHED_DEEPSLATE }
            f.prepare(); f.work.runNext()
            f.columbite.type shouldBe Material.DEEPSLATE_EMERALD_ORE
            ordinary.type shouldBe Material.POLISHED_DEEPSLATE
            f.columbite.type = Material.POLISHED_DEEPSLATE
            f.mining.onUnload(ChunkUnloadEvent(f.columbite.chunk))
            f.prepare(); f.work.runNext()
            f.columbite.type shouldBe Material.POLISHED_DEEPSLATE
        }
    }
})

private class SourceMiningFixture(random: Random = object : Random(0) { override fun nextInt(bound: Int) = bound - 1 }) : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    val plugin = paper.createSimplePlugin("ApocalypseMiningTest")
    val world = paper.addSimpleWorld("expedition")
    val player = paper.addPlayer("Survivor")
    val ore = world.getBlockAt(1, 64, 1)
    val columbite = world.getBlockAt(3, 64, 1)
    val layout = mockk<ApocalypseOriginalLayout> {
        every { sourceOresInChunk(0, 0) } returns mapOf(
            ApocalypsePoint(1, 64, 1) to "the_deeper_void:tungsten_ore",
            ApocalypsePoint(2, 64, 1) to "the_deeper_void:rhenium_ore",
            ApocalypsePoint(3, 64, 1) to "the_deeper_void:columbite",
        )
    }
    val work = QueuedMiningWork()
    val tickets = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
        override fun add(key: PaperChunkKey) = PaperChunkTicketAddResult.ADDED
        override fun remove(key: PaperChunkKey) = true
    })
    val claims = mutableSetOf<String>()
    val pending = ArrayDeque<CompletableFuture<Boolean>>()
    var active = true
    var acceptClaims = true
    var failClaimsImmediately = false
    var checkpointPending = false
    var toolHints = 0
    val wornTools = mutableListOf<Material>()
    val mining = ApocalypseSourceMining(plugin, work, tickets, { world }, { active && !checkpointPending }, { _, sourceId, amount ->
        ItemStack.of(if (sourceId == "the_deeper_void:rhenium") Material.IRON_INGOT else Material.RAW_IRON, amount).also { item ->
            if (!sourceId.startsWith("minecraft:")) item.editMeta {
                it.persistentDataContainer.set(NamespacedKey(plugin, "apocalypse_source_item"), PersistentDataType.STRING, sourceId)
            }
        }
    }, claims::contains, { id ->
        if (!acceptClaims) CompletableFuture.completedFuture(false)
        else if (failClaimsImmediately) CompletableFuture.failedFuture(IllegalStateException("Fixture immediate journal failure"))
        else {
            check(claims.add(id))
            checkpointPending = true
            CompletableFuture<Boolean>().also { pending += it }
        }
    }, { _, _ -> toolHints++ }, random, { wornTools += it.inventory.itemInMainHand.type })

    init {
        world.getChunkAt(0, 0)
        ore.type = Material.DEEPSLATE_IRON_ORE
        columbite.type = Material.DEEPSLATE_EMERALD_ORE
        player.teleport(Location(world, 1.5, 64.0, 1.5))
        player.inventory.setItemInMainHand(ItemStack.of(Material.IRON_PICKAXE))
        mining.register()
    }

    fun prepare() = mining.prepare(ore.chunk, layout)
    fun breakOre() = BlockBreakEvent(ore, player).also(mining::onBreak)
    fun drops(): List<Item> = world.entities.filterIsInstance<Item>()
    fun commit() { checkpointPending = false; pending.removeFirst().complete(true) }
    override fun close() { mining.close(); tickets.close(); paper.close() }
}

private class SequenceMiningRandom(vararg values: Int) : Random(0) {
    private val values = ArrayDeque(values.toList())
    override fun nextInt(bound: Int): Int = values.removeFirst().also { require(it in 0 until bound) }
}

private class QueuedMiningWork : EventWork {
    private val queued = ArrayDeque<() -> Unit>()
    var computeCount = 0
        private set
    val pendingCount get() = queued.size
    override fun <T> compute(action: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { future ->
        computeCount++
        queued += { runCatching(action).fold(future::complete, future::completeExceptionally); Unit }
    }
    override fun <T> onMain(action: () -> T): CompletableFuture<T> = runCatching(action).fold(
        { CompletableFuture.completedFuture(it) }, { CompletableFuture.failedFuture(it) },
    )
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> = error("Mining does not use native batch capture")
    override fun isCurrent() = true
    fun runNext() = queued.removeFirst()()
}
