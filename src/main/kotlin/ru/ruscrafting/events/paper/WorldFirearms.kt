package ru.ruscrafting.events.paper

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.core.ScheduledTask
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmId
import java.util.UUID
import java.util.logging.Level

internal data class WorldFirearmState(val id: FirearmId, val loaded: Int)

/** Owns ordinary ItemsAdder guns; event-tagged items remain exclusively with their match. */
class WorldFirearms(
    private val plugin: Plugin,
    private val firearms: TttFirearms,
    private val locale: ArcEventsLocale,
    private val scopes: TttScopes,
    private val itemIdentity: (ItemStack) -> String?,
    private val allowed: (Player) -> Boolean,
    private val canTarget: (Player, Entity) -> Boolean,
    private val shots: FirearmShots,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val reloadSounds: () -> Boolean = { true },
) : AutoCloseable {
    private class Reload(
        val worldId: UUID,
        val slot: Int,
        val snapshot: ItemStack,
        val state: WorldFirearmState,
    ) {
        var task: ScheduledTask? = null
        var presentation: AutoCloseable? = null
    }

    private val eventItemKey = NamespacedKey(plugin, "event_item")
    private val firearmKey = NamespacedKey(plugin, "world_firearm")
    private val loadedKey = NamespacedKey(plugin, "world_loaded_rounds")
    private val vanillaAmmunition = ItemStack.of(Material.IRON_NUGGET)
    private val reloads = mutableMapOf<UUID, Reload>()
    private val controls = FirearmControls(shots, clock)
    private var closed = false

    /** Air interaction is natively pre-cancelled, so it must still reach the firing path. */
    fun interact(event: PlayerInteractEvent): Boolean {
        if (closed || event.hand != EquipmentSlot.HAND ||
            event.action !in setOf(Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK) ||
            firearmId(event.player.inventory.itemInMainHand) == null
        ) return false
        val denied = event.action == Action.LEFT_CLICK_BLOCK && event.useItemInHand() == Event.Result.DENY
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        if (!denied) fire(event.player)
        return true
    }

    /** A packet attack replaces native melee; synthetic Bukkit damage does not re-enter here. */
    fun attack(event: PrePlayerAttackEntityEvent): Boolean {
        if (closed || firearmId(event.player.inventory.itemInMainHand) == null) return false
        val denied = event.isCancelled
        event.isCancelled = true
        if (!denied) fire(event.player)
        return true
    }

    fun swap(event: PlayerSwapHandItemsEvent): Boolean {
        if (closed || firearmId(event.player.inventory.itemInMainHand) == null) return false
        val denied = event.isCancelled
        event.isCancelled = true
        if (!denied) reload(event.player)
        return true
    }

    internal fun state(item: ItemStack?): WorldFirearmState? {
        val id = firearmId(item) ?: return null
        if (item!!.amount != 1) return null
        val pdc = item.itemMeta.persistentDataContainer
        if (!pdc.has(firearmKey) && !pdc.has(loadedKey)) return WorldFirearmState(id, 0)
        if (pdc.get(firearmKey, PersistentDataType.STRING) != id.name) return null
        val loaded = pdc.get(loadedKey, PersistentDataType.INTEGER) ?: return null
        return WorldFirearmState(id, loaded).takeIf { loaded in 0..baseSpec(item).magazineSize }
    }

    internal fun ammoHudState(player: Player): FirearmAmmoHudState? {
        val held = player.inventory.itemInMainHand
        val state = state(held) ?: return null
        if (!eligible(player)) return null
        val spec = baseSpec(held)
        return FirearmAmmoHudState(
            loaded = state.loaded,
            capacity = spec.magazineSize,
            reserve = reserve(player.inventory.storageContents),
            creative = player.gameMode == GameMode.CREATIVE,
            reloading = player.uniqueId in reloads || scopes.isReloading(player.uniqueId),
            zoom = scopes.projectedZoom(player.uniqueId) ?: 1f,
            burstSize = firearms.selectedBurst(held, spec),
        )
    }

    private fun firearmId(item: ItemStack?): FirearmId? {
        if (item == null || item.isEmpty || item.itemMeta.persistentDataContainer.has(eventItemKey)) return null
        return scopePreviewFirearm(itemIdentity(item)) ?: item.itemMeta.persistentDataContainer
            .get(firearmKey, PersistentDataType.STRING)?.let { runCatching { FirearmId.valueOf(it) }.getOrNull() }
    }

    private fun eligible(player: Player): Boolean = !closed && player.isOnline && !player.isDead &&
        player.gameMode in setOf(GameMode.SURVIVAL, GameMode.ADVENTURE, GameMode.CREATIVE) && allowed(player)

    private fun authorizedState(player: Player): WorldFirearmState? {
        if (!eligible(player)) {
            cancel(player.uniqueId)
            scopes.clear(player.uniqueId)
            feedback(player, "world-unavailable-actionbar")
            return null
        }
        val held = player.inventory.itemInMainHand
        if (held.amount != 1) {
            feedback(player, "world-stacked-actionbar")
            return null
        }
        val state = state(held) ?: run {
            feedback(player, "world-invalid-actionbar")
            return null
        }
        // Normalize before a reload captures its exact item snapshot, never during that lease.
        if (held.maxStackSize != 1) player.inventory.setItemInMainHand(singleFirearm(held))
        return state
    }

    private fun fire(player: Player): Boolean {
        val state = authorizedState(player) ?: return false
        val playerId = player.uniqueId
        if (player.gameMode == GameMode.CREATIVE) {
            reloads.remove(playerId)?.let { it.task?.cancel(); it.presentation?.close() }
        }
        if (playerId in reloads) {
            feedback(player, "reloading-actionbar")
            return false
        }
        scopes.cancelReload(playerId)
        val held = player.inventory.itemInMainHand
        val spec = spec(held)
        val worldId = player.world.uid
        val valid = { eligible(player) && player.world.uid == worldId }
        val target: (Entity) -> Boolean = { entity ->
            entity is LivingEntity && entity.uniqueId != playerId && entity.isValid && !entity.isDead &&
                entity.world.uid == worldId &&
                (entity !is Player || (entity.isOnline && entity.gameMode != GameMode.SPECTATOR))
        }
        return controls.fire(player, spec, firearms.selectedBurst(held, spec), state.loaded,
            scopes.isAiming(playerId), valid, target,
            withLoaded = { item, rounds -> withLoaded(item, state.id, rounds, player) },
            hasReserve = { reserve(player.inventory.storageContents) > 0 },
            reload = { reload(player) }, empty = { feedback(player, "world-empty-actionbar") },
            busy = { feedback(player, "world-busy-actionbar") }) { entity, _, amount ->
            if (amount.isFinite() && amount > 0.0 && valid() && target(entity) &&
                (entity !is Player || entity.world.pvp) && canTarget(player, entity)
            ) {
                // Preserve native armour, hurt immunity and cancellable region/team protection listeners.
                (entity as LivingEntity).damage(amount, player)
            }
        }
    }

    private fun reload(player: Player): Boolean {
        controls.cancel(player.uniqueId)
        val state = authorizedState(player) ?: return false
        if (player.gameMode == GameMode.CREATIVE) {
            cancel(player.uniqueId)
            scopes.clear(player.uniqueId)
            val snapshot = player.inventory.itemInMainHand.clone()
            val slot = player.inventory.heldItemSlot
            val worldId = player.world.uid
            scopes.animateReload(player, scopeTarget(snapshot, slot), spec(player.inventory.itemInMainHand).reloadTicks,
                sounds = reloadSounds(), stillCurrent = {
                    eligible(player) && player.gameMode == GameMode.CREATIVE && player.world.uid == worldId &&
                        player.inventory.heldItemSlot == slot && player.inventory.itemInMainHand == snapshot
                })
            player.sendEventActionBar(locale.render("weapon.creative-ammo-actionbar", player, mapOf(
                "weapon" to (player.inventory.itemInMainHand.itemMeta.displayName() ?: locale.render("weapon.${state.id.name.lowercase()}-name", player)),
            )))
            return true
        }
        if (player.uniqueId in reloads) {
            feedback(player, "reloading-actionbar")
            return false
        }
        scopes.clear(player.uniqueId)
        val spec = spec(player.inventory.itemInMainHand)
        if (state.loaded >= spec.magazineSize) {
            feedback(player, "magazine-full-actionbar")
            return false
        }
        if (reserve(player.inventory.storageContents) == 0) {
            feedback(player, "world-no-ammo-actionbar")
            return false
        }
        val pending = Reload(player.world.uid, player.inventory.heldItemSlot,
            player.inventory.itemInMainHand.clone(), state)
        reloads[player.uniqueId] = pending
        try {
            pending.task = Tasks.scheduler.runLater(spec.reloadTicks.toLong()) { finishReload(player, pending) }
        } catch (failure: Exception) {
            reloads.remove(player.uniqueId, pending)
            plugin.logger.log(Level.WARNING,
                "World firearm reload scheduling failed player=${player.uniqueId} firearm=${state.id}", failure)
            feedback(player, "world-busy-actionbar")
            return false
        }
        pending.presentation = scopes.animateReload(player, scopeTarget(pending.snapshot, pending.slot), spec.reloadTicks,
            sounds = reloadSounds(), stillCurrent = {
                reloads[player.uniqueId] === pending && eligible(player) && player.gameMode != GameMode.CREATIVE &&
                    player.world.uid == pending.worldId && player.inventory.heldItemSlot == pending.slot &&
                    player.inventory.itemInMainHand == pending.snapshot
            })
        feedback(player, "reloading-actionbar")
        return true
    }

    private fun finishReload(player: Player, pending: Reload) {
        if (!reloads.remove(player.uniqueId, pending)) return
        try {
            completeReload(player, pending)
        } finally {
            pending.presentation?.close()
        }
    }

    private fun completeReload(player: Player, pending: Reload) {
        if (!eligible(player) || player.gameMode == GameMode.CREATIVE || player.world.uid != pending.worldId ||
            player.inventory.heldItemSlot != pending.slot
        ) return
        val contents = player.inventory.storageContents
        val held = contents.getOrNull(pending.slot) ?: return
        if (held != pending.snapshot || state(held) != pending.state) return
        val spec = spec(pending.snapshot)
        val consumed = minOf(spec.magazineSize - pending.state.loaded, reserve(contents))
        if (consumed <= 0) return
        val loaded = pending.state.loaded + consumed
        val replacement = withLoaded(held, pending.state.id, loaded, player)
        var remaining = consumed
        for (slot in contents.indices) {
            if (remaining == 0) break
            val stack = contents[slot]?.takeIf(::usableAmmo) ?: continue
            val take = minOf(stack.amount, remaining)
            contents[slot] = if (take == stack.amount) null else stack.clone().also { it.amount -= take }
            remaining -= take
        }
        contents[pending.slot] = replacement
        // One owning-thread inventory write commits both reserve consumption and the cloned gun.
        player.inventory.storageContents = contents
        player.sendEventActionBar(locale.render("weapon.reload-complete-actionbar", player, mapOf(
            "loaded" to locale.text(loaded),
            "magazine" to locale.text(spec.magazineSize),
            "reserve" to locale.text(reserve(contents)),
        )))
    }

    private fun withLoaded(item: ItemStack, id: FirearmId, loaded: Int, player: Player): ItemStack = item.clone().also { replacement ->
        check(replacement.editMeta { meta ->
            meta.persistentDataContainer.set(firearmKey, PersistentDataType.STRING, id.name)
            meta.persistentDataContainer.set(loadedKey, PersistentDataType.INTEGER, loaded)
        }) { "Ordinary firearm metadata could not be updated: $id" }
        if (itemIdentity(item)?.startsWith("arcevents:world_") == true) {
            firearms.refreshWorldLore(item, replacement, player, firearms.spec(id))
        }
    }

    private fun usableAmmo(item: ItemStack): Boolean =
        item.isSimilar(vanillaAmmunition) || firearms.isWorldAmmunition(item) ||
            (!item.itemMeta.persistentDataContainer.has(eventItemKey) && itemIdentity(item) == "arc:firearm_ammo")

    private fun baseSpec(item: ItemStack) = packedFirearms[itemIdentity(item)]?.let { it.spec(firearms.spec(it.profile)) }
        ?: firearms.spec(requireNotNull(firearmId(item)))

    private fun spec(item: ItemStack) = firearms.tunedSpec(item, baseSpec(item))

    internal fun editableSpec(player: Player) = player.inventory.itemInMainHand.let { held ->
        if (eligible(player) && state(held) != null) baseSpec(held) else null
    }


    private fun scopeTarget(item: ItemStack, slot: Int) = itemIdentity(item)?.let { packedScopeTarget(it, slot) }
        ?: FirearmScopeTarget(null, requireNotNull(firearmId(item)), slot)

    internal fun scopeTarget(player: Player): FirearmScopeTarget? {
        val held = player.inventory.itemInMainHand
        return if (eligible(player) && state(held) != null && player.uniqueId !in reloads) {
            scopeTarget(held, player.inventory.heldItemSlot)
        } else null
    }

    private fun reserve(contents: Array<ItemStack?>): Int = contents.sumOf { item ->
        if (item != null && usableAmmo(item)) item.amount else 0
    }

    private fun feedback(player: Player, key: String) = player.sendEventActionBar(locale.render("weapon.$key", player))

    /** Inventory/scope changes cancel reloads without resetting the cross-weapon cooldown. */
    fun cancel(playerId: UUID) {
        controls.cancel(playerId)
        reloads.remove(playerId)?.let { pending ->
            pending.task?.cancel()
            pending.presentation?.close()
        }
        scopes.cancelReload(playerId)
    }

    fun forget(playerId: UUID) {
        cancel(playerId)
        controls.forget(playerId)
    }

    override fun close() {
        closed = true
        reloads.values.forEach {
            it.task?.cancel()
            it.presentation?.close()
        }
        reloads.clear()
        controls.clear()
    }
}
