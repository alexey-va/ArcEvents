package ru.ruscrafting.events.domain.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseLostCityAssets

class ApocalypseFreightPierTest : StringSpec({
    "the freight pier joins the old street on foot and its migration only owns new western chunks" {
        val ground = 65
        val cells = mutableMapOf<ApocalypsePoint, String>()
        ApocalypseFreightPier.draw(ground, { _, _ -> ground }) { x, y, z, state ->
            cells[ApocalypsePoint(x, y, z)] = state
        }
        cells.keys.maxOf { it.x shr 4 } shouldBe -9
        val x0 = ApocalypseFreightPier.EAST_X
        fun at(u: Int, dy: Int, z: Int) = cells[ApocalypsePoint(x0 - u, ground + (u / 4).coerceIn(0, 4) + dy, z)]
        for (u in 0..40) {
            at(u, 0, 6) shouldBe if (u == 40) "polished_tuff" else "polished_andesite"
            at(u, 1, 6) shouldBe if (u < 16 && u % 4 == 3) "polished_andesite_stairs[facing=west]" else "air"
            at(u, 2, 6) shouldBe "air"
            at(u, 3, 6) shouldBe "air"
        }
        // A second passage runs under the canopy and connects around both ends of the signal office.
        for (u in 16..40) for (dy in 1..2) at(u, dy, 11) shouldBe "air"
        for (u in listOf(16, 40)) for (z in 6..11) for (dy in 1..2) at(u, dy, z) shouldBe "air"
        at(28, 21, 14) shouldBe "iron_chain[axis=y]"
        at(28, 22, 14) shouldBe "red_terracotta"
        cells.values.none { it.contains("chest") || it.contains("barrel") } shouldBe true
    }

    "an imported source structure on another seed keeps the whole pier out of its neighbourhood" {
        val city = mockk<ApocalypseLostCityAssets>(relaxed = true)
        fun planned(overlap: Boolean) = ApocalypseWaterfront(1, city, { _, _ -> 65 }) { canvas ->
            if (overlap) canvas.blockState(-157, 69, -7, "minecraft:chest")
        }.lots.keys
        val free = planned(false)
        val occupied = planned(true)
        free.count { it.first <= -9 } shouldBe 9
        occupied shouldBe free.filterTo(mutableSetOf()) { it.first >= -8 }
    }
})
