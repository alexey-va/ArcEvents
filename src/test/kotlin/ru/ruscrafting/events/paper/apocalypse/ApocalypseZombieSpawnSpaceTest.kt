package ru.ruscrafting.events.paper.apocalypse

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Waterlogged
import ru.arc.paper.testing.MockBukkitTestRuntime

class ApocalypseZombieSpawnSpaceTest : StringSpec({
    "requires solid support and dry passable space at both feet and head" {
        MockBukkitTestRuntime.open().use {
            val stone = spawnBlock(Material.STONE, passable = false)
            val air = spawnBlock(Material.AIR)
            val dryLichen = spawnBlock(Material.GLOW_LICHEN, data = lichenData(waterlogged = false))

            fun permits(ground: Block, feet: Block, head: Block): Boolean {
                every { feet.getRelative(BlockFace.DOWN) } returns ground
                every { feet.getRelative(BlockFace.UP) } returns head
                return canSpawnApocalypseZombieAt(feet)
            }

            permits(stone, air, air) shouldBe true
            permits(stone, dryLichen, air) shouldBe true
            permits(air, air, air) shouldBe false

            val obstructions = mapOf(
                "water" to spawnBlock(Material.WATER, liquid = true),
                "lava" to spawnBlock(Material.LAVA, liquid = true),
                "waterlogged lichen" to spawnBlock(Material.GLOW_LICHEN, data = lichenData(waterlogged = true)),
                "bubble column" to spawnBlock(Material.BUBBLE_COLUMN),
                "stone" to stone,
            )
            obstructions.forEach { (name, block) ->
                withClue(name + " at feet") { permits(stone, block, air) shouldBe false }
                withClue(name + " at head") { permits(stone, air, block) shouldBe false }
            }
        }
    }
})

private fun lichenData(waterlogged: Boolean): Waterlogged = mockk {
    every { isWaterlogged } returns waterlogged
}

private fun spawnBlock(
    material: Material,
    passable: Boolean = true,
    liquid: Boolean = false,
    data: BlockData = mockk(),
): Block = mockk {
    every { type } returns material
    every { isPassable } returns passable
    every { isLiquid } returns liquid
    every { blockData } returns data
}
