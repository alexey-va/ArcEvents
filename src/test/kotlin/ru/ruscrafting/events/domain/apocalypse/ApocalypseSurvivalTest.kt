package ru.ruscrafting.events.domain.apocalypse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class ApocalypseSurvivalTest : StringSpec({
    val rules = ApocalypseSurvivalRules()
    val codec = ApocalypseSurvivalCodec()
    val start = 10 * 24_000L + 10_000

    "CW v3.3 starts on zero-based day ten and sends six six-mob pulses over 6000 ticks" {
        var state = rules.advance(ApocalypseSurvivalState(), 0, 0, Random(1)).state
        state.nextHordeDay shouldBe 10L
        rules.advance(state, start - 1, 0, Random(1)).hordeStarted shouldBe false
        val begun = rules.advance(state, start, 0, Random(1))
        begun.day shouldBe 10L
        begun.hordeStarted shouldBe true
        begun.spawns shouldBe emptyList()
        begun.state.horde!!.pulseAmount shouldBe 6
        begun.state.horde!!.endsAt shouldBe start + 6_000
        state = codec.decode(codec.encode(begun.state))
        val spawns = mutableListOf<ApocalypseZombieSpawn>()
        repeat(6) { index ->
            val tick = start + 1 + index * 1_000L
            val step = rules.advance(state, tick, spawns.size, Random(index))
            step.spawns.size shouldBe 6
            spawns += step.spawns
            state = codec.decode(codec.encode(step.state))
            rules.advance(state, tick, spawns.size, Random(index)).spawns shouldBe emptyList()
        }
        spawns.size shouldBe 36
        spawns.map { it.id }.distinct().size shouldBe 36
        spawns.all { it.sourceEntityId in setOf("minecraft:zombie", "zombies_plus:weak_zombie", "zombies_plus:slow_zombie") } shouldBe true
        val ended = rules.advance(state, start + 6_000, 36, Random(7))
        ended.hordeFinished shouldBe true
        ended.state.horde shouldBe null
        ended.state.nextHordeDay shouldBe 20L
        rules.advance(ended.state, start, 36, Random(7)).hordeStarted shouldBe false
    }

    "spawn cap multiplayer flooring missed slots and empty-server pause remain bounded" {
        val begun = rules.advance(ApocalypseSurvivalState(), start, 0, Random(1)).state
        val capped = rules.advance(begun, start + 1, 125, Random(1))
        capped.spawns shouldBe emptyList()
        capped.state.horde!!.pulsesRemaining shouldBe 5
        val grouped = rules.advance(begun, start + 1, 0, Random(1), nearbyHordePlayers = 1)
        grouped.spawns.size shouldBe 4
        rules.hordePulseAmount(0, 2) shouldBe 3
        rules.hordePulseAmount(0, 5) shouldBe 0
        rules.hordePulseAmount(0) shouldBe 6
        rules.hordePulseAmount(10) shouldBe 6
        rules.hordePulseAmount(20) shouldBe 7
        val delayed = rules.advance(grouped.state, start + 5_001, 4, Random(1))
        delayed.spawns.size shouldBe 6
        delayed.state.horde!!.pulsesRemaining shouldBe 0
        val empty = rules.advance(ApocalypseSurvivalState(), start, 0, Random(1), playersPresent = false)
        empty.state shouldBe ApocalypseSurvivalState()
        empty.hordeStarted shouldBe false
        val killed = rules.enemyDefeated(grouped.state, grouped.spawns.first().id)
        rules.enemyDefeated(killed, grouped.spawns.first().id) shouldBe killed
    }

    "hordes only start in the configured 10000 through 11200 window and do not replay missed days" {
        val missed = rules.advance(ApocalypseSurvivalState(), start + 1_201, 0, Random(1))
        missed.hordeStarted shouldBe false
        missed.state.nextHordeDay shouldBe 20L
        rules.advance(missed.state, start, 0, Random(1)).hordeStarted shouldBe false
        rules.advance(missed.state, 20 * 24_000L + 10_000, 0, Random(1)).hordeStarted shouldBe true
    }

    "exact InControl phase cutoffs differ from the inclusive Hordes table ranges" {
        rules.dayAt(0) shouldBe 0L
        rules.phasesAt(0) shouldBe setOf("t1", "l2")
        rules.phasesAt(24_000) shouldBe setOf("t2", "l2")
        rules.phasesAt(6 * 24_000L) shouldBe setOf("z1")
        fun naturalIds(day: Long) = rules.naturalSpawnRules(day * 24_000).flatMap { it.entityIds }.toSet()
        ("zombies_plus:crawler_zombie" in naturalIds(10)) shouldBe false
        ("zombies_plus:crawler_zombie" in naturalIds(11)) shouldBe true
        ("zombies_plus:axe_zombie" in naturalIds(20)) shouldBe false
        ("zombies_plus:axe_zombie" in naturalIds(21)) shouldBe true
        ("zombies_plus:runner_zombie" in naturalIds(30)) shouldBe false
        ("zombies_plus:runner_zombie" in naturalIds(31)) shouldBe true
        ("zombies_plus:brute_zombie" in naturalIds(70)) shouldBe false
        ("zombies_plus:brute_zombie" in naturalIds(71)) shouldBe true
        ("mutantmonsters:mutant_zombie" in naturalIds(90)) shouldBe false
        ("mutantmonsters:mutant_zombie" in naturalIds(91)) shouldBe true
        (0L..250L).any { "zombies_plus:shrieker_zombie" in naturalIds(it) } shouldBe false
        rules.naturalSpawnRules(11 * 24_000L).count { it.phase == "c1" } shouldBe 2
        rules.naturalSpawnRules(51 * 24_000L).count { it.phase == "c2" } shouldBe 0
        val table = ApocalypseSurvivalReference.hordeTable
        table.filter { it.includes(20) }.any { it.entityId == "zombies_plus:weak_zombie" } shouldBe true
        table.filter { it.includes(20) }.any { it.entityId == "zombies_plus:crawler_zombie" } shouldBe true
        table.filter { it.includes(21) }.any { it.entityId == "zombies_plus:weak_zombie" } shouldBe false
        table.filter { it.includes(40) }.any { it.entityId == "zombies_plus:runner_zombie" } shouldBe true
        table.filter { it.includes(99) }.any { it.entityId == "zombies_plus:brute_zombie" } shouldBe true
        table.filter { it.includes(100) }.any { it.entityId == "zombies_plus:brute_zombie" } shouldBe false
        table.filter { it.includes(100) }.any { it.entityId == "feralzombie:drone_zombie" } shouldBe true
        table.single { it.profile == "mutated" && it.includes(150) }.weight shouldBe 1
        table.single { it.profile == "mutated" && it.includes(200) }.weight shouldBe 3
    }

    "pinned Zombies Plus attributes keep InControl modifiers separate from pursuit and ranged goals" {
        fun stats(entity: String) = rules.stats(ApocalypseZombieSpawn("parity", ApocalypseSurvivalReference.variant(entity), sourceEntityId = entity), 0)
        val weak = stats("zombies_plus:weak_zombie")
        weak.health shouldBe 20.0
        weak.damage shouldBe 1.0
        weak.speed shouldBe .20999999344348907
        stats("zombies_plus:slow_zombie").speed shouldBe .17499999701976776
        val crawler = stats("zombies_plus:crawler_zombie")
        crawler.health shouldBe 15.0
        crawler.damage shouldBe 2.0
        val runner = stats("zombies_plus:runner_zombie")
        (kotlin.math.abs(runner.speed - .20700000375509262) < 1e-12) shouldBe true
        runner.pursuitSpeed shouldBe 1.8
        val brute = stats("zombies_plus:brute_zombie")
        brute.health shouldBe 40.0
        brute.damage shouldBe 5.0
        (kotlin.math.abs(brute.speed - .2040000021457672) < 1e-12) shouldBe true
        stats("zombies_plus:axe_zombie").damage shouldBe 6.0
        stats("zombies_plus:sword_zombie").damage shouldBe 5.0
        val leaper = stats("zombies_plus:leaper_zombie")
        leaper.pursuitSpeed shouldBe 1.7
        leaper.leapVelocity shouldBe .35
        val bow = stats("zombies_plus:bow_zombie")
        bow.pursuitSpeed shouldBe 1.2
        bow.shootInterval shouldBe 20
        bow.shootRadius shouldBe 15.0
        val crossbow = stats("zombies_plus:crossbow_zombie")
        crossbow.pursuitSpeed shouldBe 1.5
        crossbow.shootInterval shouldBe 0
        crossbow.shootRadius shouldBe 8.0
    }

    "CW profiles use exact horde NBT feral config and bounded additive ScalingMobs growth" {
        fun spawn(entity: String, profile: String = "default") =
            ApocalypseZombieSpawn("parity", ApocalypseSurvivalReference.variant(entity), sourceEntityId = entity, profile = profile)
        val feral = rules.stats(spawn("feralzombie:feral_zombie"), 0)
        feral.health shouldBe 90.0
        feral.damage shouldBe 5.0
        feral.speed shouldBe .43
        rules.stats(spawn("feralzombie:drone_zombie"), 0).armor shouldBe 3.0
        val mutated = rules.stats(spawn("minecraft:zombie", "mutated"), 200)
        mutated.health shouldBe 225.0
        mutated.damage shouldBe 13.5
        mutated.knockbackResistance shouldBe .1
        (kotlin.math.abs(mutated.speed - .33) < 1e-12) shouldBe true
        val capped = rules.stats(spawn("minecraft:zombie", "mutated"), 10_000)
        capped.health shouldBe 375.0
        capped.damage shouldBe 22.5
        (kotlin.math.abs(capped.speed - .63) < 1e-12) shouldBe true
        rules.stats(spawn("minecraft:zombie", "raged"), 200) shouldBe rules.stats(spawn("minecraft:zombie"), 200)
        rules.stats(spawn("mutantmonsters:mutant_zombie"), 10_000).health shouldBe 250.0
        val bunker = rules.stats(spawn("jeffs_cursed_walking_structures:bunker_zombie"), 0)
        bunker.health shouldBe 28.0
        bunker.damage shouldBe 2.0
        bunker.knockbackResistance shouldBe .3
    }

    "JCWS structure profiles retain commanded health equipment and stable procedure aliases" {
        val profiles = ApocalypseSurvivalReference.structureZombies.values.associateBy { it.id }
        profiles.keys shouldBe setOf("hazmat", "soldier", "mutated", "warehouse", "bunker")
        val hazmat = profiles.getValue("hazmat")
        hazmat.sourceEntityId shouldBe "jeffs_cursed_walking_structures:hazmat_zombie"
        val hazmatStats = rules.stats(hazmat.spawn("structure:hazmat").validated(), 0)
        hazmatStats.health shouldBe 45.0
        hazmatStats.initialHealth shouldBe 35.0
        hazmatStats.damage shouldBe 6.0
        hazmatStats.speed shouldBe .3
        hazmatStats.knockbackResistance shouldBe .1
        rules.stats(hazmat.spawn("structure:hazmat"), 200).initialHealth shouldBe 52.5
        hazmat.helmet.itemId shouldBe "marbledsarsenal:hazmat_armor_helmet"
        hazmat.boots.dropChance shouldBe 0.0
        val soldier = profiles.getValue("soldier")
        soldier.commandedHealth shouldBe 28.0
        rules.stats(soldier.spawn("structure:soldier"), 0).initialHealth shouldBe 25.0
        soldier.leggings.color shouldBe 6_192_150
        soldier.helmet.itemId shouldBe "minecraft:turtle_helmet"
        val mutated = profiles.getValue("mutated")
        mutated.visualFire shouldBe true
        mutated.helmet.headOwner shouldBe "NetherrackDigger"
        mutated.boots.dropChance shouldBe .05
        profiles.getValue("warehouse").boots.dropChance shouldBe .01
        rules.stats(profiles.getValue("warehouse").spawn("structure:warehouse"), 0).health shouldBe 23.0
        val legacy = ApocalypseZombieSpawn("legacy", ApocalypseZombieVariant.WALKER, profile = "mutated")
        ApocalypseSurvivalReference.structureZombie(legacy) shouldBe mutated
        rules.stats(legacy, 0).initialHealth shouldBe 150.0
    }

    "infection uses four 6000-tick stages then a lethal terminal transition" {
        var state = rules.expose(ApocalypseInfectionState(), 0, Random(1), 1.0)
        val infectionCodec = ApocalypseInfectionCodec()
        state.stage shouldBe ApocalypseInfectionStage.INCUBATING
        rules.advanceInfection(state, 5_999).stage shouldBe ApocalypseInfectionStage.INCUBATING
        state = rules.advanceInfection(state, 6_000)
        state.stage shouldBe ApocalypseInfectionStage.FEVER
        state = rules.advanceInfection(state, 12_000)
        state.stage shouldBe ApocalypseInfectionStage.SEVERE
        state = infectionCodec.decode(infectionCodec.encode(rules.advanceInfection(state, 18_000)))
        state.stage shouldBe ApocalypseInfectionStage.CRITICAL
        state.expired shouldBe false
        rules.advanceInfection(state, 23_999).expired shouldBe false
        state = rules.advanceInfection(state, 24_000)
        state.expired shouldBe true
        rules.cure(state, 24_000).expired shouldBe true
        rules.advanceInfection(state, 0).expired shouldBe true
        rules.onDeath(state, 24_000).stage shouldBe ApocalypseInfectionStage.HEALTHY
        rules.infectionMovementMultiplier(ApocalypseInfectionState(stage = ApocalypseInfectionStage.CRITICAL)) shouldBe .7
        rules.infectionExhaustionPerTick(ApocalypseInfectionState(stage = ApocalypseInfectionStage.CRITICAL)) shouldBe .12
    }

    "apple cure scales later infections while vaccine grants 350 seconds without incrementing cures" {
        var state = rules.expose(ApocalypseInfectionState(), 100, Random(1), 1.0)
        state = rules.cure(state, 100)
        state.curedInfections shouldBe 1
        state.immuneUntil shouldBe 100L
        rules.infectionStageDuration(state) shouldBe 5_700L
        rules.cure(state, 101).curedInfections shouldBe 1
        state = rules.expose(state, 101, Random(1), 1.0)
        state.stage shouldBe ApocalypseInfectionStage.INCUBATING
        val vaccine = rules.immunize(state, 110)
        vaccine.stage shouldBe ApocalypseInfectionStage.HEALTHY
        vaccine.curedInfections shouldBe 1
        vaccine.immuneUntil shouldBe 7_110L
        rules.expose(vaccine, 7_109, Random(1), 1.0).stage shouldBe ApocalypseInfectionStage.HEALTHY
        rules.expose(vaccine, 7_110, Random(1), 1.0).stage shouldBe ApocalypseInfectionStage.INCUBATING
        rules.onDeath(vaccine, 7_111).curedInfections shouldBe 0
    }

    "codecs reject malformed unsafe and oversized snapshots but accept additive infection defaults" {
        codec.decode("""{"formatVersion":1,"worldTick":0,"lastHordeDay":0}""") shouldBe ApocalypseSurvivalState()
        shouldThrow<IllegalArgumentException> { codec.decode("""{"formatVersion":2,"worldTick":0,"lastHordeDay":0}""") }
        shouldThrow<IllegalArgumentException> { codec.decode(codec.encode(ApocalypseSurvivalState()) + " true") }
        shouldThrow<IllegalArgumentException> { codec.decode(" ".repeat(262_145)) }
        shouldThrow<IllegalArgumentException> { codec.encode(ApocalypseSurvivalState(horde = ApocalypseHordeState(10, 1, nextPulseAt = 0))) }
        ApocalypseInfectionCodec().decode("""{"formatVersion":1,"worldTick":0,"stage":"HEALTHY","stageStartedAt":0,"immuneUntil":0}""").curedInfections shouldBe 0
        shouldThrow<IllegalArgumentException> { ApocalypseInfectionCodec().decode("""{"formatVersion":1,"worldTick":0,"stage":"CRITICAL","stageStartedAt":1,"immuneUntil":0}""") }
    }
})
