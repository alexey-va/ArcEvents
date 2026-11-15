package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.data.type.Snow
import org.bukkit.persistence.PersistentDataType
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.domain.apocalypse.ApocalypseOriginalLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.ApocalypseSceneryCell

class ApocalypseAtmosphereTest : StringSpec({
    "a previously skipped snowy bridge entrance installs the entire tread exactly once" {
        withSnowyBridge { f ->
            f.repair()
            f.shore.type shouldBe Material.POLISHED_ANDESITE
            f.deck.type shouldBe Material.POLISHED_ANDESITE
            f.status() shouldBe 2.toByte()
            f.deck.type = Material.AIR
            f.repair()
            f.deck.type shouldBe Material.AIR
        }
    }

    "a player block or stacked snow vetoes every write during the one-time legacy recheck" {
        for (stackedSnow in listOf(false, true)) {
            withSnowyBridge { f ->
                val unexpected = if (stackedSnow) snow(3) else Bukkit.createBlockData(Material.COBBLESTONE)
                f.deck.blockData = unexpected
                f.repair()
                f.shore.type shouldBe Material.GRASS_BLOCK
                f.deck.blockData shouldBe unexpected
                f.status() shouldBe 4.toByte()
                f.deck.type = Material.AIR
                f.repair()
                f.shore.type shouldBe Material.GRASS_BLOCK
                f.deck.type shouldBe Material.AIR
            }
        }
    }

    "a previously started bridge resumes around a placed block without admitting the changed lot" {
        withSnowyBridge(1) { f ->
            f.deck.type = Material.CHEST
            f.repair()
            f.shore.type shouldBe Material.POLISHED_ANDESITE
            f.deck.type shouldBe Material.CHEST
            f.status() shouldBe 4.toByte()
        }
    }
})

private fun withSnowyBridge(previousStatus: Byte = 3, check: (SnowyBridgeFixture) -> Unit) {
    MockBukkitTestRuntime.open().use { paper -> SnowyBridgeFixture(paper, previousStatus).use(check) }
}

// MockBukkit's registry omits layers for snow. Its BlockMock stores a clone of Paper's BlockData.
private fun snow(count: Int): Snow = mockk<Snow>().also { data ->
    every { data.material } returns Material.SNOW
    every { data.layers } returns count
    every { data.clone() } returns data
}

private class SnowyBridgeFixture(paper: MockBukkitTestRuntime, previousStatus: Byte) : AutoCloseable {
    private val plugin = paper.createSimplePlugin("ApocalypseSnowTest")
    private val world = paper.addSimpleWorld("expedition")
    private val chunk = world.getChunkAt(-1, 0)
    private val key = NamespacedKey(plugin, "apocalypse_waterfront_v1")
    private val atmosphere = ApocalypseAtmosphere(plugin)
    val shore = world.getBlockAt(-7, 71, 6)
    val deck = world.getBlockAt(-10, 71, 6)
    private val layout = mockk<ApocalypseOriginalLayout> {
        every { starterCraftingTable } returns ApocalypsePoint(10, 34, 40)
        every { starterFloorIvy } returns emptyMap()
        every { waterfrontLot(-1, 0) } returns listOf(
            ApocalypseSceneryCell(ApocalypsePoint(-7, 71, 6), "minecraft:grass_block", "minecraft:polished_andesite"),
            ApocalypseSceneryCell(ApocalypsePoint(-10, 71, 6), "minecraft:air", "minecraft:polished_andesite"),
        )
    }
    init {
        shore.blockData = Bukkit.createBlockData("minecraft:grass_block[snowy=true]")
        deck.blockData = snow(1)
        chunk.persistentDataContainer.set(key, PersistentDataType.BYTE, previousStatus)
    }
    fun repair() {
        atmosphere.prepare(chunk, layout)
        repeat(10) { atmosphere.tickRepairs() }
    }
    fun status() = chunk.persistentDataContainer.get(key, PersistentDataType.BYTE)
    override fun close() = atmosphere.close()
}
