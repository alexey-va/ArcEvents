package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ApocalypseAdminFlightTest : StringSpec({
    "only administrators may use native self flight commands" {
        listOf("/fly", "/cmi:fly true", "/cmi fly", "/cmi:cmi fly off").forEach { command ->
            isApocalypseSelfFlightCommand(true, command) shouldBe true
            isApocalypseSelfFlightCommand(false, command) shouldBe false
        }
        listOf("/fly GrocerMC", "/fly true GrocerMC", "/cmi fly other", "/cmi god", "/tp 0 80 0").forEach {
            isApocalypseSelfFlightCommand(true, it) shouldBe false
        }
    }
})
