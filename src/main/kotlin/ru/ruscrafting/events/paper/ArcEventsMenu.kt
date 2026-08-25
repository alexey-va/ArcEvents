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
import java.util.UUID

sealed interface EventsView {
    data object Main : EventsView
    data object Help : EventsView
    data object Admin : EventsView
    data object Arenas : EventsView
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
    private class Holder(val view: EventsView) : InventoryHolder {
        lateinit var backing: Inventory
        override fun getInventory(): Inventory = backing
    }

    private val pendingClicks = mutableSetOf<UUID>()
    private val dialogs = ArcEventsDialogMenu(service, locale, settings, ::dispatchClick)

    fun open(player: Player, view: EventsView = EventsView.Main) {
        if (dialogs.open(player, view)) return
        when (view) {
            EventsView.Main -> openMain(player)
            EventsView.Help -> openHelp(player)
            EventsView.Admin -> openAdmin(player)
            EventsView.Arenas -> openArenas(player)
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
                dispatchClick(player, holder.view, slot)
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
            EventsView.Help -> if (slot == 36) open(player, EventsView.Main) else if (slot == 44) player.closeInventory()
            EventsView.Admin -> clickAdmin(player, slot)
            EventsView.Arenas -> clickArenas(player, slot)
            EventsView.Shop -> clickShop(player, slot)
            EventsView.Roster -> clickRoster(player, slot)
            is EventsView.Body -> clickBody(player, view.bodyId, slot)
            EventsView.Report -> clickReport(player, slot)
            is EventsView.CombatLog -> clickCombatLog(player, view.page, slot)
        }
    }

    private fun openMain(player: Player) {
        val state = service.snapshot()
        val inventory = inventory(player, EventsView.Main, 54, "menu.main.title")
        inventory.setItem(4, item(Material.SPYGLASS, player, "menu.main.ttt-name", "menu.main.ttt-lore", mapOf(
            "queue" to locale.text(state.queueSize),
            "minimum" to locale.text(settings().ttt.minimumPlayers),
            "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player),
        )))
        inventory.setItem(20, if (state.hostAvailable) {
            item(Material.LIME_DYE, player, "menu.main.join-name", "menu.main.join-lore")
        } else {
            item(Material.GRAY_DYE, player, "menu.main.join-unavailable-name", "menu.main.join-unavailable-lore")
        })
        inventory.setItem(24, item(Material.RED_DYE, player, "menu.main.leave-name", "menu.main.leave-lore"))
        inventory.setItem(22, item(Material.RECOVERY_COMPASS, player, "menu.main.status-name", "menu.main.status-lore", mapOf(
            "server" to locale.text(state.serverId),
            "node_mode" to locale.render("state.mode-${state.nodeMode.name.lowercase()}", player),
            "host" to locale.text(state.hostServer),
            "state" to locale.render(if (state.hostAvailable) "state.network-ready" else "state.network-degraded", player),
        )))
        val stats = service.stats(player.uniqueId)
        if (service.roster(player.uniqueId) != null) {
            inventory.setItem(29, item(Material.PLAYER_HEAD, player, "menu.main.roster-name", "menu.main.roster-lore"))
        }
        inventory.setItem(31, item(Material.WRITABLE_BOOK, player, "menu.main.stats-name", "menu.main.stats-lore", mapOf(
            "matches" to locale.text(stats.matches),
            "wins" to locale.text(stats.wins),
            "kills" to locale.text(stats.kills),
            "deaths" to locale.text(stats.deaths),
            "karma" to locale.text(stats.karma),
        )))
        inventory.setItem(33, item(Material.KNOWLEDGE_BOOK, player, "menu.main.help-name", "menu.main.help-lore"))
        inventory.setItem(40, item(Material.NETHER_STAR, player, "menu.main.shop-name", "menu.main.shop-lore"))
        if (service.report() != null && service.participant(player.uniqueId) != null) {
            inventory.setItem(41, item(Material.WRITTEN_BOOK, player, "menu.main.report-name", "menu.main.report-lore"))
        }
        if (player.hasPermission("arcevents.admin")) {
            inventory.setItem(49, item(Material.COMMAND_BLOCK, player, "menu.main.admin-name", "menu.main.admin-lore"))
        }
        player.openInventory(inventory)
    }

