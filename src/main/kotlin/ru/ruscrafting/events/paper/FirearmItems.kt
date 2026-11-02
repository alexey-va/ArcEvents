package ru.ruscrafting.events.paper

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import java.util.UUID

/** Native stack limits on real guns, including ItemsAdder items issued by another plugin. */
internal class FirearmItems(
    private val isFirearm: (ItemStack) -> Boolean,
    private val allowed: (Player) -> Boolean = { true },
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val pending = mutableSetOf<UUID>()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSlot(event: PlayerInventorySlotChangeEvent) = refreshLater(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) { (event.whoClicked as? Player)?.let(::refreshLater) }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) { (event.whoClicked as? Player)?.let(::refreshLater) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) { (event.player as? Player)?.let(::refreshLater) }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawn(event: ItemSpawnEvent) {
        val item = event.entity.itemStack
        if (item.amount == 1 && isFirearm(item) && item.maxStackSize != 1) {
            event.entity.itemStack = singleFirearm(item)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMerge(event: ItemMergeEvent) {
        if (isFirearm(event.entity.itemStack) || isFirearm(event.target.itemStack)) event.isCancelled = true
    }

    fun refreshLater(player: Player) {
        if (!pending.add(player.uniqueId)) return
        tasks.runLater(1) {
            pending.remove(player.uniqueId)
            if (!player.isOnline || !allowed(player)) return@runLater
            val inventory = player.inventory
            val contents = inventory.contents
            val cursor = player.itemOnCursor
            // Include the cursor in the same plan, but put split guns only in ordinary storage slots.
            val normalized = normalizeFirearmItems(contents + arrayOf(cursor), isFirearm) ?: return@runLater
            inventory.contents = normalized.copyOf(contents.size)
            if (normalized.last() != cursor) player.setItemOnCursor(normalized.last())
        }
    }

    override fun close() {
        tasks.close()
        pending.clear()
    }
}

internal fun singleFirearm(item: ItemStack): ItemStack = item.clone().also {
    it.amount = 1
    it.editMeta { meta -> meta.setMaxStackSize(1) }
}

/** Pure bounded migration: preserve every gun and its magazine; never drop overflow or erase slots. */
internal fun normalizeFirearmItems(
    contents: Array<ItemStack?>,
    isFirearm: (ItemStack) -> Boolean,
): Array<ItemStack?>? {
    val result = contents.copyOf()
    val empty = ArrayDeque((0 until minOf(36, contents.size)).filter { contents[it]?.isEmpty != false })
    var changed = false
    contents.forEachIndexed { slot, item ->
        if (item == null || item.isEmpty || !isFirearm(item)) return@forEachIndexed
        if (item.amount == 1) {
            if (item.maxStackSize != 1) {
                result[slot] = singleFirearm(item)
                changed = true
            }
            return@forEachIndexed
        }
        var remaining = item.amount
        while (remaining > 1 && empty.isNotEmpty()) {
            result[empty.removeFirst()] = singleFirearm(item)
            remaining--
        }
        if (remaining != item.amount) {
            // A full inventory keeps its remaining legacy stack until space is freed. No item is lost.
            result[slot] = if (remaining == 1) singleFirearm(item) else item.clone().also { it.amount = remaining }
            changed = true
        }
    }
    return result.takeIf { changed }
}
