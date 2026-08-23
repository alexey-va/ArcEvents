package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.bukkit.Material
import ru.ruscrafting.events.config.ArcEventsConfig
import java.nio.file.Path

class TttCitadelBlueprintTest : StringSpec({
    "all player-facing anchors have two blocks of air and a solid floor" {
        val anchors = listOf(TttCitadelBlueprint.lobby, TttCitadelBlueprint.spectator) + TttCitadelBlueprint.spawns
        anchors.size shouldBe 18
        TttCitadelBlueprint.spawns.distinctBy { Triple(it.x, it.y, it.z) }.size shouldBe 16

        anchors.forEach { point ->
            val x = point.x.toInt()
            val y = point.y.toInt()
            val z = point.z.toInt()
            TttCitadelBlueprint.materialAt(x, y, z) shouldBe null
            TttCitadelBlueprint.materialAt(x, y + 1, z) shouldBe null
            TttCitadelBlueprint.materialAt(x, y - 1, z) shouldNotBe null
        }
    }

    "citadel has a substantial multi-material layout across three elevations" {
        val materials = mutableSetOf<Material>()
        var blocks = 0
        for (x in TttCitadelBlueprint.MIN_X..TttCitadelBlueprint.MAX_X) {
            for (z in TttCitadelBlueprint.MIN_Z..TttCitadelBlueprint.MAX_Z) {
                for (y in TttCitadelBlueprint.MIN_Y..TttCitadelBlueprint.MAX_Y) {
                    TttCitadelBlueprint.materialAt(x, y, z)?.let {
                        blocks++
                        materials += it
                    }
                }
            }
        }

        blocks shouldBeGreaterThan 60_000
        materials.size shouldBeGreaterThan 15
        materials shouldContain Material.BOOKSHELF
        materials shouldContain Material.BLAST_FURNACE
        materials shouldContain Material.TINTED_GLASS
        materials shouldContain Material.DARK_PRISMARINE
    }

    "every arena edge ends in a void-safe bedrock foundation" {
        listOf(-66, 0, 66).forEach { x ->
            listOf(-66, 0, 66).forEach { z ->
                TttCitadelBlueprint.materialAt(x, 5, z) shouldBe Material.BEDROCK
            }
        }
        TttCitadelBlueprint.materialAt(73, 5, 0) shouldBe null
    }

    "reviewed lab profile exactly matches the blueprint anchors" {
        val repository = Path.of(System.getProperty("arcevents.repositoryRoot"))
        val config = ArcEventsConfig.inspect(repository.resolve("scripts/lab/plugin-configs/ArcEvents"))
        val arena = config.arena

        arena.template shouldBe TttCitadelBlueprint.TEMPLATE
        arena.world shouldBe "arcevents_ttt_lab"
        arena.lobby?.coordinates() shouldBe TttCitadelBlueprint.lobby.coordinates()
        arena.spectator?.coordinates() shouldBe TttCitadelBlueprint.spectator.coordinates()
        arena.spawns.map { it.coordinates() } shouldBe TttCitadelBlueprint.spawns.map { it.coordinates() }
        arena.bounds?.minimum?.x shouldBe TttCitadelBlueprint.PLAYABLE_MIN
        arena.bounds?.minimum?.z shouldBe TttCitadelBlueprint.PLAYABLE_MIN
        arena.bounds?.maximum?.x shouldBe TttCitadelBlueprint.PLAYABLE_MAX
        arena.bounds?.maximum?.z shouldBe TttCitadelBlueprint.PLAYABLE_MAX
    }
})

private fun ru.ruscrafting.events.config.EventLocation.coordinates(): List<Double> =
    listOf(x, y, z, yaw.toDouble(), pitch.toDouble())

private fun CitadelPoint.coordinates(): List<Double> = listOf(x, y, z, yaw.toDouble(), pitch.toDouble())
