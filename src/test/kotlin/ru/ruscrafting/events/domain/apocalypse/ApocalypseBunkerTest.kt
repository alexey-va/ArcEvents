package ru.ruscrafting.events.domain.apocalypse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ApocalypseBunkerTest : StringSpec({
    val rules = ApocalypseBunkerRules()
    val codec = ApocalypseBunkerCodec()

    "JCWS scheduled proximity passes produce six two-zombie pulses at 15 17 19 21 23 25 passes" {
        var state = ApocalypseBunkerSpawnerState("bunker:1:2")
        repeat(6) { pulse ->
            val passes = 15 + pulse * 2
            state = rules.randomTick(state, passes - 1).also { it.spawns shouldBe emptyList() }.state
            val step = rules.randomTick(state, 1)
            step.spawns.size shouldBe 2
            step.spawns.all { it.sourceEntityId == "jeffs_cursed_walking_structures:bunker_zombie" } shouldBe true
            state = codec.decode(codec.encode(step.state))
            state.pulses shouldBe pulse + 1
            state.timer shouldBe -(pulse + 1) * 2
        }
        state.removed shouldBe true
        state.active.size shouldBe 12
        rules.randomTick(state, Int.MAX_VALUE).let { it.state shouldBe state; it.spawns shouldBe emptyList() }
    }

    "absence of nearby players pauses the spawner and restored counters never replay prior pulses" {
        val initial = ApocalypseBunkerSpawnerState("bunker")
        rules.randomTick(initial, 0).state shouldBe initial
        val first = rules.randomTick(initial, 15)
        val resumed = codec.decode(codec.encode(first.state))
        rules.randomTick(resumed, 16).spawns shouldBe emptyList()
        val second = rules.randomTick(resumed, 17)
        second.spawns.map { it.id } shouldBe listOf("bunker:bunker:2", "bunker:bunker:3")
        val killed = rules.enemyDefeated(resumed, first.spawns.first().id)
        rules.enemyDefeated(killed, first.spawns.first().id) shouldBe killed
        rules.enemyDefeated(killed, "outsider") shouldBe killed
        val all = rules.randomTick(initial, Int.MAX_VALUE)
        all.spawns.size shouldBe 12
        all.state.removed shouldBe true
    }

    "invalid counters foreign actors and unknown JSON fields fail closed" {
        shouldThrow<IllegalArgumentException> { codec.encode(ApocalypseBunkerSpawnerState("bunker", timer = 15)) }
        shouldThrow<IllegalArgumentException> { codec.encode(ApocalypseBunkerSpawnerState("bunker", pulses = 7)) }
        shouldThrow<IllegalArgumentException> { codec.decode("""{"siteId":"bunker","formatVersion":1,"timer":0,"pulses":0,"active":[],"quest":"reactor"}""") }
        shouldThrow<IllegalArgumentException> { codec.encode(ApocalypseBunkerSpawnerState("bunker", pulses = 1,
            active = listOf(ApocalypseZombieSpawn("foreign:0", ApocalypseZombieVariant.WALKER)))) }
    }
})
