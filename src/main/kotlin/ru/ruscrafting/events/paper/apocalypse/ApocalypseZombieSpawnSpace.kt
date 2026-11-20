package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Waterlogged

private val waterFilledBlocks = setOf(
    Material.BUBBLE_COLUMN,
    Material.KELP,
    Material.KELP_PLANT,
    Material.SEAGRASS,
    Material.TALL_SEAGRASS,
)

/** Check the feet and head blocks of a spawn candidate in an already loaded chunk. */
internal fun canSpawnApocalypseZombieAt(feet: Block): Boolean =
    feet.getRelative(BlockFace.DOWN).type.isSolid &&
        feet.isDryPassable() && feet.getRelative(BlockFace.UP).isDryPassable()

private fun Block.isDryPassable(): Boolean =
    isPassable && !isLiquid && type !in waterFilledBlocks && (blockData as? Waterlogged)?.isWaterlogged != true
