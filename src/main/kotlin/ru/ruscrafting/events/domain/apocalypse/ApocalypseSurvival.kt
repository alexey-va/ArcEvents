package ru.ruscrafting.events.domain.apocalypse

import kotlin.math.pow
import kotlin.random.Random

/** Presentation buckets only. Actual overlapping InControl phases come from phasesAt(). */
enum class ApocalypseSurvivalPhase { OUTBREAK, SPREAD, OVERRUN, COLLAPSE }
enum class ApocalypseZombieVariant { WALKER, RUNNER, BRUTE, SHRIEKER, CRAWLER }
enum class ApocalypseInfectionStage { HEALTHY, INCUBATING, FEVER, SEVERE, CRITICAL }

data class ApocalypseSurvivalSettings(
    val ticksPerDay: Long = 24_000,
    val hordeEveryDays: Int = 10,
    val hordePulseTicks: Long = 1_000,
    val hordePulseSize: Int = 6,
    val hordeAliveCap: Int = 125,
    val hordeDurationTicks: Long = 6_000,
    val hordeStartTime: Long = 10_000,
    val hordeStartBuffer: Long = 1_200,
    val hordeSpawnMultiplier: Double = 1.1,
    val hordeMultiplayerScaling: Double = .8,
    val pauseWhenEmpty: Boolean = true,
    val infectionChance: Double = .25,
    val infectionStageTicks: Long = 6_000,
    val cureStageReduction: Double = .95,
    val vaccineImmunityTicks: Long = 7_000,
    val cureImmunityTicks: Long = 0,
) {
    fun validated() = apply {
        require(ticksPerDay in 20L..2_400_000L && hordeEveryDays in 1..1_000)
        require(hordePulseTicks in 1L..24_000L && hordePulseSize in 1..256 && hordeAliveCap in 1..256)
        require(hordeDurationTicks in hordePulseTicks..2_400_000L && hordeDurationTicks % hordePulseTicks == 0L)
        require(hordeDurationTicks / hordePulseTicks <= 4_096)
        require(hordeStartTime in 0 until ticksPerDay && hordeStartBuffer in 0 until ticksPerDay)
        require(hordeSpawnMultiplier.isFinite() && hordeSpawnMultiplier in 1.0..10.0)
        require(hordeMultiplayerScaling.isFinite() && hordeMultiplayerScaling in 0.0..1.0)
        require(infectionChance.isFinite() && infectionChance in 0.0..1.0)
        require(infectionStageTicks in 1L..2_400_000L && cureStageReduction.isFinite() && cureStageReduction in 0.0..1.0)
        require(vaccineImmunityTicks in 0L..24_000_000L && cureImmunityTicks in 0L..24_000_000L)
    }
}

/** sourceEntityId/profile retain reference identity even where Paper uses a vanilla zombie carrier. */
data class ApocalypseZombieSpawn(
    val id: String,
    val variant: ApocalypseZombieVariant,
    val boss: Boolean = false,
    val sourceEntityId: String = "minecraft:zombie",
    val profile: String = "default",
) {
    fun validated() = apply {
        require(id.isNotBlank() && id.length <= 512 && id.none(Char::isISOControl))
        requireNotNull(variant)
        require(sourceEntityId in ApocalypseSurvivalReference.entityIds && profile in ApocalypseSurvivalReference.profileIds)
    }
}

/** Attribute speed and the AI goal's movement multiplier are separate in the reference mods. */
data class ApocalypseZombieStats(
    val health: Double,
    val damage: Double,
    val speed: Double,
    val armor: Double = 2.0,
    val knockbackResistance: Double = 0.0,
    val followRange: Double = 35.0,
    val pursuitSpeed: Double = 1.0,
    val shootInterval: Int = 0,
    val shootRadius: Double = 0.0,
    val leapVelocity: Double = 0.0,
    val initialHealth: Double = health,
)

