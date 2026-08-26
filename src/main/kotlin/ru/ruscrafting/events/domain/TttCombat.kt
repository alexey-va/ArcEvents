package ru.ruscrafting.events.domain

import ru.arc.network.NetworkPlayerName
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

enum class FirearmId {
    FLINTLOCK,
    REVOLVER,
    HAND_CANNON,
    DOUBLE_BARREL,
    FIVE_SEVEN,
    G36,
    AEK_971,
    RPL_20,
    VEPR_12,
    M1_GARAND,
    VSS_VINTOREZ,
    MCMILLAN,
}

enum class FirearmRarity { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

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
    val rarity: FirearmRarity,
    val lootWeight: Int,
) {
    fun validated(): FirearmSpec = apply {
        require(magazineSize in 1..64)
        require(roundsPerShot in 1..magazineSize)
        require(pellets in 1..16)
        require(damagePerPellet in 0.1..40.0)
        require(range in 4.0..160.0)
        require(spreadDegrees in 0.0..30.0)
        require(cooldownTicks in 1..100 && reloadTicks in 1..200)
        require(lootWeight in 1..16)
    }
}

object TttFirearmCatalog {
    val specs: Map<FirearmId, FirearmSpec> = listOf(
        FirearmSpec(FirearmId.FLINTLOCK, 1, 1, 1, 13.0, 48.0, 1.8, 24, 42, FirearmRarity.COMMON, 5),
        FirearmSpec(FirearmId.REVOLVER, 6, 1, 1, 8.0, 58.0, 1.0, 10, 36, FirearmRarity.UNCOMMON, 4),
        FirearmSpec(FirearmId.HAND_CANNON, 1, 1, 1, 16.0, 40.0, 2.4, 30, 56, FirearmRarity.EPIC, 1),
        FirearmSpec(FirearmId.DOUBLE_BARREL, 2, 1, 10, 2.7, 28.0, 7.5, 20, 46, FirearmRarity.UNCOMMON, 4),
        FirearmSpec(FirearmId.FIVE_SEVEN, 20, 1, 1, 5.0, 55.0, 1.3, 6, 28, FirearmRarity.COMMON, 5),
        FirearmSpec(FirearmId.G36, 30, 3, 3, 3.0, 58.0, 3.0, 6, 42, FirearmRarity.UNCOMMON, 4),
        FirearmSpec(FirearmId.AEK_971, 30, 3, 3, 3.3, 64.0, 2.5, 5, 44, FirearmRarity.RARE, 3),
        FirearmSpec(FirearmId.RPL_20, 48, 4, 4, 2.5, 56.0, 4.0, 6, 64, FirearmRarity.EPIC, 1),
        FirearmSpec(FirearmId.VEPR_12, 8, 1, 8, 2.35, 25.0, 8.0, 12, 52, FirearmRarity.RARE, 3),
        FirearmSpec(FirearmId.M1_GARAND, 8, 1, 1, 10.0, 88.0, 0.65, 14, 46, FirearmRarity.RARE, 3),
        FirearmSpec(FirearmId.VSS_VINTOREZ, 10, 1, 1, 8.5, 76.0, 0.8, 9, 42, FirearmRarity.EPIC, 2),
        FirearmSpec(FirearmId.MCMILLAN, 5, 1, 1, 16.0, 120.0, 0.25, 32, 58, FirearmRarity.LEGENDARY, 1),
    ).associateBy { it.id }.also { catalog -> catalog.values.forEach(FirearmSpec::validated) }

    private val weightedLoot: List<FirearmId> = specs.values.flatMap { spec -> List(spec.lootWeight) { spec.id } }

    /** Ensures broad variety, then fills the remaining positions from the weighted rarity pool. */
    fun lootSelection(count: Int, seed: Long): List<FirearmId> {
        require(count in 1..128)
        val random = kotlin.random.Random(seed)
        val guaranteed = FirearmId.entries.shuffled(random).take(count)
        if (guaranteed.size == count) return guaranteed
        val remaining = generateSequence { weightedLoot.random(random) }.take(count - guaranteed.size).toList()
        return (guaranteed + remaining).shuffled(random)
    }
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
        require(sequence > 0)
        require(occurredAtMs >= 0)
        attackerName?.let(NetworkPlayerName::of)
        NetworkPlayerName.of(victimName)
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
