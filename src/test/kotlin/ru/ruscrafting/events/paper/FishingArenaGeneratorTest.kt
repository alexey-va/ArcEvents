package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class FishingArenaGeneratorTest : StringSpec({
    fun block(stage: FishingArenaStage, x: Int, y: Int, z: Int) =
        FishingArenaGenerator.materialAt(stage.centerX + x, y, z)

    "each island sits beyond the maximum view distance and has ocean to the horizon" {
        FishingArenaGenerator.TEMPLATE shouldBe "fishing-archipelago-v1"
        val stages = FishingArenaStage.entries
        stages.map { it.centerX } shouldBe listOf(0, 384, 768, 1152, 1536)
        stages.zipWithNext().forEach { (first, second) ->
            // Include the player's stage bounds and the complete footprint of the next island.
            (second.centerX - FishingArenaGenerator.HARBOUR_HALF_WIDTH - (first.centerX + 40) > (16 + 1) * 16) shouldBe true
            val gap = (first.centerX + second.centerX) / 2
            FishingArenaGenerator.materialAt(gap, 63, 0) shouldBe Material.WATER
            FishingArenaGenerator.materialAt(gap, 64, 0) shouldBe null
        }
        for (stage in stages) {
            for (z in listOf(-259, 130, 259)) {
                block(stage, 0, 63, z) shouldBe Material.WATER
                block(stage, 0, 64, z) shouldBe null
            }
        }
    }

    "arrival, merchant, landing and departure connect at walking height with full headroom" {
        for (stage in FishingArenaStage.entries) {
            FishingArenaGenerator.spawn(stage).yaw shouldBe 0f
            FishingArenaGenerator.exit(stage).z shouldBe -8.5
            FishingArenaGenerator.catchLanding(stage).z shouldBe 10.5
            val route = (-9..17).map { 0 to it } + (0..9).map { it to 1 } +
                (-1..1).flatMap { x -> (9..11).map { z -> x to z } }
            for ((x, z) in route) {
                block(stage, x, 64, z)?.isSolid shouldBe true
                for (y in 65..67) block(stage, x, y, z) shouldBe null
            }
            // The NPC stands behind this counter, beneath its canopy.
            block(stage, 8, 65, 0) shouldBe Material.BARREL
            block(stage, 8, 64, -2)?.isSolid shouldBe true
            block(stage, 8, 65, -2) shouldBe null
            block(stage, 8, 66, -2) shouldBe null
            block(stage, 1, 72, -9) shouldBe Material.CYAN_WOOL
        }
    }

    "a real cast from the end of the pier lands in open water between permanent channel marks" {
        for (stage in FishingArenaStage.entries) {
            val zone = FishingArenaGenerator.fishingZone(stage)
            zone.center.z shouldBe 25.5
            zone.minX shouldBe stage.centerX - 8
            zone.maxX shouldBe stage.centerX + 8
            for (x in zone.minX..zone.maxX) for (z in zone.minZ..zone.maxZ) {
                for (y in 60..63) FishingArenaGenerator.materialAt(x, y, z) shouldBe Material.WATER
                FishingArenaGenerator.materialAt(x, 59, z) shouldBe Material.GRAVEL
                for (y in 64..90) FishingArenaGenerator.materialAt(x, y, z) shouldBe null
            }
            for (x in listOf(-9, 9)) for (z in listOf(19, 35)) {
                block(stage, x, 63, z) shouldBe Material.STRIPPED_DARK_OAK_LOG
                block(stage, x, 64, z) shouldBe Material.RED_TERRACOTTA
                block(stage, x, 66, z) shouldBe Material.LANTERN
            }
            val lowShore = (6..11).any { x -> (10..16).any { z ->
                block(stage, x, 64, z) in setOf(Material.SMOOTH_SANDSTONE_SLAB, Material.STONE_SLAB) &&
                    listOf(x + 1 to z, x to z + 1).any { (nx, nz) ->
                        block(stage, nx, 63, nz) == Material.WATER && block(stage, nx, 64, nz) == null
                    }
            } }
            lowShore shouldBe true
        }
    }

    "each source harbour has a distinct landmark beyond the casting water" {
        block(FishingArenaStage.CAMP, -2, 72, 37) shouldBe Material.SPRUCE_STAIRS
        block(FishingArenaStage.REEF, -20, 67, 35) shouldBe Material.PRISMARINE_BRICKS
        block(FishingArenaStage.SHRINE, -15, 90, 34) shouldBe Material.JUNGLE_SLAB
        block(FishingArenaStage.VOLCANO, -11, 79, 40) shouldBe Material.MAGMA_BLOCK
        (-60..38).any { x -> (16..55).any { z ->
            (69..94).any { y -> block(FishingArenaStage.CLIFFS, x, y, z) == Material.SPRUCE_LOG }
        } } shouldBe true
        FishingArenaStage.entries.forEach { stage ->
            stage.containsShore(stage.centerX - 27.5, 85.0, 45.0) shouldBe true
            stage.containsShore(stage.centerX + 41.0, 65.0, 45.0) shouldBe false
        }
    }
})
