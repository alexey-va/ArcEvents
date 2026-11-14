package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.apocalypse.ApocalypseInfectionStage
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint

class ApocalypseGuideTest : StringSpec({
    val first = GuideRoute(ApocalypsePoint(6, 35, -9))
    val second = GuideRoute(ApocalypsePoint(18, 37, -9))
    val snapshot = ApocalypseGuideSnapshot(0, 10, ApocalypseInfectionStage.HEALTHY, false, 0, true,
        listOf(first, second), GuideRoute(ApocalypsePoint(3, 72, 3)), null, GuideRoute(ApocalypsePoint(600, 40, -90)))

    "guide players to an unstocked barrel before suggesting the exit, with infection taking priority" {
        val from = ApocalypsePoint(3, 35, 39)
        snapshot.nextStep() shouldBe "supplies"
        snapshot.route(GuideTarget.SUPPLIES, from) shouldBe first
        val afterFirst = snapshot.copy(supplies = listOf(first.copy(claimed = true), second))
        afterFirst.route(GuideTarget.SUPPLIES, from) shouldBe second
        afterFirst.copy(supplies = afterFirst.supplies.map { it.copy(claimed = true) }).nextStep() shouldBe "exit"
        afterFirst.copy(infection = ApocalypseInfectionStage.FEVER).nextStep() shouldBe "cure"
    }

    "recommend a weapon route only when a real target exists and distinguish loose rounds from a firearm" {
        val outside = snapshot.copy(inStarter = false, looseAmmo = 24)
        outside.nextStep() shouldBe "weapons"
        outside.copy(weapons = null, city = first).nextStep() shouldBe "city"
        outside.copy(weapons = null).nextStep() shouldBe "shelter"
        outside.copy(hasGun = true, looseAmmo = 0).nextStep() shouldBe "ammo"
        outside.copy(hasGun = true).nextStep() shouldBe "shelter"
    }
})
