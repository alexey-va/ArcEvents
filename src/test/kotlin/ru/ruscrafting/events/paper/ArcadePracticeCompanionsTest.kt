package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import kotlin.random.Random

class ArcadePracticeCompanionsTest : StringSpec({
    "the companion casts from the side of each pier into its water and reels the float back to its rod" {
        FishingArenaStage.entries.forEach { stage ->
            val berth = PracticeHarbourCast.berth(stage)
            FishingArenaGenerator.materialAt(berth.blockX, berth.blockY - 1, berth.blockZ) shouldBe stage.deck
            val water = PracticeHarbourCast.water(stage)
            val zone = FishingArenaGenerator.fishingZone(stage)
            (water.blockX in zone.minX..zone.maxX && water.blockZ in zone.minZ..zone.maxZ) shouldBe true
            FishingArenaGenerator.materialAt(water.blockX, zone.waterSurfaceY, water.blockZ) shouldBe Material.WATER
            val tip = PracticeHarbourCast.tip(stage)
            val frames = (0 until PracticeHarbourCast.PERIOD step 2).map { PracticeHarbourCast.bobber(stage, it) }
            frames.first() shouldBe null
            frames.last() shouldBe null
            frames[PracticeHarbourCast.LAND / 2] shouldBe water
            val visible = frames.filterNotNull()
            (visible.first().distance(tip) < .25 && visible.last().distance(tip) < .25) shouldBe true
            (visible.maxOf { it.y } > tip.y + .25) shouldBe true
            visible.zipWithNext().all { (a, b) -> a.distance(b) < 1.55 } shouldBe true
        }
    }

    "the rival crosses the wharf and takes the boathouse or the headland stairs from either side" {
        fun blockAt(x: Int, y: Int, z: Int) = DisasterArenaGenerator.materialAt(x, y, z)
        for (seed in 1..8) for (hazard in listOf("lightning", "fog")) {
            val runner = PracticeDisasterRunner(Random(seed))
            runner.reset()
            repeat(180) {
                val previous = runner.position
                runner.step(hazard)
                val p = runner.position
                (kotlin.math.abs(previous.y - p.y) <= 1.0) shouldBe true
                val onHalfStep = p.y % 1.0 == .5
                blockAt(p.blockX, p.blockY, p.blockZ) shouldBe if (onHalfStep) Material.SMOOTH_SANDSTONE_STAIRS else null
                blockAt(p.blockX, p.blockY + 1, p.blockZ) shouldBe null
                (blockAt(p.blockX, p.blockY - 1, p.blockZ) != null) shouldBe true
            }
            val p = runner.position
            if (hazard == "fog") (p.y >= DisasterArenaGenerator.FOG_SAFE_Y) shouldBe true
            else (p.blockY + 2..DisasterArenaGenerator.MAX_Y).any {
                blockAt(p.blockX, it, p.blockZ) == Material.WAXED_WEATHERED_CUT_COPPER
            } shouldBe true
        }
    }

    "two second meteor warnings can produce genuine dodges and eliminations across matches" {
        val outcomes = (1..32).map { seed ->
            val runner = PracticeDisasterRunner(Random(seed))
            runner.reset()
            var target = runner.position
            for (tick in 0 until 500 step 2) {
                runner.step("meteors")
                if (tick % 60 == 0) target = runner.position
                if (tick % 60 == 40 && runner.position.distanceSquared(target) <= 9.0) runner.hit(12.0, tick.toLong())
            }
            val survived = runner.alive
            if (!survived) {
                runner.step("meteors") shouldBe null
                runner.hit(12.0, 500) shouldBe false
            }
            runner.reset()
            runner.alive shouldBe true
            survived
        }
        outcomes.toSet() shouldBe setOf(true, false)
    }

    "overlapping meteor circles in one pulse cannot double-hit a rival" {
        val runner = PracticeDisasterRunner(Random(4))
        runner.hit(12.0, 40) shouldBe false
        runner.hit(12.0, 40) shouldBe false
        runner.alive shouldBe true
        runner.hit(12.0, 100) shouldBe true
        runner.reset()
        runner.hit(12.0, 100) shouldBe false
    }
})