data class ApocalypseHordeState(
    val day: Long,
    val remaining: Int,
    val spawned: Int = 0,
    val nextPulseAt: Long,
    val active: List<ApocalypseZombieSpawn> = emptyList(),
    val startedAt: Long = 0,
    val endsAt: Long = 6_000,
    val pulseAmount: Int = 6,
    val pulsesRemaining: Int = 6,
) {
    fun validated() = apply {
        require(day >= 0 && remaining >= 0 && spawned >= 0 && spawned <= 2_000_000)
        require(startedAt >= 0 && endsAt > startedAt && nextPulseAt >= startedAt)
        require(pulseAmount in 0..4_096 && pulsesRemaining in 0..4_096 && remaining.toLong() == pulseAmount.toLong() * pulsesRemaining)
        requireNotNull(active)
        require(active.size <= 256 && active.size <= spawned && active.map { it.id }.distinct().size == active.size)
        active.forEach {
            it.validated()
            val index = it.id.substringAfterLast(':').toIntOrNull()
            require(index != null && index in 0 until spawned && it.id == "horde:$day:$index")
        }
    }
}

data class ApocalypseSurvivalState(
    val formatVersion: Int = 1,
    val worldTick: Long = 0,
    val lastHordeDay: Long = 0,
    val horde: ApocalypseHordeState? = null,
    val nextHordeDay: Long = 0,
) {
    fun validated() = apply {
        require(formatVersion == 1 && worldTick >= 0 && lastHordeDay >= 0 && nextHordeDay >= 0)
        horde?.validated()?.let { require(it.day == lastHordeDay) }
    }
}

data class ApocalypseSurvivalTransition(
    val state: ApocalypseSurvivalState,
    val day: Long,
    val phase: ApocalypseSurvivalPhase,
    val spawns: List<ApocalypseZombieSpawn> = emptyList(),
    val hordeStarted: Boolean = false,
    val hordeFinished: Boolean = false,
    val activePhases: Set<String> = emptySet(),
)

data class ApocalypseInfectionState(
    val formatVersion: Int = 1,
    val worldTick: Long = 0,
    val stage: ApocalypseInfectionStage = ApocalypseInfectionStage.HEALTHY,
    val stageStartedAt: Long = 0,
    val immuneUntil: Long = 0,
    val curedInfections: Int = 0,
    val expired: Boolean = false,
) {
    fun validated() = apply {
        require(formatVersion == 1 && worldTick >= 0 && stageStartedAt in 0..worldTick && immuneUntil >= 0)
        requireNotNull(stage)
        require(curedInfections in 0..1_000_000 && (!expired || stage == ApocalypseInfectionStage.CRITICAL))
    }
}

/** Rules distilled from CW v3.3 config and The Hordes 1.5.4c, commit 5f0265dd1872cb4d631a8605ab4a8b5d6fc27eec. */
class ApocalypseSurvivalRules(val settings: ApocalypseSurvivalSettings = ApocalypseSurvivalSettings()) {
    init { settings.validated() }

    /** Both The Hordes and InControl count elapsed days from zero. */
    fun dayAt(worldTick: Long): Long { require(worldTick >= 0); return worldTick / settings.ticksPerDay }
    fun phasesAt(worldTick: Long): Set<String> = ApocalypseSurvivalReference.phases(dayAt(worldTick))
    fun naturalSpawnRules(worldTick: Long): List<ApocalypseNaturalSpawnRule> {
        val phases = phasesAt(worldTick)
        return ApocalypseSurvivalReference.naturalRules.filter { it.phase in phases }
    }

    fun phaseAt(worldTick: Long): ApocalypseSurvivalPhase = when (dayAt(worldTick)) {
        in 0L..5L -> ApocalypseSurvivalPhase.OUTBREAK
        in 6L..30L -> ApocalypseSurvivalPhase.SPREAD
        in 31L..70L -> ApocalypseSurvivalPhase.OVERRUN
        else -> ApocalypseSurvivalPhase.COLLAPSE
    }

