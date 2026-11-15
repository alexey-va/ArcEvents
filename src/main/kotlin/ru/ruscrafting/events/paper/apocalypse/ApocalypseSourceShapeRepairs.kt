package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.domain.apocalypse.ApocalypseOriginalLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.ApocalypseShapeCell
import ru.ruscrafting.events.paper.EventWork
import java.util.logging.Level

/** One background source read at a time. The host applies small batches only in still-loaded chunks. */
internal class ApocalypseSourceShapeRepairs(
    private val plugin: Plugin,
    private val work: EventWork,
    private val ready: (Chunk) -> Boolean,
) : AutoCloseable {
    private data class Request(val chunk: Chunk, val layout: ApocalypseOriginalLayout, val cx: Int, val cz: Int)
    private class Plan(val cells: ArrayDeque<ApocalypseShapeCell>, val supports: Map<ApocalypsePoint, String>) {
        val missing = mutableSetOf<Pair<Int, Int>>()
        val fixedCaps = mutableMapOf<ApocalypsePoint, BlockData>()
    }
    private data class Applying(val request: Request, val plan: Plan)
    private val queued = LinkedHashMap<Long, Request>()
    private val planned = LinkedHashMap<Long, Applying>()
    private val done = NamespacedKey(plugin, "apocalypse_imported_shapes_v1")
    private val states = mutableMapOf<String, BlockData>()
    private var reading: Request? = null
    private var closed = false

    fun prepare(chunk: Chunk, layout: ApocalypseOriginalLayout) {
        if (closed || !chunk.isLoaded) return
        fun enqueue(current: Chunk) {
            val key = current.chunkKey
            if (receipt(current) != COMPLETE && key !in planned && reading?.chunk?.chunkKey != key) {
                queued.putIfAbsent(key, Request(current, layout, current.x, current.z))
            }
        }
        enqueue(chunk)
        // A previously unavailable seam can now be repaired on either side without holding a source plan.
        for (side in ApocalypseSourceShapes.sides) {
            val cx = chunk.x + side.modX
            val cz = chunk.z + side.modZ
            if (chunk.world.isChunkLoaded(cx, cz)) {
                val adjacent = chunk.world.getChunkAt(cx, cz)
                if (receipt(adjacent) == WAITING_FOR_SEAM) enqueue(adjacent)
            }
        }
    }

    fun tick() {
        if (closed) return
        if (reading == null && planned.size < 2 && queued.isNotEmpty()) {
            val (key, request) = queued.pollFirstEntry()
            if (request.chunk.isLoaded && receipt(request.chunk) != COMPLETE) {
                if (!ready(request.chunk)) queued[key] = request
                else {
                    reading = request
                    work.compute {
                        val cells = request.layout.shapeCells(request.cx, request.cz)
                        val points = buildSet { for (cell in cells) for (side in neighbors(cell)) add(offset(cell.position, side)) }
                        Plan(ArrayDeque(cells), request.layout.shapeSupports(points))
                    }.whenComplete { plan, failure -> work.onMain {
                        if (reading === request) {
                            reading = null
                            if (!closed && request.chunk.isLoaded) {
                                if (failure == null) planned[key] = Applying(request, plan)
                                else plugin.logger.log(Level.WARNING, "Could not read original imported shapes at " + request.cx + "," + request.cz, failure)
                            }
                        }
                    } }
                }
            }
        }
        val (key, job) = planned.pollFirstEntry() ?: return
        val chunk = job.request.chunk
        if (!chunk.isLoaded || receipt(chunk) == COMPLETE) return
        if (ready(chunk)) {
            val deadline = System.nanoTime() + 1_500_000L
            var count = minOf(32, job.plan.cells.size)
            while (count-- > 0 && System.nanoTime() < deadline) {
                val cell = job.plan.cells.removeFirst()
                apply(chunk, cell, job.plan)
            }
            if (job.plan.cells.isEmpty()) {
                val receipt = if (job.plan.missing.isEmpty()) COMPLETE else WAITING_FOR_SEAM
                chunk.persistentDataContainer.set(done, PersistentDataType.BYTE, receipt)
                if (job.plan.missing.any { (cx, cz) -> chunk.world.isChunkLoaded(cx, cz) }) queued[key] = job.request
                return
            }
        }
        planned[key] = job
    }

    /** Compare both the saved voxel and the support result; a removed torch/forcefield is never re-created. */
    private fun apply(chunk: Chunk, cell: ApocalypseShapeCell, plan: Plan) {
        val p = cell.position
        val world = chunk.world
        if (p.y !in world.minHeight until world.maxHeight) return
        val block = chunk.getBlock(p.x and 15, p.y, p.z and 15)
        val old = state(cell.original)
        val capShape = { cap: BlockData -> cap.getCollisionShape(Location(world, p.x.toDouble(), p.y + 1.0, p.z.toDouble())).boundingBoxes }
        val target = ApocalypseSourceShapes.resolve(old, cell.fitTorch, capShape) { face ->
            val n = offset(p, face)
            plan.fixedCaps[n] ?: state(plan.supports.getValue(n))
        }
        if (p in plan.supports) plan.fixedCaps[p] = target
        if (old == target || block.blockData != old) return
        for (direction in neighbors(cell)) {
            val cx = (p.x + direction.modX) shr 4
            val cz = (p.z + direction.modZ) shr 4
            if (!world.isChunkLoaded(cx, cz)) {
                plan.missing.add(cx to cz)
                return
            }
        }
        val actual = ApocalypseSourceShapes.resolve(old, cell.fitTorch, capShape) { face ->
            val n = offset(p, face)
            if (n.y !in world.minHeight until world.maxHeight) state("minecraft:air") else world.getBlockAt(n.x, n.y, n.z).blockData
        }
        if (actual == target) block.setBlockData(target, false)
    }

    private fun receipt(chunk: Chunk) = chunk.persistentDataContainer.get(done, PersistentDataType.BYTE)

    private fun state(value: String) = states.getOrPut(value) { Bukkit.createBlockData(value) }

    override fun close() {
        closed = true
        reading = null
        queued.clear()
        planned.clear()
        states.clear()
    }

    private companion object {
        const val WAITING_FOR_SEAM: Byte = 0
        const val COMPLETE: Byte = 1
        val torchNeighbors = ApocalypseSourceShapes.sides + BlockFace.DOWN
        val wallNeighbors = ApocalypseSourceShapes.sides + BlockFace.UP
        fun neighbors(cell: ApocalypseShapeCell): List<BlockFace> {
            val type = cell.original.substringBefore('[')
            return when {
                type.endsWith("torch") -> torchNeighbors
                type.endsWith("_wall") -> wallNeighbors
                else -> ApocalypseSourceShapes.sides
            }
        }
        fun offset(p: ApocalypsePoint, face: BlockFace) =
            ApocalypsePoint(p.x + face.modX, p.y + face.modY, p.z + face.modZ)
    }
}
