package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.TttTeam

class ArcEventsCommand(
    private val plugin: Plugin,
    private val service: ArcEventsService,
    private val menu: ArcEventsMenu,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val reload: () -> Result<Unit>,
) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (sender is Player) menu.open(sender) else sender.sendMessage(locale.render("command.help", sender))
            return true
        }
        when (args[0].lowercase()) {
            "menu" -> player(sender)?.let(menu::open)
            "join" -> player(sender)?.let(service::joinQueue)
            "leave" -> player(sender)?.let(service::leaveQueue)
            "status" -> player(sender)?.let(service::status)
            "shop" -> player(sender)?.let { menu.open(it, EventsView.Shop) }
            "roster" -> player(sender)?.let { menu.open(it, EventsView.Roster) }
            "report" -> player(sender)?.let { menu.open(it, EventsView.Report) }
            "team" -> team(sender, args.drop(1))
            "admin" -> admin(sender, args.drop(1))
            "qa" -> qa(sender, args.drop(1))
            "debug" -> debug(sender, args.drop(1))
            "help" -> sender.sendMessage(locale.render("command.help", sender))
            else -> sender.sendMessage(locale.render("command.unknown", sender, mapOf("input" to Component.text(args[0].take(32)))))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options = when (args.size) {
            1 -> buildList {
                addAll(listOf("menu", "join", "leave", "status", "shop", "roster", "report", "team", "help"))
                if (sender.hasPermission("arcevents.admin")) add("admin")
                if (sender.hasPermission("arcevents.qa")) add("qa")
                if (sender.hasPermission("arcevents.debug")) add("debug")
            }
            2 -> when (args[0].lowercase()) {
                "admin" -> listOf("menu", "start", "stop", "reload", "recover")
                "qa" -> listOf("status", "player", "network", "recovery")
                "debug" -> listOf("start", "advance", "end", "credit")
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("qa", true) && args[1].equals("player", true) -> servicePlayerNames()
                args[0].equals("debug", true) && args[1].equals("end", true) -> listOf("traitors", "innocents")
                args[0].equals("debug", true) && args[1].equals("credit", true) -> servicePlayerNames()
                else -> emptyList()
            }
            else -> emptyList()
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }.sorted()
    }

    private fun team(sender: CommandSender, args: List<String>) {
        val player = player(sender) ?: return
        if (!player.hasPermission("arcevents.teamchat")) return deny(sender)
        if (args.isEmpty()) {
            sender.sendMessage(locale.render("team.usage", sender))
            return
        }
        service.teamChat(player, args.joinToString(" "))
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcevents.admin")) return deny(sender)
        if (args.isEmpty() || args[0].equals("menu", true)) {
            if (sender is Player) menu.open(sender, EventsView.Admin) else sender.sendMessage(locale.render("command.help", sender))
            return
        }
        when (args[0].lowercase()) {
            "start" -> sendStartResult(sender, admin = true)
            "stop" -> sender.sendMessage(locale.render(stopMessage(service.stopByAdmin()), sender))
            "reload" -> {
                val result = reload()
                sender.sendMessage(locale.render(if (result.isSuccess) "command.reload-ok" else "command.reload-failed", sender, mapOf(
                    "reason" to locale.text(result.exceptionOrNull()?.message ?: "unknown"),
                )))
            }
            "recover" -> sender.sendMessage(locale.render("admin.recovery-started", sender, mapOf("players" to locale.text(service.retryRecovery()))))
            else -> sender.sendMessage(locale.render("command.help", sender))
        }
    }

    private fun qa(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcevents.qa")) return deny(sender)
        when (args.firstOrNull()?.lowercase() ?: "status") {
            "status" -> sender.sendMessage(Component.text(service.qaStatus()))
            "player" -> sender.sendMessage(Component.text(service.qaPlayer(args.getOrNull(1).orEmpty().take(16))))
            "network" -> service.qaNetwork().ifEmpty { listOf(ArcEventsDebug.qa("server" to settings().serverId, "nodes" to 0)) }
                .forEach { sender.sendMessage(Component.text(it)) }
            "recovery" -> sender.sendMessage(Component.text(service.qaRecovery()))
            else -> sender.sendMessage(Component.text(service.qaStatus()))
        }
    }

    private fun debug(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcevents.debug")) return deny(sender)
        if (!settings().debugEnabled) {
            sender.sendMessage(locale.render("debug.disabled", sender))
            return
        }
        val action = args.firstOrNull()?.lowercase()
        if (action == "start") {
            sendStartResult(sender, admin = false)
            return
        }
        val applied = when (action) {
            "advance" -> service.debugAdvance()
            "end" -> service.debugEnd(if (args.getOrNull(1).equals("traitors", true)) TttTeam.TRAITORS else TttTeam.INNOCENTS)
            "credit" -> {
                val target = args.getOrNull(1)?.let { servicePlayer(it) }
                val amount = args.getOrNull(2)?.toIntOrNull()
                target != null && amount != null && service.debugCredit(target, amount)
            }
            else -> false
        }
        sender.sendMessage(locale.render(if (applied) "debug.applied" else "debug.usage", sender, mapOf(
            "action" to locale.text(action ?: "unknown"),
        )))
    }

    private fun sendStartResult(sender: CommandSender, admin: Boolean) {
        service.startFromQueue().thenAccept { result ->
            val key = if (!admin && result == ReservationStartResult.STARTED) "debug.applied" else startMessage(result)
            sender.sendMessage(locale.render(key, sender, mapOf("action" to locale.text("start"))))
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

    private fun player(sender: CommandSender): Player? {
        if (sender is Player) return sender
        sender.sendMessage(locale.render("command.player-only", sender))
        return null
    }

    private fun deny(sender: CommandSender) { sender.sendMessage(locale.render("command.no-permission", sender)) }

    private fun servicePlayerNames(): List<String> = plugin.server.onlinePlayers.map(Player::getName)
    private fun servicePlayer(name: String): Player? = plugin.server.getPlayerExact(name)
}
