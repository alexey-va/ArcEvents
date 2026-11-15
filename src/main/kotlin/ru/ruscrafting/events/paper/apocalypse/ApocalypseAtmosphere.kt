package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockSupport
import org.bukkit.block.TileState
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.MultipleFacing
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.domain.apocalypse.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Runs on the existing host tick. No chunk loads, global weather, looping audio or persistent effect entities. */
internal class ApocalypseAtmosphere(plugin: Plugin) : AutoCloseable {
    private val districtKey = NamespacedKey(plugin, "apocalypse_waterfront_v1")
    private val starterKey = NamespacedKey(plugin, "apocalypse_starter_scenery_v1")
    private val states = mutableMapOf<String, BlockData>()
    private val jobs = LinkedHashMap<Long, LotJob>()
    private val amber = Particle.DustOptions(Color.fromRGB(255, 192, 85), 0.72f)
    private data class LotJob(
        val chunk: Chunk, val cells: List<ApocalypseSceneryCell>, val resumed: Boolean,
        var index: Int = 0, var applying: Boolean = false, var old: Boolean = true, var fresh: Boolean = true,
        var keptEdit: Boolean = false,
    )

    fun prepare(chunk: Chunk, layout: ApocalypseOriginalLayout) {
        if (!chunk.isLoaded) return
        val pdc = chunk.persistentDataContainer
        val table = layout.starterCraftingTable
        val vines = layout.starterFloorIvy[chunk.x to chunk.z].orEmpty()
        val hasTable = table.x shr 4 == chunk.x && table.z shr 4 == chunk.z
        if ((hasTable || vines.isNotEmpty()) && !pdc.has(starterKey)) {
            val moss = state("minecraft:moss_carpet")
            for (p in vines) {
                val block = chunk.getBlock(p.x and 15, p.y, p.z and 15)
                if (block.type == Material.VINE && (block.blockData as? MultipleFacing)?.faces == setOf(BlockFace.UP) &&
                    !block.getRelative(BlockFace.UP).blockData.isFaceSturdy(BlockFace.DOWN, BlockSupport.FULL) &&
                    moss.isSupported(block)) block.setBlockData(moss, false)
            }
            if (hasTable) {
                val block = chunk.getBlock(table.x and 15, table.y, table.z and 15)
                if (block.type.isAir && block.getRelative(BlockFace.DOWN).blockData == state(layout.starterTableFloor))
                    block.setType(Material.CRAFTING_TABLE, false)
            }
            pdc.set(starterKey, PersistentDataType.BYTE, 1)
        }
        val cells = layout.waterfrontLot(chunk.x, chunk.z) ?: return
        val status = pdc.get(districtKey, PersistentDataType.BYTE)
        if (status != COMPLETE && status != SKIPPED) jobs.computeIfAbsent(chunk.chunkKey) { LotJob(chunk, cells, status == STARTED) }
    }

    fun generated(chunk: Chunk, layout: ApocalypseOriginalLayout) {
        if (layout.waterfrontLot(chunk.x, chunk.z) != null && chunk.world.generator is ApocalypseOriginalGenerator)
            chunk.persistentDataContainer.set(districtKey, PersistentDataType.BYTE, COMPLETE)
    }

    fun allowsMarker(chunk: Chunk, layout: ApocalypseOriginalLayout, marker: ApocalypseMarker): Boolean =
        !layout.isWaterfront(marker.siteId) || chunk.persistentDataContainer.get(districtKey, PersistentDataType.BYTE) == COMPLETE

    /** Preflight the complete lot before the first write. Mark/persist a resumable partial lot in the same chunk. */
    fun tickRepairs() {
        val entry = jobs.entries.firstOrNull() ?: return
        val job = entry.value
        if (!job.chunk.isLoaded) { jobs.remove(entry.key); return }
        // Let occupied old lots settle once explorers have left their footprint.
        if (job.applying && job.chunk.world.players.any { p ->
                p.location.let { it.blockX shr 4 == job.chunk.x && it.blockZ shr 4 == job.chunk.z }
            }) {
            jobs.remove(entry.key)
            jobs[entry.key] = job
            return
        }
        val deadline = System.nanoTime() + 1_500_000L
        var budget = if (job.applying) 192 else 512
        while (budget-- > 0 && System.nanoTime() < deadline) {
            if (job.index == job.cells.size) {
                if (job.applying || job.fresh) { finish(job, if (job.keptEdit) SKIPPED else COMPLETE); return }
                // A restarted partial lot must pass the complete old-or-new check as well.
                job.chunk.persistentDataContainer.set(districtKey, PersistentDataType.BYTE, STARTED)
                job.applying = true
                job.index = 0
                return
            }
            val cell = job.cells[job.index++]
            val p = cell.position
            val block = job.chunk.getBlock(p.x and 15, p.y, p.z and 15)
            val actual = block.blockData
            val before = actual == state(cell.previous)
            val after = actual == state(cell.state)
            if (!job.applying) {
                job.old = job.old && before
                job.fresh = job.fresh && after
                if (!job.old && !job.fresh && !job.resumed) { finish(job, SKIPPED); return }
                if (!before && !after) job.keptEdit = true
            } else if (!after) {
                // This lot already passed a complete preflight. Preserve edits made since then
                // and finish the remaining original cells, without adopting that lot's containers.
                if (!before || block.state is TileState) job.keptEdit = true
                else block.setBlockData(state(cell.state), false)
            }
        }
    }

