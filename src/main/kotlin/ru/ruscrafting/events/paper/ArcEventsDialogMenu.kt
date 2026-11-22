package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.DialogTables.PanelSkin
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickHandler
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.network.QueueState
import ru.ruscrafting.events.domain.EventMode

internal const val MIN_DIALOG_PROTOCOL = 771 // Minecraft Java 1.21.6
internal const val TTT_DIALOG_ID = "events.ttt"
internal const val FISHING_DIALOG_ID = "events.fishing"
internal const val FISHING_PRIMARY_SLOT = 2
internal const val ZOMBIES_SOLO_SLOT = 1000
internal const val LEAVE_QUEUE_SLOT = 1001
internal const val LEAVE_MATCH_SLOT = 1002
internal const val ADMIN_SOLO_SLOT = 1003
private val WHITE_COLOR = TextColor.fromHexString("#ffffff")!!
private val GREEN_COLOR = TextColor.fromHexString("#9bd48d")!!
private val BLUE_COLOR = TextColor.fromHexString("#92bed8")!!
private val CYAN_COLOR = TextColor.fromHexString("#86dcf1")!!
private val PINK_COLOR = TextColor.fromHexString("#f3a2c9")!!
private val VIOLET_COLOR = TextColor.fromHexString("#c4a7e7")!!
private val TRADE_COLOR = TextColor.fromHexString("#f4d87a")!!
private val AMBER_COLOR = TextColor.fromHexString("#ffb277")!!
private val RED_COLOR = TextColor.fromHexString("#ff6b61")!!
private val WARM_NEUTRAL_COLOR = TextColor.fromHexString("#d7b486")!!
private val BODY_COLOR = TextColor.fromHexString("#e8dfd2")!!

private enum class NativeButtonRole(
    val color: TextColor,
    val prefix: String = "",
    val suffix: String = "",
) {
    COMMIT(GREEN_COLOR),
    ACTIVITY_DESTINATION(AMBER_COLOR, suffix = " ›"),
    HELP_DESTINATION(CYAN_COLOR, suffix = " ›"),
    PLAYER_DESTINATION(PINK_COLOR, suffix = " ›"),
    PERSONAL_DESTINATION(VIOLET_COLOR, suffix = " ›"),
    TEST(VIOLET_COLOR),
    TRADE_DESTINATION(TRADE_COLOR, suffix = " ›"),
    ROOT_DESTINATION(WHITE_COLOR, suffix = " ›"),
    DESTRUCTIVE(RED_COLOR),
    UNAVAILABLE(WHITE_COLOR),
    BACK(WHITE_COLOR, prefix = "‹ "),
    CLOSE(WHITE_COLOR),
    ORDINARY(WHITE_COLOR),
}

/** Apocalypse shares the catalog with the timed matches but has its own persistent world. */
private enum class CatalogMode(val match: EventMode?, val skin: PanelSkin) {
    TTT(EventMode.TTT, PanelSkin.PLUM),
    GUN_GAME(EventMode.GUN_GAME, PanelSkin.COCOA),
    DISASTERS(EventMode.DISASTERS, PanelSkin.NAVY),
    FISHING(EventMode.FISHING, PanelSkin.EVERGREEN),
    ZOMBIES(EventMode.ZOMBIES, PanelSkin.ORNATE),
    APOCALYPSE(null, PanelSkin.EVERGREEN);

    val id = match?.id ?: "apocalypse"
    val nameKey = when (match) {
        EventMode.TTT -> "menu.main.ttt-name"
        null -> "apocalypse.menu-name"
        else -> "arcade.${match.id}-name"
    }

    companion object {
        fun of(mode: EventMode) = entries.first { it.match == mode }
    }
}

private val NATIVE_DESTINATIONS = mapOf(
    "menu.main.ttt-name" to NativeButtonRole.ACTIVITY_DESTINATION,
    "arcade.gungame-name" to NativeButtonRole.ACTIVITY_DESTINATION,
    "arcade.disasters-name" to NativeButtonRole.ACTIVITY_DESTINATION,
    "arcade.fishing-name" to NativeButtonRole.ACTIVITY_DESTINATION,
    "arcade.zombies-name" to NativeButtonRole.ACTIVITY_DESTINATION,
    "apocalypse.menu-name" to NativeButtonRole.ACTIVITY_DESTINATION,
    "menu.main.stats-name" to NativeButtonRole.PERSONAL_DESTINATION,
    "menu.main.help-name" to NativeButtonRole.HELP_DESTINATION,
    "menu.main.admin-panel-name" to NativeButtonRole.ROOT_DESTINATION,
    "menu.event.arena-name" to NativeButtonRole.PERSONAL_DESTINATION,
    "menu.event.roster-name" to NativeButtonRole.PLAYER_DESTINATION,
    "menu.event.shop-name" to NativeButtonRole.TRADE_DESTINATION,
    "menu.event.report-name" to NativeButtonRole.PERSONAL_DESTINATION,
    "menu.report.combat-name" to NativeButtonRole.PERSONAL_DESTINATION,
    "menu.event.help-name" to NativeButtonRole.HELP_DESTINATION,
)

