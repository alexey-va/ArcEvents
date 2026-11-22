package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.entity.Zombie
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.domain.apocalypse.ApocalypseNaturalSpawnRule
import kotlin.random.Random

class ApocalypseAmbientPopulationTest : StringSpec({
    "a full type cap lends one unobserved ambient zombie to the empty district without disturbing the group or its battle" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("districts")
            val home = survivorAt(world, 0)
            val companion = survivorAt(world, 5)
            val newcomer = survivorAt(world, 500)
            val actors = mutableListOf(
                actorAt(world, 75, "owner/horde:10:0"),
                actorAt(world, 76, "site:bunker:0"),
                actorAt(world, 77, "ambient:watched", watchers = setOf(home)),
                actorAt(world, 78, "ambient:hurt", hurt = true),
                actorAt(world, 4, "ambient:close"),
                actorAt(world, 79, "ambient:spare"),
            )
            val population = ApocalypseAmbientPopulation(listOf(home, companion, newcomer), actors, Random(4))
            val targets = mutableListOf<Player>()
            population.spawn(ambientRule.copy(maximumThisType = actors.size), 256, { player, cost ->
                targets += player
                player.location.clone().add(65.0, 0.0, 0.0).also { cost(it) shouldBe 0 }
            }) { _, _, _ -> error("The existing actors filled the type cap") }
            targets shouldBe listOf(newcomer)
            actors.map { it.at.blockX } shouldBe listOf(75, 76, 77, 78, 4, 565)
            actors.last().mob.target shouldBe newcomer
        }
    }

    "the shared cap never searches for ground when loaded enemies are in use or both survivors share the same district" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("shared")
            val leader = survivorAt(world, 0)
            for (otherX in listOf(5, 500)) {
                val actors = mutableListOf(actorAt(world, 80, "ambient:engaged", watchers = setOf(leader)),
                    actorAt(world, 85, "owner/horde:10:1"))
                val population = ApocalypseAmbientPopulation(listOf(leader, survivorAt(world, otherX)), actors, Random(9))
                population.spawn(ambientRule, actors.size, { _, _ -> error("No ambient enemy is available") }) { _, _, _ ->
                    error("The existing actors filled the world cap")
                }
                actors.map { it.at.blockX } shouldBe listOf(80, 85)
            }
        }
    }

    "each successful roll uses vacant districts even when an empty district has no valid ground this pulse" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("open")
            val unspawnable = survivorAt(world, 0)
            val west = survivorAt(world, -500)
            val east = survivorAt(world, 500)
            val population = ApocalypseAmbientPopulation(listOf(unspawnable, west, east), mutableListOf(), Random(4))
            val created = mutableListOf<Player>()
            var failedSearches = 0
            population.spawn(ambientRule, 256, { player, _ ->
                if (player == unspawnable) { failedSearches++; null }
                else player.location.clone().add(65.0, 0.0, 0.0)
            }) { _, player, at ->
                created += player
                actorAt(world, at.blockX, "ambient:${created.size}")
            }
            failedSearches shouldBe 1
            created.size shouldBe 5
            created.toSet() shouldBe setOf(west, east)
            kotlin.math.abs(created.count { it == west } - created.count { it == east }) shouldBe 1
        }
    }

    "a solo player gets a clear street-level approach before an adjacent pack or rooftop and a teammate's position is protected" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("streets")
            val player = survivorAt(world, 0)
            val actors = mutableListOf(actorAt(world, 70, "ambient:existing"))
            val population = ApocalypseAmbientPopulation(listOf(player), actors, Random(0))
            fun score(x: Int, y: Int) = population.placementCost(Location(world, x.toDouble(), y.toDouble(), 0.0), player, 60)
            score(-70, 64) shouldBe 0
            (score(-70, 84)!! > 0) shouldBe true
            (score(80, 64)!! > 0) shouldBe true
            score(70, 64) shouldBe null
            val nearby = ApocalypseAmbientPopulation(listOf(player, survivorAt(world, -75)), actors, Random(0))
            nearby.placementCost(Location(world, -70.0, 64.0, 0.0), player, 60) shouldBe null
        }
    }

    "open sky rules stay above the roof while cave and unrestricted rules also probe the participant's floor" {
        val random = Random(5)
        val roof = 85
        val floor = 64
        ambientRule.probeHeight(0, floor, roof, random) shouldBe roof + 1
        val unrestricted = ambientRule.copy(skyOnly = false)
        unrestricted.probeHeight(0, floor, roof, random) shouldBe floor
        unrestricted.probeHeight(1, floor, roof, random) shouldBe roof + 1
        val cave = unrestricted.copy(caveOnly = true)
        cave.probeHeight(0, floor, roof, random) shouldBe floor
        cave.matches(500, 0, seesSky = true, inCave = false) shouldBe false
    }

    "all survivors and rules share a finite pulse budget and a failed search leaves room for other districts" {
        val budget = ApocalypseSpawnProbeBudget(maximum = 300, searches = 24 * 3)
        budget.perSearch shouldBe 4
        repeat(24 * 3) { repeat(budget.perSearch) { budget.take() shouldBe true } }
        budget.remaining shouldBe 12
        repeat(12) { budget.take() shouldBe true }
        budget.take() shouldBe false
        budget.remaining shouldBe 0
    }

    "legacy ambient actors dry up on chunk unload while registered or client-tracked encounters survive adoption" {
        MockBukkitTestRuntime.open().use { paper ->
            val world = paper.addSimpleWorld("reload")
            val player = survivorAt(world, 0)
            val inUse = actorAt(world, 70, "ambient:registered").mob
            val visible = actorAt(world, 80, "ambient:visible", watchers = setOf(player)).mob
            val fromDisk = actorAt(world, 140, "ambient:stale").mob
            retainLoadedAmbient(inUse, true) shouldBe true
            retainLoadedAmbient(visible, false) shouldBe true
            retainLoadedAmbient(fromDisk, false) shouldBe false
            for (mob in listOf(inUse, visible, fromDisk)) verify { mob.isPersistent = false }
            verify(exactly = 0) { inUse.remove() }
            verify(exactly = 0) { visible.remove() }
            verify(exactly = 1) { fromDisk.remove() }
        }
    }
})

private val ambientRule = ApocalypseNaturalSpawnRule("test", listOf("minecraft:zombie"), 1.0, 2, 2, 16)

private fun survivorAt(world: World, x: Int): Player = mockk {
    every { location } returns Location(world, x.toDouble(), 64.0, 0.0)
}

private fun actorAt(world: World, x: Int, token: String, watchers: Set<Player> = emptySet(), hurt: Boolean = false): ApocalypseSpawnActor {
    var position = Location(world, x.toDouble(), 64.0, 0.0)
    var targetPlayer: Player? = null
    val zombie = mockk<Zombie>(relaxed = true) {
        every { location } answers { position.clone() }
        every { trackedBy } returns watchers
        every { noDamageTicks } returns if (hurt) 5 else 0
        every { teleport(any<Location>()) } answers { position = firstArg(); true }
        every { target = any() } answers { targetPlayer = firstArg() }
        every { target } answers { targetPlayer }
    }
    return ApocalypseSpawnActor(token, "minecraft:zombie", zombie)
}
