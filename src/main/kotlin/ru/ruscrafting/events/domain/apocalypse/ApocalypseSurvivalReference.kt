package ru.ruscrafting.events.domain.apocalypse

import kotlin.random.Random

data class ApocalypseSpawnWeight(
    val entityId: String,
    val weight: Int,
    val firstDay: Long,
    val lastDay: Long = 0,
    val profile: String = "default",
) {
    fun includes(day: Long): Boolean = day >= firstDay && (lastDay == 0L || day <= lastDay)
    fun spawn(id: String): ApocalypseZombieSpawn = ApocalypseZombieSpawn(id, ApocalypseSurvivalReference.variant(entityId),
        boss = entityId == "mutantmonsters:mutant_zombie", sourceEntityId = entityId, profile = profile)
}

data class ApocalypseNaturalSpawnRule(
    val phase: String,
    val entityIds: List<String>,
    val perSecond: Double,
    val minimumAmount: Int,
    val maximumAmount: Int,
    val maximumThisType: Int,
    val minimumTime: Long = 0,
    val maximumTime: Long = 24_000,
    val caveOnly: Boolean = false,
    val skyOnly: Boolean = true,
    val minimumDistance: Int = 60,
    val maximumDistance: Int = 110,
    val maximumVerticalDistance: Int = 40,
    val minimumLight: Int = 0,
    val maximumLight: Int = 4,
    val attempts: Int = 300,
) {
    fun matches(timeOfDay: Long, light: Int, seesSky: Boolean, inCave: Boolean): Boolean =
        timeOfDay in minimumTime until maximumTime && light in minimumLight..maximumLight &&
            (!caveOnly || inCave) && (!skyOnly || seesSky)
}

/** Source item identity; modded armor needs a Paper/resource-pack equivalent supplied by the adapter. */
data class ApocalypseZombieEquipment(
    val itemId: String,
    val color: Int? = null,
    val headOwner: String? = null,
    val dropChance: Double = 0.0,
)

/**
 * JCWS file8502762 spawner procedures summon minecraft:zombie with these NBT values.
 * sourceEntityId is our stable procedure alias, not a claimed Forge entity registration.
 */
data class ApocalypseStructureZombieProfile(
    val id: String,
    val maximumHealth: Double,
    val damage: Double,
    val speed: Double = .23000000417232513,
    val knockbackResistance: Double = 0.0,
    val commandedHealth: Double = maximumHealth,
    val boots: ApocalypseZombieEquipment,
    val leggings: ApocalypseZombieEquipment,
    val chestplate: ApocalypseZombieEquipment,
    val helmet: ApocalypseZombieEquipment,
    val visualFire: Boolean = false,
) {
    val sourceEntityId: String get() = "jeffs_cursed_walking_structures:${id}_zombie"
    val initialHealth: Double get() = commandedHealth.coerceAtMost(maximumHealth)
    fun spawn(token: String): ApocalypseZombieSpawn = ApocalypseZombieSpawn(token, ApocalypseZombieVariant.WALKER,
        sourceEntityId = sourceEntityId, profile = id)
}

/** Distilled numeric/entity facts, not copied pack scripts or assets. InControl maximum days are exclusive; Hordes table maximum days inclusive. */
object ApocalypseSurvivalReference {
    /** All armor slots are in native boots/leggings/chestplate/helmet order, with exact source drop NBT. */
    val structureZombies: Map<String, ApocalypseStructureZombieProfile> = listOf(
        ApocalypseStructureZombieProfile("hazmat", 45.0, 6.0, speed = .3, knockbackResistance = .1, commandedHealth = 35.0,
            boots = ApocalypseZombieEquipment("minecraft:netherite_boots"),
            leggings = ApocalypseZombieEquipment("marbledsarsenal:hazmat_armor_leggings"),
            chestplate = ApocalypseZombieEquipment("marbledsarsenal:hazmat_armor_chestplate"),
            helmet = ApocalypseZombieEquipment("marbledsarsenal:hazmat_armor_helmet")),
        ApocalypseStructureZombieProfile("soldier", 25.0, 5.0, speed = .23, commandedHealth = 28.0,
            boots = ApocalypseZombieEquipment("minecraft:netherite_boots"),
            leggings = ApocalypseZombieEquipment("minecraft:leather_leggings", color = 6_192_150),
            chestplate = ApocalypseZombieEquipment("minecraft:leather_chestplate", color = 6_192_150),
            helmet = ApocalypseZombieEquipment("minecraft:turtle_helmet")),
        ApocalypseStructureZombieProfile("mutated", 150.0, 9.0, speed = .3, knockbackResistance = .1,
            boots = ApocalypseZombieEquipment("minecraft:netherite_boots", dropChance = .05),
            leggings = ApocalypseZombieEquipment("minecraft:leather_leggings", color = 11_546_150),
            chestplate = ApocalypseZombieEquipment("minecraft:leather_chestplate", color = 11_546_150),
            helmet = ApocalypseZombieEquipment("minecraft:player_head", headOwner = "NetherrackDigger"), visualFire = true),
        ApocalypseStructureZombieProfile("warehouse", 23.0, 5.0, speed = .3,
            boots = ApocalypseZombieEquipment("minecraft:netherite_boots", dropChance = .01),
            leggings = ApocalypseZombieEquipment("minecraft:leather_leggings", color = 4_673_362),
            chestplate = ApocalypseZombieEquipment("minecraft:leather_chestplate", color = 4_673_362),
            helmet = ApocalypseZombieEquipment("minecraft:iron_helmet")),
        ApocalypseStructureZombieProfile("bunker", 28.0, 2.0, knockbackResistance = .3,
            boots = ApocalypseZombieEquipment("minecraft:netherite_boots"),
            leggings = ApocalypseZombieEquipment("minecraft:iron_leggings"),
            chestplate = ApocalypseZombieEquipment("minecraft:iron_chestplate"),
            helmet = ApocalypseZombieEquipment("minecraft:iron_helmet")),
    ).associateBy { it.sourceEntityId }

