package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.util.Vector
import org.joml.Vector3f

class TttScopeLaserTest : StringSpec({
    "laser ray stops before unloaded chunks including negative coordinates and brief corner crossings" {
        val visited = mutableListOf<Pair<Int, Int>>()
        scopeLaserLoadedRange(Vector(15.9, 64.0, 15.0), Vector(1.0, 0.0, 1.0).normalize(), 32.0) { x, z ->
            visited += x to z
            x == 0
        } shouldBe ((.1 * kotlin.math.sqrt(2.0) - .01) plusOrMinus 1e-8)
        visited shouldBe listOf(0 to 0, 1 to 0)
        scopeLaserLoadedRange(Vector(-.2, 64.0, 0.0), Vector(-1.0, 0.0, 0.0), 32.0) { x, _ -> x == -1 } shouldBe (15.79 plusOrMinus 1e-8)
        scopeLaserLoadedRange(Vector(16.0, 64.0, 0.0), Vector(-1.0, 0.0, 0.0), 32.0) { x, _ -> x == 1 } shouldBe 0.0
    }

    "vertical rays and loaded rays keep their full bounded range without inventing an impact" {
        var checks = 0
        scopeLaserLoadedRange(Vector(1.0, 64.0, 1.0), Vector(-0.0, 1.0, 0.0), 32.0) { _, _ -> checks++; true } shouldBe 32.0
        checks shouldBe 1
        scopeLaserLoadedRange(Vector(0.0, 64.0, 0.0), Vector(1.0, 0.0, 0.0), 32.0) { _, _ -> true } shouldBe 32.0
        scopeLaserLoadedRange(Vector(), Vector(0.0, 0.0, 1.0), 32.0) { _, _ -> false } shouldBe 0.0
    }

    "beam centers join the muzzle and ray endpoint for vertical backward and diagonal aim" {
        for (delta in listOf(Vector(0.0, 0.0, 12.0), Vector(0.0, 0.0, -12.0), Vector(0.0, 12.0, 0.0), Vector(-3.0, 4.0, -7.0))) {
            val transform = scopeLaserBeamTransform(delta, .012f)
            fun point(z: Float): Vector3f = Vector3f(.5f, .5f, z).mul(transform.scale)
                .rotate(transform.leftRotation).add(transform.translation)
            point(0f).length().toDouble() shouldBe (0.0 plusOrMinus 1e-6)
            val end = point(1f)
            end.x.toDouble() shouldBe (delta.x plusOrMinus 1e-5)
            end.y.toDouble() shouldBe (delta.y plusOrMinus 1e-5)
            end.z.toDouble() shouldBe (delta.z plusOrMinus 1e-5)
            transform.scale.x shouldBe .012f
            transform.scale.y shouldBe .012f
        }
    }
})
