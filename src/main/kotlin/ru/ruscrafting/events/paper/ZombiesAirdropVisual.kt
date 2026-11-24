package ru.ruscrafting.events.paper

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/** A packet-only cargo flyby through the store. The caller owns the landed barrel and its contents. */
internal class ZombiesAirdropVisual(
    private val displays: PaperPacketDisplays,
    matchId: UUID,
    landing: Location,
    private val startMillis: Long,
) : AutoCloseable {
    private val landing = landing.clone().apply { yaw = 0f; pitch = 0f }
    private val frontEntrance = landing.blockZ > 0
    private val windSign = if (matchId.leastSignificantBits and 1L == 0L) 1f else -1f
    private var recipients = emptySet<UUID>()
    private val audience = displays.createAudienceGroup { it.uniqueId in recipients }
    private val aircraft = Model(AirdropFlight.plane)
    private val cargo = Model(listOf(AirdropFlight.barrel))
    private val chute = Model(AirdropFlight.canopy, if (frontEntrance) (PI / 2).toFloat() else 0f)
    private var landed = false
    private var closed = false

    fun tick(now: Long, viewers: Collection<Player>): Boolean {
        if (closed) return landed
        val age = (now - startMillis).coerceAtLeast(0)
        if (age >= AirdropFlight.FINISH_MS) {
            landed = true
            close()
            return true
        }
        recipients = viewers.mapTo(mutableSetOf(), Player::getUniqueId)
        val frame = AirdropFlight.frame(age, frontEntrance, windSign)
        if (frame.planeScale > 0f) {
            aircraft.move(at(frame.planeX, frame.planeY, frame.planeZ), frame.planeScale, age * .008f)
        } else {
            aircraft.clear()
        }
        if (age >= AirdropFlight.RELEASE_MS) {
            val at = at(frame.barrelX, frame.barrelY, frame.barrelZ)
            cargo.move(at)
            if (frame.canopyScale > 0f) chute.move(at.add(0.0, 1.0, 0.0), frame.canopyScale)
            else chute.clear()
        }
        return false
    }

    private fun at(x: Float, y: Float, z: Float) = landing.clone().add(x.toDouble(), y.toDouble(), z.toDouble())

    override fun close() {
        if (closed) return
        closed = true
        aircraft.clear()
        cargo.clear()
        chute.clear()
        audience.close()
        recipients = emptySet()
    }

    private inner class Model(private val parts: List<AirdropFlight.Part>, private val yaw: Float = 0f) {
        private val handles = mutableListOf<PacketBlockDisplay>()
        private var lastScale = 0f

        fun move(anchor: Location, scale: Float = 1f, spin: Float = 0f) {
            parts.forEachIndexed { index, part ->
                val fresh = index == handles.size
                val handle = if (fresh) {
                    displays.spawnBlock(anchor, part.material.createBlockData()).also {
                        handles += it
                        it.audienceGroup = audience
                        it.teleportDuration = 2
                        it.interpolationDuration = 2
                        it.brightness = Display.Brightness(12, 12)
                        it.pauseWhenNotObserved = false
                    }
                } else handles[index]
                if (!fresh) handle.teleport(anchor)
                if (fresh || lastScale != scale || part.propeller) {
                    handle.transformation = part.transform(scale, yaw, spin)
                }
            }
            lastScale = scale
        }

        fun clear() {
            handles.forEach(PacketBlockDisplay::remove)
            handles.clear()
        }
    }
}

internal object AirdropFlight {
    const val RELEASE_MS = 1_800L
    private const val TOUCHDOWN_MS = 5_600L
    const val FINISH_MS = 5_900L

    data class Frame(
        val planeX: Float, val planeY: Float, val planeZ: Float, val planeScale: Float,
        val barrelX: Float, val barrelY: Float, val barrelZ: Float, val canopyScale: Float,
    )

    data class Part(
        val material: Material, val x: Float, val y: Float, val z: Float,
        val width: Float, val height: Float, val depth: Float,
        val roll: Float = 0f, val pitch: Float = 0f, val sweep: Float = 0f, val propeller: Boolean = false,
    ) {
        fun transform(scale: Float, yaw: Float, spin: Float): Transformation {
            val bearing = Quaternionf().rotationY(yaw)
            val center = bearing.transform(Vector3f(x, y, z).mul(scale))
            val turn = bearing.rotateY(sweep).rotateX(roll + if (propeller) spin else 0f).rotateZ(pitch)
            val size = Vector3f(width, height, depth).mul(scale)
            val corner = turn.transform(Vector3f(size).mul(-.5f)).add(center)
            return Transformation(corner, turn, size, Quaternionf())
        }
    }

