package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.World
import org.bukkit.block.Block

class PracticeSpawnReachabilityTest : StringSpec({
    "finds a connected route around a wall and excludes sealed rooms and two-block jumps" {
        val world = practiceWorld()
        val origin = PracticeSpawnCell(0, 64, 0)
        val corner = PracticeSpawnCell(0, 64, 1)
        val step = PracticeSpawnCell(1, 65, 1)
        val lane = PracticeSpawnCell(2, 65, 1)
        val sealed = PracticeSpawnCell(4, 65, 1)
        val tooHigh = PracticeSpawnCell(0, 66, -1)
        val standable = setOf(origin, corner, step, lane, sealed, tooHigh)

        reachablePracticeSpawnCells(origin.location(world)) { PracticeSpawnCell(it) in standable } shouldBe
            setOf(origin, corner, step, lane)
    }

    "a low ceiling blocks a step even when both feet positions have local footing" {
        val world = practiceWorld()
        val origin = PracticeSpawnCell(0, 64, 0)
        val step = PracticeSpawnCell(1, 65, 0)
        val ceiling = mockk<Block> { every { isPassable } returns false }
        every { world.getBlockAt(0, 66, 0) } returns ceiling

        reachablePracticeSpawnCells(origin.location(world)) { PracticeSpawnCell(it) in setOf(origin, step) } shouldBe
            setOf(origin)
    }

    "never queries footing in an unloaded chunk" {
        val world = practiceWorld()
        every { world.isChunkLoaded(1, 0) } returns false
        val origin = PracticeSpawnCell(15, 64, 0)
        val inspected = mutableSetOf<PracticeSpawnCell>()
        reachablePracticeSpawnCells(origin.location(world)) {
            inspected.add(PracticeSpawnCell(it))
            true
        }.none { it.x shr 4 == 1 && it.z shr 4 == 0 } shouldBe true
        inspected.none { it.x shr 4 == 1 && it.z shr 4 == 0 } shouldBe true
    }
})

private fun practiceWorld(): World {
    val air = mockk<Block> { every { isPassable } returns true }
    return mockk {
        every { minHeight } returns -64
        every { maxHeight } returns 320
        every { isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { getBlockAt(any<Int>(), any<Int>(), any<Int>()) } returns air
    }
}
