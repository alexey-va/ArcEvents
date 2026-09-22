package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.math.floor

class FishingArenaGeneratorTest : StringSpec({
    "publishes five distinct stage contracts inside the portable bounds" {
        val stages = FishingArenaStage.entries
        stages.map { it.centerX } shouldBe listOf(0, 64, 128, 192, 256)
        stages.map(FishingArenaGenerator::spawn).map { it.x }.distinct().size shouldBe 5
        stages.map(FishingArenaGenerator::fightCenter).map { it.x }.distinct().size shouldBe 5
        FishingArenaGenerator.TEMPLATE shouldBe "fishing-v3"
        FishingArenaGenerator.MAX_X shouldBe 288

        for (stage in stages) {
            val spawn = FishingArenaGenerator.spawn(stage)
            val fight = FishingArenaGenerator.fightCenter(stage)
            val exit = FishingArenaGenerator.exit(stage)
            val trader = FishingArenaGenerator.trader(stage)
            listOf(spawn, fight, exit, trader).forEach { point ->
                point.x shouldBe point.x.coerceIn(FishingArenaGenerator.MIN_X.toDouble(), FishingArenaGenerator.MAX_X.toDouble() + 1.0)
                point.z shouldBe point.z.coerceIn(FishingArenaGenerator.MIN_Z.toDouble(), FishingArenaGenerator.MAX_Z.toDouble() + 1.0)
                point.y shouldBe FishingArenaGenerator.SPAWN_Y.toDouble()
            }

            for (point in listOf(spawn, fight, exit)) {
                val blockX = floor(point.x).toInt()
                val blockZ = floor(point.z).toInt()
                FishingArenaGenerator.materialAt(blockX, FishingArenaGenerator.WALK_Y, blockZ)?.isSolid shouldBe true
                FishingArenaGenerator.materialAt(blockX, FishingArenaGenerator.SPAWN_Y, blockZ) shouldBe null
                FishingArenaGenerator.materialAt(blockX, FishingArenaGenerator.SPAWN_Y + 1, blockZ) shouldBe null
            }

            trader.x shouldBe stage.centerX + 8.5
            trader.z shouldBe 0.5
            FishingArenaGenerator.materialAt(stage.centerX + 8, 65, 0) shouldBe org.bukkit.Material.BARREL
            for (dx in 0..8) {
                FishingArenaGenerator.materialAt(stage.centerX + dx, FishingArenaGenerator.WALK_Y, 1)?.isSolid shouldBe true
            }
            FishingArenaGenerator.materialAt(stage.centerX + 8, 65, 1) shouldBe null
            FishingArenaGenerator.materialAt(stage.centerX + 8, 66, 1) shouldBe null

            val zone = FishingArenaGenerator.fishingZone(stage)
            zone.center.x shouldBe stage.centerX + 0.5
            zone.center.z shouldBe 20.5
            zone.minimumDepth shouldBe 2
            zone.waterSurfaceY shouldBe FishingArenaGenerator.WATER_SURFACE_Y
            zone.minX..zone.maxX shouldContain stage.centerX
            zone.minZ..zone.maxZ shouldContain 20
            for (x in zone.minX..zone.maxX) {
                for (z in zone.minZ..zone.maxZ) {
                    for (y in FishingArenaGenerator.SEA_Y..FishingArenaGenerator.WATER_SURFACE_Y) {
                        FishingArenaGenerator.materialAt(x, y, z) shouldBe org.bukkit.Material.WATER
                    }
                    FishingArenaGenerator.materialAt(x, FishingArenaGenerator.SEA_Y - 1, z)?.isSolid shouldBe true
                    for (y in FishingArenaGenerator.WALK_Y..FishingArenaGenerator.MAX_Y) {
                        FishingArenaGenerator.materialAt(x, y, z) shouldBe null
                    }
                }
            }
        }
    }

    "each stage keeps a clear combat and exit route, dock, and two deep casting water" {
        for (stage in FishingArenaStage.entries) {
            for (x in stage.centerX - 10..stage.centerX + 10) {
                for (z in -5..5) {
                    FishingArenaGenerator.materialAt(x, FishingArenaGenerator.WALK_Y, z)?.isSolid shouldBe true
                }
            }
            for (z in -9..17) {
                FishingArenaGenerator.materialAt(stage.centerX, FishingArenaGenerator.WALK_Y, z)?.isSolid shouldBe true
                FishingArenaGenerator.materialAt(stage.centerX, FishingArenaGenerator.SPAWN_Y, z) shouldBe null
                FishingArenaGenerator.materialAt(stage.centerX, FishingArenaGenerator.SPAWN_Y + 1, z) shouldBe null
            }
        }
    }

    "stage palettes and authored landmarks stay distinct and bounded" {
        val stages = FishingArenaStage.entries
        val paletteSamples = stages.map { stage ->
            FishingArenaGenerator.materialAt(stage.centerX + 4, FishingArenaGenerator.WALK_Y, 4)
        }
        paletteSamples shouldBe listOf(
            org.bukkit.Material.SAND,
            org.bukkit.Material.PRISMARINE_BRICKS,
            org.bukkit.Material.POLISHED_BLACKSTONE,
            org.bukkit.Material.MOSSY_STONE_BRICKS,
            org.bukkit.Material.BLACKSTONE,
        )

        FishingArenaGenerator.materialAt(-8, 68, -2) shouldBe org.bukkit.Material.ORANGE_WOOL
        FishingArenaGenerator.materialAt(53, 65, -4) shouldBe org.bukkit.Material.MOSSY_COBBLESTONE
        FishingArenaGenerator.materialAt(121, 71, -4) shouldBe org.bukkit.Material.AMETHYST_BLOCK
        FishingArenaGenerator.materialAt(207, 70, 0) shouldBe org.bukkit.Material.MOSS_BLOCK
        FishingArenaGenerator.materialAt(244, 65, -3) shouldBe org.bukkit.Material.ORANGE_GLAZED_TERRACOTTA
        FishingArenaGenerator.materialAt(FishingArenaGenerator.MIN_X - 1, FishingArenaGenerator.WALK_Y, 0) shouldBe null
        FishingArenaGenerator.materialAt(FishingArenaGenerator.MAX_X + 1, FishingArenaGenerator.WALK_Y, 0) shouldBe null
        FishingArenaGenerator.materialAt(0, FishingArenaGenerator.MAX_Y + 1, 0) shouldBe null
    }

    "islands are separated by generated ocean and route markers face their dock and exit" {
        val stageCenters = FishingArenaStage.entries.map { it.centerX }
        stageCenters.zipWithNext().forEach { (left, right) ->
            val gap = (left + right) / 2
            FishingArenaGenerator.materialAt(gap, FishingArenaGenerator.SEA_Y, 0) shouldBe org.bukkit.Material.WATER
            FishingArenaGenerator.materialAt(gap, FishingArenaGenerator.WALK_Y, 0) shouldBe null
        }
        FishingArenaStage.entries.forEach { stage ->
            FishingArenaGenerator.spawn(stage).z shouldBe 0.5
            FishingArenaGenerator.fightCenter(stage).z shouldBe -3.5
            FishingArenaGenerator.exit(stage).z shouldBe -8.5
            FishingArenaGenerator.trader(stage).x shouldBe stage.centerX + 8.5
            FishingArenaGenerator.trader(stage).z shouldBe 0.5
            FishingArenaGenerator.spawn(stage).yaw shouldBe 0f
            FishingArenaGenerator.exit(stage).yaw shouldBe 180f
            FishingArenaGenerator.destination(stage) shouldBe FishingArenaGenerator.exit(stage)
        }
    }

    "new islands have double the walkable area and accessible shores" {
        for (stage in FishingArenaStage.entries) {
            val center = stage.centerX
            for (x in center - 19..center + 19) for (z in -14..7) {
                FishingArenaGenerator.materialAt(x, FishingArenaGenerator.WALK_Y, z)?.isSolid shouldBe true
            }
            FishingArenaGenerator.materialAt(center + 20, FishingArenaGenerator.WALK_Y, 0) shouldBe org.bukkit.Material.STONE_SLAB
            FishingArenaGenerator.materialAt(center + 21, FishingArenaGenerator.WATER_SURFACE_Y, 0) shouldBe org.bukkit.Material.WATER
            FishingArenaGenerator.materialAt(center + 21, FishingArenaGenerator.WALK_Y, 0) shouldBe null
            val landing = FishingArenaGenerator.catchLanding(stage)
            FishingArenaGenerator.materialAt(landing.x.toInt(), FishingArenaGenerator.WALK_Y, landing.z.toInt())?.isSolid shouldBe true
        }
    }
})
