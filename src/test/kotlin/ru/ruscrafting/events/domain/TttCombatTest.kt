package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

class TttCombatTest : StringSpec({
    "firearm catalog has four distinct balanced weapon contracts" {
        TttFirearmCatalog.specs.keys shouldBe FirearmId.entries.toSet()
        TttFirearmCatalog.specs.values.map(FirearmSpec::magazineSize).distinct().size shouldBe 4
        TttFirearmCatalog.specs.getValue(FirearmId.SHOTGUN).pellets shouldBe 8
        TttFirearmCatalog.specs.getValue(FirearmId.RIFLE).range shouldBe 90.0
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
})
