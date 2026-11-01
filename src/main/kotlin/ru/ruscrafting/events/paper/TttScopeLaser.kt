package ru.ruscrafting.events.paper

import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import kotlin.math.floor
import kotlin.math.min

/** Two client-only parts; the existing display owner handles budgets, viewers and packet cleanup. */
internal class TttScopeLaser(private val shooter: Player, displays: PaperPacketDisplays) : AutoCloseable {
    private val world = shooter.world
    private val audience = displays.createAudienceGroup { viewer -> viewer.canSee(shooter) }
    private val parts = arrayListOf<PacketBlockDisplay>()
    private var closed = false

    init {
        try {
            repeat(2) {
                parts += displays.spawnBlock(shooter.eyeLocation, Material.RED_CONCRETE.createBlockData()).also { display ->
                    display.audienceGroup = audience
                    display.brightness = Display.Brightness(15, 15)
                    // Use the latest server look sample directly; interpolation would trail a moving crosshair.
                    display.interpolationDuration = 0
                    display.teleportDuration = 0
                    display.viewRange = .75f
                    display.shadowRadius = 0f
                    display.shadowStrength = 0f
                    display.pauseWhenNotObserved = false
                }
            }
        } catch (failure: Exception) {
            close()
            throw failure
        }
    }

    fun update() {
        if (closed || !shooter.isOnline || shooter.world.uid != world.uid) return
        // The same eye origin/direction as the unspread firearm ray. This is a cosmetic 32 m guide.
        val eye = shooter.eyeLocation
        val direction = eye.direction
        val range = scopeLaserLoadedRange(eye.toVector(), direction, 32.0, world::isChunkLoaded)
        if (range <= .01) {
            parts.forEach { it.isVisibleByDefault = false }
            return
        }
        val hit = world.rayTrace(eye, direction, range, FluidCollisionMode.NEVER, true, 0.0) { entity ->
            entity is LivingEntity && entity != shooter && !entity.isDead && shooter.canSee(entity) &&
                (entity !is Player || entity.gameMode != GameMode.SPECTATOR)
        }
        val endpoint = hit?.hitPosition ?: eye.toVector().add(direction.clone().multiply(range))
        val distance = eye.toVector().distance(endpoint)
        // Keep the near end beyond the camera, on the same ray; never draw through near cover.
        val start = eye.toVector().add(direction.clone().multiply(min(.7, distance * .25)))
        parts[0].apply {
            isVisibleByDefault = true
            teleport(Location(world, start.x, start.y, start.z))
            transformation = scopeLaserBeamTransform(endpoint.clone().subtract(start), .012f)
        }
        parts[1].apply {
            isVisibleByDefault = hit != null
            if (hit != null) {
                // Pull the tiny contact marker just in front of the hit surface to avoid z-fighting.
                val contact = endpoint.clone().subtract(direction.clone().multiply(.025))
                teleport(Location(world, contact.x, contact.y, contact.z))
                transformation = Transformation(Vector3f(-.025f), Quaternionf(), Vector3f(.05f), Quaternionf())
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        parts.forEach(PacketBlockDisplay::remove)
        parts.clear()
        audience.close()
    }
}

/** Clip before the first unloaded chunk, including short diagonal corner crossings. Never loads a chunk. */
internal fun scopeLaserLoadedRange(origin: Vector, direction: Vector, maximum: Double, loaded: (Int, Int) -> Boolean): Double {
    var x = floor(origin.x / 16).toInt()
    var z = floor(origin.z / 16).toInt()
    val dx = if (direction.x > 0) 1 else if (direction.x < 0) -1 else 0
    val dz = if (direction.z > 0) 1 else if (direction.z < 0) -1 else 0
    val stepX = if (dx == 0) Double.POSITIVE_INFINITY else 16 / kotlin.math.abs(direction.x)
    val stepZ = if (dz == 0) Double.POSITIVE_INFINITY else 16 / kotlin.math.abs(direction.z)
    var nextX = if (dx == 0) Double.POSITIVE_INFINITY else ((x + if (dx > 0) 1 else 0) * 16 - origin.x) / direction.x
    var nextZ = if (dz == 0) Double.POSITIVE_INFINITY else ((z + if (dz > 0) 1 else 0) * 16 - origin.z) / direction.z
    var distance = 0.0
    while (distance < maximum) {
        if (!loaded(x, z)) return (distance - .01).coerceAtLeast(0.0)
        val next = min(nextX, nextZ)
        if (next >= maximum) return maximum
        if (nextX <= next) { x += dx; nextX += stepX }
        if (nextZ <= next) { z += dz; nextZ += stepZ }
        distance = next
    }
    return maximum
}

internal fun scopeLaserBeamTransform(delta: Vector, width: Float): Transformation {
    val length = delta.length().toFloat()
    val rotation = if (length > 0f) Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.toVector3f().normalize()) else Quaternionf()
    val offset = rotation.transform(Vector3f(-width / 2, -width / 2, 0f))
    return Transformation(offset, rotation, Vector3f(width, width, length), Quaternionf())
}
