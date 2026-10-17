package ru.ruscrafting.events.paper

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

/** Protect a captured inventory while durable admission or exact recovery acknowledgement is pending. */
internal class EventStateProtection(private val locked: (Player) -> Boolean) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun command(event: PlayerCommandPreprocessEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun experience(event: PlayerPickupExperienceEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun click(event: InventoryClickEvent) { if ((event.whoClicked as? Player)?.let(locked) == true) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drag(event: InventoryDragEvent) { if ((event.whoClicked as? Player)?.let(locked) == true) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun drop(event: PlayerDropItemEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun pickup(event: EntityPickupItemEvent) { if ((event.entity as? Player)?.let(locked) == true) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun swap(event: PlayerSwapHandItemsEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun consume(event: PlayerItemConsumeEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun itemDamage(event: PlayerItemDamageEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST)
    fun interact(event: PlayerInteractEvent) { if (locked(event.player)) {
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
    } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun entity(event: PlayerInteractEntityEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun armorStand(event: PlayerArmorStandManipulateEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun breakBlock(event: BlockBreakEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun placeBlock(event: BlockPlaceEvent) { if (locked(event.player)) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun damage(event: EntityDamageEvent) { if ((event.entity as? Player)?.let(locked) == true) event.isCancelled = true }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun food(event: FoodLevelChangeEvent) { if ((event.entity as? Player)?.let(locked) == true) event.isCancelled = true }
}
