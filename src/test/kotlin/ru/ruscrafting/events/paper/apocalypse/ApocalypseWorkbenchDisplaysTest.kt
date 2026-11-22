package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.block.BlockFace
import org.bukkit.util.Transformation
import org.joml.Vector3f
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchStation
import kotlin.math.abs

class ApocalypseWorkbenchDisplaysTest : StringSpec({
    "every specialist encloses the native clickable cube after all four placement rotations" {
        val models = ApocalypseWorkbenchDisplayPart.models
        models.keys shouldBe ApocalypseWorkbenchStation.entries.toSet()
        for (parts in models.filterKeys { it != ApocalypseWorkbenchStation.CRAFTING }.values) {
            parts.map { it.key }.distinct().size shouldBe parts.size
            val cabinet = parts.first()
            for (face in listOf(BlockFace.SOUTH, BlockFace.EAST, BlockFace.NORTH, BlockFace.WEST)) {
                val vertices = corners(requireNotNull(cabinet.transform(face)))
                (vertices.minOf { it.x } < -.5 && vertices.maxOf { it.x } > .5) shouldBe true
                (vertices.minOf { it.z } < -.5 && vertices.maxOf { it.z } > .5) shouldBe true
                (vertices.minOf { it.y } <= 0 && vertices.maxOf { it.y } >= 1) shouldBe true
                // An adjacent full block occupies the east side irrespective of the station's rotation.
                val clipped = corners(requireNotNull(cabinet.transform(face, setOf(BlockFace.EAST))))
                (abs(clipped.maxOf { it.x } - .5f) < .00001) shouldBe true
                (clipped.minOf { it.x } < -.5f) shouldBe true
            }
        }
    }

    "the ordinary table has one inset outline and leaves all six native faces exposed" {
        val outline = ApocalypseWorkbenchDisplayPart.models.getValue(ApocalypseWorkbenchStation.CRAFTING).single()
        outline.block shouldBe "crafting_table"
        val vertices = corners(requireNotNull(outline.transform(BlockFace.SOUTH)))
        vertices.minOf { it.x } shouldBe -.49609375f
        vertices.maxOf { it.x } shouldBe .49609375f
        vertices.minOf { it.y } shouldBe .00390625f
        vertices.maxOf { it.y } shouldBe .99609375f
        vertices.minOf { it.z } shouldBe -.49609375f
        vertices.maxOf { it.z } shouldBe .49609375f
    }

    "a front-mounted control follows the player-facing cabinet and gives way to an adjoining machine" {
        val stop = ApocalypseWorkbenchDisplayPart.models.getValue(ApocalypseWorkbenchStation.PRESS)
            .single { it.key == "stop-button" }
        val towardEast = corners(requireNotNull(stop.transform(BlockFace.EAST)))
        (towardEast.all { it.x > .5f && it.y in 0f..1f }) shouldBe true
        stop.transform(BlockFace.EAST, setOf(BlockFace.EAST)) shouldBe null
    }
})

private fun corners(pose: Transformation) = buildList {
    for (x in 0..1) for (y in 0..1) for (z in 0..1) {
        val vertex = Vector3f(x.toFloat(), y.toFloat(), z.toFloat()).mul(pose.scale)
        add(pose.leftRotation.transform(vertex).add(pose.translation))
    }
}
