package ru.ruscrafting.events.paper.apocalypse

import org.bukkit.Location
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Nameable
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Furnace
import org.bukkit.block.TileState
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import org.bukkit.event.inventory.FurnaceSmeltEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchStation
import ru.ruscrafting.events.paper.EventWork
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level

/** Native block entities carry station identity; no coordinate registry survives a changed block. */
internal class ApocalypseWorkbenchStations(
    private val plugin: Plugin,
    private val world: () -> World?,
    private val active: (Player) -> Boolean,
) : Listener, AutoCloseable {
    private val stationKey = stationKey(plugin)
    private val sourceItemKey = NamespacedKey(plugin, "apocalypse_source_item")
    private val tungstenKey = NamespacedKey(plugin, "apocalypse_tungsten_blasting")
    private var registered = false
    private var tungstenRecipe: BlastingRecipe? = null
    private data class CarrierDrop(val world: UUID, val point: ApocalypsePoint, val station: ApocalypseWorkbenchStation, val native: ItemStack?)
    private val carrierDrops = mutableMapOf<UUID, CarrierDrop>()

    fun register() {
        check(!registered)
        plugin.server.pluginManager.registerEvents(this, plugin)
        registered = true
    }

    fun registerTungstenBlasting(rawTungsten: ItemStack, tungstenIngot: ItemStack) {
        check(registered && tungstenRecipe == null)
        require(rawTungsten.amount == 1 && tungstenIngot.amount == 1)
        require(rawTungsten.itemMeta.persistentDataContainer.get(sourceItemKey, PersistentDataType.STRING) == "the_deeper_void:raw_tungsten")
        require(tungstenIngot.itemMeta.persistentDataContainer.get(sourceItemKey, PersistentDataType.STRING) == "the_deeper_void:tungsten_ingot")
        val recipe = BlastingRecipe(tungstenKey, tungstenIngot.clone(), RecipeChoice.ExactChoice(rawTungsten.clone()), 2f, 240)
        check(plugin.server.addRecipe(recipe)) { "Could not register apocalypse tungsten blasting recipe: $tungstenKey" }
        tungstenRecipe = recipe
    }

    fun stationAt(block: Block): ApocalypseWorkbenchStation? {
        if (block.world != world()) return null
        return if (block.type == Material.CRAFTING_TABLE) ApocalypseWorkbenchStation.CRAFTING else station(block.state)
    }

    fun loadedStations(chunk: Chunk): Map<ApocalypsePoint, ApocalypseWorkbenchStation> {
        if (chunk.world != world() || !chunk.isLoaded) return emptyMap()
        return buildMap {
            chunk.getTileEntities({ it.type == Material.DROPPER }, false).forEach { state ->
                station(state)?.let { put(ApocalypsePoint(state.x, state.y, state.z), it) }
            }
        }
    }

    /** Ordinary crafting tables have no block entity. Scan only a captured, palette-positive chunk off-thread. */
    fun findCraftingTables(chunk: Chunk, work: EventWork): CompletableFuture<List<ApocalypsePoint>?> {
        val bottom = chunk.world.minHeight
        val top = chunk.world.maxHeight
        val snapshot = chunk.getChunkSnapshot(false, false, false, false)
        return work.compute<List<ApocalypsePoint>?> {
            buildList {
                for (base in bottom until top step 16) {
                    if (snapshot.isSectionEmpty((base - bottom) shr 4)) continue
                    for (y in base until minOf(base + 16, top)) for (z in 0..15) for (x in 0..15) {
                        if (snapshot.getBlockType(x, y, z) == Material.CRAFTING_TABLE)
                            add(ApocalypsePoint((snapshot.x shl 4) + x, y, (snapshot.z shl 4) + z))
                    }
                }
            }
        }.exceptionally { failure ->
            plugin.logger.log(Level.WARNING, "Apocalypse crafting table scan failed in ${snapshot.worldName} chunk ${snapshot.x},${snapshot.z}", failure)
            null
        }
    }

    fun isUsable(player: Player, anchor: ApocalypsePoint, expected: ApocalypseWorkbenchStation): Boolean {
        val targetWorld = world() ?: return false
        if (!registered || player.world != targetWorld || !active(player) || player.isDead) return false
        if (anchor.y !in targetWorld.minHeight until targetWorld.maxHeight) return false
        val at = Location(targetWorld, anchor.x + .5, anchor.y + .5, anchor.z + .5)
        if (player.location.distanceSquared(at) > 36 || !targetWorld.isChunkLoaded(anchor.x shr 4, anchor.z shr 4)) return false
        return stationAt(targetWorld.getBlockAt(anchor.x, anchor.y, anchor.z)) == expected
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (event.block.world != world()) return
        val source = itemSource(event.itemInHand, stationKey) ?: return
        if (!active(event.player)) { event.isCancelled = true; return }
        val placed = event.block.state as? TileState
        if (event.block.type != Material.DROPPER || placed == null) { event.isCancelled = true; return }
        placed.persistentDataContainer.set(stationKey, PersistentDataType.STRING, source)
        if (placed is Nameable) placed.customName(event.itemInHand.itemMeta.displayName())
        orientApocalypseWorkbench(placed, event.player)
        if (!placed.update(false, false)) {
            event.isCancelled = true
            plugin.logger.warning("Apocalypse workstation placement failed: world=${event.block.world.name} x=${event.block.x} y=${event.block.y} z=${event.block.z} source=$source")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        carrierDrops.remove(event.player.uniqueId)
        val block = event.block
        if (block.world != world() || block.type != Material.DROPPER) return
        val state = block.state
        val station = station(state) ?: return
        if (!active(event.player)) { event.isCancelled = true; return }
        if (!event.isDropItems || event.player.gameMode == GameMode.CREATIVE) return
        // getDrops needs the live block entity for its name/components; the post-break snapshot cannot supply it.
        val native = try {
            state.getDrops(event.player.inventory.itemInMainHand, event.player).firstOrNull { it.type == Material.DROPPER }?.clone()
        } catch (failure: Throwable) {
            event.isCancelled = true
            plugin.logger.log(Level.WARNING, "Apocalypse workstation drop capture failed: world=${block.world.name} x=${block.x} y=${block.y} z=${block.z}", failure)
            return
        }
        carrierDrops[event.player.uniqueId] = CarrierDrop(block.world.uid, ApocalypsePoint(block.x, block.y, block.z), station, native)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun afterBreak(event: BlockBreakEvent) {
        if (event.isCancelled || !event.isDropItems) carrierDrops.remove(event.player.uniqueId)
    }

    @EventHandler fun onQuit(event: PlayerQuitEvent) { carrierDrops.remove(event.player.uniqueId) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: BlockDropItemEvent) {
        val captured = carrierDrops.remove(event.player.uniqueId)
        if (event.block.world != world()) return
        val station = station(event.blockState) ?: return
        val point = ApocalypsePoint(event.block.x, event.block.y, event.block.z)
        if (captured == null || captured.world != event.block.world.uid || captured.point != point || captured.station != station) return
        val item = stationItem(plugin, station, station.sourceId)
        (event.blockState as? Nameable)?.customName()?.let { name -> item.editMeta { it.displayName(name) } }
        // The native event also includes container contents and dependent blocks. Replace only the carrier's drop.
        val carrier = captured.native?.let { drop -> event.items.firstOrNull { it.itemStack.isSimilar(drop) } }
        if (captured.native != null && carrier == null) {
            plugin.logger.warning("Apocalypse workstation native drop changed: world=${event.block.world.name} x=${point.x} y=${point.y} z=${point.z}")
            return
        }
        if (carrier != null && carrier.itemStack.amount == 1) carrier.itemStack = item
        else {
            if (carrier != null) carrier.itemStack = carrier.itemStack.clone().also { it.amount-- }
            event.items += event.block.world.createEntity(event.blockState.location.add(.5, .5, .5), Item::class.java)
                .also { it.itemStack = item }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) { if (stationInventory(event.inventory)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) { if (stationInventory(event.view.topInventory)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) { if (stationInventory(event.view.topInventory)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onMove(event: InventoryMoveItemEvent) {
        if (stationInventory(event.source) || stationInventory(event.destination)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) { if (customStation(event.block)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplosion(event: BlockExplodeEvent) { event.blockList().removeIf(::customStation) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplosion(event: EntityExplodeEvent) { event.blockList().removeIf(::customStation) }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onExtend(event: BlockPistonExtendEvent) { if (event.blocks.any(::customStation)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onRetract(event: BlockPistonRetractEvent) { if (event.blocks.any(::customStation)) event.isCancelled = true }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBurn(event: FurnaceBurnEvent) {
        val recipe = tungstenRecipe ?: return
        val input = (event.block.state as? Furnace)?.inventory?.smelting ?: return
        if (!rawTungsten(input)) return
        if (event.block.world != world() || event.block.type != Material.BLAST_FURNACE || !recipe.inputChoice.test(input)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSmelt(event: FurnaceSmeltEvent) {
        val recipe = tungstenRecipe ?: return
        if (!rawTungsten(event.source) && event.recipe?.key != tungstenKey) return
        // ExactChoice includes presentation metadata. Reject a renamed source rather than let vanilla turn it into iron.
        if (event.block.world != world() || event.block.type != Material.BLAST_FURNACE ||
            event.recipe?.key != tungstenKey || !recipe.inputChoice.test(event.source)) event.isCancelled = true
    }

    private fun station(state: BlockState): ApocalypseWorkbenchStation? {
        if (state.type != Material.DROPPER || state !is TileState) return null
        val source = state.persistentDataContainer.get(stationKey, PersistentDataType.STRING) ?: return null
        return ApocalypseWorkbenchStation.fromSource(source)?.takeUnless { it == ApocalypseWorkbenchStation.CRAFTING }
    }

    private fun customStation(block: Block): Boolean = block.world == world() && block.type == Material.DROPPER && station(block.state) != null
    private fun stationInventory(inventory: Inventory): Boolean = inventory.location?.block?.let(::customStation) == true
    private fun rawTungsten(item: ItemStack): Boolean =
        item.itemMeta?.persistentDataContainer?.get(sourceItemKey, PersistentDataType.STRING) == "the_deeper_void:raw_tungsten"

    override fun close() {
        registered = false
        HandlerList.unregisterAll(this)
        carrierDrops.clear()
        if (tungstenRecipe != null) plugin.server.removeRecipe(tungstenKey)
        tungstenRecipe = null
    }

    companion object {
        private fun stationKey(plugin: Plugin) = NamespacedKey(plugin, "apocalypse_workbench_source")

        fun stationOf(item: ItemStack, plugin: Plugin): ApocalypseWorkbenchStation? =
            itemSource(item, stationKey(plugin))?.let(ApocalypseWorkbenchStation::fromSource)

        fun stationItem(plugin: Plugin, station: ApocalypseWorkbenchStation, sourceId: String): ItemStack {
            require(station.sourceId == sourceId)
            if (station == ApocalypseWorkbenchStation.CRAFTING) return ItemStack.of(Material.CRAFTING_TABLE)
            val item = ItemStack.of(Material.DROPPER)
            item.editMeta(BlockStateMeta::class.java) { meta ->
                val state = meta.blockState as TileState
                state.persistentDataContainer.set(stationKey(plugin), PersistentDataType.STRING, sourceId)
                meta.blockState = state
                val itemId = when (station) {
                    ApocalypseWorkbenchStation.T1, ApocalypseWorkbenchStation.AMMO -> "tacz:workbench_a"
                    ApocalypseWorkbenchStation.T3 -> "tacz:workbench_b"
                    else -> sourceId
                }
                meta.persistentDataContainer.set(NamespacedKey(plugin, "apocalypse_source_item"), PersistentDataType.STRING, itemId)
                if (itemId != sourceId) meta.persistentDataContainer.set(NamespacedKey(plugin, "apocalypse_source_block"), PersistentDataType.STRING, sourceId)
            }
            return item
        }

        private fun itemSource(item: ItemStack, key: NamespacedKey): String? {
            if (item.type != Material.DROPPER) return null
            val meta = item.itemMeta as? BlockStateMeta ?: return null
            if (!meta.hasBlockState()) return null
            val state = meta.blockState as? TileState ?: return null
            if (state.type != Material.DROPPER) return null
            return state.persistentDataContainer.get(key, PersistentDataType.STRING)?.takeIf { source ->
                ApocalypseWorkbenchStation.fromSource(source)?.let { it != ApocalypseWorkbenchStation.CRAFTING } == true
            }
        }
    }
}
