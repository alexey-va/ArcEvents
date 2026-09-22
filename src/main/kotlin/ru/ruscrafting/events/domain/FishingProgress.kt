package ru.ruscrafting.events.domain

/** Tunable rules for one player's temporary fishing adventure. */
data class FishingRules(
    val islands: Int = 5,
    val catchesPerIsland: Int = 3,
    val minBiteTicks: Int = 40,
    val maxBiteTicks: Int = 100,
    val creatureHealth: Double = 18.0,
    val bossHealth: Double = 80.0,
    val telegraphTicks: Int = 30,
    val attackIntervalTicks: Int = 60,
    val stageTravelRadius: Double = 3.0,
    val fishingRadius: Double = 6.0,
    val prices: Map<FishingOffer, Int> = FishingCatalog.defaultPrices,
    val saleBase: Int = 10,
    val salePerStage: Int = 6,
    val rareEvery: Int = 7,
    val rareMultiplier: Int = 3,
    val bossRewardBase: Int = 50,
    val bossRewardPerStage: Int = 30,
    val dynamiteDamage: Double = 45.0,
) {
    init {
        require(islands == FishingIsland.entries.size)
        require(catchesPerIsland in 1..12)
        require(minBiteTicks >= 1)
        require(maxBiteTicks in 20..1200 && minBiteTicks <= maxBiteTicks)
        require(creatureHealth in 1.0..10_000.0)
        require(bossHealth in creatureHealth..10_000.0)
        require(attackIntervalTicks in 10..1200)
        require(telegraphTicks in 1..attackIntervalTicks)
        require(stageTravelRadius in 0.5..8.0)
        require(fishingRadius in 1.0..12.0)
        require(prices.keys == FishingOffer.entries.toSet())
        require(prices.values.all { it in 1..MAX_COINS })
        require(saleBase in 0..100_000)
        require(salePerStage in 0..100_000)
        require(rareEvery in 1..100_000)
        require(rareMultiplier in 1..100)
        require(bossRewardBase in 0..100_000)
        require(bossRewardPerStage in 0..100_000)
        require((saleBase.toLong() + salePerStage.toLong() * (islands - 1)) * rareMultiplier <= MAX_COINS)
        require(bossRewardBase.toLong() + bossRewardPerStage.toLong() * (islands - 1) <= MAX_COINS)
        require(dynamiteDamage.isFinite() && dynamiteDamage in 0.1..10_000.0)
    }

    companion object {
        const val AMMO_PER_PURCHASE = 32
        const val DYNAMITE_PER_PURCHASE = 2
        const val MAX_COINS = 100_000
        const val MAX_BAG = 64
        const val MAX_DYNAMITE = 16
    }
}

enum class FishingPhase {
    CASTING,
    BITE,
    CREATURE,
    TRAVEL,
    BOSS,
    TROPHY,
    COMPLETE,
    CLOSED,
}

/**
 * Immutable state for a single temporary fishing run.
 *
 * Invalid or duplicate events are no-ops. Progress counts completed normal
 * encounters; landed catches remain in `encounter` until defeated.
 */
