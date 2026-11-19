package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class DisasterArenaGeneratorTest : StringSpec({
    val cove = DisasterArenaGenerator

    "the wharf joins the storm shelter, the high lookout and the original ship deck on foot" {
        fun solid(x: Int, y: Int, z: Int) = cove.materialAt(x, y, z)?.isSolid == true
        fun standing(x: Int, y: Int, z: Int) = solid(x, y - 1, z) && !solid(x, y, z) && !solid(x, y + 1, z)
        val spawn = Triple(0, 65, 0)
        standing(spawn.first, spawn.second, spawn.third) shouldBe true
        val reached = mutableSetOf(spawn)
        val pending = ArrayDeque(listOf(spawn))
        while (pending.isNotEmpty()) {
            val (x, y, z) = pending.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val nz = z + dz
                if (nx !in -43..24 || nz !in -22..9) continue
                for (dy in -1..1) {
                    val next = Triple(nx, y + dy, nz)
                    if (standing(nx, y + dy, nz) && (dy <= 0 || !solid(x, y + 2, z)) && reached.add(next)) pending.addLast(next)
                }
            }
        }
        for (point in listOf(Triple(-14, 65, 0), Triple(19, 70, 0), Triple(-39, 77, -17))) {
            (point in reached) shouldBe true
        }
        for (x in -5..6) for (z in -6..5) {
            standing(x, 65, z) shouldBe true
            (66..cove.MAX_Y).none { solid(x, it, z) } shouldBe true
        }
        cove.materialAt(-14, 72, 0) shouldBe Material.WAXED_WEATHERED_CUT_COPPER
        cove.materialAt(19, 69, 0) shouldBe Material.SMOOTH_SANDSTONE
    }

    "the deployed volume contains GoldenOre's ship, the jungle headland and the added lighthouse" {
        cove.materialAt(-56, 94, -33) shouldBe Material.WHITE_WOOL
        cove.materialAt(-39, 76, -17) shouldBe Material.DARK_OAK_PLANKS
        cove.materialAt(36, 85, -29) shouldBe Material.JUNGLE_WOOD
        cove.materialAt(-23, 99, -37) shouldBe Material.OCHRE_FROGLIGHT
        cove.materialAt(-80, 63, 0) shouldBe Material.WATER
        cove.materialAt(-80, 64, 0) shouldBe null
    }
})
