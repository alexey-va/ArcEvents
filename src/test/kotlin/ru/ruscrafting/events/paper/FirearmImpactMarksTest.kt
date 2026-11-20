package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockSupport
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.inventory.ItemStack
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import kotlin.math.PI
import kotlin.math.abs

class FirearmImpactMarksTest : StringSpec({
    "the generated sprite touches all six planes and projects three millimeters out of each" {
        for (face in BlockFace.entries.filter { it.isCartesian }) for (wood in listOf(false, true)) {
            val normal = Vector3f(face.modX.toFloat(), face.modY.toFloat(), face.modZ.toFloat())
            // Opaque texel limits from the 16px 1.21.11 flint/charcoal PNGs. Both generated caps are present.
            val top = if (wood) 1f else 2f
            for (angle in listOf(-.40, 0.0, .40)) {
                val pose = firearmImpactMarkTransform(face, wood, 1f, (PI + angle).toFloat())
                val distances = mutableListOf<Float>()
                for (x in listOf(2f, 15f)) for (y in listOf(top, 15f)) for (z in listOf(7.5f, 8.5f)) {
                    val corner = Vector3f((x - 8f) / 16, (8f - y) / 16, (z - 8f) / 16)
                        .rotateY(PI.toFloat()).rotate(pose.rightRotation).mul(pose.scale)
                        .rotate(pose.leftRotation).add(pose.translation)
                    val distance = normal.dot(corner)
                    distances += distance
                    (corner.sub(Vector3f(normal).mul(distance)).length() < .15f) shouldBe true
                }
                (abs(distances.min()) < .000001f) shouldBe true
                (abs(distances.max() - .003f) < .000001f) shouldBe true
            }
        }
    }

    "nearby hits on one face reuse the blemish and its twelve seconds start at the last hit" {
        MockBukkitTestRuntime.open().use { paper ->
            val f = ImpactMarkFixture(paper)
            val block = f.place(2, 80, 2)
            val hit = block.faceHit(BlockFace.SOUTH)
            f.marks.mark(hit, 0)
            f.marks.mark(RayTraceResult(hit.hitPosition.add(Vector(.04, .03, 0.0)), block, BlockFace.SOUTH), 10)
            f.records.size shouldBe 1
            f.records.single().apply {
                material shouldBe Material.FLINT
                position.toVector() shouldBe Vector(2.5, 80.5, 3.0)
            }
            f.marks.advance(249) shouldBe true
            f.marks.advance(250) shouldBe false
            f.records.single().removed shouldBe true
        }
    }

    "only exposed solid faces accept marks and narrow corners keep the true hit center" {
        MockBukkitTestRuntime.open().use { paper ->
            val f = ImpactMarkFixture(paper)
            val wall = f.place(2, 80, 2)
            val face = BlockFace.SOUTH
            fun hit(x: Double, z: Double = 3.0) = RayTraceResult(Vector(x, 80.5, z), wall, face)
            f.marks.mark(hit(2.5, 2.65), 0) // Inside the block, not on its plane.
            f.marks.mark(hit(3.0), 0) // At the outer edge, over air.
            f.marks.mark(RayTraceResult(Vector(2.5, 80.5, 3.0), mockk<Entity>()), 0)
            f.marks.mark(f.place(5, 80, 2, f.state(Material.OAK_LEAVES)).faceHit(face), 0)
            f.marks.mark(f.place(7, 80, 2, f.state(Material.STONE_SLAB, sturdy = false)).faceHit(face), 0)
            f.marks.mark(f.place(9, 80, 2, f.state(Material.WATER)).faceHit(face), 0)
            f.marks.mark(f.place(12, f.world.maxHeight - 1, 2).faceHit(BlockFace.UP), 0)
            f.records.size shouldBe 0

            f.marks.mark(hit(2.93), 0)
            val edge = f.records.single()
            edge.position.toVector() shouldBe Vector(2.93, 80.5, 3.0)
            (edge.pose!!.scale.x < .12f) shouldBe true
            f.marks.close()
            edge.removed shouldBe true
            f.marks.mark(hit(2.5), 1)
            f.records.size shouldBe 1
        }
    }

    "a mark can cross a level seam and disappears when either supporting block changes or unloads" {
        MockBukkitTestRuntime.open().use { paper ->
            val f = ImpactMarkFixture(paper)
            val wall = f.place(15, 80, 2, f.state(Material.OAK_PLANKS))
            val adjacent = f.place(16, 80, 2)
            val contact = RayTraceResult(Vector(16.0, 80.5, 3.0), wall, BlockFace.SOUTH)
            f.marks.mark(contact, 0)
            f.records.single().material shouldBe Material.CHARCOAL
            f.records.single().pose!!.scale.x shouldBe .235f
            adjacent.blockData = f.state(Material.ANDESITE)
            f.marks.advance(4) shouldBe false
            f.records.single().removed shouldBe true
            f.marks.mark(contact, 5)
            f.world.unloadChunk(1, 0, false) shouldBe true
            for (tick in 6L..9L) f.marks.advance(tick)
            f.records.last().removed shouldBe true
            f.world.isChunkLoaded(1, 0) shouldBe false
            f.marks.mark(contact, 10)
            f.records.size shouldBe 2
        }
    }

    "at most 64 marks survive even during a burst and disabling particles immediately clears them" {
        MockBukkitTestRuntime.open().use { paper ->
            val f = ImpactMarkFixture(paper)
            repeat(65) { i -> f.marks.mark(f.place(i % 10 + 2, 80, i / 10 + 2).faceHit(BlockFace.UP), 0) }
            f.records.size shouldBe 65
            f.peak shouldBe 64
            f.records.first().removed shouldBe true
            f.records.count { !it.removed } shouldBe 64
            f.enabled = false
            f.marks.advance(1) shouldBe false
            f.records.count { !it.removed } shouldBe 0
            f.marks.mark(f.place(2, 81, 2).faceHit(BlockFace.SOUTH), 2)
            f.records.size shouldBe 65
            f.marks.close()
        }
    }
})

