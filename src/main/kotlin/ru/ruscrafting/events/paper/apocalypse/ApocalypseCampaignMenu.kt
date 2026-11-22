package ru.ruscrafting.events.paper.apocalypse

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import ru.arc.network.NetworkPlayerName
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogClickHandler
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.paper.sendEventMessage

internal class ApocalypseCampaignMenu(
    private val runtime: PaperDialogRuntime,
    private val locale: ArcEventsLocale,
    private val native: (Player) -> Boolean,
    private val escapeCloses: (Player) -> Boolean,
    private val canManageCampaign: (Player) -> Boolean,
    private val campaignFriends: (Player) -> List<String>,
    private val command: (Player, List<String>) -> Unit,
) {
    fun openEntryFallback(player: Player) {
        if (!allowed(player)) return
        sendLinks(player, locale.render("apocalypse.menu-name", player), listOf(
            commandLink(player, "common-name", "common"), commandLink(player, "own-name", "my"),
            commandLink(player, "friend-name", "join", needsName = true),
        ))
    }

    fun openFriends(player: Player) {
        if (!allowed(player)) return
        if (!native(player)) {
            sendLinks(player, text(player, "friend-help"), listOf(commandLink(player, "join-action", "join", needsName = true)))
            return
        }
        runtime.beginFlow(player)
        friends(player)
    }

    fun openManage(player: Player) {
        if (!allowed(player, owner = true)) return
        if (!native(player)) {
            sendLinks(player, text(player, "manage-help"), MEMBER_ACTIONS.map { commandLink(player, "$it-name", it, needsName = true) })
            return
        }
        runtime.beginFlow(player)
        manage(player)
    }

    private fun friends(player: Player, page: Int = 0, name: String = "", invalid: Boolean = false) {
        if (!allowed(player)) return
        val names = campaignFriends(player).filter(::validName).distinctBy { it.lowercase() }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        val last = (names.size - 1).coerceAtLeast(0) / FRIENDS_PER_PAGE
        val current = page.coerceIn(0, last)
        val buttons = buildList {
            add(button(player, "join", text(player, "join-action")) { click ->
                val owner = click.text(NAME).orEmpty().trim()
                if (validName(owner)) run(player, "join", owner)
                else friends(player, current, owner.take(NAME_LENGTH), invalid = true)
            })
            names.drop(current * FRIENDS_PER_PAGE).take(FRIENDS_PER_PAGE).forEachIndexed { index, owner ->
                add(button(player, "friend_$index", text(player, "join-owner", "name" to locale.text(owner))) { run(player, "join", owner) })
            }
            if (current > 0) add(button(player, "previous", text(player, "previous")) {
                friends(player, current - 1, it.text(NAME).orEmpty().take(NAME_LENGTH))
            })
            if (current < last) add(button(player, "next", text(player, "next")) {
                friends(player, current + 1, it.text(NAME).orEmpty().take(NAME_LENGTH))
            })
        }
        show(player, "friends", "friend-title", if (invalid) listOf("friend-help", "name-error") else listOf("friend-help"),
            buttons, input = PaperDialogTextInput(NAME, text(player, "owner-name"), name, maxLength = NAME_LENGTH),
            columns = if (names.isEmpty()) 1 else 2)
    }

    private fun manage(player: Player) {
        if (!allowed(player, owner = true)) return
        val buttons = MEMBER_ACTIONS.map { action ->
            button(player, action, text(player, "link", "title" to text(player, "$action-name")), owner = true) {
                memberForm(player, action)
            }
        }
        show(player, "members", "manage-title", listOf("manage-help"), buttons, reopen = { manage(player) })
    }

    private fun memberForm(player: Player, action: String, value: String = "", invalid: Boolean = false) {
        if (!allowed(player, owner = true)) return
        val submit = button(player, "submit", text(player, "$action-action"), owner = true) {
            val name = it.text(NAME).orEmpty().trim()
            if (validName(name)) run(player, action, name)
            else memberForm(player, action, name.take(NAME_LENGTH), invalid = true)
        }
        show(player, "members.$action", "$action-name", if (invalid) listOf("$action-help", "name-error") else listOf("$action-help"),
            listOf(submit), PaperDialogTextInput(NAME, text(player, "player-name"), value, maxLength = NAME_LENGTH))
    }

    private fun show(player: Player, id: String, title: String, body: List<String>, buttons: List<PaperDialogButton>,
                     input: PaperDialogTextInput? = null, columns: Int = 1, reopen: (() -> Unit)? = null) {
        val note = Component.join(JoinConfiguration.newlines(), body.map { text(player, it) })
        val panel = DialogTables.filledBody(listOf(listOf(note)), null, 310, listOf(1), DialogTables.PanelSkin.EVERGREEN)
        runtime.open(player, PaperDialogScreen(title = text(player, title), body = listOf(panel), inputs = listOfNotNull(input),
            buttons = buttons, columns = columns, id = "events.apocalypse.campaign.$id"), reopen, {}, escapeCloses(player))
    }

    private fun button(player: Player, id: String, label: Component, owner: Boolean = false, click: (PaperDialogClickContext) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label, width = 158, onClick = PaperDialogClickHandler {
            if (it.player == player && allowed(player, owner)) click(it)
        })

    private fun allowed(player: Player, owner: Boolean = false): Boolean {
        if (!player.isOnline) return false
        val denied = when {
            !player.hasPermission("arcevents.play") -> "command.no-permission"
            owner && !canManageCampaign(player) -> "apocalypse.campaign-control.manage-denied"
            else -> return true
        }
        if (native(player)) runtime.close(player)
        player.sendEventMessage(locale.render(denied, player))
        return false
    }

    private fun run(player: Player, action: String, name: String) {
        runtime.close(player)
        command(player, listOf("apocalypse", action, name))
    }

    private fun commandLink(player: Player, key: String, action: String, needsName: Boolean = false): Component {
        val command = "/arcevents apocalypse $action"
        val example = locale.text(command).append(if (needsName) text(player, "name-argument") else Component.empty())
        return text(player, "command-link", "title" to text(player, key), "command" to example)
            .clickEvent(if (needsName) ClickEvent.suggestCommand("$command ") else ClickEvent.runCommand(command))
    }

    private fun sendLinks(player: Player, heading: Component, links: List<Component>) =
        player.sendEventMessage(Component.join(JoinConfiguration.newlines(), listOf(heading) + links))

    private fun text(player: Player, key: String, vararg values: Pair<String, Component>) =
        locale.render("apocalypse.campaign.$key", player, values.toMap()).decoration(TextDecoration.ITALIC, false)

    companion object {
        private val NAME_LENGTH = NetworkPlayerName.DEFAULT_POLICY.let { policy ->
            policy.maxBodyLength + (policy.bedrockPrefixes.maxOfOrNull(String::length) ?: 0)
        }
        private const val FRIENDS_PER_PAGE = 4
        private val NAME = PaperDialogInputId.of("player")
        private val MEMBER_ACTIONS = listOf("invite", "companion", "revoke")

        private fun validName(name: String) = NetworkPlayerName.parseOrNull(name) != null
    }
}
