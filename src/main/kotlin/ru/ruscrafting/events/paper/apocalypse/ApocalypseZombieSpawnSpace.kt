package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Waterlogged
import ru.ruscrafting.events.domain.apocalypse.ApocalypseNaturalSpawnRule
import kotlin.random.Random

internal val SPAWN_HEIGHT_OFFSETS = intArrayOf(0, 1, -1, 2, -2)

/** Share the largest configured search allowance across every natural spawn this second. */
internal class ApocalypseSpawnProbeBudget(maximum: Int, searches: Int) {
    var remaining = maximum
        private set
    val perSearch = (maximum / searches).coerceAtLeast(1)

    fun take(): Boolean {
        if (remaining == 0) return false
        remaining--
        return true
    }
}

internal fun ApocalypseNaturalSpawnRule.probeHeight(attempt: Int, playerY: Int, top: Int, random: Random): Int = when {
    skyOnly -> top + 1
    attempt % 2 == 0 -> playerY
    !caveOnly && attempt % 4 == 1 -> top + 1
    else -> playerY + random.nextInt(-maximumVerticalDistance, maximumVerticalDistance + 1)
}

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
