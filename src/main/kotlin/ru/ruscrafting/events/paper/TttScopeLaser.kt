package ru.ruscrafting.events.paper

import org.bukkit.FluidCollisionMode
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PaperPacketDisplays
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Two parts per viewer: camera-directed for the owner, server-directed for observers. */
internal class TttScopeLaser(private val shooter: Player, displays: PaperPacketDisplays) : AutoCloseable {
    private val world = shooter.world
    private val audiences = arrayListOf<PacketDisplayAudienceGroup>()
    private val parts = arrayListOf<PacketBlockDisplay>()
    private var closed = false

    init {
        try {
            val origin = shooter.eyeLocation.apply { yaw = 0f; pitch = 0f }
            val block = Material.RED_CONCRETE.createBlockData()
            for (ownerView in listOf(true, false)) {
                val audience = displays.createAudienceGroup { viewer ->
                    val owner = viewer.uniqueId == shooter.uniqueId
                    ownerView == owner && (owner || viewer.canSee(shooter))
                }.also(audiences::add)
                repeat(2) {
                    val display = displays.spawnBlock(origin, block).also(parts::add)
                    display.apply {
                        audienceGroup = audience
                        billboard = if (ownerView) Display.Billboard.CENTER else Display.Billboard.FIXED
                        brightness = Display.Brightness(15, 15)
                        interpolationDuration = 0
                        teleportDuration = 0
                        viewRange = .75f
                        shadowRadius = 0f
                        shadowStrength = 0f
                        pauseWhenNotObserved = false
                        isVisibleByDefault = false
                        // The client moves passengers with their carrier; no per-tick world teleports.
                        attachTo(shooter)
                    }
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
        val near = min(.7, distance * .25)
        val eyeOffset = (shooter.eyeHeight - shooter.height).toFloat()
        for (view in 0..1) {
            // 1.21.11 DisplayRenderer CENTER uses the local camera, with -Z pointing forward.
            // This is a first-person optic: F5 front view reverses that camera. Ray length/contact
            // remain server-resolved; the small eye/head compensation uses the latest server pitch.
            val localDirection = if (view == 0) Vector(0, 0, -1) else direction
            val localEye = if (view == 0) scopeLaserCameraEyeOffset(eyeOffset, eye.pitch) else Vector3f(0f, eyeOffset, 0f)
            val start = Vector3f(localEye).add(localDirection.toVector3f().mul(near.toFloat()))
            val end = Vector3f(localEye).add(localDirection.toVector3f().mul(distance.toFloat()))
            parts[view * 2].apply {
                isVisibleByDefault = true
                transformation = scopeLaserBeamTransform(localDirection.clone().multiply(distance - near), .012f, start)
            }
            parts[view * 2 + 1].apply {
                isVisibleByDefault = hit != null
                if (hit != null) {
                    // Pull the tiny marker toward the eye to avoid z-fighting at the sampled contact.
                    val contact = end.sub(localDirection.toVector3f().mul(.025f)).sub(.025f, .025f, .025f)
                    transformation = Transformation(contact, Quaternionf(), Vector3f(.05f), Quaternionf())
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        parts.forEach(PacketBlockDisplay::remove)
        parts.clear()
        audiences.forEach(PacketDisplayAudienceGroup::close)
        audiences.clear()
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

/** Inverse CENTER rotation of a world-vertical eye offset; yaw does not affect this vector. */
internal fun scopeLaserCameraEyeOffset(height: Float, pitch: Float): Vector3f {
    val radians = Math.toRadians(pitch.toDouble())
    return Vector3f(0f, height * cos(radians).toFloat(), height * sin(radians).toFloat())
}

internal fun scopeLaserBeamTransform(delta: Vector, width: Float, origin: Vector3f = Vector3f()): Transformation {
    val length = delta.length().toFloat()
    val rotation = if (length > 0f) Quaternionf().rotationTo(Vector3f(0f, 0f, 1f), delta.toVector3f().normalize()) else Quaternionf()
    val offset = rotation.transform(Vector3f(-width / 2, -width / 2, 0f)).add(origin)
    return Transformation(offset, rotation, Vector3f(width, width, length), Quaternionf())
}
