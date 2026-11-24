package ru.ruscrafting.events.domain

import java.util.UUID

enum class ZombiesPhase { INTERMISSION, WAVE, WON, LOST }
enum class ZombiesStatus { ALIVE, DOWNED, WAITING, LEFT }
enum class ZombieKind(val killPoints: Int, val assistPoints: Int) {
    WALKER(100, 50), RUNNER(125, 50), BRUTE(500, 150),
    STRONG(175, 75), BLOATER(150, 75), BOSS(1500, 300),
}
enum class ZombiesOffer(val basePrice: Int) { AMMO(150), MYSTERY(650), POWER(1000), VITALITY(1500) }

enum class ZombiesGear(
    private val points: Int = 0,
    val copper: Int = 0,
    val powder: Int = 0,
    val iron: Int = 0,
    val requiredBench: Int = 0,
) {
    WIRE(points = 500),
    WALL(points = 800),
    BENCH(points = 1000),
    TURRET(iron = 4, requiredBench = 2),
    HE(copper = 7, powder = 9),
    AP(copper = 7, powder = 9),
    FRAG(copper = 2, powder = 2),
    SMOKE(copper = 1, powder = 2, requiredBench = 1),
    ;

    fun pointCost(bench: Int): Int = if (this == BENCH) points * (bench + 1) else points
}

enum class ZombiesDifficulty(val defaultWaves: Int) { CLASSIC(10), EASY(25), HARD(35), HARDCORE(50), INSANE(1) }

data class ZombiesRules(
    val waves: Int = ZombiesDifficulty.CLASSIC.defaultWaves,
    val intermissionSeconds: Int = 15,
    val maximumAlive: Int = 28,
    val difficulty: ZombiesDifficulty = ZombiesDifficulty.CLASSIC,
) {
    val practice: Boolean get() = difficulty != ZombiesDifficulty.CLASSIC

    fun validated() = apply {
        require(if (practice) waves == difficulty.defaultWaves else waves in 5..20)
        require(intermissionSeconds in 8..30 && maximumAlive in 8..32)
    }

    companion object {
        fun forDifficulty(difficulty: ZombiesDifficulty): ZombiesRules = when (difficulty) {
            ZombiesDifficulty.CLASSIC -> ZombiesRules()
            ZombiesDifficulty.EASY -> ZombiesRules(difficulty.defaultWaves, 10, 16, difficulty)
            ZombiesDifficulty.HARD -> ZombiesRules(difficulty.defaultWaves, 10, 20, difficulty)
            ZombiesDifficulty.HARDCORE -> ZombiesRules(difficulty.defaultWaves, 8, 24, difficulty)
            ZombiesDifficulty.INSANE -> ZombiesRules(difficulty.defaultWaves, 15, 8, difficulty)
        }
    }
}

data class ZombiesSurvivor(
    val points: Int = 500,
    val status: ZombiesStatus = ZombiesStatus.ALIVE,
    val weapon: FirearmId = FirearmId.FIVE_SEVEN,
    val power: Int = 0,
    val vitality: Boolean = false,
    val selfRevives: Int = 0,
    val recoveryAt: Long? = null,
    val bleedOutAt: Long? = null,
    val copper: Int = 0,
    val powder: Int = 0,
    val iron: Int = 0,
    val bench: Int = 0,
)

/** Match-local wave and purchase authority. No persistent currency, drops or external reward callbacks. */
class ZombiesDirector(roster: Set<UUID>, val rules: ZombiesRules, now: Long) {
    init { require(roster.size in 1..4); rules.validated() }
    var players: Map<UUID, ZombiesSurvivor> = roster.associateWith { ZombiesSurvivor(selfRevives = if (roster.size == 1) 1 else 0) }
        private set
    var phase = ZombiesPhase.INTERMISSION
        private set
    var wave = 0
        private set
    private val clearedWaves = mutableSetOf<Int>()
    val completedWaves: Int get() = clearedWaves.size
    var pending = 0
        private set
    var alive = 0
        private set
    var waveSize = 0
        private set
    var nextWaveAt = now
        private set
    private var spawned = 0
    private val active: Boolean get() = phase == ZombiesPhase.INTERMISSION || phase == ZombiesPhase.WAVE
    private val teammates: Int get() = players.values.count { it.status != ZombiesStatus.LEFT } - 1
    private data class MysteryReservation(val weapon: FirearmId, val paid: Int)
    private val mystery = mutableMapOf<UUID, MysteryReservation>()

