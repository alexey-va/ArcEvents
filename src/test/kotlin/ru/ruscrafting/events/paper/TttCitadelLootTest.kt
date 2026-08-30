package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.domain.FirearmId

class TttCitadelLootTest : StringSpec({
    "citadel loot points have safe footing and a generous weapon pool" {
        TttCitadelLoot.validate()
        val layout = TttCitadelLoot.layout(42)
        layout.count { it.firearm != null } shouldBe 28
        layout.count { it.ammunition > 0 } shouldBe 12
        FirearmId.entries.forEach { firearm -> (layout.count { it.firearm == firearm } >= 1) shouldBe true }
    }

    "loot layout is deterministic per match seed" {
        TttCitadelLoot.layout(991) shouldBe TttCitadelLoot.layout(991)
        (TttCitadelLoot.layout(991) != TttCitadelLoot.layout(992)) shouldBe true
    }
})
