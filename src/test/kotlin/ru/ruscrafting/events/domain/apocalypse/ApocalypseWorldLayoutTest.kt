package ru.ruscrafting.events.domain.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.ArrayDeque

class ApocalypseWorldLayoutTest : StringSpec({
    val layout = ApocalypseWorldLayout(68200133L)

    "the reference center roll and radius do not move when the terrain seed changes" {
        val city = layout.sitesNear(0, 0).first { it.kind == ApocalypseSiteKind.CITY }
        city.anchor shouldBe ApocalypsePoint(-448, 71, 0)
        val other = ApocalypseWorldLayout(345627L)
        other.sitesNear(0, 0).first { it.kind == ApocalypseSiteKind.CITY } shouldBe city
        layout.cityRadius(city) shouldBe 125 + java.util.Random(-28L * 295075153L).nextInt(100)
        val sample = (-160..160 step 8).map { layout.terrainHeight(it, 156) }
        sample shouldNotBe (-160..160 step 8).map { other.terrainHeight(it, 156) }
        (sample.max() - sample.min() > 5) shouldBe true
    }

    "random spread sites and clipped chunk blocks survive negative coordinates and generation order" {
        val westFirst = ApocalypseTestWorld(layout)
        val eastFirst = ApocalypseTestWorld(ApocalypseWorldLayout(layout.seed))
        val city = layout.sitesNear(0, 0).first { it.kind == ApocalypseSiteKind.CITY }
        val cx = city.anchor.x shr 4
        val cz = city.anchor.z shr 4
        val chunks = listOf(cx - 1 to cz, cx to cz, cx + 1 to cz, cx to cz + 1)
        chunks.forEach { westFirst.chunk(it.first, it.second) }
        chunks.reversed().forEach { eastFirst.chunk(it.first, it.second) }
        for ((x, z) in chunks) {
            eastFirst.chunk(x, z).contentEquals(westFirst.chunk(x, z)) shouldBe true
            val markers = layout.markersInChunk(x, z)
            for (m in markers) {
                (m.x shr 4) shouldBe x
                (m.z shr 4) shouldBe z
                westFirst.block(m.x, m.y, m.z) shouldBe m.kind.block
            }
        }
    }

    "the starter and both deep bunker forms connect the surface stair, supplies and side wings" {
        val world = ApocalypseTestWorld(layout)
        val sites = layout.sitesNear(0, 0)
        val starter = layout.starter
        val lab = sites.first { it.kind == ApocalypseSiteKind.LAB }
        val reactor = (sites + layout.sitesNear(2500, 2500)).first { it.kind == ApocalypseSiteKind.REACTOR }
        val spawn = layout.spawn
        world.block(spawn.x, spawn.y, spawn.z) shouldBe ApocalypseBlock.AIR
        world.block(spawn.x, spawn.y + 1, spawn.z) shouldBe ApocalypseBlock.AIR
        world.block(spawn.x, spawn.y - 1, spawn.z) shouldBe ApocalypseBlock.SMOOTH_STONE
        for (site in listOf(starter, lab, reactor)) {
            val a = site.anchor
            val visited = world.walkableFrom(ApocalypsePoint(a.x, a.y + 1, a.z - 10), a)
            val points = layout.markers(site)
            points.isNotEmpty() shouldBe true
            for (m in points) {
                world.block(m.x, m.y, m.z) shouldBe m.kind.block
                layout.markersInChunk(m.x shr 4, m.z shr 4).contains(m) shouldBe true
                if (m.kind in setOf(ApocalypseMarkerKind.POWER, ApocalypseMarkerKind.LOOT, ApocalypseMarkerKind.MEDICAL, ApocalypseMarkerKind.ARMORY)) {
                    adjacent(m.x, m.y, m.z).any { it in visited } shouldBe true
                }
            }
            if (site.kind == ApocalypseSiteKind.STARTER_BUNKER) {
                (spawn in visited) shouldBe true
            } else {
                val end = points.single { it.kind == ApocalypseMarkerKind.CORE }
                val afterSwitch = world.walkableFrom(ApocalypsePoint(a.x, a.y + 1, a.z - 10), a, fieldOff = true)
                adjacent(end.x, end.y, end.z).any { it in afterSwitch } shouldBe true
            }
        }
    }
})

private fun adjacent(x: Int, y: Int, z: Int) = listOf(
    ApocalypsePoint(x - 1, y, z), ApocalypsePoint(x + 1, y, z),
    ApocalypsePoint(x, y, z - 1), ApocalypsePoint(x, y, z + 1),
)

private class ApocalypseTestWorld(private val layout: ApocalypseWorldLayout) {
    private val chunks = mutableMapOf<Pair<Int, Int>, ByteArray>()

    fun chunk(x: Int, z: Int): ByteArray = chunks.getOrPut(x to z) {
        val data = ByteArray(16 * 16 * 216)
        layout.generate(object : ApocalypseChunkCanvas(x, z, -64, 151) {
            override fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: ApocalypseBlock) {
                for (y in y1..y2) for (localZ in z1..z2) {
                    val i = ((y + 64) * 16 + localZ) * 16
                    data.fill(block.ordinal.toByte(), i + x1, i + x2 + 1)
                }
            }
        })
        data
    }

    fun block(x: Int, y: Int, z: Int): ApocalypseBlock {
        val column = chunk(x shr 4, z shr 4)
        return ApocalypseBlock.entries[column[((y + 64) * 16 + (z and 15)) * 16 + (x and 15)].toInt() and 255]
    }

    fun walkableFrom(start: ApocalypsePoint, anchor: ApocalypsePoint, fieldOff: Boolean = false): Set<ApocalypsePoint> {
        val visited = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        fun empty(x: Int, y: Int, z: Int): Boolean {
            val b = block(x, y, z)
            return b == ApocalypseBlock.AIR || b == ApocalypseBlock.LEVER ||
                (fieldOff && b == ApocalypseBlock.LIGHT_BLUE_STAINED_GLASS_PANE)
        }
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            for (next in adjacent(p.x, p.y, p.z)) {
                if (next.x !in anchor.x - 51..anchor.x + 44 || next.z !in anchor.z - 14..anchor.z + 61) continue
                for (dy in -1..1) {
                    val y = p.y + dy
                    if (y !in anchor.y - 50..anchor.y + 2 || (next.z < anchor.z - 5 && kotlin.math.abs(next.x - anchor.x) > 4)) continue
                    if (!empty(next.x, y, next.z) || !empty(next.x, y + 1, next.z) || empty(next.x, y - 1, next.z)) continue
                    val n = ApocalypsePoint(next.x, y, next.z)
                    if (visited.add(n)) queue.addLast(n)
                }
            }
        }
        return visited
    }
}
