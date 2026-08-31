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
import org.bukkit.inventory.meta.SkullMeta
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.network.QueueState
import java.util.UUID

sealed interface EventsView {
    data object Main : EventsView
    data object Help : EventsView
    data object EventHelp : EventsView
    data object Ttt : EventsView
    data object Statistics : EventsView
    data object Admin : EventsView
    data object Arenas : EventsView
    data object EventArenas : EventsView
    data object Shop : EventsView
    data object Roster : EventsView
    data class Body(val bodyId: UUID) : EventsView
    data object Report : EventsView
    data class CombatLog(val page: Int) : EventsView
}

class ArcEventsMenu(
    private val service: ArcEventsService,
    private val items: TttItems,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val reload: () -> Result<Unit>,
) {
    private class Holder(val view: EventsView, val arenaIds: List<String> = emptyList()) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    private val pendingClicks = mutableSetOf<UUID>()
    private val selectedArenas = mutableMapOf<UUID, String>()
    private val dialogs = ArcEventsDialogMenu(service, locale, settings, ::dispatchClick)

    fun open(player: Player, view: EventsView = EventsView.Main) {
        if (view == EventsView.Ttt) {
            openTtt(player)
            return
        }
        if (dialogs.open(player, view)) return
        when (view) {
            EventsView.Main -> openMain(player)
            EventsView.Help, EventsView.EventHelp -> openHelp(player, view)
            EventsView.Statistics -> openStatistics(player)
            EventsView.Ttt -> error("TTT is opened asynchronously")
            EventsView.Admin -> openAdmin(player)
            EventsView.Arenas -> openArenas(player)
            EventsView.EventArenas -> openEventArenas(player)
            EventsView.Shop -> openShop(player)
            EventsView.Roster -> openRoster(player)
            is EventsView.Body -> openBody(player, view.bodyId)
            EventsView.Report -> openReport(player)
            is EventsView.CombatLog -> openCombatLog(player, view.page)
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
                if (holder.view == EventsView.EventArenas) {
                    clickEventArenas(player, slot, holder.arenaIds)
                } else if (holder.view == EventsView.Arenas) {
                    clickArenas(player, slot, holder.arenaIds)
                } else {
                    dispatchClick(player, holder.view, slot)
                }
            } finally {
                pendingClicks.remove(player.uniqueId)
            }
        }
    }

    fun onDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is Holder) event.isCancelled = true
    }

    private fun dispatchClick(player: Player, view: EventsView, slot: Int) {
        when (view) {
            EventsView.Main -> clickMain(player, slot)
            EventsView.Help -> if (slot == 36) open(player, EventsView.Main)
            EventsView.EventHelp -> if (slot == 36) open(player, EventsView.Ttt)
            EventsView.Ttt -> clickTtt(player, slot)
            EventsView.Statistics -> if (slot == 18) open(player, EventsView.Main)
            EventsView.Admin -> clickAdmin(player, slot)
            EventsView.Arenas -> clickArenas(player, slot, emptyList())
            EventsView.EventArenas -> clickEventArenas(player, slot, emptyList())
            EventsView.Shop -> clickShop(player, slot)
            EventsView.Roster -> clickRoster(player, slot)
            is EventsView.Body -> clickBody(player, view.bodyId, slot)
            EventsView.Report -> clickReport(player, slot)
            is EventsView.CombatLog -> clickCombatLog(player, view.page, slot)
        }
    }

    private fun openMain(player: Player) {
        val state = service.snapshot()
        val inventory = inventory(player, EventsView.Main, 27, "menu.main.title")
        inventory.setItem(4, item(Material.SPYGLASS, player, "menu.main.ttt-name", "menu.main.ttt-lore", mapOf(
            "queue" to locale.text(state.queueSize),
            "minimum" to locale.text(settings().ttt.minimumPlayers),
            "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player),
        )))
        inventory.setItem(18, item(Material.WRITABLE_BOOK, player, "menu.main.stats-name", "menu.main.stats-lore"))
        inventory.setItem(22, item(Material.KNOWLEDGE_BOOK, player, "menu.main.help-name", "menu.main.help-lore"))
        if (player.hasPermission("arcevents.admin")) {
            inventory.setItem(26, item(Material.COMMAND_BLOCK, player, "menu.main.admin-name", "menu.main.admin-lore"))
        }
        player.openInventory(inventory)
    }

    private fun clickMain(player: Player, slot: Int) {
        when (slot) {
            4 -> open(player, EventsView.Ttt)
            18 -> open(player, EventsView.Statistics)
            22 -> open(player, EventsView.Help)
            26 -> if (player.hasPermission("arcevents.admin")) open(player, EventsView.Admin)
        }
    }

    private fun openTtt(player: Player) {
        service.queueControl(player.uniqueId).whenComplete { queueControl, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline) return@runSync
                val state = service.snapshot()
                val queueState = queueControl?.state
                val controls = settings().eventControls
                val adminOverride = controls.adminOverrideEnabled && player.hasPermission("arcevents.admin")
                val creatorControl = controls.creatorControlsEnabled && queueControl?.ownedBy(player.uniqueId) == true
                val canControl = adminOverride || creatorControl ||
                    (!controls.creatorControlsEnabled && player.hasPermission("arcevents.start"))
                selectedArenas.keys.retainAll(Bukkit.getOnlinePlayers().map(Player::getUniqueId).toSet())
                if (queueState != QueueState.QUEUED || !canControl) selectedArenas.remove(player.uniqueId)
                val plan = eventMenuPlan(
                    queueState = queueState,
                    queueStateAvailable = failure == null,
                    hostAvailable = state.hostAvailable,
                    rosterAvailable = service.roster(player.uniqueId) != null,
                    shopAvailable = shopAccessible(service.currentMatch(), service.participant(player.uniqueId)),
                    reportAvailable = service.report() != null && service.participant(player.uniqueId) != null,
                    evacuationAvailable = evacuationAccessible(service.currentMatch(), service.participant(player.uniqueId)),
                    canStart = canControl,
                    canSelectArena = canControl && (controls.creatorArenaSelectionEnabled || adminOverride),
                )
                val selectedArena = arenaName(selectedArenas[player.uniqueId] ?: "auto", player)
                if (dialogs.openTtt(player, queueState, plan, selectedArena)) return@runSync
                val inventory = inventory(player, EventsView.Ttt, 45, "menu.event.title")
                val values = mapOf(
                    "queue" to locale.text(state.queueSize),
                    "minimum" to locale.text(settings().ttt.minimumPlayers),
                    "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player),
                    "selected_arena" to selectedArena,
                )
                inventory.setItem(13, item(Material.SPYGLASS, player, "menu.event.overview-name", "menu.event.overview-lore", values))
                if (plan.showQueueStatus) {
                    inventory.setItem(20, item(Material.CLOCK, player, "menu.event.queue-name", "menu.event.queue-lore", values + mapOf(
                        "queue_state" to locale.render(queueStateLocaleKey(requireNotNull(queueState)), player),
                    )))
                }
                when {
                    plan.showJoin -> inventory.setItem(22, item(Material.LIME_DYE, player, "menu.event.join-name", "menu.event.join-lore"))
                    plan.showJoinUnavailable -> inventory.setItem(22, item(Material.GRAY_DYE, player, "menu.event.join-unavailable-name", "menu.event.join-unavailable-lore"))
                    plan.showLeave -> inventory.setItem(22, item(Material.RED_DYE, player, "menu.event.leave-name", "menu.event.leave-lore"))
                }
                if (plan.showStart) {
                    inventory.setItem(24, item(
                        if (state.queueSize >= settings().ttt.minimumPlayers) Material.LIME_CONCRETE else Material.GRAY_CONCRETE,
                        player,
                        "menu.event.start-name",
                        "menu.event.start-lore",
                        values,
                    ))
                }
                if (plan.showArenaSelection) {
                    inventory.setItem(29, item(Material.FILLED_MAP, player, "menu.event.arena-name", "menu.event.arena-lore", values))
                }
                if (plan.showRoster) inventory.setItem(20, item(Material.PLAYER_HEAD, player, "menu.event.roster-name", "menu.event.roster-lore"))
                if (plan.showShop) inventory.setItem(22, item(Material.NETHER_STAR, player, "menu.event.shop-name", "menu.event.shop-lore"))
                if (plan.showReport) inventory.setItem(24, item(Material.WRITTEN_BOOK, player, "menu.event.report-name", "menu.event.report-lore"))
                inventory.setItem(31, item(Material.KNOWLEDGE_BOOK, player, "menu.event.help-name", "menu.event.help-lore"))
                inventory.setItem(36, backItem(player))
                if (plan.showEvacuate) inventory.setItem(40, item(Material.ENDER_PEARL, player, "menu.event.evacuate-name", "menu.event.evacuate-lore"))
                player.openInventory(inventory)
            }
        }
    }

    private fun clickTtt(player: Player, slot: Int) {
        when (slot) {
            20 -> if (service.roster(player.uniqueId) != null) open(player, EventsView.Roster)
            22 -> when {
                shopAccessible(service.currentMatch(), service.participant(player.uniqueId)) -> open(player, EventsView.Shop)
                else -> service.queueState(player.uniqueId).whenComplete { queueState, _ ->
                    Tasks.scheduler.runSync {
                        if (!player.isOnline) return@runSync
                        when {
                            queueState == QueueState.QUEUED -> {
                                selectedArenas.remove(player.uniqueId)
                                player.closeInventory()
                                service.leaveQueue(player)
                            }
                            queueState == null && service.snapshot().hostAvailable -> {
                                player.closeInventory()
                                service.joinQueue(player)
                            }
                            else -> open(player, EventsView.Ttt)
                        }
                    }
                }
            }
            24 -> when {
                service.report() != null && service.participant(player.uniqueId) != null -> open(player, EventsView.Report)
                else -> service.queueControl(player.uniqueId).whenComplete { control, _ ->
                    Tasks.scheduler.runSync {
                        if (!player.isOnline) return@runSync
                        if (control != null && canStartQueue(player, control)) startFromEventMenu(player)
                        else open(player, EventsView.Ttt)
                    }
                }
            }
            29 -> open(player, EventsView.EventArenas)
            31 -> open(player, EventsView.EventHelp)
            36 -> open(player, EventsView.Main)
            40 -> if (evacuationAccessible(service.currentMatch(), service.participant(player.uniqueId))) {
                player.closeInventory()
                service.leave(player)
            }
        }
    }

    private fun startFromEventMenu(player: Player) {
        player.closeInventory()
        service.startFromQueue(player, selectedArenas[player.uniqueId]).thenAccept { result ->
            Tasks.scheduler.runSync {
                if (player.isOnline) {
                    if (result == ReservationStartResult.STARTED) selectedArenas.remove(player.uniqueId)
                    player.sendEventMessage(locale.render(reservationStartMessage(result, StartMessageAudience.PLAYER), player))
                }
            }
        }
    }

    private fun openStatistics(player: Player) {
        val stats = service.stats(player.uniqueId)
        val inventory = inventory(player, EventsView.Statistics, 27, "menu.stats.title")
        inventory.setItem(13, item(Material.WRITABLE_BOOK, player, "menu.stats.summary-name", "menu.stats.summary-lore", mapOf(
            "matches" to locale.text(stats.matches),
            "wins" to locale.text(stats.wins),
            "kills" to locale.text(stats.kills),
            "deaths" to locale.text(stats.deaths),
            "karma" to locale.text(stats.karma),
        )))
        inventory.setItem(18, backItem(player))
        player.openInventory(inventory)
    }

    private fun openHelp(player: Player, view: EventsView) {
        val inventory = inventory(player, view, 45, "menu.help.title")
        inventory.setItem(11, item(Material.EMERALD, player, "menu.help.innocent-name", "menu.help.innocent-lore"))
        inventory.setItem(13, item(Material.REDSTONE, player, "menu.help.traitor-name", "menu.help.traitor-lore"))
        inventory.setItem(15, item(Material.LAPIS_LAZULI, player, "menu.help.detective-name", "menu.help.detective-lore"))
        inventory.setItem(22, item(Material.CLOCK, player, "menu.help.flow-name", "menu.help.flow-lore"))
        inventory.setItem(29, item(Material.PLAYER_HEAD, player, "menu.help.evidence-name", "menu.help.evidence-lore"))
        inventory.setItem(31, item(Material.CROSSBOW, player, "menu.help.weapons-name", "menu.help.weapons-lore"))
        inventory.setItem(33, item(Material.COMMAND_BLOCK, player, "menu.help.controls-name", "menu.help.controls-lore"))
        inventory.setItem(36, backItem(player))
        player.openInventory(inventory)
    }

    private fun openAdmin(player: Player) {
        if (!player.hasPermission("arcevents.admin")) return
        val state = service.snapshot()
        val inventory = inventory(player, EventsView.Admin, 54, "menu.admin.title")
        inventory.setItem(13, item(Material.OBSERVER, player, "menu.admin.status-name", "menu.admin.status-lore", mapOf(
            "phase" to locale.render("phase.${state.phase?.name?.lowercase() ?: "idle"}", player),
            "match" to locale.text(state.matchId?.toString()?.take(8) ?: "—"),
            "queue" to locale.text(state.queueSize),
            "recovery" to locale.text(state.recoveryPending),
            "server" to locale.text(state.serverId),
            "node_mode" to locale.render("state.mode-${state.nodeMode.name.lowercase()}", player),
            "host" to locale.text(state.hostServer),
            "network_state" to locale.render(if (state.hostAvailable) "state.network-ready" else "state.network-degraded", player),
        )))
        inventory.setItem(20, item(Material.FILLED_MAP, player, "menu.admin.arenas-name", "menu.admin.arenas-lore", mapOf(
            "arenas" to locale.text(service.selectableArenaIds().size),
            "active" to (state.arenaId?.let { arenaName(it, player) } ?: locale.render("arena.auto.name", player)),
        )))
        inventory.setItem(29, item(Material.LIME_CONCRETE, player, "menu.admin.start-name", "menu.admin.start-lore"))
        inventory.setItem(31, item(Material.RED_CONCRETE, player, "menu.admin.stop-name", "menu.admin.stop-lore"))
        inventory.setItem(33, item(Material.CLOCK, player, "menu.admin.reload-name", "menu.admin.reload-lore"))
        inventory.setItem(40, item(Material.TOTEM_OF_UNDYING, player, "menu.admin.recover-name", "menu.admin.recover-lore"))
        inventory.setItem(45, backItem(player))
        player.openInventory(inventory)
    }

    private fun clickAdmin(player: Player, slot: Int) {
        if (!player.hasPermission("arcevents.admin")) return
        when (slot) {
            20 -> open(player, EventsView.Arenas)
            29 -> {
                service.startFromQueue(player, selectedArenas[player.uniqueId]).thenAccept { result ->
                    Tasks.scheduler.runSync {
                        if (!player.isOnline) return@runSync
                        if (result == ReservationStartResult.STARTED) selectedArenas.remove(player.uniqueId)
                        player.sendEventMessage(locale.render(reservationStartMessage(result, StartMessageAudience.ADMIN), player))
                        open(player, EventsView.Admin)
                    }
                }
            }
            31 -> {
                player.sendEventMessage(locale.render(stopMessage(service.stopByAdmin()), player))
                open(player, EventsView.Admin)
            }
            33 -> {
                val result = reload()
                player.sendEventMessage(locale.render(if (result.isSuccess) "command.reload-ok" else "command.reload-failed", player, mapOf(
                    "reason" to locale.text(result.exceptionOrNull()?.message ?: "unknown"),
                )))
                open(player, EventsView.Admin)
            }
            40 -> {
                val count = service.retryRecovery()
                player.sendEventMessage(locale.render("admin.recovery-started", player, mapOf("players" to locale.text(count))))
                open(player, EventsView.Admin)
            }
            45 -> open(player, EventsView.Main)
        }
    }

    private fun openArenas(player: Player) {
        if (!player.hasPermission("arcevents.admin")) return
        val available = service.selectableArenaIds().take(ARENA_SLOTS.size)
        val selected = selectedArenas[player.uniqueId] ?: "auto"
        val inventory = inventory(player, EventsView.Arenas, 54, "menu.arenas.title", arenaIds = available)
        available.zip(ARENA_SLOTS).forEach { (arenaId, slot) ->
            val material = if (selected == arenaId) Material.LIME_CONCRETE else Material.FILLED_MAP
            inventory.setItem(slot, item(material, player, "menu.arenas.entry-name", "menu.arenas.entry-lore", mapOf(
                "arena" to arenaName(arenaId, player),
                "world" to locale.text("—"),
                "template" to locale.text(arenaId),
                "state" to locale.render(if (selected == arenaId) "arena.state.next" else "arena.state.ready", player),
            )))
        }
        inventory.setItem(40, item(
            if (selected == "auto") Material.LIME_CONCRETE else Material.COMPASS,
            player,
            "menu.arenas.auto-name",
            "menu.arenas.auto-lore",
        ))
        inventory.setItem(45, backItem(player))
        player.openInventory(inventory)
    }

    private fun clickArenas(player: Player, slot: Int, renderedArenaIds: List<String>) {
        if (!player.hasPermission("arcevents.admin")) return
        val selected = if (slot == 40) "auto" else renderedArenaIds.getOrNull(ARENA_SLOTS.indexOf(slot))
        when {
            selected != null -> {
                selectedArenas[player.uniqueId] = selected
                player.sendEventMessage(locale.render("admin.arena-selected", player, mapOf(
                    "arena" to arenaName(selected, player),
                )))
                open(player, EventsView.Arenas)
            }
            slot == 45 -> open(player, EventsView.Admin)
        }
    }

    private fun openEventArenas(player: Player) {
        service.queueControl(player.uniqueId).whenComplete { control, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline) return@runSync
                if (failure != null || control == null || !canControlQueue(player, control)) {
                    open(player, EventsView.Ttt)
                    return@runSync
                }
                val available = service.selectableArenaIds().take(ARENA_SLOTS.size)
                val selected = selectedArenas[player.uniqueId] ?: "auto"
                val inventory = inventory(
                    player,
                    EventsView.EventArenas,
                    54,
                    "menu.event-arenas.title",
                    arenaIds = available,
                )
                available.zip(ARENA_SLOTS).forEach { (arenaId, slot) ->
                    inventory.setItem(slot, item(
                        if (selected == arenaId) Material.LIME_CONCRETE else Material.FILLED_MAP,
                        player,
                        "menu.event-arenas.entry-name",
                        "menu.event-arenas.entry-lore",
                        mapOf(
                            "arena" to arenaName(arenaId, player),
                            "selection" to locale.render(
                                if (selected == arenaId) "arena.state.next" else "arena.state.ready",
                                player,
                            ),
                        ),
                    ))
                }
                inventory.setItem(40, item(
                    if (selected == "auto") Material.LIME_CONCRETE else Material.COMPASS,
                    player,
                    "menu.event-arenas.auto-name",
                    "menu.event-arenas.auto-lore",
                ))
                inventory.setItem(45, backItem(player))
                player.openInventory(inventory)
            }
        }
    }

    private fun clickEventArenas(player: Player, slot: Int, renderedArenaIds: List<String>) {
        if (slot == 45) {
            open(player, EventsView.Ttt)
            return
        }
        val selected = if (slot == 40) "auto" else {
            renderedArenaIds.getOrNull(ARENA_SLOTS.indexOf(slot))
        } ?: return
        service.queueControl(player.uniqueId).whenComplete { control, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline) return@runSync
                if (failure != null || control == null || !canControlQueue(player, control)) {
                    open(player, EventsView.Ttt)
                    return@runSync
                }
                selectedArenas[player.uniqueId] = selected
                player.sendEventMessage(locale.render(
                    "queue.arena-selected",
                    player,
                    mapOf("arena" to arenaName(selected, player)),
                ))
                open(player, EventsView.EventArenas)
            }
        }
    }

    private fun canControlQueue(player: Player, control: QueueControlSnapshot): Boolean {
        val controls = settings().eventControls
        val adminOverride = controls.adminOverrideEnabled && player.hasPermission("arcevents.admin")
        if (!controls.creatorArenaSelectionEnabled && !adminOverride) return false
        return canStartQueue(player, control)
    }

    private fun canStartQueue(player: Player, control: QueueControlSnapshot): Boolean {
        if (control.state != QueueState.QUEUED) return false
        val controls = settings().eventControls
        val adminOverride = controls.adminOverrideEnabled && player.hasPermission("arcevents.admin")
        return adminOverride || (controls.creatorControlsEnabled && control.ownedBy(player.uniqueId)) ||
            (!controls.creatorControlsEnabled && player.hasPermission("arcevents.start"))
    }

    private fun openShop(player: Player) {
        val current = service.currentMatch()
        val participant = current?.participant(player.uniqueId)
        if (!shopAccessible(current, participant)) {
            player.sendEventMessage(locale.render("shop.unavailable", player))
            return
        }
        val activeMatch = requireNotNull(current)
        val activeParticipant = requireNotNull(participant)
        val inventory = inventory(player, EventsView.Shop, 45, "menu.shop.title", mapOf("credits" to locale.text(activeParticipant.credits)))
        inventory.setItem(4, item(Material.SUNFLOWER, player, "menu.shop.credits-name", "menu.shop.credits-lore", mapOf(
            "credits" to locale.text(activeParticipant.credits),
        )))
        val offers = when (activeParticipant.role) {
            TttRole.TRAITOR -> items.traitorOffers
            TttRole.DETECTIVE -> items.detectiveOffers
            TttRole.INNOCENT -> emptyList()
        }
        if (offers.isEmpty()) {
            inventory.setItem(22, item(Material.GRAY_DYE, player, "menu.shop.unavailable-name", "menu.shop.unavailable-lore"))
        } else {
            offers.zip(listOf(20, 22, 24)).forEach { (offer, slot) ->
                inventory.setItem(slot, items.offerItem(offer, player, activeMatch.matchId.toString()))
            }
        }
        inventory.setItem(36, backItem(player))
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
            36 -> open(player, EventsView.Ttt)
        }
    }

    private fun openRoster(player: Player) {
        val roster = service.roster(player.uniqueId)
        if (roster == null) {
            player.sendEventMessage(locale.render("match.unavailable", player))
            return
        }
        val inventory = inventory(player, EventsView.Roster, 54, "menu.roster.title")
        val slots = contentSlots(10, 34)
        roster.entries.take(slots.size).zip(slots).forEach { (entry, slot) ->
            inventory.setItem(slot, playerHead(entry.playerId, player, "menu.roster.player-name", "menu.roster.player-lore", mapOf(
                "player" to Component.text(entry.playerName),
                "status" to locale.render("roster.status.${entry.status.name.lowercase()}", player),
                "role" to (entry.publicRole?.let { roleName(it, player) } ?: locale.render("roster.role-hidden", player)),
            )))
        }
        inventory.setItem(45, backItem(player))
        player.openInventory(inventory)
    }

    private fun clickRoster(player: Player, slot: Int) {
        when (slot) {
            45 -> open(player, EventsView.Ttt)
        }
    }

    private fun openBody(player: Player, bodyId: UUID) {
        val evidence = service.bodyEvidence(player.uniqueId, bodyId)
        if (evidence == null) {
            player.sendEventMessage(locale.render("body.unavailable", player))
            return
        }
        val inventory = inventory(player, EventsView.Body(bodyId), 45, "menu.body.title")
        inventory.setItem(13, playerHead(evidence.victimId, player, "menu.body.victim-name", "menu.body.victim-lore", mapOf(
            "player" to Component.text(evidence.victimName),
            "role" to roleName(evidence.role, player),
        )))
        inventory.setItem(20, item(Material.CLOCK, player, "menu.body.time-name", "menu.body.time-lore", mapOf(
            "seconds" to locale.text(evidence.secondsSinceDeath),
        )))
        inventory.setItem(22, item(Material.TARGET, player, "menu.body.cause-name", "menu.body.cause-lore", mapOf(
            "weapon" to weaponName(evidence.weaponKey, player),
            "damage" to locale.text("%.1f".format(evidence.finalDamage)),
            "hit" to locale.render(if (evidence.headshot) "body.hit-head" else "body.hit-body", player),
        )))
        inventory.setItem(24, item(
            if (evidence.dnaAvailable) Material.COMPASS else Material.GRAY_DYE,
            player,
            if (evidence.dnaAvailable) "menu.body.dna-name" else "menu.body.dna-lost-name",
            if (evidence.dnaAvailable) "menu.body.dna-lore" else "menu.body.dna-lost-lore",
        ))
        inventory.setItem(31, item(
            if (evidence.detectiveCalled) Material.LIGHT_BLUE_DYE else Material.BELL,
            player,
            if (evidence.detectiveCalled) "menu.body.called-name" else "menu.body.call-name",
            if (evidence.detectiveCalled) "menu.body.called-lore" else "menu.body.call-lore",
        ))
        inventory.setItem(33, item(Material.PLAYER_HEAD, player, "menu.body.roster-name", "menu.body.roster-lore"))
        inventory.setItem(36, backItem(player))
        player.openInventory(inventory)
    }

    private fun clickBody(player: Player, bodyId: UUID, slot: Int) {
        when (slot) {
            24 -> { service.scanBody(player, bodyId); openBody(player, bodyId) }
            31 -> { service.callDetective(player, bodyId); openBody(player, bodyId) }
            33, 36 -> open(player, EventsView.Roster)
        }
    }

    private fun openReport(player: Player) {
        val report = service.report()
        if (report == null || service.participant(player.uniqueId) == null) {
            player.sendEventMessage(locale.render("report.unavailable", player))
            return
        }
        val inventory = inventory(player, EventsView.Report, 54, "menu.report.title")
        inventory.setItem(4, item(
            if (report.winner == ru.ruscrafting.events.domain.TttTeam.INNOCENTS) Material.EMERALD else Material.REDSTONE,
            player,
            "menu.report.summary-name",
            "menu.report.summary-lore",
            mapOf(
                "winner" to locale.render(if (report.winner == ru.ruscrafting.events.domain.TttTeam.INNOCENTS) "report.winner-innocents" else "report.winner-traitors", player),
                "reason" to locale.render("report.reason.${report.reason.name.lowercase()}", player),
                "time" to locale.text(formatDuration(report.durationSeconds)),
                "events" to locale.text(report.combat.size),
            ),
        ))
        val participantSlots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 29, 33)
        report.participants.take(participantSlots.size).zip(participantSlots).forEach { (entry, slot) ->
            inventory.setItem(slot, playerHead(entry.playerId, player, "menu.report.player-name", "menu.report.player-lore", mapOf(
                "player" to Component.text(entry.playerName),
                "role" to roleName(entry.role, player),
                "kills" to locale.text(entry.kills),
                "damage" to locale.text("%.1f".format(entry.damage)),
                "friendly" to locale.text("%.1f".format(entry.friendlyDamage)),
            )))
        }
        inventory.setItem(40, item(Material.WRITABLE_BOOK, player, "menu.report.combat-name", "menu.report.combat-lore", mapOf(
            "events" to locale.text(report.combat.size),
        )))
        inventory.setItem(45, backItem(player))
        player.openInventory(inventory)
    }

    private fun clickReport(player: Player, slot: Int) {
        when (slot) {
            40 -> open(player, EventsView.CombatLog(0))
            45 -> open(player, EventsView.Ttt)
        }
    }

    private fun openCombatLog(player: Player, page: Int) {
        val report = service.report()
        if (report == null || service.participant(player.uniqueId) == null) return openReport(player)
        val slots = contentSlots(10, 34)
        val maxPage = ((report.combat.size - 1).coerceAtLeast(0) / slots.size)
        val safePage = page.coerceIn(0, maxPage)
        val inventory = inventory(player, EventsView.CombatLog(safePage), 54, "menu.combat.title", mapOf(
            "page" to locale.text(safePage + 1),
            "pages" to locale.text(maxPage + 1),
        ))
        report.combat.asReversed().drop(safePage * slots.size).take(slots.size).zip(slots).forEach { (record, slot) ->
            inventory.setItem(slot, item(
                if (record.friendly) Material.RED_DYE else if (record.lethal) Material.WITHER_SKELETON_SKULL else Material.PAPER,
                player,
                "menu.combat.entry-name",
                "menu.combat.entry-lore",
                mapOf(
                    "sequence" to locale.text(record.sequence),
                    "attacker" to Component.text(record.attackerName ?: "—"),
                    "victim" to Component.text(record.victimName),
                    "weapon" to weaponName(record.weapon, player),
                    "damage" to locale.text("%.1f".format(record.finalDamage)),
                    "flags" to locale.render(when {
                        record.friendly -> "report.flag-friendly"
                        record.lethal -> "report.flag-lethal"
                        record.headshot -> "report.flag-headshot"
                        else -> "report.flag-hit"
                    }, player),
                ),
            ))
        }
        inventory.setItem(45, backItem(player))
        if (safePage > 0) inventory.setItem(48, item(Material.ARROW, player, "menu.common.previous-name", "menu.common.previous-lore"))
        if (safePage < maxPage) inventory.setItem(50, item(Material.ARROW, player, "menu.common.next-name", "menu.common.next-lore"))
        player.openInventory(inventory)
    }

    private fun clickCombatLog(player: Player, page: Int, slot: Int) {
        when (slot) {
            45 -> open(player, EventsView.Report)
            48 -> open(player, EventsView.CombatLog(page - 1))
            50 -> open(player, EventsView.CombatLog(page + 1))
        }
    }

    private fun backItem(player: Player): ItemStack {
        val appearance = settings().ui.back
        val material = Material.matchMaterial(appearance.material)?.takeIf(Material::isItem)
            ?: Material.BLUE_STAINED_GLASS_PANE
        return item(material, player, "menu.common.back-name", "menu.common.back-lore").also { stack ->
            if (appearance.customModelData > 0) {
                stack.editMeta { meta ->
                    val model = meta.customModelDataComponent
                    model.floats = listOf(appearance.customModelData.toFloat())
                    meta.setCustomModelDataComponent(model)
                }
            }
        }
    }

    private fun inventory(
        player: Player,
        view: EventsView,
        size: Int,
        titleKey: String,
        values: Map<String, Component> = emptyMap(),
        arenaIds: List<String> = emptyList(),
    ): Inventory {
        val holder = Holder(view, arenaIds.toList())
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

    private fun playerHead(
        playerId: UUID,
        audience: Player,
        nameKey: String,
        loreKey: String,
        values: Map<String, Component>,
    ): ItemStack = item(Material.PLAYER_HEAD, audience, nameKey, loreKey, values).also { stack ->
        stack.editMeta(SkullMeta::class.java) { meta -> meta.owningPlayer = Bukkit.getOfflinePlayer(playerId) }
    }

    private fun contentSlots(start: Int, end: Int): List<Int> = (start..end).filter { slot -> slot % 9 in 1..7 }

    private fun roleName(role: TttRole, player: Player): Component = locale.render(
        when (role) {
            TttRole.INNOCENT -> "role.innocent-name"
            TttRole.TRAITOR -> "role.traitor-name"
            TttRole.DETECTIVE -> "role.detective-name"
        },
        player,
    )

    private fun weaponName(key: String, player: Player): Component = locale.render("weapon.kind.${key.replace('.', '-')}", player)

    private fun arenaName(id: String, player: Player): Component = locale.render(
        "arena.${if (id.equals("default", true)) "citadel" else id.lowercase()}.name",
        player,
    )

    private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun stopMessage(result: AdminStopResult): String = when (result) {
        AdminStopResult.MATCH -> "admin.stopped"
        AdminStopResult.RESERVATION -> "admin.reservation-cancelled"
        AdminStopResult.NO_MATCH -> "admin.no-match"
    }

    companion object {
        private val ARENA_SLOTS = listOf(10, 12, 14, 16, 28, 30, 32, 34)
    }

}