    val profileIds: Set<String> = setOf("default", "raged") + structureZombies.values.map { it.id }

    fun structureZombie(spawn: ApocalypseZombieSpawn): ApocalypseStructureZombieProfile? =
        structureZombies[spawn.sourceEntityId] ?: if (spawn.sourceEntityId == "minecraft:zombie") {
            structureZombies.values.firstOrNull { it.id == spawn.profile }
        } else null

    private data class Phase(val id: String, val first: Long, val until: Long = Long.MAX_VALUE)
    private val phases = listOf(
        Phase("t1", 0, 1), Phase("t2", 1, 6), Phase("l2", 0, 6), Phase("z1", 6, 21),
        Phase("c1", 11, 51), Phase("z2", 21, 31), Phase("t4", 21, 31), Phase("z3", 31, 41),
        Phase("t5", 31, 41), Phase("b1", 31, 51), Phase("z4", 41, 51), Phase("t6", 41, 51),
        Phase("z5", 51, 71), Phase("t7", 51, 71), Phase("c2", 51), Phase("b3", 51, 71),
        Phase("d1", 61, 91), Phase("z6", 71, 81), Phase("t8", 71, 81), Phase("b4", 71),
        Phase("z7", 81), Phase("t9", 81), Phase("m1", 91), Phase("d2", 91),
    )

    fun phases(day: Long): Set<String> {
        require(day >= 0)
        return phases.filter { day >= it.first && day < it.until }.mapTo(linkedSetOf()) { it.id }
    }

    /** default.json's script selects hordes:test; its ranges deliberately overlap on days 20/40/60/80. */
    val hordeTable = listOf(
        ApocalypseSpawnWeight("zombies_plus:slow_zombie", 6, 0, 20),
        ApocalypseSpawnWeight("zombies_plus:weak_zombie", 8, 0, 20),
        ApocalypseSpawnWeight("minecraft:zombie", 10, 0),
        ApocalypseSpawnWeight("zombies_plus:crawler_zombie", 3, 20, 40),
        ApocalypseSpawnWeight("zombies_plus:axe_zombie", 2, 20, 40),
        ApocalypseSpawnWeight("zombies_plus:sword_zombie", 2, 20, 40),
        ApocalypseSpawnWeight("zombies_plus:leaper_zombie", 2, 40, 60),
        ApocalypseSpawnWeight("zombies_plus:runner_zombie", 2, 40, 60),
        ApocalypseSpawnWeight("zombies_plus:bow_zombie", 2, 60, 80),
        ApocalypseSpawnWeight("zombies_plus:crossbow_zombie", 2, 60, 80),
        ApocalypseSpawnWeight("zombies_plus:brute_zombie", 3, 80, 99),
        ApocalypseSpawnWeight("feralzombie:drone_zombie", 5, 100),
        ApocalypseSpawnWeight("feralzombie:feral_zombie", 1, 100),
        ApocalypseSpawnWeight("minecraft:zombie", 1, 80, profile = "raged"),
        ApocalypseSpawnWeight("minecraft:zombie", 1, 150, 199, "mutated"),
        ApocalypseSpawnWeight("minecraft:zombie", 3, 200, profile = "mutated"),
        ApocalypseSpawnWeight("mutantmonsters:mutant_zombie", 1, 100),
    )

