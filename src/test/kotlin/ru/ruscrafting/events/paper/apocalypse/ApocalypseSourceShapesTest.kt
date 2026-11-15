package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockFace.*
import org.bukkit.block.BlockSupport
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.Waterlogged
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.Wall
import org.bukkit.util.BoundingBox
import ru.arc.paper.testing.MockBukkitTestRuntime

class ApocalypseSourceShapesTest : StringSpec({
    "wet stained panes join a brick jamb and iron bars while retaining the original state" {
        MockBukkitTestRuntime.open().use {
            val old = Material.WHITE_STAINED_GLASS_PANE.createBlockData() as MultipleFacing
            (old as Waterlogged).isWaterlogged = true
            val actual = shape(old, mapOf(WEST to support(Material.STONE, EAST), EAST to support(Material.IRON_BARS))) as MultipleFacing
            actual.faces shouldBe setOf(WEST, EAST)
            (actual as Waterlogged).isWaterlogged shouldBe true
            old.faces shouldBe emptySet()
        }
    }

    "wooden corners meet compatible gates and fences and exclude nether brick" {
        MockBukkitTestRuntime.open().use {
            val gate = mockk<Gate> {
                every { material } returns Material.OAK_FENCE_GATE
                every { facing } returns NORTH
                every { isFaceSturdy(any(), any()) } returns false
            }
            val old = Material.OAK_FENCE.createBlockData()
            val actual = shape(old, mapOf(NORTH to support(Material.BIRCH_FENCE), EAST to gate,
                WEST to support(Material.NETHER_BRICK_FENCE), SOUTH to gate)) as MultipleFacing
            actual.faces shouldBe setOf(NORTH, EAST)
        }
    }

    "adaptive wall fixtures point away from each real support and use an eligible floor first" {
        MockBukkitTestRuntime.open().use {
            val old = Material.WALL_TORCH.createBlockData() as Directional
            old.facing = NORTH
            for (side in ApocalypseSourceShapes.sides) {
                val around = mapOf(side to support(Material.STONE, side.oppositeFace))
                (shape(old, around, true) as Directional).facing shouldBe side.oppositeFace
                val floor = support(Material.OAK_FENCE, UP, BlockSupport.CENTER)
                shape(old, around + (DOWN to floor), true).material shouldBe Material.TORCH
            }
            shape(old, emptyMap(), true).material shouldBe Material.AIR
            val keep = mapOf(SOUTH to support(Material.STONE, NORTH), DOWN to support(Material.STONE, UP, BlockSupport.CENTER))
            shape(old, keep).asString shouldBe old.asString
        }
    }

    "wall corners, open straights, covered arms and the upper wall post follow the cap shape" {
        MockBukkitTestRuntime.open().use {
            val old = FixtureWall()
            old.isWaterlogged = true
            val stone = support(Material.COBBLESTONE_WALL)
            val straight = mapOf(EAST to stone, WEST to stone)
            val open = shape(old, straight) as Wall
            open.isUp shouldBe false
            open.getHeight(WEST) shouldBe Wall.Height.LOW
            open.isWaterlogged shouldBe true
            (shape(old, mapOf(WEST to stone, SOUTH to stone)) as Wall).isUp shouldBe true
            val fullCap = listOf(BoundingBox(0.0, 0.0, 0.0, .5, .5, 1.0), BoundingBox(.5, 0.0, 0.0, 1.0, .5, 1.0))
            val bothTall = shape(old, straight + (UP to support(Material.STONE_SLAB)), cap = fullCap) as Wall
            bothTall.getHeight(WEST) shouldBe Wall.Height.TALL
            bothTall.getHeight(EAST) shouldBe Wall.Height.TALL
            bothTall.isUp shouldBe false
            val cornerAbove = shape(old, mapOf(WEST to stone, SOUTH to stone)) as Wall
            val stacked = shape(old, straight + (UP to cornerAbove), cap = listOf(BoundingBox(0.0, 0.0, .3125, .75, 1.0, .6875))) as Wall
            stacked.getHeight(WEST) shouldBe Wall.Height.TALL
            stacked.getHeight(EAST) shouldBe Wall.Height.LOW
            stacked.isUp shouldBe true
        }
    }
})

// MockBukkit leaves WallDataMock.getHeight/setHeight unimplemented. Delegate the remaining BlockData.
private class FixtureWall(
    private val data: Wall = Material.COBBLESTONE_WALL.createBlockData() as Wall,
    private val heights: MutableMap<BlockFace, Wall.Height> = mutableMapOf(),
) : Wall by data {
    override fun getHeight(face: BlockFace) = heights[face] ?: Wall.Height.NONE
    override fun setHeight(face: BlockFace, height: Wall.Height) { heights[face] = height }
    override fun clone(): Wall = FixtureWall(data.clone() as Wall, heights.toMutableMap())
}

// MockBukkit does not implement native face sturdiness. Each fixture supplies the queried face and support level.
private fun support(type: Material, face: BlockFace? = null, level: BlockSupport = BlockSupport.FULL) = mockk<BlockData> {
    every { material } returns type
    every { isFaceSturdy(any(), any()) } returns false
    if (face != null) every { isFaceSturdy(face, level) } returns true
}

private fun shape(old: BlockData, neighbors: Map<BlockFace, BlockData>, adaptive: Boolean = false,
                  cap: List<BoundingBox> = emptyList()): BlockData {
    val air = support(Material.AIR)
    return ApocalypseSourceShapes.resolve(old, adaptive, { cap }) { neighbors[it] ?: air }
}
