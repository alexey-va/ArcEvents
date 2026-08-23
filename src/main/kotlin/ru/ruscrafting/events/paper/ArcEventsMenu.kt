package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID

sealed interface EventsView {
    data object Main : EventsView
    data object Help : EventsView
    data object Admin : EventsView
    data object Shop : EventsView
}

class ArcEventsMenu(
    private val service: ArcEventsService,
    private val items: TttItems,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val reload: () -> Result<Unit>,
) {
    private class Holder(val view: EventsView) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    private val pendingClicks = mutableSetOf<UUID>()

    fun open(player: Player, view: EventsView = EventsView.Main) {
        when (view) {
            EventsView.Main -> openMain(player)
            EventsView.Help -> openHelp(player)
            EventsView.Admin -> openAdmin(player)
            EventsView.Shop -> openShop(player)
        }
    }

    fun isMenu(inventory: Inventory): Boolean = inventory.holder is Holder

    fun onClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? Holder ?: return
        event.isCancelled = true
        if (event.clickedInventory !== event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        if (!pendingClicks.add(player.uniqueId)) return
        val slot = event.rawSlot
        Tasks.scheduler.runLater(1L) {
            try {
                if (!player.isOnline || player.openInventory.topInventory.holder !== holder) return@runLater
                when (holder.view) {
                    EventsView.Main -> clickMain(player, slot)
                    EventsView.Help -> if (slot == 36) open(player, EventsView.Main) else if (slot == 44) player.closeInventory()
                    EventsView.Admin -> clickAdmin(player, slot)
                    EventsView.Shop -> clickShop(player, slot)
                }
            } finally {
                pendingClicks.remove(player.uniqueId)
            }
        }
    }

    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun openMain(player: Player) {
        val state = service.snapshot()
        val inventory = inventory(player, EventsView.Main, 54, "menu.main.title")
        inventory.setItem(4, item(Material.SPYGLASS, player, "menu.main.ttt-name", "menu.main.ttt-lore", mapOf(
            "queue" to locale.text(state.queueSize),
            "minimum" to locale.text(settings().ttt.minimumPlayers),
            "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player),
        )))
        inventory.setItem(20, item(Material.LIME_DYE, player, "menu.main.join-name", "menu.main.join-lore"))
        inventory.setItem(24, item(Material.RED_DYE, player, "menu.main.leave-name", "menu.main.leave-lore"))
        inventory.setItem(22, item(Material.RECOVERY_COMPASS, player, "menu.main.status-name", "menu.main.status-lore", mapOf(
            "server" to locale.text(state.serverId),
            "node_mode" to locale.text(state.nodeMode.name),
            "host" to locale.text(state.hostServer),
            "state" to locale.render(if (state.hostAvailable) "state.network-ready" else "state.network-degraded", player),
        )))
        val stats = service.stats(player.uniqueId)
        inventory.setItem(30, item(Material.WRITABLE_BOOK, player, "menu.main.stats-name", "menu.main.stats-lore", mapOf(
            "matches" to locale.text(stats.matches),
            "wins" to locale.text(stats.wins),
            "kills" to locale.text(stats.kills),
            "deaths" to locale.text(stats.deaths),
            "karma" to locale.text(stats.karma),
        )))
        inventory.setItem(32, item(Material.KNOWLEDGE_BOOK, player, "menu.main.help-name", "menu.main.help-lore"))
        inventory.setItem(40, item(Material.NETHER_STAR, player, "menu.main.shop-name", "menu.main.shop-lore"))
        if (player.hasPermission("arcevents.admin")) {
            inventory.setItem(49, item(Material.COMMAND_BLOCK, player, "menu.main.admin-name", "menu.main.admin-lore"))
        }
        player.openInventory(inventory)
    }

    private fun clickMain(player: Player, slot: Int) {
        when (slot) {
            20 -> { player.closeInventory(); service.joinQueue(player) }
            24 -> { player.closeInventory(); service.leaveQueue(player) }
            32 -> open(player, EventsView.Help)
            40 -> open(player, EventsView.Shop)
            49 -> if (player.hasPermission("arcevents.admin")) open(player, EventsView.Admin)
        }
    }

    private fun openHelp(player: Player) {
        val inventory = inventory(player, EventsView.Help, 45, "menu.help.title")
        inventory.setItem(11, item(Material.EMERALD, player, "menu.help.innocent-name", "menu.help.innocent-lore"))
        inventory.setItem(13, item(Material.REDSTONE, player, "menu.help.traitor-name", "menu.help.traitor-lore"))
        inventory.setItem(15, item(Material.LAPIS_LAZULI, player, "menu.help.detective-name", "menu.help.detective-lore"))
        inventory.setItem(29, item(Material.PLAYER_HEAD, player, "menu.help.evidence-name", "menu.help.evidence-lore"))
        inventory.setItem(33, item(Material.COMMAND_BLOCK, player, "menu.help.controls-name", "menu.help.controls-lore"))
        inventory.setItem(36, item(Material.ARROW, player, "menu.common.back-name", "menu.common.back-lore"))
        inventory.setItem(44, item(Material.BARRIER, player, "menu.common.close-name", "menu.common.close-lore"))
        player.openInventory(inventory)
    }

    private fun openAdmin(player: Player) {
        if (!player.hasPermission("arcevents.admin")) return
        val state = service.snapshot()
        val inventory = inventory(player, EventsView.Admin, 54, "menu.admin.title")
        inventory.setItem(13, item(Material.OBSERVER, player, "menu.admin.status-name", "menu.admin.status-lore", mapOf(
            "phase" to locale.render("phase.${state.phase?.name?.lowercase() ?: "idle"}", player),
            "match" to locale.text(state.matchId ?: "—"),
            "queue" to locale.text(state.queueSize),
            "recovery" to locale.text(state.recoveryPending),
        )))
        inventory.setItem(29, item(Material.LIME_CONCRETE, player, "menu.admin.start-name", "menu.admin.start-lore"))
        inventory.setItem(31, item(Material.RED_CONCRETE, player, "menu.admin.stop-name", "menu.admin.stop-lore"))
        inventory.setItem(33, item(Material.CLOCK, player, "menu.admin.reload-name", "menu.admin.reload-lore"))
        inventory.setItem(40, item(Material.TOTEM_OF_UNDYING, player, "menu.admin.recover-name", "menu.admin.recover-lore"))
        inventory.setItem(45, item(Material.ARROW, player, "menu.common.back-name", "menu.common.back-lore"))
        inventory.setItem(53, item(Material.BARRIER, player, "menu.common.close-name", "menu.common.close-lore"))
        player.openInventory(inventory)
    }

    private fun clickAdmin(player: Player, slot: Int) {
        if (!player.hasPermission("arcevents.admin")) return
        when (slot) {
            29 -> {
                service.startFromQueue().thenAccept { result ->
                    if (!player.isOnline) return@thenAccept
                    player.sendMessage(locale.render(startMessage(result), player))
                    open(player, EventsView.Admin)
                }
            }
            31 -> {
                player.sendMessage(locale.render(stopMessage(service.stopByAdmin()), player))
                open(player, EventsView.Admin)
            }
            33 -> {
                val result = reload()
                player.sendMessage(locale.render(if (result.isSuccess) "command.reload-ok" else "command.reload-failed", player, mapOf(
                    "reason" to locale.text(result.exceptionOrNull()?.message ?: "unknown"),
                )))
                open(player, EventsView.Admin)
            }
            40 -> {
                val count = service.retryRecovery()
                player.sendMessage(locale.render("admin.recovery-started", player, mapOf("players" to locale.text(count))))
                open(player, EventsView.Admin)
            }
            45 -> open(player, EventsView.Main)
            53 -> player.closeInventory()
        }
    }

    private fun openShop(player: Player) {
        val current = service.currentMatch()
        val participant = current?.participant(player.uniqueId)
        if (current == null || participant == null) {
            player.sendMessage(locale.render("shop.unavailable", player))
            return
        }
        val inventory = inventory(player, EventsView.Shop, 45, "menu.shop.title", mapOf("credits" to locale.text(participant.credits)))
        inventory.setItem(4, item(Material.SUNFLOWER, player, "menu.shop.credits-name", "menu.shop.credits-lore", mapOf(
            "credits" to locale.text(participant.credits),
        )))
        val offers = when (participant.role) {
            TttRole.TRAITOR -> items.traitorOffers
            TttRole.DETECTIVE -> items.detectiveOffers
            TttRole.INNOCENT -> emptyList()
        }
        if (offers.isEmpty()) {
            inventory.setItem(22, item(Material.GRAY_DYE, player, "menu.shop.unavailable-name", "menu.shop.unavailable-lore"))
        } else {
            offers.zip(listOf(20, 22, 24)).forEach { (offer, slot) ->
                inventory.setItem(slot, items.offerItem(offer, player, current.matchId.toString()))
            }
        }
        inventory.setItem(36, item(Material.ARROW, player, "menu.common.back-name", "menu.common.back-lore"))
        inventory.setItem(44, item(Material.BARRIER, player, "menu.common.close-name", "menu.common.close-lore"))
        player.openInventory(inventory)
    }

    private fun clickShop(player: Player, slot: Int) {
        val participant = service.participant(player.uniqueId)
        val offers = when (participant?.role) {
            TttRole.TRAITOR -> items.traitorOffers
            TttRole.DETECTIVE -> items.detectiveOffers
            else -> emptyList()
        }
        when (slot) {
            20, 22, 24 -> offers.getOrNull(listOf(20, 22, 24).indexOf(slot))?.let { service.buy(player, it); open(player, EventsView.Shop) }
            36 -> open(player, EventsView.Main)
            44 -> player.closeInventory()
        }
    }

    private fun inventory(
        player: Player,
        view: EventsView,
        size: Int,
        titleKey: String,
        values: Map<String, Component> = emptyMap(),
    ): Inventory {
        val holder = Holder(view)
        val inventory = Bukkit.createInventory(holder, size, locale.render(titleKey, player, values))
        holder.backing = inventory
        val fillerSettings = settings().ui.filler
        val material = Material.matchMaterial(fillerSettings.material)?.takeIf(Material::isItem) ?: Material.BLACK_STAINED_GLASS_PANE
        val filler = ItemStack.of(material).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(Component.empty().decoration(TextDecoration.ITALIC, false))
                if (fillerSettings.customModelData > 0) {
                    val model = meta.customModelDataComponent
                    model.floats = listOf(fillerSettings.customModelData.toFloat())
                    meta.setCustomModelDataComponent(model)
                }
            }
        }
        repeat(size) { inventory.setItem(it, filler) }
        return inventory
    }

    private fun item(
        material: Material,
        player: Player,
        nameKey: String,
        loreKey: String,
        values: Map<String, Component> = emptyMap(),
    ): ItemStack = ItemStack.of(material).also { stack ->
        stack.editMeta { meta ->
            meta.displayName(TttItems.nonItalic(locale.render(nameKey, player, values)))
            meta.lore(locale.lore(loreKey, player, values).map(TttItems::nonItalic))
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
        }
    }

    private fun startMessage(result: ReservationStartResult): String = when (result) {
        ReservationStartResult.STARTED -> "admin.started"
        ReservationStartResult.HOST_ONLY -> "admin.host-only"
        ReservationStartResult.ARENA_UNAVAILABLE -> "admin.arena-unavailable"
        ReservationStartResult.BUSY -> "admin.busy"
        ReservationStartResult.INSUFFICIENT_PLAYERS -> "admin.start-failed"
        ReservationStartResult.NETWORK_FAILURE -> "admin.network-failed"
    }

    private fun stopMessage(result: AdminStopResult): String = when (result) {
        AdminStopResult.MATCH -> "admin.stopped"
        AdminStopResult.RESERVATION -> "admin.reservation-cancelled"
        AdminStopResult.NO_MATCH -> "admin.no-match"
    }

}