data class FishingProgress(
    val rules: FishingRules = FishingRules(),
    val stage: Int = 0,
    val catchesOnStage: Int = 0,
    val totalCatches: Int = stage * rules.catchesPerIsland + catchesOnStage,
    val phase: FishingPhase = FishingPhase.CASTING,
    val creatureHealth: Double = 0.0,
    val castNonce: Long = 0L,
    val coins: Int = 0,
    val bag: List<FishingCatch> = emptyList(),
    val ownedGear: Set<FishingGear> = setOf(FishingGear.KNUCKLES),
    val equippedGear: FishingGear = FishingGear.KNUCKLES,
    val rodLevel: Int = 0,
    val dynamite: Int = 0,
    val bossBait: Boolean = false,
    val encounter: FishingCatch? = null,
    val lastCatch: FishingCatch? = null,
) {
    init {
        require(stage in 0 until rules.islands)
        require(catchesOnStage in 0..rules.catchesPerIsland)
        require(totalCatches >= stage * rules.catchesPerIsland + catchesOnStage)
        require(creatureHealth.isFinite() && creatureHealth >= 0.0)
        require(castNonce >= 0L)
        require(coins in 0..FishingRules.MAX_COINS)
        require(bag.size <= FishingRules.MAX_BAG)
        require(ownedGear.contains(FishingGear.KNUCKLES))
        require(equippedGear in ownedGear)
        require(rodLevel in 0..2)
        require(dynamite in 0..FishingRules.MAX_DYNAMITE)
        require(
            !bossBait ||
                (phase in setOf(FishingPhase.CASTING, FishingPhase.BITE) && catchesOnStage == rules.catchesPerIsland),
        )
        if (phase in setOf(FishingPhase.BOSS, FishingPhase.TROPHY, FishingPhase.TRAVEL, FishingPhase.COMPLETE)) {
            require(catchesOnStage == rules.catchesPerIsland)
        }

        val activeEncounter = phase == FishingPhase.CREATURE || phase == FishingPhase.BOSS
        if (activeEncounter) {
            require(encounter != null)
            require(creatureHealth > 0.0 && creatureHealth <= encounter.maxHealth)
            require(bag.size < FishingRules.MAX_BAG)
            require((phase == FishingPhase.BOSS) == encounter.boss)
        } else {
            require(creatureHealth == 0.0)
        }
        if (phase == FishingPhase.TROPHY) require(encounter?.boss == true)
        if (phase in setOf(FishingPhase.CASTING, FishingPhase.BITE, FishingPhase.TRAVEL, FishingPhase.COMPLETE, FishingPhase.CLOSED)) {
            require(encounter == null)
        }
        if (phase == FishingPhase.TRAVEL) require(!isFinalStage)
        if (phase == FishingPhase.COMPLETE) require(isFinalStage)
        if (phase == FishingPhase.BITE) require(bag.size < FishingRules.MAX_BAG)
    }

    val isFinalStage: Boolean get() = stage == rules.islands - 1
    val catchesNeeded: Int get() = rules.catchesPerIsland - catchesOnStage
    val travelUnlocked: Boolean get() = phase == FishingPhase.TRAVEL
    val bagValue: Long get() = bag.sumOf { it.value.toLong() }

    fun beginCast(): FishingProgress =
        if (phase == FishingPhase.CASTING && bag.size < FishingRules.MAX_BAG && castNonce < Long.MAX_VALUE) {
            copy(phase = FishingPhase.BITE, castNonce = castNonce + 1)
        } else {
            this
        }

    fun reelIn(): FishingProgress =
        if (phase == FishingPhase.BITE) copy(phase = FishingPhase.CASTING) else this

    /** Lands a creature but does not count quest progress or award it until defeat. */
    fun recordCatch(): FishingProgress {
        if (phase != FishingPhase.BITE || bag.size >= FishingRules.MAX_BAG) return this
        val boss = bossBait
        val landed = if (boss) FishingCatalog.bossFor(stage, rules) else FishingCatalog.catchFor(stage, totalCatches, rules)
        return copy(
            phase = if (boss) FishingPhase.BOSS else FishingPhase.CREATURE,
            creatureHealth = landed.maxHealth,
            bossBait = false,
            encounter = landed,
            lastCatch = landed,
        )
    }

    fun damageCreature(amount: Double): FishingProgress {
        if (phase !in setOf(FishingPhase.CREATURE, FishingPhase.BOSS) || !amount.isFinite() || amount <= 0.0) return this
        val landed = encounter ?: return this
        val remaining = creatureHealth - amount
        if (remaining > 0.0) return copy(creatureHealth = remaining)
        if (phase == FishingPhase.BOSS) {
            return copy(
                phase = FishingPhase.TROPHY,
                creatureHealth = 0.0,
                coins = (coins.toLong() + landed.value).coerceAtMost(FishingRules.MAX_COINS.toLong()).toInt(),
            )
        }
        return copy(
            phase = FishingPhase.CASTING,
            creatureHealth = 0.0,
            catchesOnStage = (catchesOnStage + 1).coerceAtMost(rules.catchesPerIsland),
            totalCatches = if (totalCatches == Int.MAX_VALUE) totalCatches else totalCatches + 1,
            bag = bag + landed,
            encounter = null,
        )
    }

    /** Shop transactions are atomic and only available between encounters or before trophy hand-in. */
    fun buy(offer: FishingOffer): FishingProgress {
        if (!canTrade() || stage < offer.unlockStage) return this
        val price = rules.prices[offer] ?: return this
        if (coins < price) return this

        return when (offer.kind) {
            FishingOfferKind.GEAR -> {
                val gear = offer.gear ?: return this
                if (gear in ownedGear || stage < gear.unlockStage) return this
                copy(coins = coins - price, ownedGear = ownedGear + gear, equippedGear = gear)
            }
            FishingOfferKind.AMMO -> {
                if (ownedGear.none { it.firearmId != null }) return this
                copy(coins = coins - price)
            }
            FishingOfferKind.DYNAMITE -> {
                val nextCount = dynamite + FishingRules.DYNAMITE_PER_PURCHASE
                if (nextCount > FishingRules.MAX_DYNAMITE) return this
                copy(coins = coins - price, dynamite = nextCount)
            }
            FishingOfferKind.ROD_UPGRADE -> {
                val nextLevel = when (offer) {
                    FishingOffer.ROD_ONE -> if (rodLevel == 0) 1 else return this
                    FishingOffer.ROD_TWO -> if (rodLevel == 1) 2 else return this
                    else -> return this
                }
                copy(coins = coins - price, rodLevel = nextLevel)
            }
        }
    }

    /** Equipment swaps are free, but can only select already-owned gear during a safe trade phase. */
    fun equip(gear: FishingGear): FishingProgress =
        if (canTrade() && gear in ownedGear && gear != equippedGear) {
            copy(equippedGear = gear)
        } else {
            this
        }

    /** Sells the entire bag or nothing when the coin cap would be exceeded. */
    fun sellCatch(): FishingProgress {
        if (!canTrade() || bag.isEmpty()) return this
        val totalValue = bag.sumOf { it.value.toLong() }
        if (coins.toLong() + totalValue > FishingRules.MAX_COINS) return this
        return copy(coins = coins + totalValue.toInt(), bag = emptyList())
    }

    /** Eating consumes the oldest carried catch. */
    fun eatCatch(): FishingProgress =
        if (canTrade() && bag.isNotEmpty()) copy(bag = bag.drop(1)) else this

    fun claimBossBait(): FishingProgress =
        if (phase == FishingPhase.CASTING && catchesOnStage >= rules.catchesPerIsland && !bossBait) {
            copy(bossBait = true)
        } else {
            this
        }

    fun handInTrophy(): FishingProgress {
        if (phase != FishingPhase.TROPHY || encounter?.boss != true) return this
        return copy(
            phase = if (isFinalStage) FishingPhase.COMPLETE else FishingPhase.TRAVEL,
            creatureHealth = 0.0,
            encounter = null,
            bossBait = false,
        )
    }

    /** Consumes a stick; the arena resolves its fuse, blast radius, and effect separately. */
    fun consumeDynamite(): FishingProgress {
        if (dynamite <= 0 || phase !in setOf(FishingPhase.CASTING, FishingPhase.CREATURE, FishingPhase.BOSS)) return this
        return copy(dynamite = dynamite - 1)
    }

    fun travelToNextStage(): FishingProgress {
        if (phase != FishingPhase.TRAVEL || isFinalStage) return this
        return copy(
            stage = stage + 1,
            catchesOnStage = 0,
            phase = FishingPhase.CASTING,
            creatureHealth = 0.0,
            bossBait = false,
            encounter = null,
        )
    }

    fun close(): FishingProgress =
        if (phase == FishingPhase.CLOSED) this else copy(
            phase = FishingPhase.CLOSED,
            creatureHealth = 0.0,
            coins = 0,
            bag = emptyList(),
            ownedGear = setOf(FishingGear.KNUCKLES),
            equippedGear = FishingGear.KNUCKLES,
            rodLevel = 0,
            dynamite = 0,
            bossBait = false,
            encounter = null,
            lastCatch = null,
        )

    private fun canTrade(): Boolean = phase == FishingPhase.CASTING || phase == FishingPhase.TROPHY
}
