package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class FishingProgressTest : StringSpec({
    "a full five-island run gates each boss on defeated normal catches and trophy hand-in" {
        val rules = FishingRules(catchesPerIsland = 2)
        var progress = FishingProgress(rules)

        repeat(rules.islands) { stage ->
            repeat(rules.catchesPerIsland) { catchIndex ->
                val caught = progress.beginCast().recordCatch()
                caught.phase shouldBe FishingPhase.CAUGHT
                caught.creatureHealth shouldBe 0.0
                caught.catchesOnStage shouldBe catchIndex
                caught.totalCatches shouldBe stage * rules.catchesPerIsland + catchIndex
                caught.bag.size shouldBe progress.bag.size

                val landed = caught.placeCatch()
                landed.phase shouldBe FishingPhase.CREATURE
                landed.catchesOnStage shouldBe catchIndex
                landed.totalCatches shouldBe stage * rules.catchesPerIsland + catchIndex
                landed.bag.size shouldBe progress.bag.size

                progress = landed.damageCreature(landed.creatureHealth)
                progress.phase shouldBe FishingPhase.CASTING
                progress.catchesOnStage shouldBe catchIndex + 1
                progress.totalCatches shouldBe stage * rules.catchesPerIsland + catchIndex + 1
                progress.bag.size shouldBe landed.bag.size + 1
            }

            progress = progress.claimBossBait()
            progress.bossBait shouldBe true
            val caughtBoss = progress.beginCast().recordCatch()
            caughtBoss.phase shouldBe FishingPhase.CAUGHT
            caughtBoss.creatureHealth shouldBe 0.0
            caughtBoss.encounter?.boss shouldBe true
            caughtBoss.coins shouldBe progress.coins
            val boss = caughtBoss.placeCatch()
            boss.phase shouldBe FishingPhase.BOSS
            boss.encounter?.boss shouldBe true
            boss.catchesOnStage shouldBe rules.catchesPerIsland
            boss.totalCatches shouldBe progress.totalCatches

            progress = boss.damageCreature(boss.creatureHealth)
            progress.phase shouldBe FishingPhase.TROPHY
            progress.coins shouldBe (50 * (stage + 1) + 30 * stage * (stage + 1) / 2)
            progress.damageCreature(10_000.0) shouldBe progress
            progress.handInTrophy().phase shouldBe if (stage == rules.islands - 1) {
                FishingPhase.COMPLETE
            } else {
                FishingPhase.TRAVEL
            }

            progress = progress.handInTrophy()
            if (stage < rules.islands - 1) {
                progress = progress.travelToNextStage()
                progress.stage shouldBe stage + 1
                progress.catchesOnStage shouldBe 0
                progress.totalCatches shouldBe (stage + 1) * rules.catchesPerIsland
                progress.phase shouldBe FishingPhase.CASTING
            } else {
                progress.phase shouldBe FishingPhase.COMPLETE
            }
        }
    }

    "catches can be farmed after quest progress clamps and only kills advance species or rare rolls" {
        val rules = FishingRules(catchesPerIsland = 1, rareEvery = 3)
        var progress = FishingProgress(rules)
        repeat(6) {
            progress = progress.beginCast().reelIn()
        }
        val firstLanded = progress.beginCast().recordCatch()
        firstLanded.phase shouldBe FishingPhase.CAUGHT
        firstLanded.encounter?.species shouldBe "clam"
        firstLanded.encounter?.rare shouldBe false
        firstLanded.catchesOnStage shouldBe 0
        firstLanded.totalCatches shouldBe 0
        firstLanded.bag shouldBe emptyList()
        firstLanded.castNonce shouldBe 7L
        progress = firstLanded.placeCatch().damageCreature(firstLanded.encounter!!.maxHealth)
        progress.totalCatches shouldBe 1
        progress.catchesOnStage shouldBe 1

        progress = progress.beginCast().recordCatch().placeCatch().damageCreature(10_000.0)
        progress.catchesOnStage shouldBe 1
        progress.totalCatches shouldBe 2
        progress = progress.beginCast().recordCatch()
        progress.encounter?.species shouldBe "shrimp"
        progress.encounter?.rare shouldBe true
        progress.phase shouldBe FishingPhase.CAUGHT
        val placed = progress.placeCatch()
        progress = placed.damageCreature(placed.creatureHealth)
        progress.catchesOnStage shouldBe 1
        progress.totalCatches shouldBe 3
        progress.bag.size shouldBe 3
    }

    "held catches do not fight or pay out until one placement activates normal and boss combat" {
        var progress = FishingProgress(FishingRules(catchesPerIsland = 1), dynamite = 1)
        val caught = progress.beginCast().recordCatch()
        caught.phase shouldBe FishingPhase.CAUGHT
        caught.creatureHealth shouldBe 0.0
        caught.damageCreature(10_000.0) shouldBe caught
        caught.consumeDynamite() shouldBe caught
        caught.totalCatches shouldBe 0
        caught.catchesOnStage shouldBe 0
        caught.bag shouldBe emptyList()
        caught.coins shouldBe 0

        val placed = caught.placeCatch()
        placed.phase shouldBe FishingPhase.CREATURE
        placed.creatureHealth shouldBe placed.encounter!!.maxHealth
        placed.placeCatch() shouldBe placed
        progress = placed.damageCreature(10_000.0)
        progress.totalCatches shouldBe 1
        progress.bag.size shouldBe 1
        progress = progress.claimBossBait()

        val caughtBoss = progress.beginCast().recordCatch()
        caughtBoss.phase shouldBe FishingPhase.CAUGHT
        caughtBoss.encounter?.boss shouldBe true
        caughtBoss.damageCreature(10_000.0) shouldBe caughtBoss
        caughtBoss.coins shouldBe 0

        val placedBoss = caughtBoss.placeCatch()
        placedBoss.phase shouldBe FishingPhase.BOSS
        placedBoss.creatureHealth shouldBe placedBoss.encounter!!.maxHealth
        placedBoss.placeCatch() shouldBe placedBoss
        val trophy = placedBoss.damageCreature(10_000.0)
        trophy.phase shouldBe FishingPhase.TROPHY
        trophy.coins shouldBe placedBoss.encounter!!.value
    }

    "merchant purchases debit coins, unlock by stage and refuse duplicate or invalid offers" {
        val start = FishingProgress(coins = 500)
        val knife = start.buy(FishingOffer.KNIFE)
        knife.coins shouldBe 475
        knife.ownedGear.contains(FishingGear.KNIFE) shouldBe true
        knife.equippedGear shouldBe FishingGear.KNIFE
        knife.buy(FishingOffer.KNIFE) shouldBe knife
        start.buy(FishingOffer.PISTOL) shouldBe start
        FishingProgress(coins = 1).buy(FishingOffer.KNIFE).coins shouldBe 1

        val islandTwo = FishingProgress(stage = 1, totalCatches = 3, coins = 200)
        islandTwo.buy(FishingOffer.AMMO) shouldBe islandTwo
        val pistol = islandTwo.buy(FishingOffer.PISTOL)
        pistol.coins shouldBe 120
        val ammo = pistol.buy(FishingOffer.AMMO)
        ammo.coins shouldBe 112
        ammo.buy(FishingOffer.AMMO).coins shouldBe 104

        val rodOne = islandTwo.buy(FishingOffer.ROD_ONE)
        rodOne.rodLevel shouldBe 1
        rodOne.buy(FishingOffer.ROD_ONE) shouldBe rodOne
        rodOne.buy(FishingOffer.ROD_TWO) shouldBe rodOne
        rodOne.copy(stage = 1, coins = 200).buy(FishingOffer.ROD_TWO).rodLevel shouldBe 1
        val rodTwo = rodOne.copy(stage = 2, totalCatches = 6, coins = 200).buy(FishingOffer.ROD_TWO)
        rodTwo.rodLevel shouldBe 2

        val fullDynamite = islandTwo.copy(dynamite = FishingRules.MAX_DYNAMITE)
        fullDynamite.buy(FishingOffer.DYNAMITE) shouldBe fullDynamite
        islandTwo.copy(dynamite = 14).buy(FishingOffer.DYNAMITE).dynamite shouldBe 16
    }

    "shop actions are blocked during bites and live creature combat but allowed at trophy hand-in" {
        val stocked = FishingProgress(coins = 100, bag = listOf(FishingCatch("clam", 10, false, 18.0)))
        val biting = stocked.beginCast()
        biting.buy(FishingOffer.KNIFE) shouldBe biting
        biting.feedCatch() shouldBe biting
        biting.eatCatch() shouldBe biting

        val caught = biting.recordCatch()
        caught.phase shouldBe FishingPhase.CAUGHT
        caught.buy(FishingOffer.KNIFE) shouldBe caught
        caught.feedCatch() shouldBe caught
        caught.eatCatch() shouldBe caught

        val fighting = caught.placeCatch()
        fighting.buy(FishingOffer.KNIFE) shouldBe fighting
        fighting.feedCatch() shouldBe fighting
        fighting.eatCatch() shouldBe fighting

        var trophy = FishingProgress(FishingRules(catchesPerIsland = 1), coins = 100)
        trophy = trophy.beginCast().recordCatch().placeCatch().damageCreature(10_000.0).claimBossBait()
        trophy = trophy.beginCast().recordCatch().placeCatch().damageCreature(10_000.0)
        trophy.phase shouldBe FishingPhase.TROPHY
        trophy.buy(FishingOffer.KNIFE).coins shouldBe 125
        trophy.feedCatch().bag shouldBe emptyList()
    }

    "feeding spends one catch at a time and eating removes the oldest catch" {
        val old = FishingCatch("clam", 10, false, 18.0)
        val newer = FishingCatch("shrimp", 16, false, 18.0)
        val stocked = FishingProgress(coins = 5, bag = listOf(old, newer))
        stocked.bagValue shouldBe 26L
        stocked.eatCatch().bag shouldBe listOf(newer)
        val fed = stocked.feedCatch()
        fed.coins shouldBe 15
        fed.bag shouldBe listOf(newer)
        fed.feedCatch().coins shouldBe 31
        fed.feedCatch().bag shouldBe emptyList()

        val capBlocked = stocked.copy(coins = FishingRules.MAX_COINS - 5)
        capBlocked.feedCatch() shouldBe capBlocked
        capBlocked.bag shouldBe listOf(old, newer)
    }

    "dynamite only consumes stock and leaves water, encounter and damage outcomes to the arena" {
        val water = FishingProgress(dynamite = 1)
        val thrownAtWater = water.consumeDynamite()
        thrownAtWater.phase shouldBe FishingPhase.CASTING
        thrownAtWater.dynamite shouldBe 0
        thrownAtWater.encounter shouldBe null
        thrownAtWater.creatureHealth shouldBe 0.0
        thrownAtWater.catchesOnStage shouldBe 0
        thrownAtWater.totalCatches shouldBe 0
        thrownAtWater.bag shouldBe emptyList()
        thrownAtWater.consumeDynamite() shouldBe thrownAtWater

        val fighting = FishingProgress(dynamite = 2).beginCast().recordCatch().placeCatch()
        val blast = fighting.consumeDynamite()
        blast.phase shouldBe FishingPhase.CREATURE
        blast.dynamite shouldBe 1
        blast.creatureHealth shouldBe fighting.creatureHealth
        blast.encounter shouldBe fighting.encounter
        blast.totalCatches shouldBe fighting.totalCatches

        val biting = water.beginCast()
        biting.consumeDynamite() shouldBe biting
        val held = biting.recordCatch()
        held.consumeDynamite() shouldBe held
        held.damageCreature(10_000.0) shouldBe held
        val withBait = FishingProgress(FishingRules(catchesPerIsland = 1), dynamite = 2)
            .beginCast().recordCatch().placeCatch().damageCreature(10_000.0).claimBossBait()
        val boss = withBait.beginCast().recordCatch().placeCatch()
        val bossBlast = boss.consumeDynamite()
        bossBlast.phase shouldBe FishingPhase.BOSS
        bossBlast.dynamite shouldBe 1
        bossBlast.creatureHealth shouldBe boss.creatureHealth
        bossBlast.coins shouldBe 0
        val trophy = bossBlast.damageCreature(bossBlast.creatureHealth)
        trophy.phase shouldBe FishingPhase.TROPHY
        trophy.coins shouldBe 50
        trophy.consumeDynamite() shouldBe trophy
    }

    "claimed boss bait survives bites and a reel-in until the next catch lands" {
        val required = FishingProgress(FishingRules(catchesPerIsland = 1))
            .beginCast().recordCatch().placeCatch().damageCreature(10_000.0)
        val baited = required.claimBossBait()
        val biting = baited.beginCast()
        biting.phase shouldBe FishingPhase.BITE
        biting.bossBait shouldBe true

        val reeled = biting.reelIn()
        reeled.phase shouldBe FishingPhase.CASTING
        reeled.bossBait shouldBe true
        val landed = reeled.beginCast().recordCatch()
        landed.phase shouldBe FishingPhase.CAUGHT
        landed.bossBait shouldBe false
        landed.encounter?.boss shouldBe true
        landed.placeCatch().phase shouldBe FishingPhase.BOSS
    }

    "gear switching is free only for owned gear and never grants an unlock" {
        val start = FishingProgress(coins = 25)
        start.equip(FishingGear.KNIFE) shouldBe start
        val owner = start.buy(FishingOffer.KNIFE)
        owner.equip(FishingGear.KNIFE).equippedGear shouldBe FishingGear.KNIFE
        owner.equip(FishingGear.KNUCKLES).equippedGear shouldBe FishingGear.KNUCKLES
        val biting = owner.beginCast()
        biting.equip(FishingGear.KNIFE) shouldBe biting
        val fighting = owner.beginCast().recordCatch().placeCatch()
        fighting.equip(FishingGear.KNIFE) shouldBe fighting
        val trophy = FishingProgress(FishingRules(catchesPerIsland = 1), ownedGear = setOf(FishingGear.KNUCKLES, FishingGear.KNIFE))
            .beginCast().recordCatch().placeCatch().damageCreature(10_000.0).claimBossBait().beginCast().recordCatch().placeCatch().damageCreature(10_000.0)
        trophy.equip(FishingGear.KNIFE).equippedGear shouldBe FishingGear.KNIFE
        val travel = trophy.handInTrophy()
        travel.equip(FishingGear.KNIFE) shouldBe travel
        owner.copy(phase = FishingPhase.CLOSED).equip(FishingGear.KNIFE).phase shouldBe FishingPhase.CLOSED
    }

    "close clears the live encounter and makes stale transitions inert" {
        val live = FishingProgress(coins = 10, bag = listOf(FishingCatch("clam", 10, false, 18.0)), dynamite = 2)
            .beginCast().recordCatch()
        val closed = live.close()
        closed.phase shouldBe FishingPhase.CLOSED
        closed.encounter shouldBe null
        closed.coins shouldBe 0
        closed.bag shouldBe emptyList()
        closed.dynamite shouldBe 0
        closed.ownedGear shouldBe setOf(FishingGear.KNUCKLES)
        closed.lastCatch shouldBe null
        closed.beginCast() shouldBe closed
        closed.recordCatch() shouldBe closed
        closed.placeCatch() shouldBe closed
        closed.damageCreature(10_000.0) shouldBe closed
        closed.buy(FishingOffer.KNIFE) shouldBe closed
        closed.feedCatch() shouldBe closed
        closed.eatCatch() shouldBe closed
        closed.claimBossBait() shouldBe closed
        closed.handInTrophy() shouldBe closed
        closed.consumeDynamite() shouldBe closed
        closed.travelToNextStage() shouldBe closed
        closed.close() shouldBe closed
    }

    "catalog rules validate bounds and scale creature value and health by island" {
        shouldThrow<IllegalArgumentException> { FishingRules(islands = 4) }
        shouldThrow<IllegalArgumentException> { FishingRules(catchesPerIsland = 0) }
        shouldThrow<IllegalArgumentException> { FishingRules(prices = FishingCatalog.defaultPrices + (FishingOffer.KNIFE to 0)) }
        shouldThrow<IllegalArgumentException> { FishingRules(rareEvery = 0) }
        shouldThrow<IllegalArgumentException> { FishingRules(rareMultiplier = 101) }
        shouldThrow<IllegalArgumentException> { FishingRules(saleBase = 100_000, rareMultiplier = 2) }
        shouldThrow<IllegalArgumentException> { FishingRules(bossRewardBase = 100_000, bossRewardPerStage = 1) }
        shouldThrow<IllegalArgumentException> { FishingRules(dynamiteDamage = Double.NaN) }
        shouldThrow<IllegalArgumentException> { FishingCatalog.island(5) }

        val rules = FishingRules()
        val normal = FishingCatalog.catchFor(4, 0, rules)
        normal.species shouldBe "ash_carp"
        normal.value shouldBe 34
        normal.maxHealth shouldBe 18.0 * (1.0 + 4 * 0.45)
        val rare = FishingCatalog.catchFor(4, 6, rules)
        rare.rare shouldBe true
        rare.value shouldBe 102
        rare.maxHealth shouldBe 18.0 * (1.0 + 4 * 0.45) * 1.4
        val boss = FishingCatalog.bossFor(4, rules)
        boss.species shouldBe "lava_whale"
        boss.value shouldBe 170
        boss.maxHealth shouldBe 240.0
        boss.boss shouldBe true
    }
})
