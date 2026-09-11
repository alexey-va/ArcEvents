package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
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
    TRADE_DESTINATION(TRADE_COLOR, suffix = " ›"),
    ROOT_DESTINATION(WHITE_COLOR, suffix = " ›"),
    DESTRUCTIVE(RED_COLOR),
    UNAVAILABLE(WHITE_COLOR),
    BACK(WHITE_COLOR, prefix = "‹ "),
    CLOSE(WHITE_COLOR),
    ORDINARY(WARM_NEUTRAL_COLOR),
}

private val NATIVE_DESTINATIONS = mapOf(
    "menu.main.ttt-name" to NativeButtonRole.ACTIVITY_DESTINATION,
    "menu.main.stats-name" to NativeButtonRole.PERSONAL_DESTINATION,
    "menu.main.help-name" to NativeButtonRole.HELP_DESTINATION,
    "menu.main.admin-name" to NativeButtonRole.ROOT_DESTINATION,
    "menu.event.arena-name" to NativeButtonRole.PERSONAL_DESTINATION,
    "menu.event.roster-name" to NativeButtonRole.PLAYER_DESTINATION,
    "menu.event.shop-name" to NativeButtonRole.TRADE_DESTINATION,
    "menu.event.report-name" to NativeButtonRole.PERSONAL_DESTINATION,
    "menu.event.help-name" to NativeButtonRole.HELP_DESTINATION,
    "menu.admin.arenas-name" to NativeButtonRole.PERSONAL_DESTINATION,
)

private val NATIVE_TITLE_COLORS = mapOf(
    "menu.main.title" to WHITE_COLOR,
    "menu.event.title" to AMBER_COLOR,
    "menu.stats.title" to VIOLET_COLOR,
    "menu.help.title" to CYAN_COLOR,
    "menu.admin.title" to WHITE_COLOR,
)

private val INVENTORY_ACTION_FOOTER = Regex("^\\s*\\[\\s*▶\\s*]\\s*(?:Нажмите|Click)\\b", RegexOption.IGNORE_CASE)

