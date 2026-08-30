package ru.ruscrafting.events.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.config.EventLocation

class TttLootLayoutPlannerTest : StringSpec({
    "guaranteed points reduce only the random weapon budget" {
        val randomPoints = (0 until 32).map { index -> point(index.toDouble()) }
        val guaranteed = (0 until 4).map { index -> point(100.0 + index) }

        val layout = TttLootLayoutPlanner.imported(
            points = randomPoints,
            guaranteedWeaponPoints = guaranteed,
            weaponCount = 30,
            seed = 42L,
        )

        layout shouldHaveSize 32
        layout.count { it.firearm != null } shouldBe 30
        layout.count { it.exact } shouldBe 4
        layout.filter { it.exact }.map(TttLootSpawn::point).toSet() shouldBe guaranteed.toSet()
        layout.filterNot { it.exact }.count { it.firearm != null } shouldBe 26
        layout.filterNot { it.exact }.count { it.firearm == null } shouldBe 2
    }

    "a guaranteed point already in the random catalog is never spawned twice" {
        val randomPoints = (0 until 8).map { index -> point(index.toDouble()) }
        val guaranteed = listOf(randomPoints[3], randomPoints[6])

        val layout = TttLootLayoutPlanner.imported(randomPoints, guaranteed, weaponCount = 6, seed = 7L)

        layout shouldHaveSize 8
        layout.map { it.point.blockKey() }.distinct() shouldHaveSize 8
        layout.count { it.firearm != null } shouldBe 6
        layout.count { it.exact } shouldBe 2
    }

    "weapon count and guaranteed point limits fail closed" {
        val points = (0 until 4).map { index -> point(index.toDouble()) }

        shouldThrow<IllegalArgumentException> {
            TttLootLayoutPlanner.imported(points, points.take(3), weaponCount = 2, seed = 1L)
        }
        shouldThrow<IllegalArgumentException> {
            TttLootLayoutPlanner.imported(points, emptyList(), weaponCount = 5, seed = 1L)
        }
    }

    "layout is reproducible for the same match seed" {
        val points = (0 until 12).map { index -> point(index.toDouble()) }
        val guaranteed = listOf(point(100.0), point(101.0))

        TttLootLayoutPlanner.imported(points, guaranteed, weaponCount = 9, seed = 99L) shouldBe
            TttLootLayoutPlanner.imported(points, guaranteed, weaponCount = 9, seed = 99L)
    }
}) {
    companion object {
        private fun point(x: Double) = EventLocation("arena", x + 0.5, 65.1, 0.5)
    }
}
