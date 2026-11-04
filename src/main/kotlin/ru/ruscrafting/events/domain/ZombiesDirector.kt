package ru.ruscrafting.events.domain

import java.util.UUID

enum class ZombiesPhase { INTERMISSION, WAVE, WON, LOST }
enum class ZombiesStatus { ALIVE, DOWNED, WAITING, LEFT }
enum class ZombieKind(val killPoints: Int, val assistPoints: Int) {
    WALKER(100, 50), RUNNER(125, 50), BRUTE(500, 150),
}
enum class ZombiesOffer(val basePrice: Int) { AMMO(150), MYSTERY(650), POWER(1000), VITALITY(1500) }

data class ZombiesRules(val waves: Int = 10, val intermissionSeconds: Int = 15, val maximumAlive: Int = 28) {
    fun validated() = apply {
        require(waves in 5..20 && intermissionSeconds in 8..30 && maximumAlive in 8..32)
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
    var completedWaves = 0
        private set
    var pending = 0
        private set
    var alive = 0
        private set
    var waveSize = 0
        private set
    var nextWaveAt = now + rules.intermissionSeconds * 1000L
        private set
    private var spawned = 0
    private data class MysteryReservation(val weapon: FirearmId, val paid: Int)
    private val mystery = mutableMapOf<UUID, MysteryReservation>()

    fun tick(now: Long) {
        if (phase == ZombiesPhase.INTERMISSION && now >= nextWaveAt) {
            wave++
            waveSize = 8 + (wave - 1) * 3 + (players.values.count { it.status != ZombiesStatus.LEFT } - 1) * 3
            pending = waveSize
            spawned = 0
            phase = ZombiesPhase.WAVE
        }
        players.filterValues { it.status == ZombiesStatus.DOWNED && it.recoveryAt == null && now >= requireNotNull(it.bleedOutAt) }
            .keys.forEach { id -> players = players + (id to players.getValue(id).copy(status = ZombiesStatus.WAITING)) }
        resolveDefeat()
    }

    fun spawn(): ZombieKind? {
        if (phase != ZombiesPhase.WAVE || pending == 0 || alive >= rules.maximumAlive) return null
        val kind = when {
            wave % 5 == 0 && spawned == 0 -> ZombieKind.BRUTE
            wave >= 3 && spawned % 3 == 2 -> ZombieKind.RUNNER
            else -> ZombieKind.WALKER
        }
        spawned++
        pending--
        alive++
        return kind
    }

    /** Called once after the native owner removes a known mob. Missing/despawned mobs carry no award. */
    fun removeEnemy(kind: ZombieKind, killer: UUID?, now: Long) {
        require(phase == ZombiesPhase.WAVE && alive > 0)
        alive--
        if (killer != null && players[killer]?.status == ZombiesStatus.ALIVE) {
            players = players.mapValues { (id, player) ->
                if (player.status == ZombiesStatus.LEFT) player else player.copy(points = player.points +
                    if (id == killer) kind.killPoints else kind.assistPoints)
            }
        }
        if (pending == 0 && alive == 0) {
            completedWaves = wave
            players = players.mapValues { (_, player) -> if (player.status == ZombiesStatus.LEFT) player else
                player.copy(points = player.points + 150 + 25 * wave, status = ZombiesStatus.ALIVE, recoveryAt = null, bleedOutAt = null) }
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
        if (refund) players[id]?.let { players = players + (id to it.copy(points = it.points + reserved.paid)) }
        return true
    }

    fun health(kind: ZombieKind): Double = (18.0 + (wave - 1) * 5) *
        (1 + .15 * (players.values.count { it.status != ZombiesStatus.LEFT } - 1)) * if (kind == ZombieKind.BRUTE) 6 else 1

    private fun resolveDefeat() {
        if (phase in setOf(ZombiesPhase.WON, ZombiesPhase.LOST)) return
        if (players.values.none { it.status == ZombiesStatus.ALIVE || it.status == ZombiesStatus.DOWNED && it.recoveryAt != null }) phase = ZombiesPhase.LOST
    }

    companion object {
        val MYSTERY_WEAPONS = listOf(FirearmId.G36, FirearmId.AEK_971, FirearmId.RPL_20, FirearmId.VEPR_12,
            FirearmId.M1_GARAND, FirearmId.VSS_VINTOREZ, FirearmId.MCMILLAN, FirearmId.DOUBLE_BARREL)
    }
}