    fun chooseHorde(day: Long, random: Random): ApocalypseSpawnWeight {
        require(day >= 0)
        val entries = hordeTable.filter { it.includes(day) }
        var roll = random.nextInt(entries.sumOf { it.weight })
        return entries.first { entry -> (roll < entry.weight).also { if (!it) roll -= entry.weight } }
    }

    private val weak = listOf("zombies_plus:weak_zombie", "zombies_plus:slow_zombie")
    private val walker = listOf("minecraft:zombie")
    private val melee = listOf("zombies_plus:axe_zombie", "zombies_plus:sword_zombie")
    private val fast = listOf("zombies_plus:leaper_zombie", "zombies_plus:runner_zombie")
    private val ranged = listOf("zombies_plus:bow_zombie", "zombies_plus:crossbow_zombie")
    private val heavyMelee = melee + "zombies_plus:brute_zombie"
    private fun surface(phase: String, ids: List<String>, chance: Double, minimum: Int, maximum: Int, cap: Int, night: Boolean = false) =
        ApocalypseNaturalSpawnRule(phase, ids, chance, minimum, maximum, cap,
            minimumTime = if (night) 13_500 else 0, maximumTime = if (night) 24_000 else 13_500)
    private fun cave(phase: String, ids: List<String>, chance: Double, minimum: Int, maximum: Int, cap: Int) =
        ApocalypseNaturalSpawnRule(phase, ids, chance, minimum, maximum, cap, caveOnly = true, skyOnly = false)
    private fun anywhere(phase: String, ids: List<String>, chance: Double, minimum: Int, maximum: Int, cap: Int) =
        ApocalypseNaturalSpawnRule(phase, ids, chance, minimum, maximum, cap, skyOnly = false)

    /** Both c1 cave rules are present in the actual v3.3 spawner.json; the second is not silently renamed to c2. */
    val naturalRules = listOf(
        surface("t1", weak, .3, 1, 3, 15), surface("t1", weak, .5, 1, 5, 40, true),
        surface("t2", weak, .5, 1, 3, 20), surface("t2", weak, .5, 1, 5, 40, true), cave("l2", weak, .25, 1, 5, 40),
        surface("z1", walker, .46, 1, 3, 70), cave("c1", listOf("zombies_plus:crawler_zombie"), .1, 1, 3, 15),
        surface("z2", walker, .51, 1, 3, 70), surface("t4", melee, .1, 1, 3, 15), surface("t4", melee, .2, 1, 5, 30, true),
        surface("z3", walker, .56, 1, 3, 70), surface("t5", melee, .15, 1, 3, 15), surface("t5", melee, .25, 1, 5, 30, true),
        anywhere("b1", fast, .05, 2, 5, 10), surface("z4", walker, .56, 1, 3, 80),
        surface("t6", melee, .25, 1, 3, 15), surface("t6", melee, .3, 1, 5, 40, true), surface("z5", walker, .61, 1, 3, 80),
        surface("t7", melee, .25, 1, 3, 25), surface("t7", melee, .32, 1, 5, 40, true),
        cave("c1", listOf("zombies_plus:crawler_zombie"), .15, 1, 3, 15), anywhere("b3", fast, .1, 2, 5, 5),
        anywhere("d1", ranged, .02, 3, 5, 3), surface("z6", walker, .66, 1, 3, 80),
        surface("t8", heavyMelee, .35, 1, 3, 25), surface("t8", heavyMelee, .4, 1, 5, 50, true), anywhere("b4", fast, .15, 2, 5, 10),
        surface("z7", walker, .66, 1, 3, 80), surface("t9", heavyMelee, .3, 1, 3, 25), surface("t9", heavyMelee, .4, 1, 3, 50, true),
        surface("m1", listOf("mutantmonsters:mutant_zombie"), .05, 1, 1, 1),
        surface("m1", listOf("mutantmonsters:mutant_zombie"), .1, 1, 1, 2, true), anywhere("d2", ranged, .05, 3, 5, 5),
    )

    val entityIds: Set<String> = (hordeTable.map { it.entityId } + naturalRules.flatMap { it.entityIds } + listOf(
        "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager", "zombies_plus:shrieker_zombie",
    ) + structureZombies.keys).toSet()

    fun variant(entityId: String): ApocalypseZombieVariant = when (entityId) {
        "zombies_plus:runner_zombie", "zombies_plus:leaper_zombie", "feralzombie:drone_zombie" -> ApocalypseZombieVariant.RUNNER
        "zombies_plus:brute_zombie", "feralzombie:feral_zombie", "mutantmonsters:mutant_zombie" -> ApocalypseZombieVariant.BRUTE
        "zombies_plus:crawler_zombie" -> ApocalypseZombieVariant.CRAWLER
        "zombies_plus:shrieker_zombie" -> ApocalypseZombieVariant.SHRIEKER
        else -> ApocalypseZombieVariant.WALKER
    }
}
