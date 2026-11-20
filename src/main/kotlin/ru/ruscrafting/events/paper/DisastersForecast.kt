package ru.ruscrafting.events.paper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal enum class DisasterHazard(val id: String) {
    METEORS("meteors"), LIGHTNING("lightning"), FOG("fog");

    companion object {
        fun forRound(round: Int) = entries[round % entries.size]
    }
}

/** A delayed host frame can postpone an impact, but cannot catch up by striking an unannounced target. */
internal class DisastersPulse(private val warningMs: Long, private val restMs: Long, private val endsAt: Long) {
    enum class Beat { QUIET, MARK, CHARGE, IMPACT }

    private var impactAt: Long? = null
    private var nextMark = Long.MIN_VALUE

    fun advance(now: Long): Beat {
        if (now >= endsAt) return Beat.QUIET
        val deadline = impactAt
        if (deadline == null) {
            if (now < nextMark || now + warningMs >= endsAt) return Beat.QUIET
            impactAt = now + warningMs
            return Beat.MARK
        }
        if (now < deadline) return Beat.CHARGE
        impactAt = null
        nextMark = now + restMs
        return Beat.IMPACT
    }

    fun charge(now: Long): Double = impactAt?.let { (1.0 - (it - now).toDouble() / warningMs).coerceIn(0.0, 1.0) } ?: 0.0
}

/** Local X follows the lane. The same radius, ends and width drive the ground marks and the impact test. */
internal class DisastersFootprint(val hazard: DisasterHazard, val across: Boolean) {
    val outline: List<Pair<Double, Double>> = when (hazard) {
        DisasterHazard.METEORS -> List(18) { i ->
            val angle = i * 2 * PI / 18
            METEOR_RADIUS * cos(angle) to METEOR_RADIUS * sin(angle)
        }
        DisasterHazard.LIGHTNING -> buildList {
            for (step in -4..4) {
                add(offset(step * LANE_LENGTH / 4, -LANE_WIDTH))
                add(offset(step * LANE_LENGTH / 4, LANE_WIDTH))
            }
            add(offset(-LANE_LENGTH, 0.0))
            add(offset(LANE_LENGTH, 0.0))
        }
        DisasterHazard.FOG -> emptyList()
    }

    fun offset(forward: Double, side: Double) = if (across) side to forward else forward to side

    fun contains(dx: Double, dy: Double, dz: Double): Boolean {
        if (abs(dy) > VERTICAL_REACH) return false
        val (forward, side) = offset(dx, dz)
        return when (hazard) {
            DisasterHazard.METEORS -> dx * dx + dz * dz <= METEOR_RADIUS * METEOR_RADIUS
            DisasterHazard.LIGHTNING -> abs(forward) <= LANE_LENGTH && abs(side) <= LANE_WIDTH
            DisasterHazard.FOG -> false
        }
    }

    companion object {
        const val WARNING_MS = 2_000L
        const val METEOR_RADIUS = 3.0
        const val LANE_LENGTH = 9.0 // Half-length in blocks, measured from the marked target.
        const val LANE_WIDTH = 2.0 // Half-width; stepping out sideways is always possible on the open wharf.
        const val VERTICAL_REACH = 2.5

        /** The burning head follows one accelerating path from seaward sky to the exact marked centre. */
        fun meteorAltitude(progress: Double): Double = 7.0 * (1.0 - .35 * progress - .65 * progress * progress)

        /** Two banks roll in from sea level. The first recedes, the second reaches the lookout's safe elevation. */
        fun fogSurface(progress: Double, safeY: Double, secondPass: Boolean): Double {
            val t = progress.coerceIn(0.0, 1.0)
            val firstCrest = if (secondPass) .75 else .58
            val level = when {
                t <= .24 -> firstCrest * t / .24
                t <= .42 -> firstCrest + (.25 - firstCrest) * ((t - .24) / .18)
                else -> .25 + .75 * ((t - .42) / .34).coerceAtMost(1.0)
            }
            return safeY - 4.4 * (1.0 - level)
        }
    }
}
