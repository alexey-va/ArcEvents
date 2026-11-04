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
        players[id]?.let { players = players + (id to it.copy(status = ZombiesStatus.LEFT, recoveryAt = null, bleedOutAt = null)) }
        resolveDefeat()
    }

    fun price(id: UUID, offer: ZombiesOffer): Int = offer.basePrice * if (offer == ZombiesOffer.POWER) players.getValue(id).power + 1 else 1

    fun canBuy(id: UUID, offer: ZombiesOffer): Boolean {
        val player = players[id] ?: return false
        return phase in setOf(ZombiesPhase.WAVE, ZombiesPhase.INTERMISSION) && player.status == ZombiesStatus.ALIVE &&
            !(offer == ZombiesOffer.POWER && player.power == 3) && !(offer == ZombiesOffer.VITALITY && player.vitality) &&
            player.points >= price(id, offer)
    }

    fun buy(id: UUID, offer: ZombiesOffer, mystery: FirearmId? = null): Boolean {
        if (!canBuy(id, offer)) return false
        val player = players.getValue(id)
        if (offer == ZombiesOffer.MYSTERY) require(mystery in MYSTERY_WEAPONS && mystery != player.weapon)
        players = players + (id to player.copy(points = player.points - price(id, offer),
            weapon = if (offer == ZombiesOffer.MYSTERY) requireNotNull(mystery) else player.weapon,
            power = player.power + if (offer == ZombiesOffer.POWER) 1 else 0,
            vitality = player.vitality || offer == ZombiesOffer.VITALITY))
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