private val NATIVE_TITLE_COLORS = mapOf(
    "menu.main.title" to WHITE_COLOR,
    "menu.stats.title" to VIOLET_COLOR,
    "menu.report.title" to VIOLET_COLOR,
    "menu.help.title" to CYAN_COLOR,
)

private val INVENTORY_ACTION_FOOTER = Regex("^\\s*\\[\\s*▶\\s*]\\s*(?:Нажмите|Click)(?=\\s|[.,!]|$)", RegexOption.IGNORE_CASE)

internal fun dialogFrontendSupported(enabled: Boolean, protocolVersion: Int, view: EventsView): Boolean =
    enabled && protocolVersion >= MIN_DIALOG_PROTOCOL && view in setOf(
        EventsView.Main, EventsView.Help, EventsView.EventHelp, EventsView.Ttt,
        EventsView.Statistics, EventsView.Admin, EventsView.Fishing, EventsView.Apocalypse, EventsView.Report,
        EventsView.Arcade(EventMode.ZOMBIES), EventsView.Arcade(EventMode.GUN_GAME), EventsView.Arcade(EventMode.DISASTERS),
    )

@Suppress("UnstableApiUsage")
internal class ArcEventsDialogMenu(
    private val runtime: PaperDialogRuntime,
    private val service: ArcEventsService,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val dispatch: (Player, EventsView, Int) -> Unit,
    private val protocols: ClientProtocolResolver = ClientProtocolResolver(),
    private val escapeCloses: (Player) -> Boolean = { false },
    private val mainSlot: (String) -> Int,
    private val openAdmin: (Player) -> Boolean = { false },
    private val present: (Player, PaperDialogScreen, (() -> Unit)?, () -> Unit, Boolean) -> Unit =
        { target, model, reopen, dismissed, closeOnEscape -> runtime.open(target, model, reopen, dismissed, closeOnEscape) },
) {
    fun open(player: Player, view: EventsView): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), view)) return false
        if (view == EventsView.Admin) return !player.hasPermission("arcevents.admin") || openAdmin(player)
        val screen = when (view) {
            EventsView.Main -> main(player)
            EventsView.Help -> help(player)
            EventsView.EventHelp -> { openRules(player, EventMode.TTT); return true }
            EventsView.Statistics -> statistics(player)
            EventsView.Apocalypse -> apocalypse(player)
            else -> return false
        }
        show(player, view, screen)
        return true
    }

    fun openTtt(
        player: Player,
        entry: EventEntryStatus,
        plan: EventMenuPlan,
        selectedArena: Component,
        reopen: (() -> Unit)? = null,
        onDismiss: () -> Unit = {},
        queueSize: Int = 0,
        feedback: String? = null,
        checkEntry: () -> Unit = {},
    ): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), EventsView.Ttt)) return false
        show(player, EventsView.Ttt, ttt(player, entry, plan, selectedArena, queueSize, feedback, checkEntry), reopen, onDismiss)
        return true
    }

    fun openTttLoading(player: Player, reopen: () -> Unit, onDismiss: () -> Unit): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), EventsView.Ttt)) return false
        show(player, EventsView.Ttt, screen(player, CatalogMode.TTT.nameKey, listOf(panel(player, listOf("dialog.working"), skin = CatalogMode.TTT.skin)),
            emptyList(), 1, TTT_DIALOG_ID), reopen, onDismiss)
        return true
    }

    fun openFishing(player: Player, entry: EventEntryStatus, reopen: () -> Unit, feedback: String? = null,
                    checkEntry: () -> Unit = reopen, onDismiss: () -> Unit = {}): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), EventsView.Fishing)) return false
        show(player, EventsView.Fishing, fishing(player, entry, feedback, checkEntry), reopen, onDismiss)
        return true
    }

    fun openFishingLoading(player: Player, reopen: () -> Unit, checking: Boolean = false, onDismiss: () -> Unit): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), EventsView.Fishing)) return false
        show(player, EventsView.Fishing, screen(player, CatalogMode.FISHING.nameKey, listOf(
            panel(player, listOf(if (checking) "dialog.working" else "arcade.fishing-loading-lore"), skin = CatalogMode.FISHING.skin),
        ), emptyList(), 1, FISHING_DIALOG_ID), reopen, onDismiss)
        return true
    }

    private fun show(player: Player, view: EventsView, screen: PaperDialogScreen, reopen: (() -> Unit)? = null, onDismiss: () -> Unit = {}) {
        present(player, screen, reopen ?: { open(player, view); Unit }, onDismiss,
            screen.exitButton?.closeDialogBeforeAction ?: escapeCloses(player))
    }

    private fun main(player: Player): PaperDialogScreen {
        val state = service.snapshot()
        fun entry(element: String, key: String, values: Map<String, Component> = emptyMap(), actionKey: String = key): PaperDialogButton {
            val name = "$key-name"
            return styledButton(player, name, "$key-lore", NATIVE_DESTINATIONS.getValue(name), actionId("$actionKey-name"), values, 200) { actor ->
                if (element == "admin" && !actor.hasPermission("arcevents.admin")) open(actor, EventsView.Main)
                else dispatch(actor, EventsView.Main, mainSlot(element))
            }
        }
        val actions = buildList {
            add(entry("ttt", "menu.main.ttt",
                mapOf("queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().ttt.minimumPlayers),
                    "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player))))
            add(entry("gungame", "arcade.gungame",
                mapOf("queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().arcade.rules(EventMode.GUN_GAME).minimumPlayers))))
            add(entry("disasters", "arcade.disasters",
                mapOf("queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().arcade.rules(EventMode.DISASTERS).minimumPlayers))))
            add(entry("fishing", "arcade.fishing"))
            add(entry("zombies", "arcade.zombies"))
            add(entry("apocalypse", "apocalypse.menu"))
            add(entry("statistics", "menu.main.stats"))
            add(entry("help", "menu.main.help"))
            if (player.hasPermission("arcevents.admin")) add(entry("admin", "menu.main.admin-panel", actionKey = "menu.main.admin"))
        }
        val modes = EventMode.entries.map(service::modeUnavailable)
        val availability = when {
            null in modes -> "dialog.ready"
            ReservationStartResult.BUSY in modes -> "dialog.busy"
            else -> "dialog.arena-pending"
        }
        return screen(player, "menu.main.title", listOf(panel(player, listOf("dialog.root-status"), mapOf(
            "queue" to locale.text(state.queueSize), "availability" to locale.render(availability, player)))), actions, 2, "events.main")
    }

    private fun fishing(player: Player, entry: EventEntryStatus, feedback: String? = null, checkEntry: () -> Unit): PaperDialogScreen {
        val state = service.snapshot()
        val currentMode = service.currentMode()
        val fishingMatch = currentMode == EventMode.FISHING
        val phaseName = if (fishingMatch) state.phase?.name?.lowercase() else null
        val phase = locale.render("phase.${phaseName ?: "idle"}", player)
        val values = mapOf(
            "phase" to phase,
            "seconds" to locale.text(if (fishingMatch) state.secondsRemaining.coerceAtLeast(0) else 0),
            "maximum" to locale.text(settings().arcade.rules(EventMode.FISHING).roundSeconds),
            "arena" to locale.render("arena.fishing.name", player),
        )
        val participant = fishingMatch && service.arcadeSnapshot()?.participants?.get(player.uniqueId)?.status?.let {
            it != ru.ruscrafting.events.domain.ParticipantStatus.RESTORED
        } == true
        val actions = buildList {
            when {
                participant -> add(button(player, "menu.event.evacuate-name", "menu.event.evacuate-lore", EventsView.Fishing, LEAVE_MATCH_SLOT))
                entry.showCheck -> add(checkButton(player, EventsView.Fishing, entry, checkEntry))
                entry.queue == QueueState.QUEUED -> add(button(player, "menu.event.leave-name", "menu.event.leave-lore", EventsView.Fishing, LEAVE_QUEUE_SLOT))
                entry.ready -> add(button(player, "arcade.fishing-start-name", "arcade.fishing-start-lore", EventsView.Fishing, FISHING_PRIMARY_SLOT, values))
            }
            add(rulesButton(player, CatalogMode.FISHING))
            if (entry.canStartSoloTest(participant) && player.hasPermission("arcevents.admin"))
                add(soloButton(player, EventsView.Fishing, EventMode.FISHING))
        }
        val summary = values + ("minutes" to locale.text(((if (participant) state.secondsRemaining else settings().arcade.fishing.roundSeconds.toLong()) + 59) / 60))
        return screen(player, CatalogMode.FISHING.nameKey, listOfNotNull(panel(player, buildList {
            add("dialog.fishing-overview")
            add("dialog.map")
            add(if (participant) "dialog.fishing-active" else "dialog.fishing-facts")
            if (!participant) addAll(entry.statusKeys(false))
        }, summary, CatalogMode.FISHING.skin), feedback?.let { feedbackPanel(player, it) }), actions, 2, FISHING_DIALOG_ID)
    }

    private fun apocalypse(player: Player): PaperDialogScreen {
        val available = !service.isParticipant(player.uniqueId)
        val content = buildList {
            add("dialog.apocalypse-overview")
            add("dialog.apocalypse-facts")
            if (!available) add("dialog.other-match")
        }
        val actions = buildList {
            if (available) {
                if (player.hasPermission("arcevents.admin"))
                    add(button(player, "apocalypse.test-name", "apocalypse.test-lore", EventsView.Apocalypse, ADMIN_SOLO_SLOT))
                else add(button(player, "apocalypse.enter-name", "apocalypse.enter-lore", EventsView.Apocalypse, 0))
            }
            add(rulesButton(player, CatalogMode.APOCALYPSE))
        }
        return screen(player, CatalogMode.APOCALYPSE.nameKey, listOf(panel(player, content, skin = CatalogMode.APOCALYPSE.skin)),
            actions, 2, "events.apocalypse")
    }

    private fun soloButton(player: Player, view: EventsView, mode: EventMode) =
        button(player, "menu.admin.solo-test-name", "menu.admin.solo-test-lore", view, ADMIN_SOLO_SLOT, locale.soloTestText(player, mode))

    fun openArcade(player: Player, mode: EventMode, entry: EventEntryStatus, canStart: Boolean, participating: Boolean, arena: Component,
                   joinSlot: Int, startSlot: Int, reopen: () -> Unit, feedback: String? = null, queueSize: Int = 0,
                   checkEntry: () -> Unit = reopen): Boolean {
        val view = EventsView.Arcade(mode)
        val catalog = CatalogMode.of(mode)
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), view)) return false
        val values = ruleValues(mode) + mapOf("queue" to locale.text(queueSize), "arena" to arena,
            "mode" to locale.render("arcade.${mode.id}-name", player))
        val queued = entry.queue == QueueState.QUEUED
        val actions = buildList {
            if (mode == EventMode.ZOMBIES && entry.queue == null && !participating && entry.ready)
                add(button(player, "arcade.zombies-solo-name", "arcade.zombies-solo-lore", view, ZOMBIES_SOLO_SLOT, values))
            if (canStart) add(button(player, "arcade.start-name", "arcade.start-lore", view, startSlot, values))
            if (!participating && entry.showCheck) add(checkButton(player, view, entry, checkEntry))
            else if (participating || !entry.inOtherMode && (entry.ready || queued)) add(button(player,
                when { participating -> "menu.event.evacuate-name"; queued -> "arcade.leave-name"; mode == EventMode.ZOMBIES -> "arcade.zombies-team-name"; else -> "arcade.join-name" },
                when { participating -> "menu.event.evacuate-lore"; queued -> "arcade.leave-lore"; mode == EventMode.ZOMBIES -> "arcade.zombies-team-lore"; else -> "arcade.join-lore" },
                view, when { participating -> LEAVE_MATCH_SLOT; queued -> LEAVE_QUEUE_SLOT; else -> joinSlot }, values))
            add(rulesButton(player, catalog))
            if (entry.canStartSoloTest(participating) && player.hasPermission("arcevents.admin"))
                add(soloButton(player, view, mode))
        }
        val content = buildList {
            add("dialog.${mode.id}-overview")
            if (entry.ready || participating || !mode.usesSharedArenas) add("dialog.map")
            if (!participating) add("dialog.queue-facts")
            addAll(entry.statusKeys(participating))
        }
        show(player, view, screen(player, catalog.nameKey, listOfNotNull(panel(player, content, values, catalog.skin), feedback?.let { feedbackPanel(player, it) }), actions, 2,
            "events.${mode.id}"), reopen)
        return true
    }

    fun openArcadeLoading(player: Player, mode: EventMode, reopen: () -> Unit, onDismiss: () -> Unit): Boolean {
        val view = EventsView.Arcade(mode)
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), view)) return false
        val catalog = CatalogMode.of(mode)
        show(player, view, screen(player, catalog.nameKey, listOf(panel(player, listOf("dialog.working"), skin = catalog.skin)),
            emptyList(), 1, "events.${mode.id}"), reopen, onDismiss)
        return true
    }

    private fun ttt(player: Player, entry: EventEntryStatus, plan: EventMenuPlan, selectedArena: Component, queueSize: Int, feedback: String?, checkEntry: () -> Unit): PaperDialogScreen {
        val values = mapOf("queue" to locale.text(queueSize), "minimum" to locale.text(settings().ttt.minimumPlayers),
            "maximum" to locale.text(settings().ttt.maximumPlayers), "selected_arena" to selectedArena)
        val bodies = listOfNotNull(panel(player, buildList {
            add("dialog.ttt-overview")
            if (entry.ready || plan.showEvacuate || plan.showArenaSelection) add("dialog.ttt-map")
            if (!plan.showRoster) add("dialog.queue-facts")
            addAll(entry.statusKeys(plan.showEvacuate))
        }, values, CatalogMode.TTT.skin), feedback?.let { feedbackPanel(player, it) })
        val actions = buildList {
            when { plan.showJoin -> add(button(player, "menu.event.join-name", "menu.event.join-lore", EventsView.Ttt, 22))
                plan.showLeave -> add(button(player, "menu.event.leave-name", "menu.event.leave-lore", EventsView.Ttt, LEAVE_QUEUE_SLOT))
                !plan.showEvacuate && entry.showCheck -> add(checkButton(player, EventsView.Ttt, entry, checkEntry)) }
            if (plan.showStart && queueSize >= settings().ttt.minimumPlayers) add(button(player, "menu.event.start-name", "menu.event.start-lore", EventsView.Ttt, 24, values))
            if (plan.showArenaSelection) add(button(player, "menu.event.arena-name", "menu.event.arena-lore", EventsView.Ttt, 29, values))
            if (plan.showRoster) add(button(player, "menu.event.roster-name", "menu.event.roster-lore", EventsView.Ttt, 20))
            if (plan.showShop) add(button(player, "menu.event.shop-name", "menu.event.shop-lore", EventsView.Ttt, 22))
            if (plan.showReport) add(button(player, "menu.event.report-name", "menu.event.report-lore", EventsView.Ttt, 24))
            add(rulesButton(player, CatalogMode.TTT))
            if (plan.showEvacuate) add(button(player, "menu.event.evacuate-name", "menu.event.evacuate-lore", EventsView.Ttt, 40))
            if (entry.canStartSoloTest(plan.showEvacuate) && player.hasPermission("arcevents.admin"))
                add(soloButton(player, EventsView.Ttt, EventMode.TTT))
        }
        return screen(player, CatalogMode.TTT.nameKey, bodies, actions, 2, TTT_DIALOG_ID)
    }

    fun openReport(player: Player, report: RoundReportView, page: Int, combatSlot: Int,
                   reopen: () -> Unit, changePage: (Int) -> Unit): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), EventsView.Report)) return false
        val pageSize = 8
        val last = (report.participants.size - 1).coerceAtLeast(0) / pageSize
        val current = page.coerceIn(0, last)
        val values = mapOf(
            "winner" to locale.render(if (report.winner == ru.ruscrafting.events.domain.TttTeam.INNOCENTS) "report.winner-innocents" else "report.winner-traitors", player),
            "reason" to locale.render("report.reason.${report.reason.name.lowercase()}", player),
            "time" to locale.text("%d:%02d".format(report.durationSeconds / 60, report.durationSeconds % 60)),
            "events" to locale.text(report.combat.size),
        )
        val rows = report.participants.drop(current * pageSize).take(pageSize).map { entry -> listOf(
            locale.text(entry.playerName),
            locale.render("role.${entry.role.name.lowercase()}-label", player),
            locale.text(entry.kills),
            locale.render("dialog.report-damage", player, mapOf("damage" to locale.text("%.1f".format(entry.damage)),
                "friendly" to locale.text("%.1f".format(entry.friendlyDamage)))),
        ) }
        val body = listOf(
            DialogTables.filledBody(listOf(listOf(nativeText(Component.join(JoinConfiguration.newlines(),
                locale.lore("menu.report.summary-lore", player, values))))), null, 400, listOf(1), DialogTables.PanelSkin.NAVY),
            DialogTables.filledBody(rows.map { row -> row.map(::nativeText) },
                listOf("player", "role", "kills", "damage-header").map { nativeText(locale.render("dialog.report-$it", player)) },
                400, listOf(3, 2, 1, 2), DialogTables.PanelSkin.NAVY),
        )
        val actions = buildList {
            add(button(player, "menu.report.combat-name", "menu.report.combat-lore", EventsView.Report, combatSlot, values))
            if (current > 0) add(PaperDialogButton(PaperDialogActionId.of("previous"),
                nativeText(locale.render("menu.common.previous-name", player)), width = 200,
                onClick = PaperDialogClickHandler { changePage(current - 1) }))
            if (current < last) add(PaperDialogButton(PaperDialogActionId.of("next"),
                nativeText(locale.render("menu.common.next-name", player)), width = 200,
                onClick = PaperDialogClickHandler { changePage(current + 1) }))
        }
        show(player, EventsView.Report, screen(player, "menu.report.title", body, actions, 2, "events.report"), reopen)
        return true
    }

    private fun statistics(player: Player) = screen(player, "menu.stats.title",
        listOf(body(player, "menu.stats.summary-name", "menu.stats.summary-lore", mapOf(
            "matches" to locale.text(service.stats(player.uniqueId).matches), "wins" to locale.text(service.stats(player.uniqueId).wins),
            "kills" to locale.text(service.stats(player.uniqueId).kills), "deaths" to locale.text(service.stats(player.uniqueId).deaths),
            "karma" to locale.text(service.stats(player.uniqueId).karma)))),
        emptyList(), 1, "events.stats")

    private fun help(player: Player) = screen(player, "dialog.rules-index-title", emptyList(),
        CatalogMode.entries.map { rulesButton(player, it, catalog = true) }, 2, "events.help")

    private fun ruleValues(mode: EventMode): Map<String, Component> {
        if (mode == EventMode.TTT) return emptyMap()
        val rules = settings().arcade.rules(mode)
        return mapOf("waves" to locale.text(settings().zombies.waves), "minimum" to locale.text(rules.minimumPlayers),
            "maximum" to locale.text(rules.maximumPlayers), "rounds" to locale.text(rules.disasterRounds))
    }

    private fun rulesButton(player: Player, mode: CatalogMode, catalog: Boolean = false) =
        PaperDialogButton(PaperDialogActionId.of("rules_${mode.id}"),
            nativeLabel(player, locale.render(if (catalog) mode.nameKey else "dialog.rules-name", player),
                if (catalog) NativeButtonRole.ACTIVITY_DESTINATION else NativeButtonRole.HELP_DESTINATION),
            tooltip = if (catalog) Component.empty() else nativeText(locale.render("dialog.rules-tooltip", player)), width = 200,
            onClick = PaperDialogClickHandler { openRules(it.player, mode) })

    internal fun openRules(player: Player, mode: EventMode, page: Int = 0) = openRules(player, CatalogMode.of(mode), page)

    private fun openRules(player: Player, mode: CatalogMode, page: Int = 0) {
        val lastPage = if (mode == CatalogMode.TTT || mode == CatalogMode.ZOMBIES) 1 else 0
        val safePage = page.coerceIn(0, lastPage)
        val name = locale.render(mode.nameKey, player)
        val model = screen(player, "dialog.rules-title", listOf(panel(player,
            listOf("dialog.rules-${mode.id}-$safePage"), mode.match?.let(::ruleValues).orEmpty(), mode.skin)), buildList {
            if (safePage > 0) add(PaperDialogButton(PaperDialogActionId.of("previous"),
                nativeText(locale.render("dialog.rules-previous", player)), width = 220,
                onClick = PaperDialogClickHandler { openRules(it.player, mode, safePage - 1) }))
            if (safePage < lastPage) add(PaperDialogButton(PaperDialogActionId.of("next"),
                nativeText(locale.render("dialog.rules-next", player)), width = 220,
                onClick = PaperDialogClickHandler { openRules(it.player, mode, safePage + 1) }))
        }, 1, "events.rules.${mode.id}.$safePage").copy(
            title = nativeTint(locale.render("dialog.rules-title", player, mapOf("mode" to name)), CYAN_COLOR))
        present(player, model, { openRules(player, mode, safePage) }, {}, model.exitButton!!.closeDialogBeforeAction)
    }

    private fun feedbackPanel(player: Player, key: String) = panel(player, listOf(when (key) {
        "queue.unavailable" -> "dialog.error-unavailable"
        else -> if (key.startsWith("queue.")) "dialog.${key.substringAfter('.')}" else key
    }))

    private fun panel(player: Player, keys: List<String>, values: Map<String, Component> = emptyMap(),
                      skin: PanelSkin = PanelSkin.NAVY): PaperDialogBody =
        DialogTables.filledBody(rows = listOf(listOf(nativeText(Component.join(JoinConfiguration.newlines(),
            keys.flatMapIndexed { index, key ->
                val lines = locale.lore(key, player, values)
                if (index == 0 && key.endsWith("-overview") && keys.size > 1) lines + Component.empty() else lines
            })))), headers = null, width = 340,
            columnWeights = listOf(1), skin = skin)

    private fun screen(player: Player, titleKey: String, bodies: List<PaperDialogBody>, buttons: List<PaperDialogButton>, columns: Int, id: String): PaperDialogScreen {
        val closes = id == "events.main" || escapeCloses(player)
        val title = locale.render(titleKey, player)
        val styledTitle = if (NATIVE_DESTINATIONS[titleKey] == NativeButtonRole.ACTIVITY_DESTINATION) nativeText(title)
            else nativeTint(title, NATIVE_TITLE_COLORS[titleKey] ?: WHITE_COLOR)
        return PaperDialogScreen(styledTitle, body = bodies.map { it.copy(text = nativeText(it.text)) }, buttons = buttons, columns = columns,
            exitButton = button(player, if (closes) "menu.common.close-name" else "menu.common.back-name",
                if (closes) "menu.common.close-lore" else "menu.common.back-lore", EventsView.Main, -1, width = 200), id = id)
    }

    private fun body(player: Player, nameKey: String, loreKey: String, values: Map<String, Component> = emptyMap()): PaperDialogBody {
        val content = nativeText(Component.join(JoinConfiguration.newlines(), listOf(locale.render(nameKey, player, values)) + nativeLore(loreKey, player, values)))
        return when (nameKey) {
            "menu.main.title", "menu.event.overview-name" -> DialogTables.filledBody(
                rows = listOf(listOf(content)), headers = null, width = 400, columnWeights = listOf(1))
            "menu.stats.summary-name" -> DialogTables.filledBody(listOf(listOf(content)), null, 320, listOf(1), DialogTables.PanelSkin.PLUM)
            "arcade.fishing-guide-name" -> DialogTables.framedBody(content, frame = DialogTables.Frame.UNCOMMON, width = 400)
            else -> PaperDialogBody(content, 468)
        }
    }

    private fun nativeText(value: Component): Component = value
        .color(nativeColor(value.color()))
        .decoration(TextDecoration.ITALIC, false)
        .children(value.children().map(::nativeText))

    private fun nativeTint(value: Component, color: TextColor, root: Boolean = true): Component {
        val source = value.color()
        val tinted = if (root || source?.value() in NATIVE_NEUTRALS) value.color(color) else value
        return tinted.decoration(TextDecoration.ITALIC, false)
            .children(tinted.children().map { nativeTint(it, color, root = false) })
    }

    private fun nativeColor(color: TextColor?): TextColor? = when (color?.value()) {
        0x20252b -> BODY_COLOR
        0xe3b341 -> AMBER_COLOR
        0xd6a8ff, 0xc58cff -> VIOLET_COLOR
        0x5fb3ff, 0x92bed8 -> BLUE_COLOR
        0x7bd88f, 0x79c99e -> GREEN_COLOR
        0xd84a4a -> RED_COLOR
        0xff9f0f -> TRADE_COLOR
        0xf4bd6a -> AMBER_COLOR
        0x86dcf1 -> CYAN_COLOR
        0xf3a2c9 -> PINK_COLOR
        0xc4a7e7 -> VIOLET_COLOR
        0xf4d87a -> TRADE_COLOR
        0xffb277 -> AMBER_COLOR
        0xf2f0e6 -> WHITE_COLOR
        0xe6fff3 -> BODY_COLOR
        in NATIVE_NEUTRALS -> BODY_COLOR
        else -> color
    }

    private fun nativeLore(path: String, player: Player, values: Map<String, Component>): List<Component> =
        locale.lore(path, player, values)
            .filterNot(::isInventoryActionFooter)
            .dropWhile { PlainTextComponentSerializer.plainText().serialize(it).isBlank() }
            .dropLastWhile { PlainTextComponentSerializer.plainText().serialize(it).isBlank() }
            .map(::nativeText)

    private fun isInventoryActionFooter(line: Component): Boolean {
        val text = PlainTextComponentSerializer.plainText().serialize(line).trim()
        return INVENTORY_ACTION_FOOTER.containsMatchIn(text)
    }

    private fun button(player: Player, nameKey: String, loreKey: String, view: EventsView, slot: Int, values: Map<String, Component> = emptyMap(), width: Int = 200): PaperDialogButton {
        val role = nativeRole(nameKey, slot)
        val id = when (role) {
            NativeButtonRole.BACK -> PaperDialogActionId.of("back")
            NativeButtonRole.CLOSE -> PaperDialogActionId.of("close")
            else -> actionId(nameKey)
        }
        return styledButton(player, nameKey, loreKey, role, id, values, width) { actor ->
            if (slot >= 0 && role != NativeButtonRole.UNAVAILABLE) dispatch(actor, view, slot)
        }
    }

    private fun actionId(nameKey: String) = PaperDialogActionId.of("action_" + nameKey.replace(Regex("[^a-z0-9_]"), "_").take(38))

    private fun styledButton(player: Player, nameKey: String, loreKey: String, role: NativeButtonRole, id: PaperDialogActionId,
                             values: Map<String, Component>, width: Int, click: (Player) -> Unit): PaperDialogButton =
        PaperDialogButton(id, nativeLabel(player, locale.render(nameKey, player, values), role),
            tooltip = Component.join(JoinConfiguration.newlines(), nativeLore(loreKey, player, values)), width = width,
            closeDialogBeforeAction = role == NativeButtonRole.CLOSE,
            onClick = PaperDialogClickHandler { context -> if (context.player.isOnline) click(context.player) })

    private fun checkButton(player: Player, view: EventsView, entry: EventEntryStatus, checkEntry: () -> Unit): PaperDialogButton =
        button(player, "${entry.checkKey}-name", "${entry.checkKey}-lore", view, 0)
            .copy(onClick = PaperDialogClickHandler { if (it.player.isOnline) checkEntry() })

    private fun nativeRole(nameKey: String, slot: Int): NativeButtonRole = when {
        slot < 0 && nameKey.endsWith("close-name") -> NativeButtonRole.CLOSE
        slot < 0 -> NativeButtonRole.BACK
        slot == ADMIN_SOLO_SLOT -> NativeButtonRole.TEST
        nameKey == "apocalypse.enter-name" -> NativeButtonRole.COMMIT
        nameKey.contains("unavailable") -> NativeButtonRole.UNAVAILABLE
        nameKey in NATIVE_DESTINATIONS -> NATIVE_DESTINATIONS.getValue(nameKey)
        nameKey.contains("leave") || nameKey.contains("evacuate") || nameKey.contains("stop") -> NativeButtonRole.DESTRUCTIVE
        nameKey.contains("join") || nameKey.contains("start") || nameKey.contains("recover") || nameKey == "arcade.zombies-solo-name" -> NativeButtonRole.COMMIT
        else -> NativeButtonRole.ORDINARY
    }

    private fun nativeLabel(player: Player, value: Component, role: NativeButtonRole): Component {
        val color = if (role == NativeButtonRole.ACTIVITY_DESTINATION) value.color() ?: role.color else role.color
        return Component.empty()
        .append(if (role == NativeButtonRole.UNAVAILABLE && !hasUnavailablePrefix(value)) {
            nativeTint(locale.render("menu.common.unavailable-prefix", player), role.color)
        } else if (role.prefix.isNotEmpty() && !hasPrefix(value, role)) {
            Component.text(role.prefix, role.color)
        } else Component.empty())
        .append(nativeTint(value, color))
        .append(if (role.suffix.isNotEmpty() && !hasSuffix(value)) Component.text(role.suffix, color) else Component.empty())
        .decoration(TextDecoration.ITALIC, false)
    }

    private val NATIVE_NEUTRALS = setOf(
        0x20252b, 0x555555, 0x666666, 0x707070, 0x707a76, 0x77736d, 0x777777,
        0x8c8c8c, 0x969696, 0x9aa8b7, 0xaaaaaa, 0xaaa49a, 0xb8b8b8, 0xb8c8c0,
        0xc9c3ba, 0xd0d0d0,
    )

    private fun hasPrefix(value: Component, role: NativeButtonRole): Boolean {
        val text = PlainTextComponentSerializer.plainText().serialize(value).trimStart()
        return role == NativeButtonRole.BACK && text.startsWith("‹")
    }

    private fun hasUnavailablePrefix(value: Component): Boolean {
        val text = PlainTextComponentSerializer.plainText().serialize(value).trimStart()
        return text.startsWith("[Недоступно]") || text.startsWith("[Unavailable]")
    }

    private fun hasSuffix(value: Component): Boolean =
        PlainTextComponentSerializer.plainText().serialize(value).trimEnd().endsWith("›")
}
