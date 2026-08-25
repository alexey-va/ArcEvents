package ru.ruscrafting.events.paper

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import java.time.Duration

internal const val MIN_DIALOG_PROTOCOL = 771 // Minecraft Java 1.21.6

internal fun dialogFrontendSupported(enabled: Boolean, protocolVersion: Int, view: EventsView): Boolean =
    enabled && protocolVersion >= MIN_DIALOG_PROTOCOL && view in setOf(EventsView.Main, EventsView.Help, EventsView.Admin)

@Suppress("UnstableApiUsage")
internal class ArcEventsDialogMenu(
    private val service: ArcEventsService,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val dispatch: (Player, EventsView, Int) -> Unit,
    private val protocols: ClientProtocolResolver = ClientProtocolResolver(),
) {
    fun open(player: Player, view: EventsView): Boolean {
        if (!dialogFrontendSupported(settings().ui.dialogsEnabled, protocols.resolve(player), view)) return false
        if (view == EventsView.Admin && !player.hasPermission("arcevents.admin")) return true
        val dialog = when (view) {
            EventsView.Main -> main(player)
            EventsView.Help -> help(player)
            EventsView.Admin -> admin(player)
            else -> return false
        }
        return runCatching { player.showDialog(dialog) }.isSuccess
    }

    private fun main(player: Player): Dialog {
        val state = service.snapshot()
        val stats = service.stats(player.uniqueId)
        val bodies = listOf(
            body(
                Material.SPYGLASS,
                player,
                "menu.main.ttt-name",
                "menu.main.ttt-lore",
                mapOf(
                    "queue" to locale.text(state.queueSize),
                    "minimum" to locale.text(settings().ttt.minimumPlayers),
                    "arena_state" to locale.render(
                        if (state.arenaReady) "state.arena-ready" else "state.arena-unavailable",
                        player,
                    ),
                ),
            ),
            body(
                Material.RECOVERY_COMPASS,
                player,
                "menu.main.status-name",
                "menu.main.status-lore",
                mapOf(
                    "server" to locale.text(state.serverId),
                    "node_mode" to locale.render("state.mode-${state.nodeMode.name.lowercase()}", player),
                    "host" to locale.text(state.hostServer),
                    "state" to locale.render(
                        if (state.hostAvailable) "state.network-ready" else "state.network-degraded",
                        player,
                    ),
                ),
            ),
            body(
                Material.WRITABLE_BOOK,
                player,
                "menu.main.stats-name",
                "menu.main.stats-lore",
                mapOf(
                    "matches" to locale.text(stats.matches),
                    "wins" to locale.text(stats.wins),
                    "kills" to locale.text(stats.kills),
                    "deaths" to locale.text(stats.deaths),
                    "karma" to locale.text(stats.karma),
                ),
            ),
        )
        val actions = buildList {
            add(button(
                player,
                if (state.hostAvailable) "menu.main.join-name" else "menu.main.join-unavailable-name",
                if (state.hostAvailable) "menu.main.join-lore" else "menu.main.join-unavailable-lore",
                EventsView.Main,
                20,
            ))
            add(button(player, "menu.main.leave-name", "menu.main.leave-lore", EventsView.Main, 24))
            if (service.roster(player.uniqueId) != null) {
                add(button(player, "menu.main.roster-name", "menu.main.roster-lore", EventsView.Main, 29))
            }
            add(button(player, "menu.main.help-name", "menu.main.help-lore", EventsView.Main, 33))
            add(button(player, "menu.main.shop-name", "menu.main.shop-lore", EventsView.Main, 40))
            if (service.report() != null && service.participant(player.uniqueId) != null) {
                add(button(player, "menu.main.report-name", "menu.main.report-lore", EventsView.Main, 41))
            }
            if (player.hasPermission("arcevents.admin")) {
                add(button(player, "menu.main.admin-name", "menu.main.admin-lore", EventsView.Main, 49))
            }
        }
        return dialog(player, "menu.main.title", bodies, actions, columns = 2)
    }

    private fun help(player: Player): Dialog = dialog(
        player,
        "menu.help.title",
        listOf(
            body(Material.EMERALD, player, "menu.help.innocent-name", "menu.help.innocent-lore"),
            body(Material.REDSTONE, player, "menu.help.traitor-name", "menu.help.traitor-lore"),
            body(Material.LAPIS_LAZULI, player, "menu.help.detective-name", "menu.help.detective-lore"),
            body(Material.CLOCK, player, "menu.help.flow-name", "menu.help.flow-lore"),
            body(Material.PLAYER_HEAD, player, "menu.help.evidence-name", "menu.help.evidence-lore"),
            body(Material.CROSSBOW, player, "menu.help.weapons-name", "menu.help.weapons-lore"),
            body(Material.COMMAND_BLOCK, player, "menu.help.controls-name", "menu.help.controls-lore"),
        ),
        listOf(button(player, "menu.common.back-name", "menu.common.back-lore", EventsView.Help, 36)),
        columns = 1,
    )

    private fun admin(player: Player): Dialog {
        val state = service.snapshot()
        return dialog(
            player,
            "menu.admin.title",
            listOf(body(
                Material.OBSERVER,
                player,
                "menu.admin.status-name",
                "menu.admin.status-lore",
                mapOf(
                    "phase" to locale.render("phase.${state.phase?.name?.lowercase() ?: "idle"}", player),
                    "match" to locale.text(state.matchId?.toString()?.take(8) ?: "—"),
                    "queue" to locale.text(state.queueSize),
                    "recovery" to locale.text(state.recoveryPending),
                ),
            )),
            listOf(
                button(player, "menu.admin.arenas-name", "menu.admin.arenas-lore", EventsView.Admin, 20),
                button(player, "menu.admin.start-name", "menu.admin.start-lore", EventsView.Admin, 29),
                button(player, "menu.admin.stop-name", "menu.admin.stop-lore", EventsView.Admin, 31),
                button(player, "menu.admin.reload-name", "menu.admin.reload-lore", EventsView.Admin, 33),
                button(player, "menu.admin.recover-name", "menu.admin.recover-lore", EventsView.Admin, 40),
                button(player, "menu.common.back-name", "menu.common.back-lore", EventsView.Admin, 45),
            ),
            columns = 2,
        )
    }

    private fun dialog(
        player: Player,
        titleKey: String,
        bodies: List<DialogBody>,
        actions: List<ActionButton>,
        columns: Int,
    ): Dialog = Dialog.create { factory ->
        factory.empty()
            .base(
                DialogBase.builder(TttItems.nonItalic(locale.render(titleKey, player)))
                    .canCloseWithEscape(true)
                    .pause(false)
                    .afterAction(DialogBase.DialogAfterAction.CLOSE)
                    .body(bodies)
                    .build(),
            )
            .type(DialogType.multiAction(actions, closeButton(player), columns))
    }

    private fun body(
        material: Material,
        player: Player,
        nameKey: String,
        loreKey: String,
        values: Map<String, Component> = emptyMap(),
    ): DialogBody {
        val name = TttItems.nonItalic(locale.render(nameKey, player, values))
        val lore = locale.lore(loreKey, player, values).map(TttItems::nonItalic)
        val stack = ItemStack.of(material).also { item ->
            item.editMeta { meta ->
                meta.displayName(name)
                meta.lore(lore)
                meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            }
        }
        val description = Component.join(JoinConfiguration.newlines(), listOf(name) + lore)
        return DialogBody.item(stack)
            .description(DialogBody.plainMessage(description, 360))
            .showDecorations(true)
            .showTooltip(true)
            .width(32)
            .height(32)
            .build()
    }

    private fun button(
        player: Player,
        nameKey: String,
        loreKey: String,
        view: EventsView,
        slot: Int,
    ): ActionButton = ActionButton.builder(TttItems.nonItalic(locale.render(nameKey, player)))
        .tooltip(Component.join(JoinConfiguration.newlines(), locale.lore(loreKey, player).map(TttItems::nonItalic)))
        .width(150)
        .action(DialogAction.customClick(
            DialogActionCallback { _, audience ->
                val clicked = audience as? Player ?: return@DialogActionCallback
                Tasks.scheduler.runLater(1L) {
                    if (clicked.isOnline) dispatch(clicked, view, slot)
                }
            },
            ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build(),
        ))
        .build()

    private fun closeButton(player: Player): ActionButton = ActionButton
        .builder(TttItems.nonItalic(locale.render("menu.common.close-name", player)))
        .tooltip(Component.join(
            JoinConfiguration.newlines(),
            locale.lore("menu.common.close-lore", player).map(TttItems::nonItalic),
        ))
        .width(150)
        .build()
}
