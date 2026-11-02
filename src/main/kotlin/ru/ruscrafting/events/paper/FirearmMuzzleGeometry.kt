package ru.ruscrafting.events.paper

import org.bukkit.util.Vector
import ru.ruscrafting.events.domain.FirearmId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

private data class MuzzlePoint(val x: Double, val y: Double, val z: Double)
private data class MuzzleProfile(val hipRight: MuzzlePoint, val rawLeft: MuzzlePoint, val scopedLeft: MuzzlePoint, val aim: MuzzlePoint)

/**
 * Barrel-face centers from the shipped vendor models and event_sights hip/aim transforms.
 * Camera coordinates: +X right, +Y up, -Z forward, in blocks. Native 1.21.11 settled hand pose.
 * The source fixture records the selected element, model hashes and native transform inputs.
 * Double-barrel uses its right barrel; one trigger still consumes the existing single round.
 */
private val MUZZLES = mapOf(
    FirearmId.FLINTLOCK to MuzzleProfile(
        MuzzlePoint(0.665389958, -0.274537038, -0.933285085),
        MuzzlePoint(-0.665389958, -0.274537038, -0.933285085),
        MuzzlePoint(-0.665389958, -0.274537038, -0.933285085),
        MuzzlePoint(0.000000000, -0.082031250, -0.876562500),
    ),
    FirearmId.REVOLVER to MuzzleProfile(
        MuzzlePoint(0.658853277, -0.286703882, -1.009349545),
        MuzzlePoint(-0.658853277, -0.286703882, -1.009349545),
        MuzzlePoint(-0.658853277, -0.286703882, -1.009349545),
        MuzzlePoint(0.000000000, -0.118125000, -0.975000000),
    ),
    FirearmId.HAND_CANNON to MuzzleProfile(
        MuzzlePoint(0.655583303, -0.266276210, -1.045081106),
        MuzzlePoint(-0.655583303, -0.266276210, -1.045081106),
        MuzzlePoint(-0.655583303, -0.266276210, -1.045081106),
        MuzzlePoint(0.000000000, -0.101591175, -1.009021250),
    ),
    FirearmId.DOUBLE_BARREL to MuzzleProfile(
        MuzzlePoint(0.660028614, -0.223091019, -1.306183642),
        MuzzlePoint(-0.605384494, -0.223507688, -1.301421094),
        MuzzlePoint(-0.605384494, -0.223507688, -1.301421094),
        MuzzlePoint(0.031083283, -0.051000000, -1.302250000),
    ),
    FirearmId.FIVE_SEVEN to MuzzleProfile(
        MuzzlePoint(0.466250000, -0.260625000, -1.157500000),
        MuzzlePoint(-0.466250000, -0.260625000, -1.157500000),
        MuzzlePoint(-0.466250000, -0.260625000, -1.157500000),
        MuzzlePoint(0.000000000, -0.095468750, -1.140625000),
    ),
    FirearmId.G36 to MuzzleProfile(
        MuzzlePoint(0.475293402, -0.255182254, -1.491875000),
        MuzzlePoint(0.002734835, -0.144708185, -1.445000000),
        MuzzlePoint(-0.514769557, -0.253113396, -1.491875000),
        MuzzlePoint(-0.001198826, -0.168280412, -1.606250000),
    ),
    FirearmId.AEK_971 to MuzzleProfile(
        MuzzlePoint(0.488345064, -0.241207346, -1.636250000),
        MuzzlePoint(-0.003500000, -0.131435500, -1.448750000),
        MuzzlePoint(-0.500328618, -0.240579314, -1.636250000),
        MuzzlePoint(-0.008700000, -0.235200150, -1.663375000),
    ),
    FirearmId.RPL_20 to MuzzleProfile(
        MuzzlePoint(0.535530009, -0.272583665, -1.974375000),
        MuzzlePoint(0.006875000, -0.096749369, -1.753125000),
        MuzzlePoint(-0.525543714, -0.273107025, -1.974375000),
        MuzzlePoint(0.000000000, -0.213943530, -1.752000000),
    ),
    FirearmId.VEPR_12 to MuzzleProfile(
        MuzzlePoint(0.598843902, -0.312919539, -2.045500000),
        MuzzlePoint(0.003624495, -0.130893505, -1.308125000),
        MuzzlePoint(-0.550486578, -0.315453839, -2.045500000),
        MuzzlePoint(-0.002734063, -0.240147976, -1.734250000),
    ),
    FirearmId.M1_GARAND to MuzzleProfile(
        MuzzlePoint(0.471352834, -0.182696172, -1.801875000),
        MuzzlePoint(0.005000000, -0.081891250, -1.905625000),
        MuzzlePoint(-0.332801640, -0.202785139, -1.801875000),
        MuzzlePoint(0.000000000, -0.052754250, -1.713500000),
    ),
    FirearmId.VSS_VINTOREZ to MuzzleProfile(
        MuzzlePoint(0.463074078, -0.261549491, -1.933125000),
        MuzzlePoint(0.008750000, -0.257311250, -2.270000000),
        MuzzlePoint(-0.432766004, -0.265943952, -1.933125000),
        MuzzlePoint(0.000000000, -0.234320475, -1.736400000),
    ),
    FirearmId.MCMILLAN to MuzzleProfile(
        MuzzlePoint(0.493923007, -0.249715822, -1.927500000),
        MuzzlePoint(0.004750000, -0.171474242, -1.724375000),
        MuzzlePoint(-0.495670609, -0.249624234, -1.927500000),
        MuzzlePoint(0.000000000, -0.236413582, -1.810000000),
    ),
)

internal fun firearmMuzzleOffset(
    id: FirearmId, direction: Vector, yaw: Float, aiming: Boolean, leftHand: Boolean,
    scopedModel: Boolean = false, scopeZoom: Float? = null,
): Vector {
    val profile = MUZZLES.getValue(id)
    val point = when {
        aiming -> profile.aim
        !leftHand -> profile.hipRight
        scopedModel -> profile.scopedLeft
        else -> profile.rawLeft
    }
    // Native hand FOV stays 70 degrees; the settled world multiplier is 1 / server zoom.
    // Client FOV settings, FOV Effects, bob and the native zoom/equip transition remain client-owned.
    val zoom = scopeZoom?.takeIf { it.isFinite() && it >= 1f }?.toDouble() ?: 1.0
    val perspective = tan(Math.toRadians(35.0 / zoom)) / tan(Math.toRadians(35.0))
    val heading = Math.toRadians(yaw.toDouble())
    val right = Vector(-cos(heading), 0.0, -sin(heading))
    val up = right.clone().crossProduct(direction).normalize()
    return right.multiply(point.x * perspective).add(up.multiply(point.y * perspective))
        .subtract(direction.clone().multiply(point.z))
}

/** Keep the muzzle before the nearest crosshair-contact plane, including point-blank targets. */
internal fun firearmMuzzleBeforeContact(offset: Vector, direction: Vector, contactDistance: Double): Vector {
    val forward = offset.dot(direction)
    val scale = if (forward > .000001) ((contactDistance - .03).coerceAtLeast(0.0) / forward).coerceAtMost(1.0) else 0.0
    return offset.clone().multiply(scale)
}

internal fun firearmZeroingDirection(origin: Vector, aimPoint: Vector, fallback: Vector): Vector {
    val delta = aimPoint.clone().subtract(origin)
    return if (delta.lengthSquared() > .000001 && delta.dot(fallback) > 0.0) delta.normalize() else fallback.clone()
}
