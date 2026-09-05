package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DisasterArenaGeneratorTest : StringSpec({
    "players can walk and jump from spawn to every fog terrace and lightning shelter" {
        fun solid(x: Int, y: Int, z: Int) = DisasterArenaGenerator.materialAt(x, y, z)?.isSolid == true
        fun standing(x: Int, y: Int, z: Int) = solid(x, y - 1, z) && !solid(x, y, z) && !solid(x, y + 1, z)
        val spawn = Triple(0, 65, 0)
        standing(spawn.first, spawn.second, spawn.third) shouldBe true
        val reached = mutableSetOf(spawn)
        val pending = ArrayDeque(listOf(spawn))
        while (pending.isNotEmpty()) {
            val (x, y, z) = pending.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                for (dy in -1..1) {
                    val next = Triple(x + dx, y + dy, z + dz)
                    if (standing(next.first, next.second, next.third) &&
                        !solid(x, y + 2, z) && reached.add(next)) pending.addLast(next)
                }
            }
        }
        for (x in listOf(-13, 13)) {
            for (z in listOf(-13, 13)) (Triple(x, 68, z) in reached) shouldBe true
            (Triple(x, 65, 0) in reached) shouldBe true
            solid(x, 70, 0) shouldBe true
        }
        reached.none { kotlin.math.abs(it.first) > 24 || kotlin.math.abs(it.third) > 24 } shouldBe true
    }
})
