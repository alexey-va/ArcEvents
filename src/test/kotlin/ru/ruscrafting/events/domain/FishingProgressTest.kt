package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class FishingProgressTest : StringSpec({
    "a cast, catch and creature defeat advance one island without duplicate progress" {
        val rules = FishingRules(catchesPerIsland = 2)
        var progress = FishingProgress(rules = rules)

        progress = progress.beginCast()
        progress.phase shouldBe FishingPhase.BITE
        progress = progress.recordCatch()
        progress.phase shouldBe FishingPhase.CREATURE
        progress.totalCatches shouldBe 1
        progress.recordCatch() shouldBe progress

        progress = progress.damageCreature(rules.creatureHealth)
        progress.phase shouldBe FishingPhase.CASTING
        progress = progress.beginCast().recordCatch().damageCreature(rules.creatureHealth)
        progress.phase shouldBe FishingPhase.TRAVEL
        progress.catchesOnStage shouldBe rules.catchesPerIsland

        progress = progress.travelToNextStage()
        progress.stage shouldBe 1
        progress.catchesOnStage shouldBe 0
        progress.totalCatches shouldBe rules.catchesPerIsland
        progress.travelToNextStage() shouldBe progress
    }

    "the last catch summons a boss and only its defeat completes" {
        val rules = FishingRules(catchesPerIsland = 2)
        var progress = FishingProgress(rules = rules)

        repeat(rules.catchesPerIsland) {
            progress = progress.beginCast().recordCatch().damageCreature(rules.creatureHealth)
        }
        progress.phase shouldBe FishingPhase.TRAVEL
        progress = progress.travelToNextStage()

        repeat(rules.catchesPerIsland) {
            progress = progress.beginCast().recordCatch().damageCreature(rules.creatureHealth)
        }
        progress.phase shouldBe FishingPhase.TRAVEL
        progress = progress.travelToNextStage()

        repeat(rules.catchesPerIsland - 1) {
            progress = progress.beginCast().recordCatch().damageCreature(rules.creatureHealth)
        }
        progress = progress.beginCast().recordCatch()
        progress.phase shouldBe FishingPhase.BOSS
        progress.totalCatches shouldBe rules.islands * rules.catchesPerIsland
        progress.damageCreature(1.0).phase shouldBe FishingPhase.BOSS
        progress.damageCreature(rules.bossHealth).phase shouldBe FishingPhase.COMPLETE
    }

    "an unresolved final catch remains pending when no damage is applied" {
        val rules = FishingRules(catchesPerIsland = 1)
        var progress = FishingProgress(rules)
        repeat(rules.islands - 1) {
            progress = progress.beginCast().recordCatch().damageCreature(rules.creatureHealth).travelToNextStage()
        }
        progress = progress.beginCast().recordCatch()
        progress.phase shouldBe FishingPhase.BOSS
        progress.damageCreature(0.0) shouldBe progress
        progress.damageCreature(Double.NaN) shouldBe progress
    }

    "stale damage, travel, close and duplicate catches are inert" {
        val progress = FishingProgress()
        progress.damageCreature(2.0) shouldBe progress
        progress.travelToNextStage() shouldBe progress
        progress.close().close().phase shouldBe FishingPhase.CLOSED
        progress.close().recordCatch() shouldBe progress.close()
    }
})
