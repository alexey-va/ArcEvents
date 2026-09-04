package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

class TttCombatTest : StringSpec({
    "firearm catalog exposes twelve distinct balanced weapon contracts" {
        TttFirearmCatalog.specs.keys shouldBe FirearmId.entries.toSet()
        TttFirearmCatalog.specs.size shouldBe 12
        TttFirearmCatalog.specs.values.map(FirearmSpec::magazineSize).distinct().size shouldBe 9
        TttFirearmCatalog.specs.getValue(FirearmId.DOUBLE_BARREL).pellets shouldBe 10
        TttFirearmCatalog.specs.getValue(FirearmId.MCMILLAN).range shouldBe 120.0
        TttFirearmCatalog.specs.values.map(FirearmSpec::rarity).toSet() shouldBe FirearmRarity.entries.toSet()
    }

    "large map loot guarantees every firearm and remains deterministic" {
        val loot = TttFirearmCatalog.lootSelection(20, 42)
        loot.size shouldBe 20
        loot.toSet() shouldBe FirearmId.entries.toSet()
        TttFirearmCatalog.lootSelection(20, 42) shouldBe loot
        (TttFirearmCatalog.lootSelection(20, 43) != loot) shouldBe true
    }

    "zero spread preserves a normalized ray" {
        val changed = FirearmSpread.apply(ShotDirection(3.0, 0.0, 4.0), 0.0, 0.0)
        changed.x shouldBeExactly 0.6
        changed.y shouldBeExactly 0.0
        changed.z shouldBeExactly 0.8
        sqrt(changed.x * changed.x + changed.y * changed.y + changed.z * changed.z) shouldBeExactly 1.0
    }

    "spread remains normalized and changes both axes" {
        val changed = FirearmSpread.apply(ShotDirection(0.0, 0.0, 1.0), 4.0, -2.0)
        (kotlin.math.abs(changed.x) > 0.01) shouldBe true
        (kotlin.math.abs(changed.y) > 0.01) shouldBe true
        (kotlin.math.abs(sqrt(changed.x * changed.x + changed.y * changed.y + changed.z * changed.z) - 1.0) < 1e-9) shouldBe true
    }

    "vertical and nearly vertical shots retain their configured pitch spread" {
        for (vertical in listOf(-1.0, 1.0)) {
            for (horizontal in listOf(0.0, 1e-12)) {
                val changed = FirearmSpread.apply(ShotDirection(horizontal, vertical, 0.0), 30.0, 5.0)
                val lateral = sqrt(changed.x * changed.x + changed.z * changed.z)
                (kotlin.math.abs(lateral - kotlin.math.sin(Math.toRadians(5.0))) < 1e-9) shouldBe true
                (kotlin.math.abs(sqrt(lateral * lateral + changed.y * changed.y) - 1.0) < 1e-9) shouldBe true
            }
        }
    }
})
