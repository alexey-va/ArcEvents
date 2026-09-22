package ru.ruscrafting.events.domain

/** Stable island and creature keys shared by the fishing domain and its UI. */
enum class FishingIsland(
    val catchSpecies: List<String>,
    val bossSpecies: String,
) {
    CAMP(listOf("clam", "shore_crab", "shrimp"), "spider_crab"),
    REEF(listOf("reef_perch", "reef_eel", "reef_piranha"), "giant_piranha"),
    SHRINE(listOf("needlefish", "bowlfish", "seahorse"), "pufferfish"),
    CLIFFS(listOf("tuna", "mackerel", "rockfish"), "albatross"),
    VOLCANO(listOf("ash_carp", "lava_salmon", "ember_trout"), "lava_whale"),
}

enum class FishingOfferKind { GEAR, AMMO, DYNAMITE, ROD_UPGRADE }

/** Permanent-in-session equipment stats; firearm damage comes from the shared firearm catalog. */
enum class FishingGear(
    val firearmId: FirearmId?,
    val meleeDamage: Double,
    val cooldownMillis: Long,
    val unlockStage: Int,
) {
    KNUCKLES(null, 4.0, 700L, 0),
    KNIFE(null, 7.0, 500L, 0),
    MACHETE(null, 11.0, 650L, 0),
    PISTOL(FirearmId.FIVE_SEVEN, 0.0, 450L, 1),
    SHOTGUN(FirearmId.DOUBLE_BARREL, 0.0, 850L, 1),
    BURST_RIFLE(FirearmId.G36, 0.0, 650L, 2),
    ASSAULT_RIFLE(FirearmId.AEK_971, 0.0, 450L, 3),
    SNIPER(FirearmId.MCMILLAN, 0.0, 1_200L, 4),
}

/** Catalog defaults can be overridden by validated world configuration. */
enum class FishingOffer(
    val gear: FishingGear?,
    val price: Int,
    val unlockStage: Int,
    val kind: FishingOfferKind,
) {
    KNIFE(FishingGear.KNIFE, 25, 0, FishingOfferKind.GEAR),
    MACHETE(FishingGear.MACHETE, 55, 0, FishingOfferKind.GEAR),
    PISTOL(FishingGear.PISTOL, 80, 1, FishingOfferKind.GEAR),
    SHOTGUN(FishingGear.SHOTGUN, 140, 1, FishingOfferKind.GEAR),
    BURST_RIFLE(FishingGear.BURST_RIFLE, 220, 2, FishingOfferKind.GEAR),
    ASSAULT_RIFLE(FishingGear.ASSAULT_RIFLE, 300, 3, FishingOfferKind.GEAR),
    SNIPER(FishingGear.SNIPER, 420, 4, FishingOfferKind.GEAR),
    AMMO(null, 8, 1, FishingOfferKind.AMMO),
    DYNAMITE(null, 12, 1, FishingOfferKind.DYNAMITE),
    ROD_ONE(null, 45, 1, FishingOfferKind.ROD_UPGRADE),
    ROD_TWO(null, 120, 2, FishingOfferKind.ROD_UPGRADE),
}

data class FishingCatch(
    val species: String,
    val value: Int,
    val rare: Boolean,
    val maxHealth: Double,
    val boss: Boolean = false,
) {
    init {
        require(species.isNotBlank())
        require(value >= 0)
        require(maxHealth.isFinite() && maxHealth > 0.0)
    }
}

object FishingCatalog {
    val defaultPrices: Map<FishingOffer, Int> = FishingOffer.entries.associateWith(FishingOffer::price)

    fun island(stage: Int): FishingIsland =
        FishingIsland.entries.getOrNull(stage) ?: throw IllegalArgumentException("Unknown fishing stage: $stage")

    /** Uses completed normal kills so aborting and retrying a cast cannot reroll a catch. */
    fun catchFor(stage: Int, completedNormalKills: Int, rules: FishingRules): FishingCatch {
        require(completedNormalKills >= 0)
        val island = island(stage)
        val index = completedNormalKills % island.catchSpecies.size
        val rare = completedNormalKills % rules.rareEvery == rules.rareEvery - 1
        val baseValue = rules.saleBase + rules.salePerStage * stage
        return FishingCatch(
            species = island.catchSpecies[index],
            value = baseValue * if (rare) rules.rareMultiplier else 1,
            rare = rare,
            maxHealth = rules.creatureHealth * (1.0 + stage * 0.45) * if (rare) 1.4 else 1.0,
        )
    }

    fun bossFor(stage: Int, rules: FishingRules): FishingCatch {
        val island = island(stage)
        return FishingCatch(
            species = island.bossSpecies,
            value = rules.bossRewardBase + rules.bossRewardPerStage * stage,
            rare = false,
            maxHealth = rules.bossHealth * (1.0 + stage * 0.5),
            boss = true,
        )
    }
}