    fun tick(now: Long) {
        if (phase == ZombiesPhase.INTERMISSION && now >= nextWaveAt) startWave(wave + 1)
        players.filterValues { it.status == ZombiesStatus.DOWNED && it.recoveryAt == null && now >= requireNotNull(it.bleedOutAt) }
            .keys.forEach { id -> players = players + (id to players.getValue(id).copy(status = ZombiesStatus.WAITING)) }
        resolveDefeat()
    }

    /** The arena owner removes the previous wave's native enemies after this transition succeeds. */
    fun jumpToWave(number: Int, now: Long): Boolean {
        if (!rules.practice || !active || number !in 1..rules.waves) return false
        nextWaveAt = now
        startWave(number)
        return true
    }

    private fun startWave(number: Int) {
        wave = number
        val ramp = (wave - 1) / 2
        waveSize = when (rules.difficulty) {
            ZombiesDifficulty.CLASSIC -> 8 + (wave - 1) * 3 + teammates * 3
            ZombiesDifficulty.EASY -> minOf(18, 7 + ramp) + teammates * 2
            ZombiesDifficulty.HARD -> minOf(24, 8 + ramp) + teammates * 3
            ZombiesDifficulty.HARDCORE -> minOf(28, 9 + ramp) + teammates * 3
            ZombiesDifficulty.INSANE -> 1
        }
        pending = waveSize
        alive = 0
        spawned = 0
        phase = ZombiesPhase.WAVE
    }

    fun spawn(kind: ZombieKind? = null): ZombieKind? {
        if (phase != ZombiesPhase.WAVE || alive >= rules.maximumAlive) return null
        if (kind != null) {
            if (!rules.practice) return null
            waveSize++
            alive++
            return kind
        }
        if (pending == 0) return null
        val scheduled = scheduledKind()
        spawned++
        pending--
        alive++
        return scheduled
    }

    private fun scheduledKind(): ZombieKind {
        if (rules.difficulty == ZombiesDifficulty.INSANE) return ZombieKind.BOSS
        if (wave % 5 == 0 && spawned == 0) return ZombieKind.BRUTE
        return when (rules.difficulty) {
            ZombiesDifficulty.HARD -> when (spawned % 8) {
                2, 5, 7 -> ZombieKind.RUNNER
                4 -> if (wave >= 3) ZombieKind.STRONG else ZombieKind.WALKER
                6 -> if (wave >= 5) ZombieKind.BLOATER else ZombieKind.WALKER
                else -> ZombieKind.WALKER
            }
            ZombiesDifficulty.HARDCORE -> when (spawned % 6) {
                1, 3 -> ZombieKind.RUNNER
                2 -> ZombieKind.STRONG
                4 -> ZombieKind.BLOATER
                else -> ZombieKind.WALKER
            }
            else -> if (wave >= 3 && spawned % 3 == 2) ZombieKind.RUNNER else ZombieKind.WALKER
        }
    }

    /** Called once after the native owner removes a known mob. Missing/despawned mobs carry no award. */
    fun removeEnemy(kind: ZombieKind, killer: UUID?, now: Long) {
        require(phase == ZombiesPhase.WAVE && alive > 0)
        alive--
        if (killer != null && players[killer]?.status == ZombiesStatus.ALIVE) {
            players = players.mapValues { (id, player) ->
                if (player.status == ZombiesStatus.LEFT) player else {
                    val materials = if (rules.practice && id == killer) 1 else 0
                    player.copy(
                        points = player.points.award(if (id == killer) kind.killPoints else kind.assistPoints),
                        copper = player.copper.award(materials),
                        powder = player.powder.award(materials),
                    )
                }
            }
        }
        if (pending == 0 && alive == 0) {
            clearedWaves += wave
            players = players.mapValues { (_, player) -> if (player.status == ZombiesStatus.LEFT) player else
                player.copy(points = player.points.award(150 + 25 * wave), status = ZombiesStatus.ALIVE, recoveryAt = null, bleedOutAt = null) }
            phase = if (wave == rules.waves) ZombiesPhase.WON else ZombiesPhase.INTERMISSION
            nextWaveAt = now + rules.intermissionSeconds * 1000L
        }
    }

