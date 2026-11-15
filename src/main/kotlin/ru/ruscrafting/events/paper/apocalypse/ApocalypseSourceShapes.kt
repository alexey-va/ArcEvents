package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockSupport
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Lightable
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.Wall
import org.bukkit.block.data.type.Wall.Height
import org.bukkit.util.BoundingBox

/** Native 1.21.11 attachment rules, applied after all imported blocks around a cell are present. */
internal object ApocalypseSourceShapes {
    // Lost Cities tests its adaptive torch from below, west, east, north, then south.
    val sides = listOf(BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH)

    fun resolve(old: BlockData, fitTorch: Boolean, capShape: (BlockData) -> Collection<BoundingBox>,
                neighbor: (BlockFace) -> BlockData): BlockData {
        if (old is Wall) return wall(old, capShape, neighbor)
        if (old is MultipleFacing) {
            val result = old.clone() as MultipleFacing
            val fence = Tag.FENCES.isTagged(old.material)
            for (side in sides) {
                val adjacent = neighbor(side)
                val type = adjacent.material
                val joins = if (fence) {
                    Tag.FENCES.isTagged(type) && Tag.WOODEN_FENCES.isTagged(type) == Tag.WOODEN_FENCES.isTagged(old.material) ||
                        gateConnects(adjacent, side)
                } else pane(type) || Tag.WALLS.isTagged(type)
                result.setFace(side, joins || solidFace(adjacent, side))
            }
            return result
        }
        val pair = when (old.material) {
            Material.TORCH, Material.WALL_TORCH -> Material.TORCH to Material.WALL_TORCH
            Material.SOUL_TORCH, Material.SOUL_WALL_TORCH -> Material.SOUL_TORCH to Material.SOUL_WALL_TORCH
            Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH -> Material.REDSTONE_TORCH to Material.REDSTONE_WALL_TORCH
            Material.COPPER_TORCH, Material.COPPER_WALL_TORCH -> Material.COPPER_TORCH to Material.COPPER_WALL_TORCH
            else -> return old
        }
        fun supportsWall(side: BlockFace) = neighbor(side).isFaceSturdy(side.oppositeFace, BlockSupport.FULL)
        if (!fitTorch && old is Directional && supportsWall(old.facing.oppositeFace)) return old
        val result = if (neighbor(BlockFace.DOWN).isFaceSturdy(BlockFace.UP, BlockSupport.CENTER)) {
            pair.first.createBlockData()
        } else {
            val support = sides.firstOrNull(::supportsWall) ?: return Material.AIR.createBlockData()
            (pair.second.createBlockData() as Directional).apply { facing = support.oppositeFace }
        }
        if (old is Lightable && result is Lightable) result.isLit = old.isLit
        return result
    }

    private fun solidFace(adjacent: BlockData, side: BlockFace) =
        !Tag.LEAVES.isTagged(adjacent.material) && !Tag.SHULKER_BOXES.isTagged(adjacent.material) &&
            adjacent.material !in connectionExceptions && adjacent.isFaceSturdy(side.oppositeFace, BlockSupport.FULL)

    private fun gateConnects(adjacent: BlockData, side: BlockFace) =
        adjacent is Gate && (adjacent.facing.modX == 0) != (side.modX == 0)

    private fun pane(type: Material) = type.name.endsWith("_PANE") || type.name.endsWith("_BARS")

    private fun wall(old: Wall, capShape: (BlockData) -> Collection<BoundingBox>, neighbor: (BlockFace) -> BlockData): BlockData {
        val above = neighbor(BlockFace.UP)
        // These two collision shapes consult a live block entity. Never read one during chunk generation.
        if (above.material == Material.MOVING_PISTON || Tag.SHULKER_BOXES.isTagged(above.material)) return old
        val boxes = capShape(above).filter { it.minY <= 0 && it.maxY > 0 }.sortedBy { it.minZ }
        val result = old.clone() as Wall
        for (side in sides) {
            val adjacent = neighbor(side)
            val connects = Tag.WALLS.isTagged(adjacent.material) || pane(adjacent.material) ||
                gateConnects(adjacent, side) || solidFace(adjacent, side)
            val height = when {
                !connects -> Height.NONE
                covers(boxes, side) -> Height.TALL
                else -> Height.LOW
            }
            result.setHeight(side, height)
        }
        val n = result.getHeight(BlockFace.NORTH)
        val s = result.getHeight(BlockFace.SOUTH)
        val e = result.getHeight(BlockFace.EAST)
        val w = result.getHeight(BlockFace.WEST)
        val isolated = sides.all { result.getHeight(it) == Height.NONE }
        val asymmetric = (n == Height.NONE) != (s == Height.NONE) || (e == Height.NONE) != (w == Height.NONE)
        val tallPair = n == Height.TALL && s == Height.TALL || e == Height.TALL && w == Height.TALL
        result.isUp = above is Wall && above.isUp || isolated || asymmetric ||
            !tallPair && (Tag.WALL_POST_OVERRIDE.isTagged(above.material) || covers(boxes, BlockFace.UP))
        return result
    }

    /** WallBlock tests a 2 × 2 post or a 2 × 9 arm against the complete bottom face of the cap. */
    private fun covers(boxes: List<BoundingBox>, face: BlockFace): Boolean {
        val x1 = if (face == BlockFace.WEST) 0.0 else 7.0 / 16
        val x2 = if (face == BlockFace.EAST) 1.0 else 9.0 / 16
        val z1 = if (face == BlockFace.NORTH) 0.0 else 7.0 / 16
        val z2 = if (face == BlockFace.SOUTH) 1.0 else 9.0 / 16
        if (boxes.any { it.minX <= x1 && it.maxX >= x2 && it.minZ <= z1 && it.maxZ >= z2 }) return true
        if (boxes.size < 2) return false
        val edges = (listOf(x1, x2) + boxes.flatMap { listOf(it.minX, it.maxX) }.filter { it > x1 && it < x2 }).distinct().sorted()
        return edges.zipWithNext().all { (left, right) ->
            var covered = z1
            for (box in boxes) {
                if (box.minX <= left && box.maxX >= right && box.minZ <= covered) covered = maxOf(covered, box.maxZ)
            }
            covered >= z2
        }
    }

    private val connectionExceptions = setOf(Material.BARRIER, Material.CARVED_PUMPKIN, Material.JACK_O_LANTERN,
        Material.MELON, Material.PUMPKIN)
}