    /** One state per player, like the reference. Paper owns the frozen clock when the server is empty. */
    fun advance(
        state: ApocalypseSurvivalState,
        worldTick: Long,
        aliveEnemies: Int,
        random: Random,
        nearbyHordePlayers: Int = 0,
        playersPresent: Boolean = true,
    ): ApocalypseSurvivalTransition {
        state.validated()
        require(worldTick >= 0 && aliveEnemies >= 0 && nearbyHordePlayers >= 0)
        val now = if (settings.pauseWhenEmpty && !playersPresent) state.worldTick else maxOf(worldTick, state.worldTick)
        val day = dayAt(now)
        val phase = phaseAt(now)
        val phases = phasesAt(now)
        fun result(next: ApocalypseSurvivalState, spawns: List<ApocalypseZombieSpawn> = emptyList(), started: Boolean = false, finished: Boolean = false) =
            ApocalypseSurvivalTransition(next, day, phase, spawns, started, finished, phases)
        if (settings.pauseWhenEmpty && !playersPresent) return result(state)
        var next = state.copy(worldTick = now)
        var horde = state.horde
        if (horde != null && now >= horde.endsAt) return result(next.copy(horde = null), finished = true)
        if (horde == null) {
            val time = now % settings.ticksPerDay
            var dueDay = state.nextHordeDay
            if (dueDay == 0L) {
                dueDay = day / settings.hordeEveryDays * settings.hordeEveryDays
                if (dueDay == 0L || dueDay < day || time > settings.hordeStartTime + settings.hordeStartBuffer) dueDay += settings.hordeEveryDays
            }
            if (day > dueDay || day == dueDay && time > settings.hordeStartTime + settings.hordeStartBuffer) {
                dueDay = (day / settings.hordeEveryDays + 1) * settings.hordeEveryDays
            }
            next = next.copy(nextHordeDay = dueDay)
            if (day < dueDay || time !in settings.hordeStartTime..(settings.hordeStartTime + settings.hordeStartBuffer)) return result(next)
            val amount = hordePulseAmount(state.lastHordeDay, 0)
            val pulses = (settings.hordeDurationTicks / settings.hordePulseTicks).toInt()
            horde = ApocalypseHordeState(day = dueDay, remaining = amount * pulses, nextPulseAt = after(now, 1),
                startedAt = now, endsAt = after(now, settings.hordeDurationTicks), pulseAmount = amount, pulsesRemaining = pulses)
            return result(next.copy(lastHordeDay = dueDay, nextHordeDay = dueDay + settings.hordeEveryDays, horde = horde), started = true)
        }
        val activeHorde = requireNotNull(horde)
        if (now < activeHorde.nextPulseAt || activeHorde.pulsesRemaining == 0) return result(next)
        // The runtime normally calls every tick. A delayed call drops missed slots, never creates a catch-up burst.
        val consumedSlots = minOf(activeHorde.pulsesRemaining.toLong(), 1 + (now - activeHorde.nextPulseAt) / settings.hordePulseTicks).toInt()
        val left = activeHorde.pulsesRemaining - consumedSlots
        var amount = activeHorde.pulseAmount
        var others = nearbyHordePlayers
        while (others-- > 0 && amount > 0) amount = (amount * settings.hordeMultiplayerScaling).toInt()
        val capacity = (settings.hordeAliveCap - maxOf(aliveEnemies, activeHorde.active.size)).coerceAtLeast(0)
        val spawns = List(minOf(amount, capacity)) { index ->
            ApocalypseSurvivalReference.chooseHorde(activeHorde.day, random).spawn("horde:${activeHorde.day}:${activeHorde.spawned + index}")
        }
        val advanced = activeHorde.copy(remaining = left * activeHorde.pulseAmount, spawned = activeHorde.spawned + spawns.size,
            nextPulseAt = after(activeHorde.nextPulseAt, consumedSlots * settings.hordePulseTicks), pulsesRemaining = left, active = activeHorde.active + spawns)
        return result(next.copy(horde = advanced), spawns)
    }

    /** 1.5.4c builds the next spawn data from the previously recorded horde day; scaling is linear, not exponential. */
    fun hordePulseAmount(previousHordeDay: Long, nearbyHordePlayers: Int = 0): Int {
        require(previousHordeDay >= 0 && nearbyHordePlayers >= 0)
        var amount = (settings.hordePulseSize * (1 + (previousHordeDay / settings.hordeEveryDays) * (settings.hordeSpawnMultiplier - 1)))
            .coerceAtMost(4_096.0).toInt()
        var others = nearbyHordePlayers
        while (others-- > 0 && amount > 0) amount = (amount * settings.hordeMultiplayerScaling).toInt()
        return amount
    }

    fun enemyDefeated(state: ApocalypseSurvivalState, enemyId: String): ApocalypseSurvivalState {
        state.validated()
        val horde = state.horde ?: return state
        if (horde.active.none { it.id == enemyId }) return state
        return state.copy(horde = horde.copy(active = horde.active.filterNot { it.id == enemyId }))
    }

