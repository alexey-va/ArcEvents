package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.World
import kotlin.math.abs

private const val WALK_RADIUS = 12
private const val MAX_WALK_CELLS = 384
private val horizontalSteps = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
private val verticalSteps = listOf(0, 1, -1)

internal data class PracticeSpawnCell(val x: Int, val y: Int, val z: Int) {
    constructor(at: Location) : this(at.blockX, at.blockY, at.blockZ)

    fun location(world: World): Location = Location(world, x + .5, y.toDouble(), z + .5)
}

/** Local walking paths from the real participant, with no diagonal corner cuts or chunk loads. */
internal fun reachablePracticeSpawnCells(
    origin: Location,
    canStand: (Location) -> Boolean,
): Set<PracticeSpawnCell> {
    val world = origin.world
    val start = PracticeSpawnCell(origin)
    val footing = mutableMapOf<PracticeSpawnCell, Boolean>()
    fun standable(cell: PracticeSpawnCell): Boolean = footing.getOrPut(cell) {
        abs(cell.x - start.x) <= WALK_RADIUS && abs(cell.z - start.z) <= WALK_RADIUS &&
            cell.y in world.minHeight + 1 until world.maxHeight - 1 &&
            world.isChunkLoaded(cell.x shr 4, cell.z shr 4) && canStand(cell.location(world))
    }

    if (!standable(start)) return emptySet()
    val reachable = linkedSetOf(start)
    val pending = ArrayDeque<PracticeSpawnCell>().also { it.addLast(start) }
    while (pending.isNotEmpty()) {
        val here = pending.removeFirst()
        for ((dx, dz) in horizontalSteps) for (dy in verticalSteps) {
            val next = PracticeSpawnCell(here.x + dx, here.y + dy, here.z + dz)
            if (next in reachable || !standable(next)) continue
            if (dy != 0) {
                // The lower column needs extra clearance while stepping up or down.
                val lower = if (dy > 0) here else next
                if (!world.getBlockAt(lower.x, lower.y + 2, lower.z).isPassable) continue
            }
            reachable.add(next)
            if (reachable.size == MAX_WALK_CELLS) return reachable
            pending.addLast(next)
        }
    }
    return reachable
}
