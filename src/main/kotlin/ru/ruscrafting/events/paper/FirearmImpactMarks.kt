package ru.ruscrafting.events.paper

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockSupport
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.bukkit.util.RayTraceResult
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random

/** Main-thread residue of a confirmed bullet contact. The existing flight ticker owns its lifetime. */
internal class FirearmImpactMarks(private val displays: PaperPacketDisplays, private val particles: () -> Boolean) : AutoCloseable {
    private data class Support(val block: Block, val state: BlockData)
    private class Mark(val world: World, val point: Vector, val face: BlockFace, val backing: List<Support>,
                       val display: PacketItemDisplay, val checkSlot: Int, var expires: Long) {
        fun intact() = Bukkit.getWorld(world.uid) === world && backing.all { (block, state) ->
            exposed(world, block.x, block.y, block.z, face) && block.blockData == state
        }
    }

    private val marks = ArrayDeque<Mark>()
    private var checkSlot = 0
    private var closed = false

    fun mark(result: RayTraceResult, tick: Long) {
        if (closed || !particles() || result.hitEntity != null) return
        val block = result.hitBlock ?: return
        val face = result.hitBlockFace?.takeIf { it.isCartesian } ?: return
        val point = result.hitPosition
        val normal = face.direction
        val local = point.clone().subtract(Vector(block.x + .5, block.y + .5, block.z + .5))
        val clearance = .5 - maxOf(abs(local.x) * (1 - abs(face.modX)), abs(local.y) * (1 - abs(face.modY)),
            abs(local.z) * (1 - abs(face.modZ)))
        if (abs(local.dot(normal) - .5) > .00001 || clearance < 0.0) return
        val world = block.world
        val anchor = support(world, block.x, block.y, block.z, face) ?: return
        val nearby = marks.firstOrNull { it.world === world && it.face == face && it.point.distanceSquared(point) < .20 * .20 }
        if (nearby != null) {
            marks.remove(nearby)
            if (nearby.intact()) {
                nearby.expires = tick + LIFE
                marks.addLast(nearby)
                return
            }
            nearby.display.remove()
        }

        // The sprite may cross a seam in a flat wall. At an exposed edge shrink it around the actual hit.
        val backing = mutableListOf(anchor)
        var size = 1f
        if (clearance < RADIUS) {
            val inside = point.clone().subtract(normal.clone().multiply(.5))
            edges@ for (x in footprint(inside.x)) for (y in footprint(inside.y)) for (z in footprint(inside.z)) {
                if (x == block.x && y == block.y && z == block.z) continue
                val other = support(world, x, y, z, face)
                if (other == null) {
                    size = (clearance / RADIUS).toFloat()
                    if (size < .32f) return
                    backing.subList(1, backing.size).clear()
                    break@edges
                }
                backing += other
            }
        }
        val material = anchor.state.material
        val wood = Tag.LOGS.isTagged(material) || Tag.PLANKS.isTagged(material)
        val chip = if (wood) Material.CHARCOAL else Material.FLINT
        val pose = firearmImpactMarkTransform(face, wood, size, (PI + Random.nextDouble(-.40, .40)).toFloat())
        if (marks.size == 64) marks.removeFirst().display.remove()
        val display = displays.spawnItem(point.toLocation(world), ItemStack.of(chip))
        try {
            display.transformation = pose
            display.viewRange = .65f
        } catch (failure: Exception) {
            display.remove()
            throw failure
        }
        marks.addLast(Mark(world, point, face, backing, display, checkSlot, tick + LIFE))
        checkSlot = (checkSlot + 1) % 4
    }

    /** Returns whether the flight ticker still has residue to maintain. */
    fun advance(tick: Long): Boolean {
        if (marks.isEmpty()) return false
        val enabled = particles()
        val slot = (tick % 4).toInt()
        marks.removeAll { mark ->
            val retire = !enabled || tick >= mark.expires || mark.checkSlot == slot && !mark.intact()
            if (retire) mark.display.remove()
            retire
        }
        return marks.isNotEmpty()
    }

    override fun close() {
        closed = true
        marks.forEach { it.display.remove() }
        marks.clear()
    }

    private companion object {
        const val LIFE = 240L
        // Half-diagonal of either full 16x16 model quad, at any in-plane angle.
        const val RADIUS = .15
        fun footprint(axis: Double) = floor(axis - RADIUS).toInt()..floor(axis + RADIUS).toInt()

        fun exposed(world: World, x: Int, y: Int, z: Int, face: BlockFace): Boolean {
            val frontX = x + face.modX
            val frontY = y + face.modY
            val frontZ = z + face.modZ
            val height = world.minHeight until world.maxHeight
            return y in height && frontY in height && world.isChunkLoaded(x shr 4, z shr 4) &&
                world.isChunkLoaded(frontX shr 4, frontZ shr 4) && world.getBlockAt(frontX, frontY, frontZ).isEmpty
        }

        fun support(world: World, x: Int, y: Int, z: Int, face: BlockFace): Support? {
            if (!exposed(world, x, y, z, face)) return null
            val block = world.getBlockAt(x, y, z)
            if (block.isEmpty || block.isLiquid) return null
            val state = block.blockData
            if (!state.isOccluding || !state.isFaceSturdy(face, BlockSupport.FULL) || Tag.LEAVES.isTagged(state.material)) return null
            return Support(block, state)
        }
    }
}

internal fun firearmImpactMarkTransform(face: BlockFace, wood: Boolean, size: Float, roll: Float): Transformation {
    val normal = Vector3f(face.modX.toFloat(), face.modY.toFloat(), face.modZ.toFloat())
    val rotation = Quaternionf().rotationTo(0f, 0f, 1f, normal.x, normal.y, normal.z).rotateZ(roll)
    val depth = .048f
    // Vanilla generated items in NONE are centered at z=±1/32 even after ItemDisplay's native Y half-turn.
    // The rear cap touches the support; the opposite cap clears the depth buffer by three millimeters.
    return Transformation(normal.mul(depth / 32f), rotation,
        Vector3f((if (wood) .235f else .215f) * size, (if (wood) .155f else .185f) * size, depth), Quaternionf())
}