    /** Compatibility helper for presentation callers; natural spawning should evaluate naturalSpawnRules instead. */
    fun chooseVariant(phase: ApocalypseSurvivalPhase, random: Random): ApocalypseZombieVariant {
        val day = when (phase) { ApocalypseSurvivalPhase.OUTBREAK -> 0L; ApocalypseSurvivalPhase.SPREAD -> 6L
            ApocalypseSurvivalPhase.OVERRUN -> 31L; ApocalypseSurvivalPhase.COLLAPSE -> 81L }
        val rules = ApocalypseSurvivalReference.naturalRules.filter { it.phase in ApocalypseSurvivalReference.phases(day) }
        return ApocalypseSurvivalReference.variant(rules.random(random).entityIds.random(random))
    }

    /** Compatibility carrier lookup; spawning code should pass its complete source identity. */
    fun stats(variant: ApocalypseZombieVariant, day: Long): ApocalypseZombieStats {
        val entityId = when (variant) {
            ApocalypseZombieVariant.WALKER -> "minecraft:zombie"
            ApocalypseZombieVariant.RUNNER -> "zombies_plus:runner_zombie"
            ApocalypseZombieVariant.BRUTE -> "zombies_plus:brute_zombie"
            ApocalypseZombieVariant.SHRIEKER -> "zombies_plus:shrieker_zombie"
            ApocalypseZombieVariant.CRAWLER -> "zombies_plus:crawler_zombie"
        }
        return stats(ApocalypseZombieSpawn("stats", variant, sourceEntityId = entityId), day)
    }

    /**
     * CW v3.3: Zombies+ file 5889911 attribute/goal bytecode, feralzombie-common.toml,
     * InControl finalize rules, Hordes test-table NBT, ScalingMobs file 7398187.
     * Equipment, randomized spawn bonuses and difficulty adjustments belong to the native adapter.
     */
    fun stats(spawn: ApocalypseZombieSpawn, day: Long): ApocalypseZombieStats {
        spawn.validated()
        require(day >= 0)
        // These decimals deliberately retain the float-to-double constants from the pinned mod.
        val zombieSpeed = .23000000417232513
        val structure = ApocalypseSurvivalReference.structureZombie(spawn)
        val base = structure?.let {
            ApocalypseZombieStats(it.maximumHealth, it.damage, it.speed,
                knockbackResistance = it.knockbackResistance, initialHealth = it.initialHealth)
        } ?: when (spawn.sourceEntityId) {
            "zombies_plus:weak_zombie" -> ApocalypseZombieStats(20.0, 1.0, .20999999344348907)
            "zombies_plus:slow_zombie" -> ApocalypseZombieStats(20.0, 1.0, .17499999701976776)
            "zombies_plus:crawler_zombie" -> ApocalypseZombieStats(15.0, 2.5 * .8, zombieSpeed)
            "zombies_plus:runner_zombie" -> ApocalypseZombieStats(20.0, 3.0, zombieSpeed * .9, pursuitSpeed = 1.8)
            // Leaper registers inherited Zombie.createAttributes(), rather than its own builder.
            "zombies_plus:leaper_zombie" -> ApocalypseZombieStats(20.0, 3.0, zombieSpeed, pursuitSpeed = 1.7, leapVelocity = .35)
            "zombies_plus:brute_zombie" -> ApocalypseZombieStats(40.0, 5.0, .17000000178813934 * 1.2)
            "zombies_plus:axe_zombie" -> ApocalypseZombieStats(20.0, 6.0, zombieSpeed)
            "zombies_plus:sword_zombie" -> ApocalypseZombieStats(20.0, 5.0, zombieSpeed)
            "zombies_plus:bow_zombie" -> ApocalypseZombieStats(20.0, 3.0, zombieSpeed,
                pursuitSpeed = 1.2, shootInterval = 20, shootRadius = 15.0)
            // RangedCrossbowAttackGoal owns its charging/cooldown; zero means no fixed bow interval.
            "zombies_plus:crossbow_zombie" -> ApocalypseZombieStats(20.0, 3.0, zombieSpeed,
                pursuitSpeed = 1.5, shootRadius = 8.0)
            "feralzombie:feral_zombie" -> ApocalypseZombieStats(30.0 * 3, 5.0, .43)
            "feralzombie:drone_zombie" -> ApocalypseZombieStats(30.0, 4.0, .25, armor = 3.0)
            // ponytail: only horde max health is verified; damage/speed retain the vanilla carrier
            // until the pinned Mutant Monsters entity implementation replaces that adapter.
            "mutantmonsters:mutant_zombie" -> ApocalypseZombieStats(250.0, 3.0, zombieSpeed)
            else -> ApocalypseZombieStats(20.0, 3.0, zombieSpeed)
        }
        // Rage is a JCWS block-breaking behavior, not a health/damage/speed modifier.
        if (spawn.sourceEntityId == "mutantmonsters:mutant_zombie") return base
        val healthDamageGrowth = 1.0 + (day * .0025).coerceAtMost(1.5)
        val speedGrowth = 1.0 + (day * .0005).coerceAtMost(1.1)
        return base.copy(health = base.health * healthDamageGrowth, initialHealth = base.initialHealth * healthDamageGrowth,
            damage = base.damage * healthDamageGrowth, speed = base.speed * speedGrowth)
    }