    private fun clickMain(player: Player, slot: Int) {
        when (slot) {
            20 -> { player.closeInventory(); service.joinQueue(player) }
            24 -> { player.closeInventory(); service.leaveQueue(player) }
            29 -> open(player, EventsView.Roster)
            33 -> open(player, EventsView.Help)
            40 -> open(player, EventsView.Shop)
            41 -> open(player, EventsView.Report)
            49 -> if (player.hasPermission("arcevents.admin")) open(player, EventsView.Admin)
        }
    }

    private fun openHelp(player: Player) {
        val inventory = inventory(player, EventsView.Help, 45, "menu.help.title")
        inventory.setItem(11, item(Material.EMERALD, player, "menu.help.innocent-name", "menu.help.innocent-lore"))
        inventory.setItem(13, item(Material.REDSTONE, player, "menu.help.traitor-name", "menu.help.traitor-lore"))
        inventory.setItem(15, item(Material.LAPIS_LAZULI, player, "menu.help.detective-name", "menu.help.detective-lore"))
        inventory.setItem(22, item(Material.CLOCK, player, "menu.help.flow-name", "menu.help.flow-lore"))
        inventory.setItem(29, item(Material.PLAYER_HEAD, player, "menu.help.evidence-name", "menu.help.evidence-lore"))
        inventory.setItem(31, item(Material.CROSSBOW, player, "menu.help.weapons-name", "menu.help.weapons-lore"))
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
            "match" to locale.text(state.matchId?.toString()?.take(8) ?: "—"),
            "queue" to locale.text(state.queueSize),
            "recovery" to locale.text(state.recoveryPending),
        )))
        inventory.setItem(20, item(Material.FILLED_MAP, player, "menu.admin.arenas-name", "menu.admin.arenas-lore", mapOf(
            "arenas" to locale.text(service.arenaEntries().count(ArenaPoolEntry::ready)),
            "active" to (state.arenaId?.let { arenaName(it, player) } ?: locale.render("arena.auto.name", player)),
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
            20 -> open(player, EventsView.Arenas)
            29 -> {
                service.startFromQueue(player).thenAccept { result ->
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

    private fun openArenas(player: Player) {
        if (!player.hasPermission("arcevents.admin")) return
        val inventory = inventory(player, EventsView.Arenas, 54, "menu.arenas.title")
        service.arenaEntries().take(ARENA_SLOTS.size).zip(ARENA_SLOTS).forEach { (arena, slot) ->
            val material = when {
                arena.active -> Material.NETHER_STAR
                arena.next -> Material.CLOCK
                arena.ready -> Material.LIME_CONCRETE
                else -> Material.RED_CONCRETE
            }
            inventory.setItem(slot, item(material, player, "menu.arenas.entry-name", "menu.arenas.entry-lore", mapOf(
                "arena" to arenaName(arena.id, player),
                "world" to locale.text(arena.world),
                "template" to locale.text(arena.template.ifEmpty { "—" }),
                "state" to locale.render(when {
                    arena.active -> "arena.state.active"
                    arena.next -> "arena.state.next"
                    arena.ready -> "arena.state.ready"
                    else -> "arena.state.unavailable"
                }, player),
            )))
        }
        inventory.setItem(40, item(Material.COMPASS, player, "menu.arenas.auto-name", "menu.arenas.auto-lore"))
        inventory.setItem(45, item(Material.ARROW, player, "menu.common.back-name", "menu.common.back-lore"))
        inventory.setItem(53, item(Material.BARRIER, player, "menu.common.close-name", "menu.common.close-lore"))
        player.openInventory(inventory)
    }

    private fun clickArenas(player: Player, slot: Int) {
        if (!player.hasPermission("arcevents.admin")) return
        val selected = if (slot == 40) "auto" else service.arenaEntries().getOrNull(ARENA_SLOTS.indexOf(slot))?.id
        when {
            selected != null -> {
                player.sendMessage(locale.render(if (service.selectNextArena(selected)) "admin.arena-selected" else "admin.arena-selection-failed", player, mapOf(
                    "arena" to arenaName(selected, player),
                )))
                open(player, EventsView.Arenas)
            }
            slot == 45 -> open(player, EventsView.Admin)
            slot == 53 -> player.closeInventory()
        }
    }

    private fun openShop(player: Player) {
        val current = service.currentMatch()
        val participant = current?.participant(player.uniqueId)
        if (!shopAccessible(current, participant)) {
            player.sendMessage(locale.render("shop.unavailable", player))
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

    private fun openRoster(player: Player) {
        val roster = service.roster(player.uniqueId)
        if (roster == null) {
            player.sendMessage(locale.render("match.unavailable", player))
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
        inventory.setItem(45, item(Material.ARROW, player, "menu.common.back-name", "menu.common.back-lore"))
        inventory.setItem(53, item(Material.BARRIER, player, "menu.common.close-name", "menu.common.close-lore"))
        player.openInventory(inventory)
    }

    private fun clickRoster(player: Player, slot: Int) {
        when (slot) {
            45 -> open(player, EventsView.Main)
            53 -> player.closeInventory()
        }
    }

    private fun openBody(player: Player, bodyId: UUID) {
        val evidence = service.bodyEvidence(player.uniqueId, bodyId)
        if (evidence == null) {
            player.sendMessage(locale.render("body.unavailable", player))
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
        inventory.setItem(36, item(Material.ARROW, player, "menu.common.back-name", "menu.common.back-lore"))
        inventory.setItem(44, item(Material.BARRIER, player, "menu.common.close-name", "menu.common.close-lore"))
        player.openInventory(inventory)
    }

    private fun clickBody(player: Player, bodyId: UUID, slot: Int) {
        when (slot) {
            24 -> { service.scanBody(player, bodyId); openBody(player, bodyId) }
            31 -> { service.callDetective(player, bodyId); openBody(player, bodyId) }
            33, 36 -> open(player, EventsView.Roster)
            44 -> player.closeInventory()
        }
    }

    private fun openReport(player: Player) {
        val report = service.report()
        if (report == null || service.participant(player.uniqueId) == null) {
            player.sendMessage(locale.render("report.unavailable", player))
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
        inventory.setItem(45, item(Material.ARROW, player, "menu.common.back-name", "menu.common.back-lore"))
        inventory.setItem(53, item(Material.BARRIER, player, "menu.common.close-name", "menu.common.close-lore"))
        player.openInventory(inventory)
    }

    private fun clickReport(player: Player, slot: Int) {
        when (slot) {
            40 -> open(player, EventsView.CombatLog(0))
            45 -> open(player, EventsView.Main)
            53 -> player.closeInventory()
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
        if (safePage > 0) inventory.setItem(45, item(Material.ARROW, player, "menu.common.previous-name", "menu.common.previous-lore"))
        inventory.setItem(49, item(Material.WRITTEN_BOOK, player, "menu.combat.back-name", "menu.combat.back-lore"))
        if (safePage < maxPage) inventory.setItem(53, item(Material.ARROW, player, "menu.common.next-name", "menu.common.next-lore"))
        player.openInventory(inventory)
    }

    private fun clickCombatLog(player: Player, page: Int, slot: Int) {
        when (slot) {
            45 -> open(player, EventsView.CombatLog(page - 1))
            49 -> open(player, EventsView.Report)
            53 -> open(player, EventsView.CombatLog(page + 1))
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

    private fun startMessage(result: ReservationStartResult): String = when (result) {
        ReservationStartResult.STARTED -> "admin.started"
        ReservationStartResult.ARENA_UNAVAILABLE -> "admin.arena-unavailable"
        ReservationStartResult.BUSY -> "admin.busy"
        ReservationStartResult.INSUFFICIENT_PLAYERS -> "admin.start-failed"
        ReservationStartResult.RECOVERY_PENDING -> "admin.start-recovery-pending"
        ReservationStartResult.NETWORK_FAILURE -> "admin.network-failed"
    }

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
