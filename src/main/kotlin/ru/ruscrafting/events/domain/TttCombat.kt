package ru.ruscrafting.events.domain

import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

enum class FirearmId { PISTOL, SMG, SHOTGUN, RIFLE }

data class FirearmSpec(
    val id: FirearmId,
    val magazineSize: Int,
    val roundsPerShot: Int,
    val pellets: Int,
    val damagePerPellet: Double,
    val range: Double,
    val spreadDegrees: Double,
    val cooldownTicks: Int,
    val reloadTicks: Int,
) {
    fun validated(): FirearmSpec = apply {
        require(magazineSize in 1..64)
        require(roundsPerShot in 1..magazineSize)
        require(pellets in 1..16)
        require(damagePerPellet in 0.1..40.0)
        require(range in 4.0..160.0)
        require(spreadDegrees in 0.0..30.0)
        require(cooldownTicks in 1..100 && reloadTicks in 1..200)
    }
}

object TttFirearmCatalog {
    val specs: Map<FirearmId, FirearmSpec> = listOf(
        FirearmSpec(FirearmId.PISTOL, 12, 1, 1, 6.0, 52.0, 1.4, 7, 30),
        FirearmSpec(FirearmId.SMG, 24, 3, 3, 3.0, 42.0, 4.2, 5, 40),
        FirearmSpec(FirearmId.SHOTGUN, 6, 1, 8, 2.6, 26.0, 8.5, 18, 48),
        FirearmSpec(FirearmId.RIFLE, 8, 1, 1, 11.0, 90.0, 0.45, 22, 46),
    ).associateBy { it.id }.also { catalog -> catalog.values.forEach(FirearmSpec::validated) }
}

data class ShotDirection(val x: Double, val y: Double, val z: Double) {
    fun normalized(): ShotDirection {
        val length = kotlin.math.sqrt(x * x + y * y + z * z)
        require(length > 0.0 && length.isFinite())
        return ShotDirection(x / length, y / length, z / length)
    }
}

object FirearmSpread {
    fun apply(base: ShotDirection, yawOffsetDegrees: Double, pitchOffsetDegrees: Double): ShotDirection {
        val direction = base.normalized()
        val yaw = Math.toRadians(yawOffsetDegrees)
        val pitch = Math.toRadians(pitchOffsetDegrees)
        val yawedX = direction.x * cos(yaw) - direction.z * sin(yaw)
        val yawedZ = direction.x * sin(yaw) + direction.z * cos(yaw)
        val horizontal = kotlin.math.sqrt(yawedX * yawedX + yawedZ * yawedZ)
        val basePitch = kotlin.math.atan2(direction.y, horizontal) + pitch
        return ShotDirection(
            yawedX / horizontal.coerceAtLeast(1e-9) * cos(basePitch),
            sin(basePitch),
            yawedZ / horizontal.coerceAtLeast(1e-9) * cos(basePitch),
        ).normalized()
    }
}

data class CombatRecord(
    val sequence: Int,
    val occurredAtMs: Long,
    val attackerId: UUID?,
    val attackerName: String?,
    val victimId: UUID,
    val victimName: String,
    val weapon: String,
    val finalDamage: Double,
    val friendly: Boolean,
    val headshot: Boolean,
    val lethal: Boolean,
) {
    fun validated(): CombatRecord = apply {
        require(sequence in 1..10_000)
        require(occurredAtMs >= 0)
        require(attackerName == null || attackerName.matches(Regex("[A-Za-z0-9_]{1,16}")))
        require(victimName.matches(Regex("[A-Za-z0-9_]{1,16}")))
        require(weapon.length in 1..48)
        require(finalDamage.isFinite() && finalDamage in 0.0..100.0)
    }
}

enum class RosterStatus { ALIVE, MISSING, CONFIRMED_DEAD, DISCONNECTED }

data class RosterEntry(
    val playerId: UUID,
    val playerName: String,
    val status: RosterStatus,
    val publicRole: TttRole?,
)