    fun infectionStageDuration(state: ApocalypseInfectionState): Long =
        (settings.infectionStageTicks * settings.cureStageReduction.pow(state.curedInfections)).toLong().coerceAtLeast(1)

    fun advanceInfection(state: ApocalypseInfectionState, worldTick: Long): ApocalypseInfectionState {
        state.validated()
        require(worldTick >= 0)
        val now = maxOf(state.worldTick, worldTick)
        if (state.stage == ApocalypseInfectionStage.HEALTHY || state.expired) return state.copy(worldTick = now)
        val duration = infectionStageDuration(state)
        val elapsed = (now - state.stageStartedAt) / duration
        val available = ApocalypseInfectionStage.CRITICAL.ordinal - state.stage.ordinal
        val steps = elapsed.coerceAtMost(available.toLong()).toInt()
        return state.copy(worldTick = now, stage = ApocalypseInfectionStage.entries[state.stage.ordinal + steps],
            stageStartedAt = state.stageStartedAt + steps * duration, expired = elapsed > available)
    }

    fun expose(state: ApocalypseInfectionState, worldTick: Long, random: Random, chance: Double = settings.infectionChance): ApocalypseInfectionState {
        require(chance.isFinite() && chance in 0.0..1.0)
        val next = advanceInfection(state, worldTick)
        if (next.stage != ApocalypseInfectionStage.HEALTHY || next.worldTick < next.immuneUntil || random.nextDouble() >= chance) return next
        return next.copy(stage = ApocalypseInfectionStage.INCUBATING, stageStartedAt = next.worldTick)
    }

    /** Golden apples cure and increase the count. The reference grants no immunity for this path. */
    fun cure(state: ApocalypseInfectionState, worldTick: Long): ApocalypseInfectionState {
        val next = advanceInfection(state, worldTick)
        if (next.stage == ApocalypseInfectionStage.HEALTHY || next.expired) return next
        return next.copy(stage = ApocalypseInfectionStage.HEALTHY, stageStartedAt = next.worldTick,
            immuneUntil = after(next.worldTick, settings.cureImmunityTicks), curedInfections = (next.curedInfections + 1).coerceAtMost(1_000_000))
    }

    /** cure:vaccine's duration 350 is converted from seconds to 7000 ticks by InfectionDataLoader. */
    fun immunize(state: ApocalypseInfectionState, worldTick: Long, durationTicks: Long = settings.vaccineImmunityTicks): ApocalypseInfectionState {
        require(durationTicks in 0L..24_000_000L)
        val next = advanceInfection(state, worldTick)
        if (next.expired) return next
        return next.copy(stage = ApocalypseInfectionStage.HEALTHY, stageStartedAt = next.worldTick,
            immuneUntil = maxOf(next.immuneUntil, after(next.worldTick, durationTicks)))
    }

    fun onDeath(state: ApocalypseInfectionState, worldTick: Long): ApocalypseInfectionState {
        state.validated(); require(worldTick >= 0)
        return ApocalypseInfectionState(worldTick = maxOf(state.worldTick, worldTick))
    }

    fun infectionExhaustionPerTick(state: ApocalypseInfectionState): Double = state.stage.ordinal * .03
    fun infectionMovementMultiplier(state: ApocalypseInfectionState): Double = 1.0 - (state.stage.ordinal - 1).coerceAtLeast(0) * .1
    private fun after(now: Long, duration: Long): Long = if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration
}
