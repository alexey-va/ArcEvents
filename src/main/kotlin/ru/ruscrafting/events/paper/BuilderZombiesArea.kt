package ru.ruscrafting.events.paper

import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.util.Vector
import ru.ruscrafting.events.config.EventBounds
import ru.ruscrafting.events.config.EventLocation
import kotlin.math.floor

/** Loaded terrain around the tester. Locations here are safe to read and never built or leased from disk. */
class BuilderZombiesArea private constructor(private val origin: Location) {
    private val world = origin.world
    val anchor: Location get() = origin.clone()
    val bounds = EventBounds(
        EventLocation(world.name, origin.x - RADIUS, origin.y - 1, origin.z - RADIUS),
        EventLocation(world.name, origin.x + RADIUS, origin.y + 8, origin.z + RADIUS),
    )

    fun contains(location: Location): Boolean {
        val (minimum, maximum) = bounds
        return location.world?.uid == world.uid && location.x in minimum.x..maximum.x &&
            location.y in minimum.y..maximum.y && location.z in minimum.z..maximum.z
    }

    fun ready(): Boolean = usableFloor(origin) && spawnLocations(listOf(origin)).isNotEmpty() && dropLocations().isNotEmpty()

    internal fun spawnLocations(players: List<Location>): List<Location> {
        if (players.isEmpty()) return emptyList()
        return floorLocations(SPAWN_OFFSETS).map { point -> point to players.minOf(point::distanceSquared) }
            .filter { (_, distance) -> distance >= 64 }
            .sortedByDescending { (_, distance) -> distance }
            .map { (point, _) -> point }
    }

    internal fun dropLocations(): List<Location> = floorLocations(DROP_OFFSETS)

    internal fun usableFloor(point: Location): Boolean = usableFloor(point, point)

    internal fun pursuitStep(from: Location, target: Location, step: Double): Location? {
        if (!contains(from) || !contains(target) || step <= 0) return null
        val direction = target.toVector().subtract(from.toVector()).setY(0.0)
        val distance = direction.length()
        if (distance <= 1.45) return null
        val delta = direction.multiply(minOf(step, distance - 1.45) / distance)
        // ponytail: ground-only pursuit with a sideways step around small obstacles. An idle actor can respawn.
        for ((x, z) in listOf(delta.x to delta.z, delta.x to 0.0, 0.0 to delta.z)) {
            if (x * x + z * z < .000001) continue
            val destination = from.clone().add(x, 0.0, z)
            if (usableFloor(destination, from)) {
                destination.direction = direction
                return destination
            }
        }
        return null
    }

    internal fun loadedRange(start: Location, direction: Vector, maximum: Double): Double =
        if (contains(start)) firearmLoadedRange(start.toVector(), direction, maximum, world::isChunkLoaded) else 0.0

    internal fun clearLine(start: Location, end: Location): Boolean {
        if (!contains(start) || !contains(end)) return false
        val direction = end.toVector().subtract(start.toVector())
        val distance = direction.length()
        if (distance <= .0001) return true
        direction.multiply(1.0 / distance)
        return loadedRange(start, direction, distance) >= distance &&
            world.rayTraceBlocks(start, direction, distance, FluidCollisionMode.NEVER, true) == null
    }

    private fun usableFloor(point: Location, from: Location): Boolean {
        if (!contains(point) || point.y != origin.y || from.y != origin.y) return false
        val y = point.blockY
        if (y <= world.minHeight || y + 3 >= world.maxHeight) return false
        // A scaled boss is just under one block wide and just over three blocks tall.
        val minX = floor(minOf(point.x, from.x) - HALF_WIDTH).toInt()
        val maxX = floor(maxOf(point.x, from.x) + HALF_WIDTH).toInt()
        val minZ = floor(minOf(point.z, from.z) - HALF_WIDTH).toInt()
        val maxZ = floor(maxOf(point.z, from.z) + HALF_WIDTH).toInt()
        for (x in minX..maxX) for (z in minZ..maxZ) {
            if (!world.isChunkLoaded(x shr 4, z shr 4) || !world.getBlockAt(x, y - 1, z).type.isOccluding) return false
            // Passable blocks can include pressure plates, tripwire, fire or liquids.
            if ((y..y + 3).any { !world.getBlockAt(x, it, z).type.isAir }) return false
        }
        return true
    }

    private fun floorLocations(offsets: List<Pair<Int, Int>>): List<Location> =
        offsets.mapNotNull { (x, z) -> origin.clone().add(x.toDouble(), 0.0, z.toDouble()).takeIf(::usableFloor) }

    companion object {
        private const val WORLD = "builder_flat"
        private const val RADIUS = 18.0
        private const val HALF_WIDTH = .49
        private val SPAWN_OFFSETS = listOf(
            10 to 0, 7 to 7, 0 to 10, -7 to 7, -10 to 0, -7 to -7, 0 to -10, 7 to -7,
            14 to 0, 10 to 10, 0 to 14, -10 to 10, -14 to 0, -10 to -10, 0 to -14, 10 to -10,
        )
        private val DROP_OFFSETS = listOf(0 to 3, 3 to 0, 0 to -3, -3 to 0)

        fun create(anchor: Location): BuilderZombiesArea? {
            if (anchor.world?.name != WORLD || !anchor.x.isFinite() || !anchor.y.isFinite() || !anchor.z.isFinite() ||
                !anchor.yaw.isFinite() || !anchor.pitch.isFinite() || anchor.y - anchor.blockY > .15) return null
            val origin = anchor.clone().apply {
                x = blockX + .5
                y = blockY.toDouble()
                z = blockZ + .5
            }
            return BuilderZombiesArea(origin).takeIf(BuilderZombiesArea::ready)
        }
    }
}