internal fun shopAccessible(current: TttMatch?, participant: TttParticipant?): Boolean =
    current?.phase == MatchPhase.ACTIVE && participant?.status == ParticipantStatus.ALIVE

internal fun evacuationAccessible(current: TttMatch?, participant: TttParticipant?): Boolean =
    current?.phase in setOf(
        MatchPhase.PREPARING,
        MatchPhase.COUNTDOWN,
        MatchPhase.ACTIVE,
        MatchPhase.RESOLVING,
        MatchPhase.CANCELLED,
        MatchPhase.RESTORING,
    ) && participant?.status != null && participant.status != ParticipantStatus.RESTORED

internal data class EventMenuPlan(
    val showJoin: Boolean,
    val showJoinUnavailable: Boolean,
    val showLeave: Boolean,
    val showQueueStatus: Boolean,
    val showStart: Boolean,
    val showArenaSelection: Boolean,
    val showRoster: Boolean,
    val showShop: Boolean,
    val showReport: Boolean,
    val showEvacuate: Boolean,
)

internal fun eventMenuPlan(
    queueState: QueueState?,
    queueStateAvailable: Boolean,
    hostAvailable: Boolean,
    rosterAvailable: Boolean,
    shopAvailable: Boolean,
    reportAvailable: Boolean,
    evacuationAvailable: Boolean,
    canStart: Boolean,
    canSelectArena: Boolean,
): EventMenuPlan {
    val participating = queueState != null || rosterAvailable || shopAvailable || reportAvailable || evacuationAvailable
    return EventMenuPlan(
        showJoin = !participating && queueStateAvailable && hostAvailable,
        showJoinUnavailable = !participating && (!queueStateAvailable || !hostAvailable),
        showLeave = queueState == QueueState.QUEUED,
        showQueueStatus = queueState != null,
        showStart = queueState == QueueState.QUEUED && canStart,
        showArenaSelection = queueState == QueueState.QUEUED && canSelectArena,
        showRoster = rosterAvailable,
        showShop = shopAvailable,
        showReport = reportAvailable,
        showEvacuate = evacuationAvailable,
    )
}

internal fun queueStateLocaleKey(state: QueueState): String = "menu.event.state.${state.name.lowercase()}"