    fun down(id: UUID, now: Long): Boolean {
        val player = players[id] ?: return false
        if (phase !in setOf(ZombiesPhase.WAVE, ZombiesPhase.INTERMISSION) || player.status != ZombiesStatus.ALIVE) return false
        players = players + (id to player.copy(status = ZombiesStatus.DOWNED,
            selfRevives = (player.selfRevives - 1).coerceAtLeast(0),
            recoveryAt = if (player.selfRevives > 0) now + 3000L else null, bleedOutAt = now + 25_000L))
        resolveDefeat()
        return true
    }

    fun revive(id: UUID): Boolean {
        val player = players[id] ?: return false
        if (phase !in setOf(ZombiesPhase.WAVE, ZombiesPhase.INTERMISSION) || player.status != ZombiesStatus.DOWNED) return false
        players = players + (id to player.copy(status = ZombiesStatus.ALIVE, recoveryAt = null, bleedOutAt = null))
        return true
    }

    fun disconnect(id: UUID) {
        cancelMystery(id, refund = false)
        players[id]?.let { players = players + (id to it.copy(status = ZombiesStatus.LEFT, recoveryAt = null, bleedOutAt = null)) }
        resolveDefeat()
    }

    fun price(id: UUID, offer: ZombiesOffer): Int = offer.basePrice * if (offer == ZombiesOffer.POWER) players.getValue(id).power + 1 else 1

    fun canBuy(id: UUID, offer: ZombiesOffer): Boolean {
        val player = players[id] ?: return false
        return phase in setOf(ZombiesPhase.WAVE, ZombiesPhase.INTERMISSION) && player.status == ZombiesStatus.ALIVE &&
            !(offer == ZombiesOffer.MYSTERY && id in mystery) &&
            !(offer == ZombiesOffer.POWER && player.power == 3) && !(offer == ZombiesOffer.VITALITY && player.vitality) &&
            player.points >= price(id, offer)
    }

    fun buy(id: UUID, offer: ZombiesOffer): Boolean {
        // Mystery weapons enter inventory only through a paid world-box reservation and claim.
        if (offer == ZombiesOffer.MYSTERY) return false
        if (!canBuy(id, offer)) return false
        val player = players.getValue(id)
        players = players + (id to player.copy(points = player.points - price(id, offer),
            power = player.power + if (offer == ZombiesOffer.POWER) 1 else 0,
            vitality = player.vitality || offer == ZombiesOffer.VITALITY))
        return true
    }

    private fun fieldSurvivor(id: UUID): ZombiesSurvivor? =
        players[id]?.takeIf { rules.practice && it.status == ZombiesStatus.ALIVE }

    fun canBuyGear(id: UUID, gear: ZombiesGear): Boolean {
        val player = fieldSurvivor(id) ?: return false
        return active && player.bench >= gear.requiredBench && (gear != ZombiesGear.BENCH || player.bench < 2) &&
            player.points >= gear.pointCost(player.bench) && player.copper >= gear.copper &&
            player.powder >= gear.powder && player.iron >= gear.iron
    }

    fun buyGear(id: UUID, gear: ZombiesGear): Boolean {
        if (!canBuyGear(id, gear)) return false
        val player = players.getValue(id)
        players = players + (id to player.copy(
            points = player.points - gear.pointCost(player.bench),
            copper = player.copper - gear.copper,
            powder = player.powder - gear.powder,
            iron = player.iron - gear.iron,
            bench = player.bench + if (gear == ZombiesGear.BENCH) 1 else 0,
        ))
        return true
    }

