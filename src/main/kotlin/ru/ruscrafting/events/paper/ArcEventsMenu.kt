package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.plugin.Plugin
import ru.arc.core.Tasks
import ru.arc.paper.menu.PaperMenuConfiguration
import ru.arc.paper.menu.PaperMenuFrame
import ru.arc.paper.menu.PaperMenuRuntime
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.physicalFrame
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.network.QueueState
import java.util.UUID
import java.util.concurrent.CompletableFuture

sealed interface EventsView {
    data object Main : EventsView
    data object Help : EventsView
    data object EventHelp : EventsView
    data object Ttt : EventsView
    data object Fishing : EventsView
    data object Apocalypse : EventsView
    data class Arcade(val mode: EventMode) : EventsView
    data object Statistics : EventsView
    data object Arenas : EventsView
    data object EventArenas : EventsView
    data object Shop : EventsView
    data object Roster : EventsView
    data class Body(val bodyId: UUID) : EventsView
    data object Report : EventsView
    data class CombatLog(val page: Int) : EventsView
}

class ArcEventsMenu(
    plugin: Plugin,
    private val service: ArcEventsService,
    private val items: TttItems,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val layouts: ArcEventsMenuLayouts,
    private val escapeCloses: (Player) -> Boolean = { false },
    private val apocEntry: (Player) -> Unit = {},
    dialogs: PaperDialogRuntime? = null,
) : AutoCloseable {

    private val pendingClicks = mutableSetOf<UUID>()
    private val pendingDialogLoads = mutableMapOf<UUID, UUID>()
    private val pendingFishingActions = mutableMapOf<UUID, UUID>()
    private val pendingArcadeActions = mutableMapOf<UUID, UUID>()
    private val selectedArenas = mutableMapOf<UUID, String>()
    private val ownsDialogRuntime = dialogs == null
    internal val dialogRuntime = dialogs ?: PaperDialogRuntime(plugin)
    private val protocols = ClientProtocolResolver()
    private var firearmSettings: FirearmSettingsDialog? = null
    private var adminDialog: ArcEventsAdminDialog? = null
    private var campaignMenu: ru.ruscrafting.events.paper.apocalypse.ApocalypseCampaignMenu? = null
    private var campaignCommand: ((Player, List<String>) -> Unit)? = null

    internal fun bindCampaignMenu(control: ru.ruscrafting.events.paper.apocalypse.ApocalypseCampaignControl) {
        val perform: (Player, List<String>) -> Unit = { player, args -> control.command(player, args); Unit }
        campaignCommand = perform
        campaignMenu = ru.ruscrafting.events.paper.apocalypse.ApocalypseCampaignMenu(dialogRuntime, locale, ::supportsDialogs,
            escapeCloses, control::canManage, control::friends, perform)
    }

    internal fun openApocalypseFriends(player: Player) { campaignMenu?.openFriends(player) }
    internal fun openApocalypseCampaign(player: Player) { campaignMenu?.openManage(player) }

    internal fun bindAdminDialog(command: ArcEventsCommand, apocalypse: ru.ruscrafting.events.paper.apocalypse.ApocalypseRuntime) {
        adminDialog = ArcEventsAdminDialog(dialogRuntime, service, locale, settings, command, apocalypse,
            tuneGun = { openFirearmSettings(it, admin = true, quiet = true) },
            arenaName = ::arenaName, escapeCloses = escapeCloses)
    }

    private fun supportsDialogs(player: Player) =
        settings().ui.dialogsEnabled && protocols.resolve(player) >= MIN_DIALOG_PROTOCOL

    private fun mayOpenAdmin(player: Player): Boolean {
        if (!player.isOnline) return false
        if (player.canManageEventMenus()) return true
        dialogRuntime.close(player)
        player.sendEventMessage(locale.render("command.no-permission", player))
        return false
    }

    internal fun openModeAdmin(player: Player, mode: EventMode) {
        if (!mayOpenAdmin(player)) return
        val admin = adminDialog
        if (admin != null && supportsDialogs(player)) admin.openMode(player, mode)
        else player.sendEventMessage(locale.render("menu.event.admin-commands", player, mapOf(
            "event" to locale.render(CatalogMode.of(mode).nameKey, player), "mode" to locale.text(mode.id))))
    }

    internal fun openApocalypseAdmin(player: Player) {
        if (!mayOpenAdmin(player)) return
        val admin = adminDialog
        if (admin != null && supportsDialogs(player)) admin.openApocalypse(player)
        else player.sendEventMessage(locale.render("admin-dialog.reset-usage", player))
    }

    internal fun openAdminReset(player: Player, target: String?) {
        if (!mayOpenAdmin(player)) return
        val admin = adminDialog
        if (admin == null || !supportsDialogs(player)) {
            player.sendEventMessage(locale.render("admin-dialog.reset-usage", player))
            return
        }
        dialogRuntime.beginFlow(player)
        admin.openReset(player, target)
    }

    internal fun bindFirearmSettings(firearms: TttFirearms, world: WorldFirearms) {
        firearmSettings = FirearmSettingsDialog(dialogRuntime, locale, firearms,
            specFor = { player -> service.editableFirearmSpec(player) ?: world.editableSpec(player) },
            cancelFire = { id -> world.cancel(id); service.clearFirearmScope(id) },
            native = ::supportsDialogs,
            escapeCloses = escapeCloses)
    }

    internal fun openFirearmSettings(player: Player, admin: Boolean = false, args: List<String> = emptyList(),
                                    quiet: Boolean = false): Boolean =
        firearmSettings?.open(player, admin, args, quiet) == true

    private val eventDialogs = ArcEventsDialogMenu(dialogRuntime, service, locale, settings,
        { player, view, slot -> dispatchClick(player, view, slot, nativePresentation = true) },
        protocols = protocols, escapeCloses = escapeCloses, mainSlot = { element(EventsView.Main, it) },
    )
    private val fishingTrader = FishingTraderDialog(dialogRuntime, service, locale, settings, escapeCloses)
    private val zombiesSupply = ZombiesSupplyDialog(dialogRuntime, service, locale)
    private val menuRuntime = PaperMenuRuntime(plugin, Tasks.scheduler, layouts.current())
    private val activeFrames = mutableMapOf<UUID, ActiveFrame>()

    private data class ActiveFrame(
        val view: EventsView,
        val frame: PaperMenuFrame,
        val arenaIds: List<String> = emptyList(),
        val centerClick: (() -> Unit)? = null,
        val soloSlot: Int? = null,
        val adminSlot: Int? = null,
    )

    /** Start a fresh dialog flow for an entry outside a native dialog callback. */
    fun openRoot(player: Player, view: EventsView = EventsView.Main) {
        val requestedView = if (view == EventsView.Main && service.isParticipant(player.uniqueId)) {
            when (service.currentMode()) {
                EventMode.GUN_GAME -> EventsView.Arcade(EventMode.GUN_GAME)
                EventMode.DISASTERS -> EventsView.Arcade(EventMode.DISASTERS)
                EventMode.FISHING -> EventsView.Fishing
                EventMode.ZOMBIES -> EventsView.Arcade(EventMode.ZOMBIES)
                else -> EventsView.Ttt
            }
        } else view
        pendingDialogLoads.remove(player.uniqueId)
        pendingFishingActions.remove(player.uniqueId)
        pendingArcadeActions.remove(player.uniqueId)
        if (requestedView !in nativeDialogViews) dialogRuntime.close(player)
        dialogRuntime.beginFlow(player)
        open(player, requestedView)
    }

    fun openFishingTraderRoot(player: Player) {
        dialogRuntime.beginFlow(player)
        fishingTrader.open(player)
    }

    fun openZombiesSupply(player: Player, offer: ru.ruscrafting.events.domain.ZombiesOffer) {
        dialogRuntime.beginFlow(player)
        zombiesSupply.open(player, offer)
    }

    fun open(player: Player, view: EventsView = EventsView.Main) {
        if (view == EventsView.Shop && service.fishingAdventure(player) != null) {
            fishingTrader.open(player)
            return
        }
        pendingArcadeActions.remove(player.uniqueId)
        if (view != EventsView.Ttt && view !is EventsView.Arcade) pendingDialogLoads.remove(player.uniqueId)
        if (view != EventsView.Fishing) pendingFishingActions.remove(player.uniqueId)
        if (view !in nativeDialogViews) dialogRuntime.close(player)
        if (view == EventsView.Ttt) {
            openTtt(player)
            return
        }
        if (view == EventsView.Fishing) {
            openFishing(player)
            return
        }
        if (view is EventsView.Arcade) {
            if (view.mode == EventMode.FISHING) {
                openFishing(player)
                return
            }
            openArcade(player, view.mode)
            return
        }
        if (eventDialogs.open(player, view)) return
        when (view) {
            EventsView.Main -> openMain(player)
            EventsView.Help, EventsView.EventHelp -> openHelp(player, view)
            EventsView.Statistics -> openStatistics(player)
            EventsView.Ttt -> error("TTT is opened asynchronously")
            EventsView.Fishing -> error("Fishing is opened natively")
            EventsView.Apocalypse -> campaignMenu?.openEntryFallback(player) ?: apocEntry(player)
            EventsView.Arenas -> openArenas(player)
            EventsView.EventArenas -> openEventArenas(player)
            EventsView.Shop -> openShop(player)
            EventsView.Roster -> openRoster(player)
            is EventsView.Body -> openBody(player, view.bodyId)
            EventsView.Report -> openReport(player)
            is EventsView.CombatLog -> openCombatLog(player, view.page)
        }
    }

    private fun scheduleClick(player: Player, active: ActiveFrame, slot: Int) {
        if (!pendingClicks.add(player.uniqueId)) return
        Tasks.scheduler.runLater(1L) {
            try {
                if (!player.isOnline || activeFrames[player.uniqueId] !== active || menuRuntime.session(player) == null) return@runLater
                if (slot == active.adminSlot) {
                    dispatchClick(player, active.view, MODE_ADMIN_SLOT)
                } else if (slot == active.soloSlot) {
                    dispatchClick(player, active.view, ADMIN_SOLO_SLOT)
                } else if (active.centerClick != null && slot == element(active.view, "center")) {
                    active.centerClick()
                } else if (active.view == EventsView.EventArenas) {
                    clickEventArenas(player, slot, active.arenaIds)
                } else if (active.view == EventsView.Arenas) {
                    clickArenas(player, slot, active.arenaIds)
                } else {
                    dispatchClick(player, active.view, slot, nativePresentation = false)
                }
            } finally {
                pendingClicks.remove(player.uniqueId)
            }
        }
    }

    fun replaceMenus(candidate: PaperMenuConfiguration) {
        layouts.replace(candidate)
        activeFrames.clear()
        menuRuntime.replace(candidate)
    }

    override fun close() {
        activeFrames.clear()
        pendingDialogLoads.clear()
        pendingFishingActions.clear()
        pendingArcadeActions.clear()
        menuRuntime.close()
        if (ownsDialogRuntime) dialogRuntime.close()
    }

    private fun dispatchClick(player: Player, view: EventsView, slot: Int, nativePresentation: Boolean = false) {
        if (slot == MODE_ADMIN_SLOT) {
            if (!nativePresentation) player.closeInventory()
            when (view) {
                EventsView.Ttt -> openModeAdmin(player, EventMode.TTT)
                EventsView.Fishing -> openModeAdmin(player, EventMode.FISHING)
                is EventsView.Arcade -> openModeAdmin(player, view.mode)
                EventsView.Apocalypse -> openApocalypseAdmin(player)
                else -> return
            }
            return
        }
        if (slot == ADMIN_SOLO_SLOT && !player.hasPermission("arcevents.admin")) {
            player.sendEventMessage(locale.render("command.no-permission", player))
            open(player, view)
            return
        }
        if (slot == LEAVE_QUEUE_SLOT) {
            if (service.isParticipant(player.uniqueId)) { open(player, view); return }
            selectedArenas.remove(player.uniqueId)
            closeSurface(player, nativePresentation)
            service.leaveQueue(player)
            return
        }
        if (slot == LEAVE_MATCH_SLOT) {
            val mode = if (view == EventsView.Fishing) EventMode.FISHING else (view as? EventsView.Arcade)?.mode
            val member = service.arcadeSnapshot()?.takeIf { it.mode == mode }?.participants?.get(player.uniqueId)
            if (member == null || member.status == ParticipantStatus.RESTORED) { open(player, view); return }
            closeSurface(player, nativePresentation)
            service.leave(player)
            return
        }
        when (view) {
            EventsView.Main -> clickMain(player, slot, nativePresentation)
            EventsView.Help -> if (slot == element(view, "back")) open(player, EventsView.Main)
            EventsView.EventHelp -> if (slot == element(view, "back")) open(player, EventsView.Ttt)
            EventsView.Ttt -> clickTtt(player, slot, nativePresentation)
            EventsView.Fishing -> clickFishing(player, slot, nativePresentation)
            EventsView.Apocalypse -> when (slot) {
                APOCALYPSE_COMMON_SLOT, ADMIN_SOLO_SLOT -> {
                    closeSurface(player, nativePresentation)
                    campaignCommand?.invoke(player, listOf("apocalypse", "common")) ?: apocEntry(player)
                }
                APOCALYPSE_OWN_SLOT -> {
                    closeSurface(player, nativePresentation)
                    campaignCommand?.invoke(player, listOf("apocalypse", "my"))
                }
                APOCALYPSE_FRIEND_SLOT -> openApocalypseFriends(player)
            }
            is EventsView.Arcade -> clickArcade(player, view.mode, slot, nativePresentation)
            EventsView.Statistics -> if (slot == element(view, "back")) open(player, EventsView.Main)
            EventsView.Arenas -> clickArenas(player, slot, emptyList())
            EventsView.EventArenas -> clickEventArenas(player, slot, emptyList())
            EventsView.Shop -> clickShop(player, slot)
            EventsView.Roster -> clickRoster(player, slot)
            is EventsView.Body -> clickBody(player, view.bodyId, slot)
            EventsView.Report -> clickReport(player, slot)
            is EventsView.CombatLog -> clickCombatLog(player, view.page, slot)
        }
    }

    private fun closeSurface(player: Player, nativePresentation: Boolean) {
        closeSurfaceForPresentation(player, nativePresentation, dialogRuntime::close)
    }

    companion object {
        val nativeDialogViews = setOf<EventsView>(EventsView.Main, EventsView.Help, EventsView.EventHelp, EventsView.Ttt, EventsView.Fishing, EventsView.Apocalypse, EventsView.Arcade(EventMode.ZOMBIES), EventsView.Arcade(EventMode.GUN_GAME), EventsView.Arcade(EventMode.DISASTERS), EventsView.Statistics, EventsView.Report)

        internal fun closeSurfaceForPresentation(
            player: Player,
            nativePresentation: Boolean,
            closeDialog: (Player) -> Unit,
        ) {
            if (nativePresentation) closeDialog(player) else player.closeInventory()
        }
    }

    private fun openMain(player: Player) {
        val state = service.snapshot()
        val view = EventsView.Main
        val inventory = inventory(player, view, "menu.main.title")
        inventory.setItem(element(view, "ttt"), item(Material.SPYGLASS, player, "menu.main.ttt-name", "menu.main.ttt-lore", mapOf(
            "queue" to locale.text(state.queueSize),
            "minimum" to locale.text(settings().ttt.minimumPlayers),
            "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player),
        )))
        inventory.setItem(element(view, "gungame"), item(Material.IRON_SWORD, player, "arcade.gungame-name", "arcade.gungame-lore", mapOf(
            "queue" to locale.text(state.queueSize),
            "minimum" to locale.text(settings().arcade.rules(EventMode.GUN_GAME).minimumPlayers),
        )))
        inventory.setItem(element(view, "disasters"), item(Material.LIGHTNING_ROD, player, "arcade.disasters-name", "arcade.disasters-lore", mapOf(
            "queue" to locale.text(state.queueSize),
            "minimum" to locale.text(settings().arcade.rules(EventMode.DISASTERS).minimumPlayers),
        )))
        inventory.setItem(element(view, "fishing"), item(Material.FISHING_ROD, player, "arcade.fishing-name", "arcade.fishing-lore"))
        inventory.setItem(element(view, "zombies"), item(Material.ZOMBIE_HEAD, player, "arcade.zombies-name", "arcade.zombies-lore"))
        inventory.setItem(element(view, "apocalypse"), item(Material.COPPER_GRATE, player, "apocalypse.menu-name", "apocalypse.menu-lore"))
        inventory.setItem(element(view, "statistics"), item(Material.WRITABLE_BOOK, player, "menu.main.stats-name", "menu.main.stats-lore"))
        inventory.setItem(element(view, "help"), item(Material.KNOWLEDGE_BOOK, player, "menu.main.help-name", "menu.main.help-lore"))
        show(player, view, inventory)
    }

    private fun clickMain(player: Player, slot: Int, nativePresentation: Boolean) {
        val view = EventsView.Main
        when (slot) {
            element(view, "ttt") -> open(player, EventsView.Ttt)
            element(view, "gungame") -> open(player, EventsView.Arcade(EventMode.GUN_GAME))
            element(view, "disasters") -> open(player, EventsView.Arcade(EventMode.DISASTERS))
            element(view, "fishing") -> open(player, EventsView.Fishing)
            element(view, "zombies") -> open(player, EventsView.Arcade(EventMode.ZOMBIES))
            element(view, "apocalypse") -> if (nativePresentation) open(player, EventsView.Apocalypse) else {
                player.closeInventory()
                open(player, EventsView.Apocalypse)
            }
            element(view, "statistics") -> open(player, EventsView.Statistics)
            element(view, "help") -> open(player, EventsView.Help)
        }
    }

    private fun <T> readEntry(player: Player, retryMatchId: String?, read: () -> CompletableFuture<T>): CompletableFuture<T> =
        if (retryMatchId == null) read() else service.retryRoute(player, retryMatchId).thenCompose { read() }

    private fun entryStatus(player: Player, mode: EventMode, queueState: QueueState?, readFailed: Boolean): EventEntryStatus =
        EventEntryStatus(queueState, service.modeUnavailable(mode), readFailed,
            inOtherMode = service.isParticipant(player.uniqueId) && service.currentMode() != mode)

    private fun openArcade(player: Player, mode: EventMode, feedback: String? = null, retryMatchId: String? = null) {
        if (mode == EventMode.FISHING) {
            openFishing(player)
            return
        }
        if (retryMatchId != null && player.uniqueId in pendingDialogLoads) return
        val previousSession = menuRuntime.session(player)
        val token = UUID.randomUUID()
        pendingDialogLoads[player.uniqueId] = token
        val dialogLoading = eventDialogs.openArcadeLoading(player, mode, { openArcade(player, mode) }) {
            pendingDialogLoads.remove(player.uniqueId, token)
        }
        val id = player.uniqueId
        readEntry(player, retryMatchId) { service.queueControl(id) }.whenComplete { control, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline || pendingDialogLoads[player.uniqueId] != token) return@runSync
                pendingDialogLoads.remove(player.uniqueId, token)
                if (!dialogLoading && previousSession != null && menuRuntime.session(player) !== previousSession) return@runSync
                val queueState = control?.state
                val view = EventsView.Arcade(mode)
                val state = service.snapshot()
                val rules = settings().arcade.rules(mode)
                val controls = settings().eventControls
                val adminOverride = controls.adminOverrideEnabled && player.hasPermission("arcevents.admin")
                val creator = controls.creatorControlsEnabled && control?.ownedBy(player.uniqueId) == true
                val arcadeParticipant = service.arcadeSnapshot()?.takeIf { it.mode == mode }?.participants?.get(player.uniqueId)
                val inArcadeMatch = arcadeParticipant != null && arcadeParticipant.status != ParticipantStatus.RESTORED
                val entry = entryStatus(player, mode, queueState, failure != null)
                val checkEntry = { openArcade(player, mode, retryMatchId = control?.matchId) }
                val canStart = queueState == QueueState.QUEUED && (adminOverride || creator ||
                    (!controls.creatorControlsEnabled && player.hasPermission("arcevents.start"))) &&
                    entry.ready && state.matchId == null && (control?.queueSize ?: 0) >= rules.minimumPlayers
                val arenaId = state.arenaId?.takeIf { inArcadeMatch }
                    ?: if (mode.usesSharedArenas) service.defaultArenaId(mode) ?: "auto" else mode.primaryArenaId
                val arena = arenaName(arenaId, player)
                if (eventDialogs.openArcade(player, mode, entry, canStart, inArcadeMatch, arena,
                        element(view, "center"), element(view, "right"), { openArcade(player, mode) }, feedback, control?.queueSize ?: 0, checkEntry)) return@runSync
                if (mode == EventMode.ZOMBIES) {
                    player.sendEventMessage(locale.render("arcade.zombies-dialog-unavailable", player))
                    return@runSync
                }
                if (feedback != null) player.sendEventMessage(locale.render(feedback, player))
                val values = mapOf(
                    "queue" to locale.text(control?.queueSize ?: 0),
                    "minimum" to locale.text(rules.minimumPlayers),
                    "rounds" to locale.text(rules.disasterRounds),
                    "mode" to locale.render("arcade.${mode.id}-name", player),
                    "selected_arena" to arena,
                )
                val inventory = inventory(player, view, "arcade.${mode.id}-title", values)
                inventory.setItem(element(view, "overview"), item(Material.IRON_SWORD.takeIf { mode == EventMode.GUN_GAME } ?: Material.LIGHTNING_ROD, player, "arcade.${mode.id}-name", "arcade.${mode.id}-guide", values))
                val queued = queueState == QueueState.QUEUED
                val center = when {
                    inArcadeMatch -> Material.ENDER_PEARL to "menu.event.evacuate"
                    entry.showCheck -> Material.COMPASS to entry.checkKey
                    entry.inOtherMode -> null
                    queued -> Material.RED_DYE to "arcade.leave"
                    entry.ready -> Material.LIME_DYE to "arcade.join"
                    else -> null
                }
                center?.let { (material, key) -> inventory.setItem(element(view, "center"), item(material, player, "$key-name", "$key-lore", values)) }
                if (canStart) inventory.setItem(element(view, "right"), item(Material.LIME_CONCRETE, player, "arcade.start-name", "arcade.start-lore", values))
                inventory.setItem(element(view, "left"), item(Material.CLOCK, player, "arcade.status-name", "arcade.status-lore",
                    values + ("queue_state" to statusText(player, entry, inArcadeMatch))))
                inventory.setItem(element(view, "help"), item(Material.KNOWLEDGE_BOOK, player, "arcade.${mode.id}-guide-name", "arcade.${mode.id}-guide", values))
                inventory.setItem(element(view, "back"), backItem(player))
                val solo = entry.canStartSoloTest(inArcadeMatch) && player.hasPermission("arcevents.admin")
                val soloSlot = if (solo) element(view, "evacuate") else null
                if (soloSlot != null) inventory.setItem(soloSlot, item(Material.COMPARATOR, player, "menu.admin.solo-test-name",
                    "menu.admin.solo-test-lore", locale.soloTestText(player, mode)))
                show(player, view, inventory, soloSlot = soloSlot, centerClick = when {
                    inArcadeMatch -> { { dispatchClick(player, view, LEAVE_MATCH_SLOT) } }
                    entry.showCheck -> checkEntry
                    queued -> { { dispatchClick(player, view, LEAVE_QUEUE_SLOT) } }
                    else -> null
                })
            }
        }
    }

    private fun openFishing(player: Player, feedback: String? = null, retryMatchId: String? = null) {
        val id = player.uniqueId
        val token = UUID.randomUUID()
        pendingDialogLoads[id] = token
        if (!eventDialogs.openFishingLoading(player, { openFishing(player) }, checking = true) { pendingDialogLoads.remove(id, token) }) {
            pendingDialogLoads.remove(id, token)
            player.sendEventMessage(locale.render("arcade.fishing-dialog-unavailable", player))
            return
        }
        readEntry(player, retryMatchId) { service.queueEntry(id) }.whenComplete { route, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline || !pendingDialogLoads.remove(id, token)) return@runSync
                val entry = entryStatus(player, EventMode.FISHING, route?.state, failure != null)
                eventDialogs.openFishing(player, entry, { openFishing(player) }, feedback,
                    checkEntry = { openFishing(player, retryMatchId = route?.matchId) })
            }
        }
    }

    private fun clickFishing(player: Player, slot: Int, nativePresentation: Boolean) {
        if (slot != FISHING_PRIMARY_SLOT && slot != ADMIN_SOLO_SLOT) return
        if (service.isParticipant(player.uniqueId)) { openFishing(player); return }
        val token = UUID.randomUUID()
        pendingFishingActions[player.uniqueId] = token
        val loading = eventDialogs.openFishingLoading(
            player,
            reopen = {
                pendingFishingActions.remove(player.uniqueId, token)
                openFishing(player)
            },
            onDismiss = { pendingFishingActions.remove(player.uniqueId, token) },
        )
        if (!loading) {
            pendingFishingActions.remove(player.uniqueId, token)
            player.sendEventMessage(locale.render("arcade.fishing-dialog-unavailable", player))
            return
        }
        val start = if (slot == ADMIN_SOLO_SLOT) service.startSoloTest(player, EventMode.FISHING) else service.startFishing(player)
        start.whenComplete { result, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline || pendingFishingActions[player.uniqueId] != token) return@runSync
                pendingFishingActions.remove(player.uniqueId, token)
                if (failure == null && result == ReservationStartResult.STARTED) closeSurface(player, nativePresentation)
                else openFishing(player, if (failure == null && result != null)
                    reservationStartMessage(result, StartMessageAudience.PLAYER) else "queue.start-network-failed")

            }
        }
    }

    private fun clickArcade(player: Player, mode: EventMode, slot: Int, nativePresentation: Boolean = false) {
        val view = EventsView.Arcade(mode)
        if (slot == element(view, "back")) { open(player, EventsView.Main); return }
        if (slot == element(view, "help")) { if (nativePresentation) eventDialogs.openRules(player, mode); return }
        if (pendingArcadeActions.containsKey(player.uniqueId)) return
        if (slot !in setOf(element(view, "center"), element(view, "right"), ZOMBIES_SOLO_SLOT, ADMIN_SOLO_SLOT)) return
        if (slot == ZOMBIES_SOLO_SLOT && mode != EventMode.ZOMBIES) return
        val token = UUID.randomUUID()
        pendingArcadeActions[player.uniqueId] = token
        pendingDialogLoads.remove(player.uniqueId)
        fun current() = player.isOnline && pendingArcadeActions[player.uniqueId] == token
        eventDialogs.openArcadeLoading(player, mode, {
            pendingArcadeActions.remove(player.uniqueId, token)
            openArcade(player, mode)
        }) { pendingArcadeActions.remove(player.uniqueId, token) }
        fun completeStart(result: ReservationStartResult?, failure: Throwable?) {
            Tasks.scheduler.runSync {
                if (!current()) return@runSync
                pendingArcadeActions.remove(player.uniqueId, token)
                if (failure == null && result == ReservationStartResult.STARTED) {
                    closeSurface(player, nativePresentation)
                } else {
                    openArcade(player, mode, if (failure == null && result != null)
                        reservationStartMessage(result, StartMessageAudience.PLAYER) else "queue.start-network-failed")
                }
            }
        }
        if (slot == ZOMBIES_SOLO_SLOT || slot == ADMIN_SOLO_SLOT) {
            val start = if (slot == ADMIN_SOLO_SLOT) service.startSoloTest(player, mode) else service.startZombiesSolo(player)
            start.whenComplete(::completeStart)
            return
        }
        service.queueControl(player.uniqueId).whenComplete { control, failure ->
            Tasks.scheduler.runSync {
                if (!current()) return@runSync
                if (failure != null) {
                    pendingArcadeActions.remove(player.uniqueId, token)
                    openArcade(player, mode, "queue.start-network-failed")
                    return@runSync
                }
                if (slot == element(view, "center")) {
                    if (control?.state != null || service.isParticipant(player.uniqueId) || !service.modeAvailable(mode)) {
                        pendingArcadeActions.remove(player.uniqueId, token)
                        openArcade(player, mode)
                    } else {
                        service.joinQueue(player, mode).whenComplete { joined, joinFailure ->
                            Tasks.scheduler.runSync {
                                if (!current()) return@runSync
                                pendingArcadeActions.remove(player.uniqueId, token)
                                openArcade(player, mode, if (joinFailure != null || joined != true) "queue.unavailable" else null)
                            }
                        }
                    }
                } else {
                    val controls = settings().eventControls
                    val allowed = control?.state == QueueState.QUEUED &&
                        (controls.adminOverrideEnabled && player.hasPermission("arcevents.admin") ||
                            controls.creatorControlsEnabled && control.ownedBy(player.uniqueId) ||
                            !controls.creatorControlsEnabled && player.hasPermission("arcevents.start")) &&
                        control.queueSize >= settings().arcade.rules(mode).minimumPlayers &&
                        service.modeAvailable(mode) && service.snapshot().matchId == null
                    if (allowed) service.startFromQueue(player, null, mode).whenComplete(::completeStart)
                    else {
                        pendingArcadeActions.remove(player.uniqueId, token)
                        openArcade(player, mode, "queue.start-not-owner")
                    }
                }
            }
        }
    }

    private fun openTtt(player: Player, feedback: String? = null, retryMatchId: String? = null) {
        if (retryMatchId != null && player.uniqueId in pendingDialogLoads) return
        val previousSession = menuRuntime.session(player)
        val loadToken = UUID.randomUUID()
        pendingDialogLoads[player.uniqueId] = loadToken
        val dialogLoading = eventDialogs.openTttLoading(player, { openTtt(player) }) {
            pendingDialogLoads.remove(player.uniqueId, loadToken)
        }
        val id = player.uniqueId
        readEntry(player, retryMatchId) { service.queueControl(id) }.whenComplete { queueControl, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline || pendingDialogLoads[player.uniqueId] != loadToken) return@runSync
                pendingDialogLoads.remove(player.uniqueId, loadToken)
                if (!dialogLoading && previousSession != null && menuRuntime.session(player) !== previousSession) return@runSync
                val state = service.snapshot()
                val queueState = queueControl?.state
                val entry = entryStatus(player, EventMode.TTT, queueState, failure != null)
                val checkEntry = { openTtt(player, retryMatchId = queueControl?.matchId) }
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
                    hostAvailable = entry.ready,
                    rosterAvailable = service.roster(player.uniqueId) != null,
                    shopAvailable = shopAccessible(service.currentMatch(), service.participant(player.uniqueId)),
                    reportAvailable = service.report() != null && service.participant(player.uniqueId) != null,
                    evacuationAvailable = evacuationAccessible(service.currentMatch(), service.participant(player.uniqueId)),
                    canStart = canControl && entry.ready,
                    canSelectArena = canControl && (controls.creatorArenaSelectionEnabled || adminOverride),
                )
                val selectedArena = arenaName(state.arenaId?.takeIf { plan.showEvacuate } ?: selectedArenaId(player.uniqueId), player)
                if (dialogLoading && eventDialogs.openTtt(player, entry, plan, selectedArena, { openTtt(player) }, {
                    pendingDialogLoads.remove(player.uniqueId, loadToken)
                }, queueControl?.queueSize ?: 0, feedback, checkEntry)) return@runSync
                if (feedback != null) player.sendEventMessage(locale.render(feedback, player))
                val view = EventsView.Ttt
                val inventory = inventory(player, view, "menu.event.title")
                val values = mapOf(
                    "queue" to locale.text(queueControl?.queueSize ?: 0),
                    "minimum" to locale.text(settings().ttt.minimumPlayers),
                    "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player),
                    "selected_arena" to selectedArena,
                )
                inventory.setItem(element(view, "overview"), item(Material.SPYGLASS, player, "menu.event.overview-name", "menu.event.overview-lore", values))
                if (plan.showQueueStatus || plan.showJoinUnavailable) {
                    inventory.setItem(element(view, "left"), item(Material.CLOCK, player, "menu.event.queue-name", "menu.event.queue-lore", values + mapOf(
                        "queue_state" to statusText(player, entry, plan.showEvacuate),
                    )))
                }
                when {
                    plan.showJoin -> inventory.setItem(element(view, "center"), item(Material.LIME_DYE, player, "menu.event.join-name", "menu.event.join-lore"))
                    plan.showLeave -> inventory.setItem(element(view, "center"), item(Material.RED_DYE, player, "menu.event.leave-name", "menu.event.leave-lore"))
                    entry.showCheck && !plan.showEvacuate -> inventory.setItem(element(view, "center"), item(Material.COMPASS, player, "${entry.checkKey}-name", "${entry.checkKey}-lore"))
                }
                if (plan.showStart) {
                    inventory.setItem(element(view, "right"), item(
                        if ((queueControl?.queueSize ?: 0) >= settings().ttt.minimumPlayers) Material.LIME_CONCRETE else Material.GRAY_CONCRETE,
                        player,
                        "menu.event.start-name",
                        "menu.event.start-lore",
                        values,
                    ))
                }
                if (plan.showArenaSelection) {
                    inventory.setItem(element(view, "arena"), item(Material.FILLED_MAP, player, "menu.event.arena-name", "menu.event.arena-lore", values))
                }
                if (plan.showRoster) inventory.setItem(element(view, "left"), item(Material.PLAYER_HEAD, player, "menu.event.roster-name", "menu.event.roster-lore"))
                if (plan.showShop) inventory.setItem(element(view, "center"), item(Material.NETHER_STAR, player, "menu.event.shop-name", "menu.event.shop-lore"))
                if (plan.showReport) inventory.setItem(element(view, "right"), item(Material.WRITTEN_BOOK, player, "menu.event.report-name", "menu.event.report-lore"))
                inventory.setItem(element(view, "help"), item(Material.KNOWLEDGE_BOOK, player, "menu.event.help-name", "menu.event.help-lore"))
                inventory.setItem(element(view, "back"), backItem(player))
                if (plan.showEvacuate) inventory.setItem(element(view, "evacuate"), item(Material.ENDER_PEARL, player, "menu.event.evacuate-name", "menu.event.evacuate-lore"))
                val soloSlot = if (entry.canStartSoloTest(plan.showEvacuate) && player.hasPermission("arcevents.admin")) element(view, "evacuate") else null
                if (soloSlot != null) inventory.setItem(soloSlot, item(Material.COMPARATOR, player, "menu.admin.solo-test-name",
                    "menu.admin.solo-test-lore", locale.soloTestText(player, EventMode.TTT)))
                show(player, view, inventory, soloSlot = soloSlot, centerClick = when {
                    plan.showLeave -> { { dispatchClick(player, view, LEAVE_QUEUE_SLOT) } }
                    entry.showCheck && !plan.showEvacuate -> checkEntry
                    else -> null
                })
            }
        }
    }

    private fun clickTtt(player: Player, slot: Int, nativePresentation: Boolean) {
        val view = EventsView.Ttt
        when (slot) {
            ADMIN_SOLO_SLOT -> startFromEventMenu(player, nativePresentation, soloTest = true)
            element(view, "left") -> if (service.roster(player.uniqueId) != null) open(player, EventsView.Roster)
            element(view, "center") -> when {
                shopAccessible(service.currentMatch(), service.participant(player.uniqueId)) -> open(player, EventsView.Shop)
                else -> {
                    if (pendingArcadeActions.containsKey(player.uniqueId)) return
                    val token = UUID.randomUUID()
                    pendingArcadeActions[player.uniqueId] = token
                    fun current() = player.isOnline && pendingArcadeActions[player.uniqueId] == token
                    eventDialogs.openTttLoading(player, {
                        pendingArcadeActions.remove(player.uniqueId, token)
                        openTtt(player)
                    }) { pendingArcadeActions.remove(player.uniqueId, token) }
                    service.queueState(player.uniqueId).whenComplete { queueState, failure ->
                        Tasks.scheduler.runSync {
                            if (!current()) return@runSync
                            when {
                                failure != null -> {
                                    pendingArcadeActions.remove(player.uniqueId, token)
                                    openTtt(player)
                                }
                                queueState == null && !service.isParticipant(player.uniqueId) && service.modeAvailable(EventMode.TTT) -> {
                                    service.joinQueue(player).whenComplete { joined, joinFailure ->
                                        Tasks.scheduler.runSync {
                                            if (!current()) return@runSync
                                            pendingArcadeActions.remove(player.uniqueId, token)
                                            openTtt(player, if (joinFailure != null || joined != true) "queue.unavailable" else null)
                                        }
                                    }
                                }
                                else -> {
                                    pendingArcadeActions.remove(player.uniqueId, token)
                                    openTtt(player)
                                }
                            }
                        }
                    }
                }
            }
            element(view, "right") -> when {
                service.report() != null && service.participant(player.uniqueId) != null -> open(player, EventsView.Report)
                else -> service.queueControl(player.uniqueId).whenComplete { control, _ ->
                    Tasks.scheduler.runSync {
                        if (!player.isOnline) return@runSync
                        if (control != null && canStartQueue(player, control)) startFromEventMenu(player, nativePresentation)
                        else open(player, EventsView.Ttt)
                    }
                }
            }
            element(view, "arena") -> if (nativePresentation) openEventArenas(player, nativePresentation = true)
                else open(player, EventsView.EventArenas)
            element(view, "help") -> open(player, EventsView.EventHelp)
            element(view, "back") -> open(player, EventsView.Main)
            element(view, "evacuate") -> if (evacuationAccessible(service.currentMatch(), service.participant(player.uniqueId))) {
                closeSurface(player, nativePresentation)
                service.leave(player)
            }
        }
    }

    private fun startFromEventMenu(player: Player, nativePresentation: Boolean, soloTest: Boolean = false) {
        if (pendingArcadeActions.containsKey(player.uniqueId)) return
        val token = UUID.randomUUID()
        pendingArcadeActions[player.uniqueId] = token
        eventDialogs.openTttLoading(player, {
            pendingArcadeActions.remove(player.uniqueId, token)
            openTtt(player)
        }) { pendingArcadeActions.remove(player.uniqueId, token) }
        val start = if (soloTest) service.startSoloTest(player, EventMode.TTT)
            else service.startFromQueue(player, selectedArenas[player.uniqueId])
        start.whenComplete { result, failure ->
            Tasks.scheduler.runSync {
                if (!player.isOnline || pendingArcadeActions[player.uniqueId] != token) return@runSync
                pendingArcadeActions.remove(player.uniqueId, token)
                if (failure == null && result == ReservationStartResult.STARTED) {
                    selectedArenas.remove(player.uniqueId)
                    closeSurface(player, nativePresentation)
                } else {
                    openTtt(player, if (failure == null && result != null)
                        reservationStartMessage(result, StartMessageAudience.PLAYER) else "queue.start-network-failed")
                }
            }
        }
    }

    private fun openStatistics(player: Player) {
        val stats = service.stats(player.uniqueId)
        val view = EventsView.Statistics
        val inventory = inventory(player, view, "menu.stats.title")
        inventory.setItem(element(view, "summary"), item(Material.WRITABLE_BOOK, player, "menu.stats.summary-name", "menu.stats.summary-lore", mapOf(
            "matches" to locale.text(stats.matches),
            "wins" to locale.text(stats.wins),
            "kills" to locale.text(stats.kills),
            "deaths" to locale.text(stats.deaths),
            "karma" to locale.text(stats.karma),
        )))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun openHelp(player: Player, view: EventsView) {
        val inventory = inventory(player, view, "menu.help.title")
        inventory.setItem(element(view, "innocent"), item(Material.EMERALD, player, "menu.help.innocent-name", "menu.help.innocent-lore"))
        inventory.setItem(element(view, "traitor"), item(Material.REDSTONE, player, "menu.help.traitor-name", "menu.help.traitor-lore"))
        inventory.setItem(element(view, "detective"), item(Material.LAPIS_LAZULI, player, "menu.help.detective-name", "menu.help.detective-lore"))
        inventory.setItem(element(view, "flow"), item(Material.CLOCK, player, "menu.help.flow-name", "menu.help.flow-lore"))
        inventory.setItem(element(view, "evidence"), item(Material.PLAYER_HEAD, player, "menu.help.evidence-name", "menu.help.evidence-lore"))
        inventory.setItem(element(view, "weapons"), item(Material.CROSSBOW, player, "menu.help.weapons-name", "menu.help.weapons-lore"))
        inventory.setItem(element(view, "controls"), item(Material.COMMAND_BLOCK, player, "menu.help.controls-name", "menu.help.controls-lore"))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun openArenas(player: Player) {
        if (!player.hasPermission("arcevents.admin")) return
        val view = EventsView.Arenas
        val arenaSlots = region(view, "arenas")
        val available = service.selectableArenaIds().take(arenaSlots.size)
        val selected = selectedArenaId(player.uniqueId)
        val inventory = inventory(player, view, "menu.arenas.title")
        available.zip(arenaSlots).forEach { (arenaId, slot) ->
            val material = if (selected == arenaId) Material.LIME_CONCRETE else Material.FILLED_MAP
            inventory.setItem(slot, item(material, player, "menu.arenas.entry-name", "menu.arenas.entry-lore", mapOf(
                "arena" to arenaName(arenaId, player),
                "world" to locale.text("—"),
                "template" to locale.text(arenaId),
                "state" to locale.render(if (selected == arenaId) "arena.state.next" else "arena.state.ready", player),
            )))
        }
        inventory.setItem(element(view, "auto"), item(
            if (selected == "auto") Material.LIME_CONCRETE else Material.COMPASS,
            player,
            "menu.arenas.auto-name",
            "menu.arenas.auto-lore",
        ))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory, available)
    }

    private fun clickArenas(player: Player, slot: Int, renderedArenaIds: List<String>) {
        if (!player.hasPermission("arcevents.admin")) return
        val view = EventsView.Arenas
        val selected = if (slot == element(view, "auto")) "auto" else renderedArenaIds.getOrNull(region(view, "arenas").indexOf(slot))
        when {
            selected != null -> {
                selectedArenas[player.uniqueId] = selected
                player.sendEventMessage(locale.render("admin.arena-selected", player, mapOf(
                    "arena" to arenaName(selected, player),
                )))
                open(player, EventsView.Arenas)
            }
            slot == element(view, "back") -> openRoot(player, EventsView.Ttt)
        }
    }

    private fun openEventArenas(player: Player, nativePresentation: Boolean = false, selection: String? = null) {
        val id = player.uniqueId
        val sourceSession = menuRuntime.session(player)
        val token = UUID.randomUUID()
        pendingDialogLoads[id] = token
        val dialogLoading = nativePresentation && eventDialogs.openTttLoading(player, { openTtt(player) }) {
            pendingDialogLoads.remove(id, token)
        }
        if (nativePresentation && !dialogLoading) dialogRuntime.close(player)
        service.queueControl(id).whenComplete { control, failure ->
            Tasks.scheduler.runSync {
                if (!pendingDialogLoads.remove(id, token) || !player.isOnline) return@runSync
                if (!dialogLoading && menuRuntime.session(player) !== sourceSession) return@runSync
                if (failure != null || control == null || !canControlQueue(player, control)) {
                    if (dialogLoading) openTtt(player) else openRoot(player, EventsView.Ttt)
                    return@runSync
                }
                if (selection != null) {
                    selectedArenas[id] = selection
                    player.sendEventMessage(locale.render(
                        "queue.arena-selected",
                        player,
                        mapOf("arena" to arenaName(selection, player)),
                    ))
                }
                val view = EventsView.EventArenas
                val arenaSlots = region(view, "arenas")
                val available = service.selectableArenaIds().take(arenaSlots.size)
                val selected = selectedArenaId(id)
                val inventory = inventory(
                    player,
                    view,
                    "menu.event-arenas.title",
                )
                available.zip(arenaSlots).forEach { (arenaId, slot) ->
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
                inventory.setItem(element(view, "auto"), item(
                    if (selected == "auto") Material.LIME_CONCRETE else Material.COMPASS,
                    player,
                    "menu.event-arenas.auto-name",
                    "menu.event-arenas.auto-lore",
                ))
                inventory.setItem(element(view, "back"), backItem(player))
                if (dialogLoading) dialogRuntime.close(player)
                show(player, view, inventory, available)
            }
        }
    }

    private fun clickEventArenas(player: Player, slot: Int, renderedArenaIds: List<String>) {
        val view = EventsView.EventArenas
        if (slot == element(view, "back")) {
            openRoot(player, EventsView.Ttt)
            return
        }
        val selected = if (slot == element(view, "auto")) "auto" else {
            renderedArenaIds.getOrNull(region(view, "arenas").indexOf(slot))
        } ?: return
        openEventArenas(player, selection = selected)
    }

    private fun canControlQueue(player: Player, control: QueueControlSnapshot): Boolean {
        val controls = settings().eventControls
        val adminOverride = controls.adminOverrideEnabled && player.hasPermission("arcevents.admin")
        if (!controls.creatorArenaSelectionEnabled && !adminOverride) return false
        return canStartQueue(player, control)
    }

    private fun selectedArenaId(playerId: UUID): String = selectedArenas[playerId]
        ?: service.defaultArenaId()
        ?: "auto"

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
        val view = EventsView.Shop
        val inventory = inventory(player, view, "menu.shop.title", mapOf("credits" to locale.text(activeParticipant.credits)))
        inventory.setItem(element(view, "credits"), item(Material.SUNFLOWER, player, "menu.shop.credits-name", "menu.shop.credits-lore", mapOf(
            "credits" to locale.text(activeParticipant.credits),
        )))
        val offers = when (activeParticipant.role) {
            TttRole.TRAITOR -> items.traitorOffers
            TttRole.DETECTIVE -> items.detectiveOffers
            TttRole.INNOCENT -> emptyList()
        }
        val offerSlots = region(view, "offers")
        if (offers.isEmpty()) {
            inventory.setItem(offerSlots[offerSlots.size / 2], item(Material.GRAY_DYE, player, "menu.shop.unavailable-name", "menu.shop.unavailable-lore"))
        } else {
            offers.zip(offerSlots).forEach { (offer, slot) ->
                inventory.setItem(slot, items.offerItem(offer, player, activeMatch.matchId.toString()))
            }
        }
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun clickShop(player: Player, slot: Int) {
        val view = EventsView.Shop
        val participant = service.participant(player.uniqueId)
        val offers = when (participant?.role) {
            TttRole.TRAITOR -> items.traitorOffers
            TttRole.DETECTIVE -> items.detectiveOffers
            else -> emptyList()
        }
        val index = region(view, "offers").indexOf(slot)
        when {
            index >= 0 -> offers.getOrNull(index)?.let { service.buy(player, it); open(player, view) }
            slot == element(view, "back") -> openRoot(player, EventsView.Ttt)
        }
    }

    private fun openRoster(player: Player) {
        val roster = service.roster(player.uniqueId)
        if (roster == null) {
            player.sendEventMessage(locale.render("match.unavailable", player))
            return
        }
        val view = EventsView.Roster
        val inventory = inventory(player, view, "menu.roster.title")
        val slots = region(view, "players")
        roster.entries.take(slots.size).zip(slots).forEach { (entry, slot) ->
            inventory.setItem(slot, playerHead(entry.playerId, player, "menu.roster.player-name", "menu.roster.player-lore", mapOf(
                "player" to Component.text(entry.playerName),
                "status" to locale.render("roster.status.${entry.status.name.lowercase()}", player),
                "role" to (entry.publicRole?.let { roleName(it, player) } ?: locale.render("roster.role-hidden", player)),
            )))
        }
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun clickRoster(player: Player, slot: Int) {
        if (slot == element(EventsView.Roster, "back")) openRoot(player, EventsView.Ttt)
    }

    private fun openBody(player: Player, bodyId: UUID) {
        val evidence = service.bodyEvidence(player.uniqueId, bodyId)
        if (evidence == null) {
            player.sendEventMessage(locale.render("body.unavailable", player))
            return
        }
        val view = EventsView.Body(bodyId)
        val inventory = inventory(player, view, "menu.body.title")
        inventory.setItem(element(view, "victim"), playerHead(evidence.victimId, player, "menu.body.victim-name", "menu.body.victim-lore", mapOf(
            "player" to Component.text(evidence.victimName),
            "role" to roleName(evidence.role, player),
        )))
        inventory.setItem(element(view, "time"), item(Material.CLOCK, player, "menu.body.time-name", "menu.body.time-lore", mapOf(
            "seconds" to locale.text(evidence.secondsSinceDeath),
        )))
        inventory.setItem(element(view, "cause"), item(Material.TARGET, player, "menu.body.cause-name", "menu.body.cause-lore", mapOf(
            "weapon" to weaponName(evidence.weaponKey, player),
            "damage" to locale.text("%.1f".format(evidence.finalDamage)),
            "hit" to locale.render(if (evidence.headshot) "body.hit-head" else "body.hit-body", player),
        )))
        inventory.setItem(element(view, "dna"), item(
            if (evidence.dnaAvailable) Material.COMPASS else Material.GRAY_DYE,
            player,
            if (evidence.dnaAvailable) "menu.body.dna-name" else "menu.body.dna-lost-name",
            if (evidence.dnaAvailable) "menu.body.dna-lore" else "menu.body.dna-lost-lore",
        ))
        inventory.setItem(element(view, "call"), item(
            if (evidence.detectiveCalled) Material.LIGHT_BLUE_DYE else Material.BELL,
            player,
            if (evidence.detectiveCalled) "menu.body.called-name" else "menu.body.call-name",
            if (evidence.detectiveCalled) "menu.body.called-lore" else "menu.body.call-lore",
        ))
        inventory.setItem(element(view, "roster"), item(Material.PLAYER_HEAD, player, "menu.body.roster-name", "menu.body.roster-lore"))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun clickBody(player: Player, bodyId: UUID, slot: Int) {
        val view = EventsView.Body(bodyId)
        when (slot) {
            element(view, "dna") -> { service.scanBody(player, bodyId); openBody(player, bodyId) }
            element(view, "call") -> { service.callDetective(player, bodyId); openBody(player, bodyId) }
            element(view, "roster"), element(view, "back") -> open(player, EventsView.Roster)
        }
    }

    private fun openReport(player: Player, page: Int = 0) {
        val report = service.report()
        if (report == null || service.participant(player.uniqueId) == null) {
            player.sendEventMessage(locale.render("report.unavailable", player))
            return
        }
        val view = EventsView.Report
        if (eventDialogs.openReport(player, report, page, element(view, "combat"), { openReport(player, page) }, { openReport(player, it) })) return
        val inventory = inventory(player, view, "menu.report.title")
        inventory.setItem(element(view, "summary"), item(
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
        val participantSlots = region(view, "participants")
        report.participants.take(participantSlots.size).zip(participantSlots).forEach { (entry, slot) ->
            inventory.setItem(slot, playerHead(entry.playerId, player, "menu.report.player-name", "menu.report.player-lore", mapOf(
                "player" to Component.text(entry.playerName),
                "role" to roleName(entry.role, player),
                "kills" to locale.text(entry.kills),
                "damage" to locale.text("%.1f".format(entry.damage)),
                "friendly" to locale.text("%.1f".format(entry.friendlyDamage)),
            )))
        }
        inventory.setItem(element(view, "combat"), item(Material.WRITABLE_BOOK, player, "menu.report.combat-name", "menu.report.combat-lore", mapOf(
            "events" to locale.text(report.combat.size),
        )))
        inventory.setItem(element(view, "back"), backItem(player))
        show(player, view, inventory)
    }

    private fun clickReport(player: Player, slot: Int) {
        val view = EventsView.Report
        when (slot) {
            element(view, "combat") -> open(player, EventsView.CombatLog(0))
            element(view, "back") -> open(player, EventsView.Ttt)
        }
    }

    private fun openCombatLog(player: Player, page: Int) {
        val report = service.report()
        if (report == null || service.participant(player.uniqueId) == null) return openReport(player)
        val view = EventsView.CombatLog(page)
        val slots = region(view, "entries")
        val maxPage = ((report.combat.size - 1).coerceAtLeast(0) / slots.size)
        val safePage = page.coerceIn(0, maxPage)
        val currentView = EventsView.CombatLog(safePage)
        val inventory = inventory(player, currentView, "menu.combat.title", mapOf(
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
        inventory.setItem(element(currentView, "back"), backItem(player))
        if (safePage > 0) inventory.setItem(element(currentView, "previous"), item(Material.ARROW, player, "menu.common.previous-name", "menu.common.previous-lore"))
        if (safePage < maxPage) inventory.setItem(element(currentView, "next"), item(Material.ARROW, player, "menu.common.next-name", "menu.common.next-lore"))
        show(player, currentView, inventory)
    }

    private fun clickCombatLog(player: Player, page: Int, slot: Int) {
        val view = EventsView.CombatLog(page)
        when (slot) {
            element(view, "back") -> openRoot(player, EventsView.Report)
            element(view, "previous") -> open(player, EventsView.CombatLog(page - 1))
            element(view, "next") -> open(player, EventsView.CombatLog(page + 1))
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
        titleKey: String,
        values: Map<String, Component> = emptyMap(),
    ): PaperMenuFrame {
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
        return menuRuntime.physicalFrame(
            ArcEventsMenuLayouts.menu(view),
            locale.render(titleKey, player, values),
            filler,
        )
    }

    private fun show(
        player: Player,
        view: EventsView,
        frame: PaperMenuFrame,
        arenaIds: List<String> = emptyList(),
        centerClick: (() -> Unit)? = null,
        soloSlot: Int? = null,
    ) {
        val adminSlot = if ((view == EventsView.Ttt || view is EventsView.Arcade) && player.canManageEventMenus())
            layouts.modeAdminSlot(view) else null
        if (adminSlot != null) frame.setItem(adminSlot,
            item(Material.COMMAND_BLOCK, player, "menu.event.admin-panel-name", "menu.event.admin-inventory-lore"))
        val active = ActiveFrame(view, frame, arenaIds.toList(), centerClick, soloSlot, adminSlot)
        activeFrames[player.uniqueId] = active
        menuRuntime.open(player, ArcEventsMenuLayouts.menu(view)) {
            val latest = activeFrames[player.uniqueId].takeIf { it === active } ?: active
            latest.frame.content { slot, _ -> scheduleClick(player, latest, slot) }
        }
    }

    private fun element(view: EventsView, id: String): Int = layouts.slot(view, id)

    private fun statusText(player: Player, entry: EventEntryStatus, participating: Boolean): Component =
        Component.join(JoinConfiguration.separator(Component.text(" · ")), entry.statusKeys(participating)
            .ifEmpty { listOf("state.idle") }.map { locale.render(it, player) })

    private fun region(view: EventsView, id: String): List<Int> = layouts.region(view, id)

    private fun item(
        material: Material,
        player: Player,
        nameKey: String,
        loreKey: String,
        values: Map<String, Component> = emptyMap(),
    ): ItemStack {
        val styleId = nameKey.removePrefix("menu.").removeSuffix("-name").replace('.', '-') +
            "-" + material.name.lowercase().replace('_', '-')
        val appearance = settings().ui.menuItems[styleId]
        val configuredMaterial = appearance?.material?.let(Material::matchMaterial)?.takeIf(Material::isItem) ?: material
        return ItemStack.of(configuredMaterial).also { stack ->
            stack.editMeta { meta ->
                meta.displayName(TttItems.nonItalic(locale.render(nameKey, player, values)))
                meta.lore(locale.lore(loreKey, player, values).map(TttItems::nonItalic))
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
                if (appearance != null && appearance.customModelData > 0) {
                    val model = meta.customModelDataComponent
                    model.floats = listOf(appearance.customModelData.toFloat())
                    meta.setCustomModelDataComponent(model)
                }
            }
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

    private fun roleName(role: TttRole, player: Player): Component = locale.render(
        when (role) {
            TttRole.INNOCENT -> "role.innocent-name"
            TttRole.TRAITOR -> "role.traitor-name"
            TttRole.DETECTIVE -> "role.detective-name"
        },
        player,
    )

    private fun weaponName(key: String, player: Player): Component = locale.render("weapon.kind.${key.replace('.', '-')}", player)

    private fun arenaName(id: String, player: Player): Component {
        val key = "arena.${if (id.equals("default", true)) "citadel" else id.lowercase()}.name"
        val label = locale.render(key, player)
        return if (label == Component.text(key)) locale.text(id) else label
    }

    private fun formatDuration(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

}

internal fun Player.canManageEventMenus() = hasPermission("arcevents.admin") && hasPermission("arcevents.play")

internal fun shopAccessible(current: TttMatch?, participant: TttParticipant?): Boolean =
    current?.phase == MatchPhase.ACTIVE && participant?.status == ParticipantStatus.ALIVE

internal fun evacuationAccessible(current: TttMatch?, participant: TttParticipant?): Boolean =
    current?.phase in setOf(
        MatchPhase.PREPARING,
        MatchPhase.COUNTDOWN,
        MatchPhase.ACTIVE,
        MatchPhase.RESOLVING,
        MatchPhase.INTERMISSION,
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

internal data class EventEntryStatus(
    val queue: QueueState?,
    val unavailable: ReservationStartResult? = null,
    val readFailed: Boolean = false,
    val inOtherMode: Boolean = false,
) {
    val ready get() = !readFailed && !inOtherMode && unavailable == null
    val hasRoute get() = queue != null && queue != QueueState.QUEUED
    val showCheck get() = !inOtherMode && (hasRoute || queue == null && !ready)
    val checkKey get() = if (hasRoute) "menu.event.check-route" else "menu.event.refresh"

    fun canStartSoloTest(participating: Boolean) = ready && !hasRoute && !participating

    fun statusKeys(participating: Boolean): List<String> = buildList {
        if (participating) { add("menu.event.state.matched"); return@buildList }
        if (inOtherMode) { add("dialog.other-match"); return@buildList }
        if (queue != null) add(if (queue == QueueState.MATCHED) "dialog.linked-match" else queueStateLocaleKey(queue))
        if (readFailed) add("dialog.read-failed")
        else if (!hasRoute && unavailable != null) add(if (unavailable == ReservationStartResult.BUSY) "dialog.busy" else "dialog.arena-pending")
    }
}
