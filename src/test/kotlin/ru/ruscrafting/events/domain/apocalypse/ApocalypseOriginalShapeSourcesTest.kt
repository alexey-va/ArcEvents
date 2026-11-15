package ru.ruscrafting.events.domain.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ApocalypseOriginalShapeSourcesTest : StringSpec({
    "only final imported fixtures survive later source writes, in top-down order" {
        val shapes = ApocalypseShapeCollector()
        shapes.put(-16, 65, -1, "minecraft:wall_torch[facing=north]", true)
        shapes.put(-16, 66, -1, "minecraft:mossy_cobblestone_wall", false)
        shapes.put(-16, 67, -1, "minecraft:iron_bars[waterlogged=true]", false)
        shapes.put(-16, 68, -1, "minecraft:oak_fence", false)
        shapes.put(-16, 68, -1, "minecraft:air", false)
        shapes.put(-15, 64, -1, "minecraft:vine[north=true]", false)
        shapes.put(-15, 65, -1, "minecraft:glow_lichen[up=true]", false)
        shapes.put(-15, 66, -1, "minecraft:glass_pane", false)
        shapes.put(-15, 66, -1, "minecraft:chest", false)
        val cells = shapes.cells()
        cells.map { it.position.y } shouldBe listOf(67, 66, 65)
        cells.map { it.fitTorch } shouldBe listOf(false, false, true)
        cells.all { (it.position.x shr 4) == -1 && (it.position.z shr 4) == -1 } shouldBe true
    }

    "sparse source neighbors across a negative chunk seam keep exact native state and last overlay" {
        val target = ApocalypsePoint(-17, 68, -16)
        val upper = target.copy(y = 69)
        val samples = ApocalypseShapeSamples(-2, -1, listOf(target, upper))
        samples.box(-18, 67, -17, -16, 68, -15, ApocalypseBlock.STONE)
        samples.blockState(-16, 68, -16, "minecraft:chest")
        samples.blockState(-17, 69, -16, "minecraft:glass_pane[waterlogged=true]")
        samples.result shouldBe mapOf(target to "minecraft:stone", upper to "minecraft:glass_pane[waterlogged=true]")
    }
})