internal fun dialogFrontendSupported(enabled: Boolean, protocolVersion: Int, view: EventsView): Boolean =
    enabled && protocolVersion >= MIN_DIALOG_PROTOCOL && view in setOf(
        EventsView.Main, EventsView.Help, EventsView.EventHelp, EventsView.Ttt,
        EventsView.Statistics, EventsView.Admin,
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
    private val present: (Player, PaperDialogScreen, (() -> Unit)?, () -> Unit, Boolean) -> Unit =
        { target, model, reopen, dismissed, closeOnEscape -> runtime.open(target, model, reopen, dismissed, closeOnEscape) },
) {
    fun open(player: Player, view: EventsView): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), view)) return false
        if (view == EventsView.Admin && !player.hasPermission("arcevents.admin")) return true
        val screen = when (view) {
            EventsView.Main -> main(player)
            EventsView.Help, EventsView.EventHelp -> help(player, view)
            EventsView.Statistics -> statistics(player)
            EventsView.Admin -> admin(player)
            else -> return false
        }
        show(player, view, screen)
        return true
    }

    fun openTtt(
        player: Player,
        queueState: QueueState?,
        plan: EventMenuPlan,
        selectedArena: Component,
        reopen: (() -> Unit)? = null,
        onDismiss: () -> Unit = {},
    ): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), EventsView.Ttt)) return false
        show(player, EventsView.Ttt, ttt(player, queueState, plan, selectedArena), reopen, onDismiss)
        return true
    }

    fun openTttLoading(player: Player, reopen: () -> Unit, onDismiss: () -> Unit): Boolean {
        if (!settings().ui.dialogsEnabled || !dialogFrontendSupported(true, protocols.resolve(player), EventsView.Ttt)) return false
        show(player, EventsView.Ttt, screen(player, "menu.event.title", listOf(body(player, "menu.event.loading-name", "menu.event.loading-lore")),
            emptyList(), 1, TTT_DIALOG_ID), reopen, onDismiss)
        return true
    }

    private fun show(player: Player, view: EventsView, screen: PaperDialogScreen, reopen: (() -> Unit)? = null, onDismiss: () -> Unit = {}) {
        present(player, screen, reopen ?: { open(player, view); Unit }, onDismiss, escapeCloses(player))
    }

    private fun main(player: Player): PaperDialogScreen {
        val state = service.snapshot()
        val actions = buildList {
            add(button(player, "menu.main.ttt-name", "menu.main.ttt-lore", EventsView.Main, 4,
                mapOf("queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().ttt.minimumPlayers),
                    "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player))))
            add(button(player, "arcade.gungame-name", "arcade.gungame-lore", EventsView.Main, 2,
                mapOf("queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().arcade.rules(EventMode.GUN_GAME).minimumPlayers))))
            add(button(player, "arcade.disasters-name", "arcade.disasters-lore", EventsView.Main, 6,
                mapOf("queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().arcade.rules(EventMode.DISASTERS).minimumPlayers))))
            add(button(player, "menu.main.stats-name", "menu.main.stats-lore", EventsView.Main, 18))
            add(button(player, "menu.main.help-name", "menu.main.help-lore", EventsView.Main, 22))
            if (player.hasPermission("arcevents.admin")) add(button(player, "menu.main.admin-name", "menu.main.admin-lore", EventsView.Main, 26))
        }
        return screen(player, "menu.main.title", listOf(body(player, "menu.main.title", "menu.main.main-body", mapOf(
            "queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().ttt.minimumPlayers),
            "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player)))), actions, 2, "events.main")
    }

    private fun ttt(player: Player, queueState: QueueState?, plan: EventMenuPlan, selectedArena: Component): PaperDialogScreen {
        val state = service.snapshot()
        val values = mapOf("queue" to locale.text(state.queueSize), "minimum" to locale.text(settings().ttt.minimumPlayers),
            "arena_state" to locale.render(if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable", player), "selected_arena" to selectedArena)
        val bodies = buildList {
            add(body(player, "menu.event.overview-name", "menu.event.overview-lore", values))
            if (plan.showQueueStatus) add(body(player, "menu.event.queue-name", "menu.event.queue-lore",
                values + ("queue_state" to locale.render(queueStateLocaleKey(requireNotNull(queueState)), player))))
        }
        val actions = buildList {
            when { plan.showJoin -> add(button(player, "menu.event.join-name", "menu.event.join-lore", EventsView.Ttt, 22))
                plan.showJoinUnavailable -> add(button(player, "menu.event.join-unavailable-name", "menu.event.join-unavailable-lore", EventsView.Ttt, 22))
                plan.showLeave -> add(button(player, "menu.event.leave-name", "menu.event.leave-lore", EventsView.Ttt, 22)) }
            if (plan.showStart) add(button(player, "menu.event.start-name", "menu.event.start-lore", EventsView.Ttt, 24, values))
            if (plan.showArenaSelection) add(button(player, "menu.event.arena-name", "menu.event.arena-lore", EventsView.Ttt, 29, values))
            if (plan.showRoster) add(button(player, "menu.event.roster-name", "menu.event.roster-lore", EventsView.Ttt, 20))
            if (plan.showShop) add(button(player, "menu.event.shop-name", "menu.event.shop-lore", EventsView.Ttt, 22))
            if (plan.showReport) add(button(player, "menu.event.report-name", "menu.event.report-lore", EventsView.Ttt, 24))
            add(button(player, "menu.event.help-name", "menu.event.help-lore", EventsView.Ttt, 31))
            if (plan.showEvacuate) add(button(player, "menu.event.evacuate-name", "menu.event.evacuate-lore", EventsView.Ttt, 40))
        }
        return screen(player, "menu.event.title", bodies, actions, 2, TTT_DIALOG_ID)
    }

    private fun statistics(player: Player) = screen(player, "menu.stats.title",
        listOf(body(player, "menu.stats.summary-name", "menu.stats.summary-lore", mapOf(
            "matches" to locale.text(service.stats(player.uniqueId).matches), "wins" to locale.text(service.stats(player.uniqueId).wins),
            "kills" to locale.text(service.stats(player.uniqueId).kills), "deaths" to locale.text(service.stats(player.uniqueId).deaths),
            "karma" to locale.text(service.stats(player.uniqueId).karma)))),
        emptyList(), 1, "events.stats")

    private fun help(player: Player, view: EventsView) = screen(player, "menu.help.title",
        listOf("innocent", "traitor", "detective", "flow", "evidence", "weapons", "controls").map {
            body(player, "menu.help.$it-name", "menu.help.$it-lore")
        }, emptyList(), 1, "events.help")

    private fun admin(player: Player): PaperDialogScreen {
        val state = service.snapshot()
        return screen(player, "menu.admin.title", listOf(body(player, "menu.admin.status-name", "menu.admin.status-lore", mapOf(
            "phase" to locale.render("phase.${state.phase?.name?.lowercase() ?: "idle"}", player), "match" to locale.text(state.matchId?.toString()?.take(8) ?: "—"),
            "queue" to locale.text(state.queueSize), "recovery" to locale.text(state.recoveryPending), "server" to locale.text(state.serverId),
            "node_mode" to locale.render("state.mode-${state.nodeMode.name.lowercase()}", player), "host" to locale.text(state.hostServer),
            "network_state" to locale.render(if (state.hostAvailable) "state.network-ready" else "state.network-degraded", player)))),
            listOf(button(player, "menu.admin.arenas-name", "menu.admin.arenas-lore", EventsView.Admin, 20), button(player, "menu.admin.start-name", "menu.admin.start-lore", EventsView.Admin, 29),
                button(player, "menu.admin.stop-name", "menu.admin.stop-lore", EventsView.Admin, 31), button(player, "menu.admin.reload-name", "menu.admin.reload-lore", EventsView.Admin, 33),
                button(player, "menu.admin.recover-name", "menu.admin.recover-lore", EventsView.Admin, 40)), 2, "events.admin")
    }

    private fun screen(player: Player, titleKey: String, bodies: List<PaperDialogBody>, buttons: List<PaperDialogButton>, columns: Int, id: String) =
        PaperDialogScreen(nativeTint(locale.render(titleKey, player), NATIVE_TITLE_COLORS[titleKey] ?: WHITE_COLOR), body = bodies.map { it.copy(text = nativeText(it.text)) }, buttons = buttons, columns = columns,
            exitButton = button(player, if (escapeCloses(player)) "menu.common.close-name" else "menu.common.back-name",
                if (escapeCloses(player)) "menu.common.close-lore" else "menu.common.back-lore", EventsView.Main, -1, width = 200), id = id)

    private fun body(player: Player, nameKey: String, loreKey: String, values: Map<String, Component> = emptyMap()) =
        PaperDialogBody(nativeText(Component.join(JoinConfiguration.newlines(), listOf(locale.render(nameKey, player, values)) + nativeLore(loreKey, player, values))), 468)

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
            .map(::nativeText)

    private fun isInventoryActionFooter(line: Component): Boolean {
        val text = PlainTextComponentSerializer.plainText().serialize(line).trim()
        return INVENTORY_ACTION_FOOTER.matches(text)
    }

    private fun button(player: Player, nameKey: String, loreKey: String, view: EventsView, slot: Int, values: Map<String, Component> = emptyMap(), width: Int = 230): PaperDialogButton {
        val role = nativeRole(nameKey, slot)
        return PaperDialogButton(PaperDialogActionId.of(if (slot < 0) "back" else "action_" + nameKey.replace(Regex("[^a-z0-9_]"), "_").take(38)), nativeLabel(player, locale.render(nameKey, player, values), role),
            tooltip = Component.join(JoinConfiguration.newlines(), nativeLore(loreKey, player, values)), width = width,
            onClick = PaperDialogClickHandler { context ->
                if (slot >= 0 && role != NativeButtonRole.UNAVAILABLE && context.player.isOnline) dispatch(context.player, view, slot)
            })
    }

    private fun nativeRole(nameKey: String, slot: Int): NativeButtonRole = when {
        slot < 0 && nameKey.endsWith("close-name") -> NativeButtonRole.CLOSE
        slot < 0 -> NativeButtonRole.BACK
        nameKey.contains("unavailable") -> NativeButtonRole.UNAVAILABLE
        nameKey in NATIVE_DESTINATIONS -> NATIVE_DESTINATIONS.getValue(nameKey)
        nameKey.contains("leave") || nameKey.contains("evacuate") || nameKey.contains("stop") -> NativeButtonRole.DESTRUCTIVE
        nameKey.contains("join") || nameKey.contains("start") || nameKey.contains("recover") -> NativeButtonRole.COMMIT
        else -> NativeButtonRole.ORDINARY
    }

    private fun nativeLabel(player: Player, value: Component, role: NativeButtonRole): Component = Component.empty()
        .append(if (role == NativeButtonRole.UNAVAILABLE && !hasUnavailablePrefix(value)) {
            nativeTint(locale.render("menu.common.unavailable-prefix", player), role.color)
        } else if (role.prefix.isNotEmpty() && !hasPrefix(value, role)) {
            Component.text(role.prefix, role.color)
        } else Component.empty())
        .append(nativeTint(value, role.color))
        .append(if (role.suffix.isNotEmpty() && !hasSuffix(value)) Component.text(role.suffix, role.color) else Component.empty())
        .decoration(TextDecoration.ITALIC, false)

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