    fun grantSupplies(id: UUID, copper: Int = 0, powder: Int = 0, iron: Int = 0, points: Int = 0): Boolean {
        val player = fieldSurvivor(id) ?: return false
        if (!active || copper !in 0..Int.MAX_VALUE - player.copper || powder !in 0..Int.MAX_VALUE - player.powder ||
            iron !in 0..Int.MAX_VALUE - player.iron || points !in 0..Int.MAX_VALUE - player.points) return false
        players = players + (id to player.copy(
            copper = player.copper + copper, powder = player.powder + powder,
            iron = player.iron + iron, points = player.points + points,
        ))
        return true
    }

    fun canAcceptAirdrop(id: UUID): Boolean = (active || phase == ZombiesPhase.WON) && fieldSurvivor(id) != null

    /** The arena owner validates the single-use drop and equips any gift before committing this award. */
    fun acceptAirdrop(id: UUID, bonusPoints: Boolean, deliveredWeapon: FirearmId? = null): Boolean {
        if (!canAcceptAirdrop(id)) return false
        val player = players.getValue(id)
        if (deliveredWeapon != null && (deliveredWeapon !in MYSTERY_WEAPONS || deliveredWeapon == player.weapon)) return false
        players = players + (id to player.copy(
            copper = player.copper.award(3), powder = player.powder.award(4), iron = player.iron.award(2),
            points = player.points.award(if (bonusPoints) 250 else 0), weapon = deliveredWeapon ?: player.weapon,
        ))
        return true
    }

    fun reserveMystery(id: UUID, weapon: FirearmId): Boolean {
        if (!canBuy(id, ZombiesOffer.MYSTERY)) return false
        val player = players.getValue(id)
        require(weapon in MYSTERY_WEAPONS && weapon != player.weapon)
        val paid = price(id, ZombiesOffer.MYSTERY)
        mystery[id] = MysteryReservation(weapon, paid)
        players = players + (id to player.copy(points = player.points - paid))
        return true
    }

    fun claimMystery(id: UUID): FirearmId? {
        val player = players[id] ?: return null
        if (player.status != ZombiesStatus.ALIVE || phase !in setOf(ZombiesPhase.WAVE, ZombiesPhase.INTERMISSION)) return null
        val reserved = mystery.remove(id) ?: return null
        players = players + (id to player.copy(weapon = reserved.weapon))
        return reserved.weapon
    }

    /** Expiry/quit consumes the paid roll. Only technical cancellation returns its original debit once. */
    fun cancelMystery(id: UUID, refund: Boolean): Boolean {
        val reserved = mystery.remove(id) ?: return false
        if (refund) players[id]?.let { players = players + (id to it.copy(points = it.points.award(reserved.paid))) }
        return true
    }

    fun health(kind: ZombieKind): Double {
        val round = wave - 1
        val base = when (rules.difficulty) {
            ZombiesDifficulty.CLASSIC -> 18.0 + round * 5
            ZombiesDifficulty.EASY -> minOf(45.0, 14.0 + round * 1.4)
            ZombiesDifficulty.HARD -> minOf(70.0, 18.0 + round * 1.8)
            ZombiesDifficulty.HARDCORE -> minOf(92.0, 22.0 + round * 2)
            ZombiesDifficulty.INSANE -> 22.0
        }
        val party = 1 + .15 * teammates
        return when (kind) {
            ZombieKind.BRUTE -> base * party * 6
            ZombieKind.STRONG -> base * party * 1.65
            ZombieKind.BLOATER -> base * party * 1.25
            ZombieKind.BOSS -> 420.0 * party
            else -> base * party
        }
    }

    private fun Int.award(amount: Int): Int = this + minOf(amount, Int.MAX_VALUE - this)

    private fun resolveDefeat() {
        if (phase in setOf(ZombiesPhase.WON, ZombiesPhase.LOST)) return
        if (players.values.none { it.status == ZombiesStatus.ALIVE || it.status == ZombiesStatus.DOWNED && it.recoveryAt != null }) phase = ZombiesPhase.LOST
    }

    companion object {
        val MYSTERY_WEAPONS = listOf(FirearmId.G36, FirearmId.AEK_971, FirearmId.RPL_20, FirearmId.VEPR_12,
            FirearmId.M1_GARAND, FirearmId.VSS_VINTOREZ, FirearmId.MCMILLAN, FirearmId.DOUBLE_BARREL)
    }
}