    private fun finish(job: LotJob, status: Byte) {
        job.chunk.persistentDataContainer.set(districtKey, PersistentDataType.BYTE, status)
        jobs.remove(job.chunk.chunkKey)
    }

    private fun state(text: String): BlockData = states.getOrPut(text) { Bukkit.createBlockData(text) }

    /** Call once a second only for this expedition's living players, with this player's unopened starter barrels. */
    fun show(player: Player, layout: ApocalypseLayout, step: Long, unopenedSupplies: List<ApocalypseMarker>) {
        val at = player.location
        val world = player.world
        for (barrel in unopenedSupplies) {
            val x = barrel.x + .5
            val y = barrel.y + .5
            val z = barrel.z + .5
            val dx = at.x - x
            val dz = at.z - z
            if (dx * dx + (at.y - y) * (at.y - y) + dz * dz > 32 * 32 ||
                !world.isChunkLoaded(barrel.x shr 4, barrel.z shr 4) || world.getBlockAt(barrel.x, barrel.y, barrel.z).type != Material.BARREL) continue
            // Four corner reflections sit on the barrel face nearest this viewer, never through the lid above it.
            val faceX = if (abs(dx) >= abs(dz)) if (dx >= 0) 1 else -1 else 0
            val faceZ = if (faceX == 0) if (dz >= 0) 1 else -1 else 0
            for (side in listOf(-.37, .37)) for (height in listOf(-.30, .30)) {
                player.spawnParticle(Particle.DUST, x + .54 * faceX + side * faceZ, y + height, z + .54 * faceZ + side * faceX,
                    2, .025, .035, .025, 0.0, amber)
            }
            player.spawnParticle(Particle.WAX_ON, x + .58 * faceX, y + .38, z + .58 * faceZ, 2, .12, .07, .12, .01)
        }
        if (at.block.lightFromSky < 12 || player.eyeLocation.block.isLiquid) return
        val phase = step / 20L + (player.uniqueId.leastSignificantBits and 1023)
        val angle = phase * .37
        player.spawnParticle(Particle.WHITE_ASH, at.x + cos(angle) * 6, at.y + 1.8, at.z + sin(angle) * 6, 8, 3.0, 1.1, 3.0, .012)
        if (phase % 3L == 0L) player.spawnParticle(Particle.ASH, at.x - 4, at.y + 1.0, at.z + 4, 4, 2.2, .6, 2.2, .01)
        if (layout is ApocalypseOriginalLayout) {
            val vent = layout.waterfrontVent
            if ((at.x - vent.x) * (at.x - vent.x) + (at.z - vent.z) * (at.z - vent.z) <= 106 * 106 &&
                world.isChunkLoaded(vent.x shr 4, vent.z shr 4) && world.getBlockAt(vent.x, vent.y, vent.z).type == Material.WEATHERED_COPPER_GRATE) {
                player.spawnParticle(Particle.CAMPFIRE_SIGNAL_SMOKE, vent.x + .5, vent.y + 1.05, vent.z + .5,
                    2, .10, .06, .10, .012, null, true)
            }
            val quay = layout.waterfrontApproach
            val dx = quay.x - at.x
            val dz = quay.z - at.z
            if (dx * dx + dz * dz < 44 * 44 && phase % 33L == 0L)
                player.playSound(at.clone().add(cos(angle) * 6, 1.5, sin(angle) * 6), Sound.BLOCK_COPPER_GRATE_HIT, SoundCategory.AMBIENT, .38f, .55f)
        }
        if (phase % 51L == 0L) player.playSound(at, Sound.AMBIENT_BASALT_DELTAS_ADDITIONS, SoundCategory.AMBIENT, .22f, .7f)
    }

    override fun close() { jobs.clear(); states.clear() }

    private companion object {
        const val STARTED: Byte = 1
        const val COMPLETE: Byte = 2
        const val SKIPPED: Byte = 3
    }
}

/** Invoked by the mining owner's main-thread callback for its still-loaded, original-source chunk. */
internal fun repairApocalypseColumbite(chunk: Chunk, ores: Map<ApocalypsePoint, String>, repairedKey: NamespacedKey) {
    if (!chunk.isLoaded || "the_deeper_void:columbite" !in ores.values || chunk.persistentDataContainer.has(repairedKey)) return
    for ((point, ore) in ores) {
        if (ore != "the_deeper_void:columbite" || point.x shr 4 != chunk.x || point.z shr 4 != chunk.z) continue
        val block = chunk.getBlock(point.x and 15, point.y, point.z and 15)
        if (block.type == Material.POLISHED_DEEPSLATE) block.setType(Material.DEEPSLATE_EMERALD_ORE, false)
    }
    chunk.persistentDataContainer.set(repairedKey, PersistentDataType.BYTE, 1)
}
