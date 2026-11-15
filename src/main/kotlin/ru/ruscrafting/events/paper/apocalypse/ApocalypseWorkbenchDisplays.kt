package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplayAudienceGroup
import ru.arc.paper.display.PaperPacketDisplays
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchStation

/** The closed cabinets cover the real, clickable dropper. All tools and cabinetry are client-only. */
internal class ApocalypseWorkbenchDisplays(
    private val displays: PaperPacketDisplays,
    private val stations: ApocalypseWorkbenchStations,
    private val active: (Player) -> Boolean,
) : Listener, AutoCloseable {
    private data class Scene(val type: ApocalypseWorkbenchStation, val facing: BlockFace, val headroom: Boolean,
                             val neighbors: Set<BlockFace>, val parts: List<PacketBlockDisplay>)

    private val chunks = mutableMapOf<Long, Map<ApocalypsePoint, ApocalypseWorkbenchStation>>()
    private val scenes = mutableMapOf<ApocalypsePoint, Scene>()
    private val blockData = mutableMapOf<String, BlockData>()
    private var world: World? = null
    private var audience: PacketDisplayAudienceGroup? = null

    fun register(plugin: Plugin) {
        check(audience == null)
        audience = displays.createAudienceGroup(active)
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    /** Called on Paper once a second, including the first tick after the last expedition member leaves. */
    fun refresh(survivors: List<Player>) {
        val audience = audience ?: return
        val viewers = survivors.filter { !it.isDead && active(it) }
        val current = viewers.firstOrNull()?.world
        if (world != current) {
            clear()
            world = current
        }
        if (current == null) return

        val visited = mutableSetOf<Long>()
        val wanted = linkedSetOf<ApocalypsePoint>()
        for (viewer in viewers) {
            if (viewer.world != current) continue
            val at = viewer.location
            val nearby = mutableListOf<Pair<ApocalypsePoint, Double>>()
            for (cx in (at.blockX - RANGE shr 4)..(at.blockX + RANGE shr 4)) {
                for (cz in (at.blockZ - RANGE shr 4)..(at.blockZ + RANGE shr 4)) {
                    if (!current.isChunkLoaded(cx, cz)) continue
                    val chunkKey = Chunk.getChunkKey(cx, cz)
                    visited += chunkKey
                    val points = chunks.getOrPut(chunkKey) { stations.loadedStations(current.getChunkAt(cx, cz)) }
                    for (point in points.keys) {
                        val dx = point.x + .5 - at.x
                        val dy = point.y - at.y
                        val dz = point.z + .5 - at.z
                        val distance = dx * dx + dy * dy + dz * dz
                        if (distance <= RANGE * RANGE) nearby += point to distance
                    }
                }
            }
            nearby.sortBy { it.second }
            nearby.take(NEAREST_PER_VIEWER).forEach { wanted += it.first }
        }
        chunks.keys.retainAll(visited)
        scenes.keys.filter { it !in wanted }.forEach(::remove)
        var opened = 0
        for (point in wanted) {
            val block = current.getBlockAt(point.x, point.y, point.z)
            val type = stations.stationAt(block)
            if (type == null || type == ApocalypseWorkbenchStation.CRAFTING) {
                remove(point)
                continue
            }
            val facing = (block.blockData as? Directional)?.facing ?: BlockFace.SOUTH
            val headroom = point.y + 1 < current.maxHeight && block.getRelative(BlockFace.UP).isPassable
            val neighbors = CARDINALS.filterTo(mutableSetOf()) { side ->
                val x = point.x + side.modX
                val z = point.z + side.modZ
                !current.isChunkLoaded(x shr 4, z shr 4) || current.getBlockAt(x, point.y, z).type.let {
                    it == Material.DROPPER || it.isOccluding
                }
            }
            val old = scenes[point]
            if (old?.type == type && old.facing == facing && old.headroom == headroom && old.neighbors == neighbors) continue
            if (opened >= NEW_PER_REFRESH) {
                if (old != null && old.type != type) remove(point)
                continue
            }
            remove(point)
            val anchor = block.location.add(.5, 0.0, .5)
            val parts = mutableListOf<PacketBlockDisplay>()
            try {
                for (part in ApocalypseWorkbenchDisplayPart.models.getValue(type)) {
                    if (!headroom && part.center[1] > 16.5f) continue
                    val pose = part.transform(facing, neighbors) ?: continue
                    val data = blockData.getOrPut(part.block) { Bukkit.createBlockData("minecraft:" + part.block) }
                    displays.spawnBlock(anchor, data).also(parts::add).apply {
                        audienceGroup = audience
                        transformation = pose
                        viewRange = (if (part.detail) DETAIL_RANGE else RANGE) / 64f
                        pauseWhenNotObserved = part.detail
                    }
                }
                scenes[point] = Scene(type, facing, headroom, neighbors, parts)
                opened++
            } catch (failure: Exception) {
                parts.forEach(PacketBlockDisplay::remove)
                throw failure
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) = invalidate(event.block)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) = invalidate(event.block)

    private fun invalidate(block: Block) {
        if (block.world != world || block.type != Material.DROPPER) return
        chunks.remove(Chunk.getChunkKey(block.x shr 4, block.z shr 4))
        remove(ApocalypsePoint(block.x, block.y, block.z))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onUnload(event: ChunkUnloadEvent) {
        if (event.world != world) return
        chunks.remove(event.chunk.chunkKey)
        scenes.keys.filter { it.x shr 4 == event.chunk.x && it.z shr 4 == event.chunk.z }.forEach(::remove)
    }

    private fun remove(point: ApocalypsePoint) {
        scenes.remove(point)?.parts?.forEach(PacketBlockDisplay::remove)
    }

    private fun clear() {
        scenes.keys.toList().forEach(::remove)
        chunks.clear()
    }

    override fun close() {
        HandlerList.unregisterAll(this)
        clear()
        audience?.close()
        audience = null
        world = null
    }

    private companion object {
        val CARDINALS = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
        const val RANGE = 24
        const val DETAIL_RANGE = 12
        const val NEAREST_PER_VIEWER = 12
        const val NEW_PER_REFRESH = 4
    }
}

/** Model coordinates are sixteenths of a block. +Z is the front, Y=0 the supporting floor. */
internal data class ApocalypseWorkbenchDisplayPart(
    val key: String, val block: String, val center: FloatArray, val size: FloatArray,
    val rotation: FloatArray?, val detail: Boolean,
) {
    fun transform(facing: BlockFace, neighbors: Set<BlockFace> = emptySet()): Transformation? {
        val yaw = when (facing) {
            BlockFace.EAST -> 90
            BlockFace.NORTH -> 180
            BlockFace.WEST -> -90
            else -> 0
        }
        val turn = Quaternionf().rotationY(Math.toRadians(yaw.toDouble()).toFloat())
        val orientation = Quaternionf(turn)
        rotation?.let { orientation.rotateXYZ(it[0] * RAD, it[1] * RAD, it[2] * RAD) }
        val scale = Vector3f(size[0], size[1], size[2])
        val middle = Vector3f(center[0], center[1], center[2])
        // Join the cabinet skins and adjoining counters exactly at the block boundary.
        // Tools above the counter have their own authored footprint and rotation.
        if (center[1] <= 16.5f && neighbors.isNotEmpty()) {
            val front = if (facing.modY == 0) facing else BlockFace.SOUTH
            val right = when (front) {
                BlockFace.EAST -> BlockFace.NORTH
                BlockFace.NORTH -> BlockFace.WEST
                BlockFace.WEST -> BlockFace.SOUTH
                else -> BlockFace.EAST
            }
            for ((axis, positive) in listOf(0 to right, 2 to front)) {
                var from = middle[axis] - scale[axis] / 2
                var to = middle[axis] + scale[axis] / 2
                if (positive.oppositeFace in neighbors) from = maxOf(from, -8f)
                if (positive in neighbors) to = minOf(to, 8f)
                if (to <= from) return null
                middle.setComponent(axis, (from + to) / 2)
                scale.setComponent(axis, to - from)
            }
        }
        scale.div(16f)
        turn.transform(middle.div(16f))
        val corner = orientation.transform(Vector3f(scale).mul(-.5f)).add(middle)
        return Transformation(corner, orientation, scale, Quaternionf())
    }

    companion object {
        private val RAD = Math.toRadians(1.0).toFloat()
        val models: Map<ApocalypseWorkbenchStation, List<ApocalypseWorkbenchDisplayPart>> by lazy {
            val type = object : TypeToken<Map<ApocalypseWorkbenchStation, List<ApocalypseWorkbenchDisplayPart>>>() {}.type
            requireNotNull(ApocalypseWorkbenchDisplayPart::class.java.getResourceAsStream("/apocalypse/workbench-displays.json"))
                .bufferedReader().use { reader -> Gson().fromJson(reader, type) }
        }
    }
}

/** A dropper placed at the player's feet can face up. Preserve the station's horizontal working side. */
internal fun orientApocalypseWorkbench(state: TileState, player: Player) {
    val data = state.blockData as? Directional ?: return
    if (data.facing == BlockFace.UP || data.facing == BlockFace.DOWN) {
        data.facing = player.facing.oppositeFace
        state.blockData = data
    }
}
