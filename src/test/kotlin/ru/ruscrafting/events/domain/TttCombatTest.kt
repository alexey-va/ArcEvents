package ru.ruscrafting.events.domain

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

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
        val changed = FirearmSpread.sample(ShotDirection(3.0, 0.0, 4.0), 0.0)
        changed.x shouldBeExactly 0.6
        changed.y shouldBeExactly 0.0
        changed.z shouldBeExactly 0.8
        sqrt(changed.x * changed.x + changed.y * changed.y + changed.z * changed.z) shouldBeExactly 1.0
    }

    "configured spread is a circular cone at every elevation" {
        val limit = cos(Math.toRadians(5.0))
        for (aim in listOf(ShotDirection(0.0, 0.0, 1.0), ShotDirection(.3, .8, -.5),
                ShotDirection(0.0, 1.0, 0.0), ShotDirection(1e-12, -1.0, 0.0))) {
            val axis = aim.normalized()
            val random = Random(42)
            var total = 0.0
            repeat(256) {
                val ray = FirearmSpread.sample(aim, 5.0, random)
                val dot = ray.x * axis.x + ray.y * axis.y + ray.z * axis.z
                (dot in (limit - 1e-12)..(1.0 + 1e-12)) shouldBe true
                (kotlin.math.abs(ray.x * ray.x + ray.y * ray.y + ray.z * ray.z - 1.0) < 1e-10) shouldBe true
                total += dot
            }
            (kotlin.math.abs(total / 256 - (1.0 + limit) * .5) < .0003) shouldBe true
        }
    }
})
