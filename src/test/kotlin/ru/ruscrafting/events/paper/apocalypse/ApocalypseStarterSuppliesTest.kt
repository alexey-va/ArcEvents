package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.block.Barrel
import ru.ruscrafting.events.domain.apocalypse.ApocalypseInfectionStage
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint

class ApocalypseStarterSuppliesTest : StringSpec({
    "starter barrel counts its unclaimed batch and remaining shared inventory" {
        val key = NamespacedKey("arcevents", "apocalypse_original_loot_v1")
        var empty = true
        var stocked = false
        val barrel = mockk<Barrel> {
            every { snapshotInventory } returns mockk {
                every { isEmpty } answers { empty }
            }
            every { persistentDataContainer } returns mockk {
                every { has(key) } answers { stocked }
            }
        }

        starterBarrelHasSupplies(barrel, key, awaitingStock = true) shouldBe true
        starterBarrelHasSupplies(barrel, key, awaitingStock = false) shouldBe false
        stocked = true
        starterBarrelHasSupplies(barrel, key, awaitingStock = true) shouldBe false
        empty = false
        starterBarrelHasSupplies(barrel, key, awaitingStock = false) shouldBe true
        empty = true
        starterBarrelHasSupplies(barrel, key, awaitingStock = false) shouldBe false
    }

    "the route skips a drained unvisited barrel while retaining the personal visit count" {
        val here = ApocalypsePoint(0, 30, 0)
        val drained = GuideRoute(ApocalypsePoint(1, 30, 0), available = false)
        val stocked = GuideRoute(ApocalypsePoint(8, 30, 0))
        val exit = GuideRoute(ApocalypsePoint(0, 64, 0))
        val first = ApocalypseGuideSnapshot(1, 10, ApocalypseInfectionStage.HEALTHY, false, 0,
            true, listOf(drained, stocked), exit, null, null)

        first.automaticTarget() shouldBe GuideTarget.SUPPLIES
        first.route(GuideTarget.SUPPLIES, here) shouldBe stocked
        first.nextStep() shouldBe "supplies"

        val visited = first.copy(supplies = listOf(drained, stocked.copy(claimed = true)))
        visited.automaticTarget() shouldBe GuideTarget.EXIT
        visited.nextStep() shouldBe "exit"
        visited.supplies.count(GuideRoute::claimed) shouldBe 1

        val empty = first.copy(supplies = listOf(drained, stocked.copy(available = false)))
        empty.automaticTarget() shouldBe GuideTarget.EXIT
        empty.route(GuideTarget.SUPPLIES, here) shouldBe null
        empty.supplies.count(GuideRoute::claimed) shouldBe 0
    }
})
