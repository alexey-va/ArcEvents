package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.Plugin
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketLease
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.ruscrafting.events.domain.apocalypse.ApocalypseOriginalLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseSourcePalette
import ru.ruscrafting.events.paper.EventWork
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Only authored ore coordinates can mint the source material, once through the existing world journal. */
internal class ApocalypseSourceMining(
    private val plugin: Plugin,
    private val work: EventWork,
    private val chunkTickets: PaperChunkTicketRegistry,
    private val world: () -> World?,
    private val active: (Player) -> Boolean,
    private val dropFactory: (Player, String, Int) -> ItemStack,
    private val claimed: (String) -> Boolean,
    private val claim: (String) -> CompletableFuture<Boolean>,
    private val requiredTool: (Player, String) -> Unit,
    private val random: Random = Random(),
    private val damageHandTool: (Player) -> Unit = { it.damageItemStack(EquipmentSlot.HAND, 1) },
) : Listener, AutoCloseable {
    private class SourceChunk(val ores: Map<ApocalypsePoint, String>? = null, val pending: Boolean = true)
    private class SourceRequest(val key: Long, val chunk: Chunk, val layout: ApocalypseOriginalLayout, val marker: SourceChunk, var priority: Boolean) {
        val world = chunk.world
        val x = chunk.x
        val z = chunk.z
    }
    private val chunks = mutableMapOf<Long, SourceChunk>()
    private val queued = java.util.LinkedHashMap<Long, SourceRequest>()
    private var inFlight: SourceRequest? = null
    private var dispatching = false
    private val leases = mutableSetOf<PaperChunkTicketLease>()
    private val columbiteRepaired = NamespacedKey(plugin, "apocalypse_columbite_v1")
    private val expectedStates by lazy { SOURCE_ORES.associateWith { Bukkit.createBlockData(requireNotNull(ApocalypseSourcePalette.vanillaState(it))) } }
    @Volatile private var registered = false

    fun register() {
        check(!registered)
        plugin.server.pluginManager.registerEvents(this, plugin)
        registered = true
    }

    /** Source structure planning stays on EventWork; only its small loaded-chunk index is published on Main. */
    fun prepare(chunk: Chunk, layout: ApocalypseOriginalLayout, priority: Boolean = false) {
        if (!registered || chunk.world != world() || !chunk.isLoaded) return
        val key = chunkKey(chunk.x, chunk.z)
        val previous = chunks[key]
        if (previous?.ores != null) return
        queued[key]?.let { request ->
            if (priority) { request.priority = true; queued.putFirst(key, request) }
            dispatchNext()
            return
        }
        if (previous?.pending == true) return
        val pending = SourceChunk()
        chunks[key] = pending
        val request = SourceRequest(key, chunk, layout, pending, priority)
        if (priority) queued.putFirst(key, request) else queued[key] = request
        if (queued.size > MAX_QUEUED) {
            val victim = queued.entries.lastOrNull { !it.value.priority } ?: queued.lastEntry()
            queued.remove(victim.key)
            if (chunks[victim.key] === victim.value.marker) chunks[victim.key] = SourceChunk(pending = false)
        }
        dispatchNext()
    }

    private fun dispatchNext() {
        if (!registered || inFlight != null || dispatching) return
        dispatching = true
        try {
            while (registered && inFlight == null && queued.isNotEmpty()) {
                val request = queued.pollFirstEntry().value
                if (request.world != world() || !request.chunk.isLoaded || chunks[request.key] !== request.marker) {
                    chunks.remove(request.key, request.marker)
                    continue
                }
                inFlight = request
                val future = try {
                    work.compute { request.layout.sourceOresInChunk(request.x, request.z).filterValues(SOURCE_ORES::contains) }
                } catch (failure: Throwable) {
                    completeIndex(request, null, failure)
                    continue
                }
                future.whenComplete { ores, failure ->
                    if (!registered) return@whenComplete
                    work.onMain { completeIndex(request, ores, failure) }.whenComplete { _, nativeFailure ->
                        if (nativeFailure != null && registered)
                            plugin.logger.log(Level.WARNING, "Apocalypse source ore index publication failed: chunk=${request.x},${request.z}", nativeFailure)
                    }
                }
            }
        } finally { dispatching = false }
    }

    private fun completeIndex(request: SourceRequest, ores: Map<ApocalypsePoint, String>?, failure: Throwable?) {
        if (inFlight !== request) return
        inFlight = null
        if (registered && request.world == world() && chunks[request.key] === request.marker && request.chunk.isLoaded) {
            if (failure != null) failedIndex(request.key, request.chunk, failure)
            else try {
                repairApocalypseColumbite(request.chunk, requireNotNull(ores), columbiteRepaired)
                chunks[request.key] = SourceChunk(ores, pending = false)
            } catch (repairFailure: Throwable) { failedIndex(request.key, request.chunk, repairFailure) }
        }
        dispatchNext()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onUnload(event: ChunkUnloadEvent) {
        if (event.world == world()) {
            val key = chunkKey(event.chunk.x, event.chunk.z)
            chunks.remove(key)
            queued.remove(key)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val target = world() ?: return
        val block = event.block
        if (!registered || block.world != target) return
        val point = ApocalypsePoint(block.x, block.y, block.z)
        val key = chunkKey(block.x shr 4, block.z shr 4)
        val source = chunks[key]
        val ores = source?.ores
        if (ores == null) {
            if (block.type in POSSIBLE_CARRIERS) event.isCancelled = true
            return
        }
        val ore = ores[point] ?: return
        val expectedState = expectedStates.getValue(ore)
        if (block.blockData != expectedState) return
        if (!active(event.player)) { event.isCancelled = true; return }
        val id = "source-ore:${point.x}:${point.y}:${point.z}"
        if (claimed(id)) return
        event.isCancelled = true
        val tool = event.player.inventory.itemInMainHand
        val tools = if (ore == COLUMBITE) DIAMOND_PICKAXES else PICKAXES
        if (tool.type !in tools) { requiredTool(event.player, ore); return }
        val fortune = tool.getEnchantmentLevel(Enchantment.FORTUNE).coerceAtLeast(0)
        val drops = if (ore == TUNGSTEN_ORE) listOf(dropFactory(event.player, "the_deeper_void:raw_tungsten", fortuneMultiplier(fortune)))
        else buildList {
            add(dropFactory(event.player, "minecraft:raw_iron", (1 + random.nextInt(2)) * fortuneMultiplier(fortune)))
            if (random.nextInt(2) == 0) add(dropFactory(event.player, "the_deeper_void:rhenium", fortuneMultiplier(fortune)))
        }
        val acquired = chunkTickets.acquire(block.chunk)
        val lease = (acquired as? PaperChunkTicketAcquireResult.Acquired)?.lease
        if (lease == null) {
            if (acquired is PaperChunkTicketAcquireResult.Failed)
                plugin.logger.log(Level.WARNING, "Apocalypse source ore chunk retention failed: id=$id", acquired.failure)
            return
        }
        leases += lease
        val committed = runCatching { claim(id) }.getOrElse { CompletableFuture.failedFuture(it) }
        // Pending means admitted. Charge this held tool before ACK/quit; a later journal failure does not refund wear.
        val admitted = runCatching { committed.getNow(true) }.getOrDefault(false)
        // Paper's damage API forces past the native Creative exemption, while retaining Unbreaking and item events.
        if (admitted && event.player.gameMode != GameMode.CREATIVE) runCatching { damageHandTool(event.player) }.onFailure { failure ->
            plugin.logger.log(Level.WARNING, "Apocalypse source ore tool wear failed: id=$id player=${event.player.uniqueId}", failure)
        }
        committed.whenComplete { accepted, failure ->
            if (!registered) return@whenComplete // close already releases this generation's leases.
            work.onMain {
                try {
                    if (failure != null) plugin.logger.log(Level.WARNING, "Apocalypse source ore claim failed: id=$id", failure)
                    else if (accepted == true && registered && lease in leases && world() == target && chunks[key] === source &&
                        target.isChunkLoaded(point.x shr 4, point.z shr 4)) {
                        val current = target.getBlockAt(point.x, point.y, point.z)
                        if (current.blockData == expectedState) {
                            current.setType(Material.AIR, false)
                            drops.forEach { target.dropItemNaturally(current.location.add(.5, .5, .5), it) }
                        }
                    }
                } finally { release(lease) }
            }.whenComplete { _, nativeFailure ->
                if (nativeFailure != null && registered) {
                    plugin.logger.log(Level.WARNING, "Apocalypse source ore fulfillment failed: id=$id", nativeFailure)
                    work.cleanupNative { release(lease); true }
                }
            }
        }
    }

    private fun fortuneMultiplier(level: Int): Int = if (level == 0) 1 else 1 + maxOf(0, random.nextInt(level + 2) - 1)

    private fun failedIndex(key: Long, chunk: Chunk, failure: Throwable) {
        chunks[key] = SourceChunk(pending = false)
        plugin.logger.log(Level.WARNING, "Apocalypse source ore index failed: world=${chunk.world.name} chunk=${chunk.x},${chunk.z}", failure)
    }

    private fun release(lease: PaperChunkTicketLease) {
        if (!leases.remove(lease)) return
        runCatching(lease::close).onFailure { failure ->
            plugin.logger.log(Level.WARNING, "Apocalypse source ore chunk release failed: chunk=${lease.key}", failure)
        }
    }

    override fun close() {
        registered = false
        HandlerList.unregisterAll(this)
        chunks.clear()
        queued.clear()
        inFlight = null
        leases.toList().forEach(::release)
    }

    companion object {
        private const val TUNGSTEN_ORE = "the_deeper_void:tungsten_ore"
        private const val COLUMBITE = "the_deeper_void:columbite"
        private const val MAX_QUEUED = 512
        private val SOURCE_ORES = setOf(TUNGSTEN_ORE, COLUMBITE)
        private val POSSIBLE_CARRIERS = setOf(Material.DEEPSLATE_IRON_ORE, Material.DEEPSLATE_EMERALD_ORE, Material.POLISHED_DEEPSLATE)
        private val DIAMOND_PICKAXES = setOf(Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE)
        private val PICKAXES = setOf(Material.IRON_PICKAXE, Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE)
        private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) xor (z.toLong() and 0xffffffffL)
    }
}
