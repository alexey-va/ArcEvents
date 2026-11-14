package ru.ruscrafting.events.domain.apocalypse

/** Reference JCWS proximity spawner counters; this is not a power/security/reactor quest. */
data class ApocalypseBunkerSpawnerState(
    val siteId: String,
    val formatVersion: Int = 1,
    val timer: Int = 0,
    val pulses: Int = 0,
    val active: List<ApocalypseZombieSpawn> = emptyList(),
) {
    val removed: Boolean get() = pulses == 6

    fun validated() = apply {
        require(siteId.isNotBlank() && siteId.length <= 160 && siteId.none(Char::isISOControl))
        require(formatVersion == 1 && pulses in 0..6 && timer in -12..14)
        requireNotNull(active)
        require(active.size <= pulses * 2 && active.map { it.id }.distinct().size == active.size)
        active.forEach {
            it.validated()
            val index = it.id.substringAfterLast(':').toIntOrNull()
            require(index != null && index in 0 until pulses * 2 && it.id == "$siteId:bunker:$index")
            require(it.sourceEntityId == "jeffs_cursed_walking_structures:bunker_zombie")
        }
    }
}

data class ApocalypseBunkerSpawnerTransition(val state: ApocalypseBunkerSpawnerState, val spawns: List<ApocalypseZombieSpawn> = emptyList())

class ApocalypseBunkerRules {
    /** One scheduled block tick (every 25 ticks) increments Timer for each player in the inflated-35 AABB. */
    fun randomTick(state: ApocalypseBunkerSpawnerState, nearbyPlayers: Int): ApocalypseBunkerSpawnerTransition {
        state.validated(); require(nearbyPlayers >= 0)
        var next = state
        val spawns = mutableListOf<ApocalypseZombieSpawn>()
        // Six pulses need at most 120 player passes; terminal blocks never continue processing the collection.
        repeat(minOf(nearbyPlayers, 120)) {
            if (next.removed) return@repeat
            val timer = next.timer + 1
            if (timer < 15) { next = next.copy(timer = timer); return@repeat }
            val added = List(2) { index -> ApocalypseZombieSpawn("${state.siteId}:bunker:${next.pulses * 2 + index}",
                ApocalypseZombieVariant.WALKER, sourceEntityId = "jeffs_cursed_walking_structures:bunker_zombie") }
            val pulses = next.pulses + 1
            next = next.copy(timer = -pulses * 2, pulses = pulses, active = next.active + added)
            spawns += added
        }
        return ApocalypseBunkerSpawnerTransition(next, spawns)
    }

    fun enemyDefeated(state: ApocalypseBunkerSpawnerState, enemyId: String): ApocalypseBunkerSpawnerState =
        state.validated().let { if (it.active.none { spawn -> spawn.id == enemyId }) it else it.copy(active = it.active.filterNot { spawn -> spawn.id == enemyId }) }
}
