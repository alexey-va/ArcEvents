package ru.ruscrafting.events.domain

/** Tunable, non-economic rules for one player's temporary fishing adventure. */
data class FishingRules(
    val islands: Int = 3,
    val catchesPerIsland: Int = 3,
    val minBiteTicks: Int = 40,
    val maxBiteTicks: Int = 100,
    val creatureHealth: Double = 18.0,
    val bossHealth: Double = 80.0,
    val telegraphTicks: Int = 30,
    val attackIntervalTicks: Int = 60,
    val stageTravelRadius: Double = 3.0,
    val fishingRadius: Double = 6.0,
) {
    init {
        require(islands == 3) { "Fishing adventure requires exactly three islands" }
        require(catchesPerIsland in 1..12)
        require(minBiteTicks >= 1)
        require(maxBiteTicks in 20..1200 && minBiteTicks <= maxBiteTicks)
        require(creatureHealth in 1.0..10_000.0)
        require(bossHealth in creatureHealth..10_000.0)
        require(attackIntervalTicks in 10..1200)
        require(telegraphTicks in 1..attackIntervalTicks)
        require(stageTravelRadius in 0.5..8.0)
        require(fishingRadius in 1.0..12.0)
    }
}

enum class FishingPhase {
    CASTING,
    BITE,
    CREATURE,
    TRAVEL,
    BOSS,
    COMPLETE,
    CLOSED,
}

/**
 * Pure state machine for the fishing adventure.
 *
 * Invalid or duplicate events are deliberately no-ops. Bukkit events can be
 * replayed after a hook is removed or arrive after a close, so throwing here
 * would turn stale input into a match failure.
 */
data class FishingProgress(
    val rules: FishingRules = FishingRules(),
    val stage: Int = 0,
    val catchesOnStage: Int = 0,
    val totalCatches: Int = 0,
    val phase: FishingPhase = FishingPhase.CASTING,
    val creatureHealth: Double = 0.0,
    val castNonce: Long = 0L,
) {
    init {
        require(stage in 0 until rules.islands)
        require(catchesOnStage in 0..rules.catchesPerIsland)
        require(totalCatches == stage * rules.catchesPerIsland + catchesOnStage)
        require(creatureHealth.isFinite() && creatureHealth >= 0.0)
        if (phase in setOf(FishingPhase.CREATURE, FishingPhase.BOSS)) require(creatureHealth > 0.0)
        if (phase == FishingPhase.BOSS) require(stage == rules.islands - 1)
    }

    val isFinalStage: Boolean get() = stage == rules.islands - 1
    val catchesNeeded: Int get() = rules.catchesPerIsland - catchesOnStage
    val travelUnlocked: Boolean get() = phase == FishingPhase.TRAVEL

    fun beginCast(): FishingProgress =
        if (phase == FishingPhase.CASTING) copy(phase = FishingPhase.BITE, castNonce = castNonce + 1) else this

    fun reelIn(): FishingProgress =
        if (phase == FishingPhase.BITE) copy(phase = FishingPhase.CASTING) else this

    fun recordCatch(): FishingProgress {
        if (phase != FishingPhase.BITE || catchesOnStage >= rules.catchesPerIsland) return this
        val nextCatch = catchesOnStage + 1
        val nextTotal = totalCatches + 1
        return if (isFinalStage && nextCatch == rules.catchesPerIsland) {
            copy(
                catchesOnStage = nextCatch,
                totalCatches = nextTotal,
                phase = FishingPhase.BOSS,
                creatureHealth = rules.bossHealth,
            )
        } else {
            copy(
                catchesOnStage = nextCatch,
                totalCatches = nextTotal,
                phase = FishingPhase.CREATURE,
                creatureHealth = rules.creatureHealth,
            )
        }
    }

    fun damageCreature(amount: Double): FishingProgress {
        if (phase !in setOf(FishingPhase.CREATURE, FishingPhase.BOSS) || !amount.isFinite() || amount <= 0.0) return this
        val remaining = creatureHealth - amount
        if (remaining > 0.0) return copy(creatureHealth = remaining)
        return when {
            phase == FishingPhase.BOSS -> copy(phase = FishingPhase.COMPLETE, creatureHealth = 0.0)
            catchesOnStage == rules.catchesPerIsland && !isFinalStage -> copy(phase = FishingPhase.TRAVEL, creatureHealth = 0.0)
            else -> copy(phase = FishingPhase.CASTING, creatureHealth = 0.0)
        }
    }

    fun travelToNextStage(): FishingProgress {
        if (phase != FishingPhase.TRAVEL || isFinalStage) return this
        return copy(stage = stage + 1, catchesOnStage = 0, phase = FishingPhase.CASTING, creatureHealth = 0.0)
    }

    fun close(): FishingProgress = if (phase == FishingPhase.CLOSED) this else copy(phase = FishingPhase.CLOSED)
}