private class ImpactMarkFixture(paper: MockBukkitTestRuntime) {
    val world = paper.addSimpleWorld("impact-test")
    data class Record(val position: Location, val material: Material, var pose: Transformation? = null, var removed: Boolean = false)
    val records = mutableListOf<Record>()
    var peak = 0
    var enabled = true
    private val displays = mockk<PaperPacketDisplays>()
    val marks = FirearmImpactMarks(displays) { enabled }
    private val stone = state(Material.STONE)

    init {
        every { displays.spawnItem(any(), any()) } answers {
            val record = Record(firstArg<Location>().clone(), secondArg<ItemStack>().type)
            records += record
            peak = maxOf(peak, records.count { !it.removed })
            mockk<PacketItemDisplay>(relaxed = true).also { handle ->
                every { handle.transformation = any() } answers { record.pose = firstArg() }
                every { handle.remove() } answers { record.removed shouldBe false; record.removed = true }
            }
        }
    }

    // MockBukkit leaves precisely these two native block-state shape queries unimplemented in 1.21.11.
    fun state(type: Material, sturdy: Boolean = true) = mockk<BlockData>().also { data ->
        every { data.material } returns type
        every { data.clone() } returns data
        every { data.isOccluding } returns true
        every { data.isFaceSturdy(any(), BlockSupport.FULL) } returns sturdy
    }

    fun place(x: Int, y: Int, z: Int, state: BlockData = stone): Block {
        world.loadChunk(x shr 4, z shr 4)
        return world.getBlockAt(x, y, z).also { it.blockData = state }
    }
}

private fun Block.faceHit(face: BlockFace) = RayTraceResult(
    Vector(x + .5, y + .5, z + .5).add(face.direction.multiply(.5)), this, face,
)