    // Nose points west. The wing tips, twin propellers and raised fin read from below the aisle ceiling.
    val plane = buildList {
        add(Part(Material.LIGHT_GRAY_CONCRETE, -.10f, 0f, 0f, 1.63f, .76f, .96f))
        add(Part(Material.LIGHT_GRAY_CONCRETE, -1.13f, -.075f, 0f, .89f, .50f, .77f))
        add(Part(Material.LIGHT_GRAY_CONCRETE, -1.62f, -.10f, 0f, .25f, .31f, .53f))
        add(Part(Material.GRAY_CONCRETE, -1.03f, .23f, 0f, .73f, .31f, .80f, pitch = .24f))
        add(Part(Material.LIGHT_GRAY_CONCRETE, .99f, .10f, 0f, .88f, .53f, .65f, pitch = .10f))
        add(Part(Material.LIGHT_GRAY_CONCRETE, 1.60f, .20f, 0f, .70f, .32f, .37f))
        add(Part(Material.SMOOTH_STONE, 1.65f, .25f, 0f, .46f, .12f, 2.03f))
        add(Part(Material.ORANGE_TERRACOTTA, 1.64f, .63f, 0f, .43f, .89f, .14f, pitch = -.33f))
        for (side in listOf(-1f, 1f)) {
            add(Part(Material.SMOOTH_STONE, -.28f, .17f, side * .89f, .93f, .14f, 1.78f, sweep = side * .17f))
            add(Part(Material.ORANGE_TERRACOTTA, -.08f, .19f, side * 1.94f, .60f, .13f, .64f,
                roll = -side * .10f, sweep = side * .21f))
            add(Part(Material.CYAN_TERRACOTTA, -.50f, .05f, side * 1.03f, .91f, .36f, .36f))
            add(Part(Material.GRAY_CONCRETE, -1.01f, .08f, side * 1.03f, .10f, .12f, .93f, propeller = true))
        }
    }
    val barrel = Part(Material.BARREL, 0f, .5f, 0f, 1f, 1f, 1f)

    // The canopy opens about the top of the barrel. Each suspension line reaches the lower edge of the fabric.
    val canopy = buildList {
        add(Part(Material.WHITE_WOOL, 0f, 1.37f, 0f, .86f, .16f, .66f))
        for (side in listOf(-1f, 1f)) {
            add(Part(Material.ORANGE_WOOL, 0f, 1.22f, side * .68f, .86f, .17f, .84f, roll = side * .38f))
            for (end in listOf(-1f, 1f)) {
                add(Part(Material.SMOOTH_QUARTZ, end * .34f, .51f, side * .65f,
                    .055f, 1.23f, .055f, roll = side * .52f))
            }
        }
    }

    fun frame(age: Long, frontEntrance: Boolean, windSign: Float): Frame {
        // The higher shelves end below Y=72. The front doors have a separate clear lane below their lintel.
        val laneY = if (frontEntrance) 3.40f else 7.53f
        val laneZ = if (frontEntrance) -2.40f else 2.05f
        val planeX = 1.20f + (RELEASE_MS - age) * .0075f
        val planeScale = minOf(((age + 100) / 450f).coerceIn(0f, 1f), ((4_150 - age) / 550f).coerceIn(0f, 1f))
        val sinceRelease = (age - RELEASE_MS).coerceAtLeast(0)
        val u = (sinceRelease.toFloat() / (TOUCHDOWN_MS - RELEASE_MS)).coerceIn(0f, 1f)
        val coast = (1f - sinceRelease / 320f).coerceIn(0f, 1f)
        val y = if (u < .25f) 1f - 4f * u * u else 1f - u
        val drift = u * u * (3f - 2f * u)
        val sway = (.48 * sin(2 * PI * u) * u * (1f - u)).toFloat() * windSign
        val open = ((sinceRelease - 400) / 600f).coerceIn(0f, 1f)
        val fold = ((FINISH_MS - age) / (FINISH_MS - TOUCHDOWN_MS).toFloat()).coerceIn(0f, 1f)
        return Frame(planeX, laneY, laneZ, planeScale * if (frontEntrance) .90f else 1f, 1.20f * coast * coast,
            (laneY - .45f) * y, if (u == 1f) 0f else laneZ * (1f - drift) + sway, open * fold)
    }
}
