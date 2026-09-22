package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class FishingArenaGeneratorTest : StringSpec({
    "publishes three distinct stage contracts inside the portable bounds" {
        val stages = FishingArenaStage.entries
        stages.map(FishingArenaGenerator::spawn).map { it.x }.distinct().size shouldBe 3
        stages.map(FishingArenaGenerator::fightCenter).map { it.x }.distinct().size shouldBe 3

        for (stage in stages) {
            val spawn = FishingArenaGenerator.spawn(stage)
            val fight = FishingArenaGenerator.fightCenter(stage)
            val exit = FishingArenaGenerator.exit(stage)
            listOf(spawn, fight, exit).forEach { point ->
                point.x shouldBe point.x.coerceIn(FishingArenaGenerator.MIN_X.toDouble(), FishingArenaGenerator.MAX_X.toDouble() + 1.0)
                point.z shouldBe point.z.coerceIn(FishingArenaGenerator.MIN_Z.toDouble(), FishingArenaGenerator.MAX_Z.toDouble() + 1.0)
                point.y shouldBe FishingArenaGenerator.SPAWN_Y.toDouble()
            }
            val zone = FishingArenaGenerator.fishingZone(stage)
            zone.center.x shouldBe stage.centerX + 0.5
            zone.center.z shouldBe 15.5
            zone.minimumDepth shouldBe FishingArenaGenerator.MIN_FISHING_DEPTH
            zone.waterSurfaceY shouldBe FishingArenaGenerator.WATER_SURFACE_Y
            zone.minX..zone.maxX shouldContain stage.centerX
            zone.minZ..zone.maxZ shouldContain 15
            for (x in zone.minX..zone.maxX) {
                for (z in zone.minZ..zone.maxZ) {
                    for (y in FishingArenaGenerator.SEA_Y..FishingArenaGenerator.WATER_SURFACE_Y) {
                        FishingArenaGenerator.materialAt(x, y, z) shouldBe org.bukkit.Material.WATER
                    }
                }
            }
        }
    }

    "each stage has a wide solid combat deck and two deep casting water" {
        for (stage in FishingArenaStage.entries) {
            for (x in stage.centerX - 10..stage.centerX + 10) {
                for (z in -5..5) {
                    FishingArenaGenerator.materialAt(x, FishingArenaGenerator.WALK_Y, z)?.isSolid shouldBe true
                }
            }
            FishingArenaGenerator.materialAt(stage.centerX, FishingArenaGenerator.WALK_Y, 13)?.isSolid shouldBe true
            val zone = FishingArenaGenerator.fishingZone(stage)
            val waterX = zone.center.x.toInt()
            val waterZ = zone.center.z.toInt()
            for (y in FishingArenaGenerator.SEA_Y..FishingArenaGenerator.WATER_SURFACE_Y) {
                FishingArenaGenerator.materialAt(waterX, y, waterZ) shouldBe org.bukkit.Material.WATER
            }
            FishingArenaGenerator.materialAt(waterX, FishingArenaGenerator.SEA_Y - 1, waterZ)?.isSolid shouldBe true
        }
    }

    "stage materials and authored landmarks stay distinct and bounded" {
        FishingArenaGenerator.materialAt(0, FishingArenaGenerator.WALK_Y, 0) shouldBe org.bukkit.Material.CUT_SANDSTONE
        FishingArenaGenerator.materialAt(48, FishingArenaGenerator.WALK_Y, 2) shouldBe org.bukkit.Material.MOSSY_COBBLESTONE
        FishingArenaGenerator.materialAt(96, FishingArenaGenerator.WALK_Y, 0) shouldBe org.bukkit.Material.POLISHED_BLACKSTONE
        FishingArenaGenerator.materialAt(-8, 68, -2) shouldBe org.bukkit.Material.ORANGE_WOOL
        FishingArenaGenerator.materialAt(37, 65, -4) shouldBe org.bukkit.Material.MOSSY_COBBLESTONE
        FishingArenaGenerator.materialAt(89, 71, -4) shouldBe org.bukkit.Material.AMETHYST_BLOCK
        FishingArenaGenerator.materialAt(FishingArenaGenerator.MIN_X - 1, FishingArenaGenerator.WALK_Y, 0) shouldBe null
        FishingArenaGenerator.materialAt(0, FishingArenaGenerator.MAX_Y + 1, 0) shouldBe null
    }

    "islands are separated by generated ocean and the route markers face the dock" {
        val stageCenters = FishingArenaStage.entries.map { it.centerX }
        stageCenters.zipWithNext().forEach { (left, right) ->
            val gap = (left + right) / 2
            FishingArenaGenerator.materialAt(gap, FishingArenaGenerator.SEA_Y, 0) shouldBe org.bukkit.Material.WATER
        }
        FishingArenaStage.entries.forEach { stage ->
            FishingArenaGenerator.spawn(stage).z shouldBe 0.5
            FishingArenaGenerator.fightCenter(stage).z shouldBe -3.5
            FishingArenaGenerator.exit(stage).z shouldBe -8.5
            abs(FishingArenaGenerator.spawn(stage).z - FishingArenaGenerator.exit(stage).z) shouldBe 9.0
            FishingArenaGenerator.spawn(stage).yaw shouldBe 0f
            FishingArenaGenerator.exit(stage).yaw shouldBe 180f
            FishingArenaGenerator.destination(stage) shouldBe FishingArenaGenerator.exit(stage)
        }
    }
})
