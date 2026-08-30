package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.Tasks
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.FirearmId
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttTeam

class ArcEventsCommand(
    private val plugin: Plugin,
    private val service: ArcEventsService,
    private val menu: ArcEventsMenu,
    private val weaponPoints: ArenaWeaponPointEditor,
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
            "start" -> player(sender)?.let { player ->
                if (player.hasPermission("arcevents.start")) {
                    sendStartResult(player, StartMessageAudience.PLAYER)
                } else deny(player)
            }
            "leave" -> player(sender)?.let(service::leave)
            "spawn" -> player(sender)?.let(service::requestMapSpawnReturn)
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
                addAll(listOf("menu", "join", "leave", "spawn", "status", "shop", "roster", "report", "team", "help"))
                if (sender.hasPermission("arcevents.start")) add("start")
                if (sender.hasPermission("arcevents.admin")) add("admin")
                if (sender.hasPermission("arcevents.qa")) add("qa")
                if (sender.hasPermission("arcevents.debug")) add("debug")
            }
            2 -> when (args[0].lowercase()) {
                "admin" -> listOf("menu", "status", "player", "network", "arenas", "arena", "weapons", "recovery", "start", "stop", "reload", "recover")
                "qa" -> listOf("status", "player", "network", "arenas", "recovery")
                "debug" -> DEBUG_ACTIONS
                else -> emptyList()
            }
            else -> nestedCompletions(args)
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }.sorted()
    }

    private fun nestedCompletions(args: Array<out String>): List<String> {
        val root = args.getOrNull(0)?.lowercase()
        val action = args.getOrNull(1)?.lowercase()
        if (args.size == 3 && root in setOf("qa", "admin") && action == "player") return servicePlayerNames()
        if (args.size == 3 && root == "admin" && action in setOf("arena", "start")) return arenaIds(includeAuto = true)
        if (args.size == 3 && root == "admin" && action == "weapons") return ADMIN_WEAPON_ACTIONS
        if (root != "debug") return emptyList()
        return when (args.size) {
            3 -> when (action) {
                "player", "credit", "role", "health", "weapon", "ammo", "item", "kill", "revive", "discover", "dna", "call", "menu", "close" -> servicePlayerNames()
                "bootstrap" -> arenaIds(includeAuto = true) + servicePlayerNames()
                "end" -> listOf("innocents", "traitors")
                "timer" -> listOf("5", "30", "60", "300")
                "loot" -> listOf("status", "respawn", "clear")
                else -> emptyList()
            }
            4 -> when (action) {
                "bootstrap" -> servicePlayerNames().filterNot { it in args.drop(2) }
                "credit" -> listOf("-2", "-1", "1", "2", "16")
                "role" -> listOf("innocent", "traitor", "detective")
                "health" -> listOf("1", "10", "20")
                "weapon" -> FirearmId.entries.map { it.name.lowercase() }
                "ammo" -> listOf("1", "12", "24", "64")
                "item" -> DEBUG_ITEMS
                "kill" -> servicePlayerNames()
                "discover", "dna", "call" -> servicePlayerNames()
                "menu" -> DEBUG_VIEWS
                else -> emptyList()
            }
            5 -> when (action) {
                "bootstrap" -> servicePlayerNames().filterNot { it in args.drop(2) }
                "weapon" -> listOf("0", "1", "6", "8", "12", "24")
                else -> emptyList()
            }
            else -> if (action == "bootstrap") servicePlayerNames().filterNot { it in args.drop(2) } else emptyList()
        }
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
            "status" -> sender.sendMessage(Component.text(service.qaStatus()))
            "player" -> sender.sendMessage(Component.text(service.qaPlayer(args.getOrNull(1).orEmpty().take(16))))
            "network" -> sendNetwork(sender)
            "arenas" -> sendArenas(sender)
            "arena" -> {
                val arena = args.getOrNull(1)?.lowercase()
                sender.sendMessage(locale.render(if (service.selectNextArena(arena)) "admin.arena-selected" else "admin.arena-selection-failed", sender, mapOf(
                    "arena" to locale.text(arena ?: "auto"),
                )))
            }
            "weapons" -> editWeaponPoints(sender, args.drop(1))
            "recovery" -> sender.sendMessage(Component.text(service.qaRecovery()))
            "start" -> {
                val arena = args.getOrNull(1)?.lowercase()
                if (arena != null && !service.selectNextArena(arena)) {
                    sender.sendMessage(locale.render("admin.arena-selection-failed", sender, mapOf("arena" to locale.text(arena))))
                } else sendStartResult(sender, StartMessageAudience.ADMIN)
            }
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
            "network" -> sendNetwork(sender)
            "arenas" -> sendArenas(sender)
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
        val action = args.firstOrNull()?.lowercase() ?: "help"
        when (action) {
            "help" -> return sender.sendMessage(locale.render("debug.usage", sender))
            "status" -> return sender.sendMessage(Component.text(service.qaStatus()))
            "player" -> return sender.sendMessage(Component.text(service.qaPlayer(args.getOrNull(1).orEmpty().take(16))))
            "network" -> return sendNetwork(sender)
            "arenas" -> return sendArenas(sender)
            "recovery" -> return sender.sendMessage(Component.text(service.qaRecovery()))
            "bodies" -> {
                service.qaBodies().ifEmpty { listOf(ArcEventsDebug.qa("server" to settings().serverId, "bodies" to 0)) }
                    .forEach { sender.sendMessage(Component.text(it)) }
                return
            }
        }
        if (!settings().debugMutationsAllowed) {
            return debugResult(sender, action, DebugMutationResult.MUTATIONS_DISABLED)
        }
        if (action == "start") {
            sendStartResult(sender, StartMessageAudience.DEBUG)
            return
        }
        val result = when (action) {
            "bootstrap" -> {
                val requestedArena = args.getOrNull(1)?.lowercase()?.takeIf { it in arenaIds(includeAuto = true) }
                val requested = args.drop(if (requestedArena == null) 1 else 2)
                val players = if (requested.isEmpty()) plugin.server.onlinePlayers.toList() else requested.mapNotNull(::servicePlayer)
                if (requested.isNotEmpty() && players.size != requested.distinct().size) DebugMutationResult.PLAYER_NOT_FOUND
                else service.debugStartLocal(players, requestedArena)
            }
            "advance" -> service.debugAdvance()
            "end" -> parseTeam(args.getOrNull(1))?.let(service::debugEnd) ?: DebugMutationResult.INVALID_ARGUMENT
            "credit" -> {
                val target = args.getOrNull(1)?.let(::servicePlayer)
                val amount = args.getOrNull(2)?.toIntOrNull()
                if (target == null) DebugMutationResult.PLAYER_NOT_FOUND
                else if (amount == null) DebugMutationResult.INVALID_ARGUMENT
                else service.debugCredit(target, amount)
            }
            "role" -> withPlayer(args.getOrNull(1)) { target ->
                val role = args.getOrNull(2)?.uppercase()?.let { runCatching { TttRole.valueOf(it) }.getOrNull() }
                role?.let { service.debugRole(target, it) } ?: DebugMutationResult.INVALID_ARGUMENT
            }
            "timer" -> args.getOrNull(1)?.toIntOrNull()?.let(service::debugTimer) ?: DebugMutationResult.INVALID_ARGUMENT
            "health" -> withPlayer(args.getOrNull(1)) { target ->
                args.getOrNull(2)?.toDoubleOrNull()?.let { service.debugHealth(target, it) } ?: DebugMutationResult.INVALID_ARGUMENT
            }
            "weapon" -> withPlayer(args.getOrNull(1)) { target ->
                val firearm = args.getOrNull(2)?.uppercase()?.let { runCatching { FirearmId.valueOf(it) }.getOrNull() }
                val loaded = args.getOrNull(3)
                if (firearm == null || !validOptionalInteger(loaded)) DebugMutationResult.INVALID_ARGUMENT
                else service.debugWeapon(target, firearm, loaded?.toInt())
            }
            "ammo" -> withPlayer(args.getOrNull(1)) { target ->
                args.getOrNull(2)?.toIntOrNull()?.let { service.debugAmmo(target, it) } ?: DebugMutationResult.INVALID_ARGUMENT
            }
            "item" -> withPlayer(args.getOrNull(1)) { target ->
                parseDebugItem(args.getOrNull(2))?.let { service.debugItem(target, it) } ?: DebugMutationResult.INVALID_ARGUMENT
            }
            "kill" -> withPlayer(args.getOrNull(1)) { victim ->
                val killerName = args.getOrNull(2)
                val killer = killerName?.let(::servicePlayer)
                if (killerName != null && killer == null) DebugMutationResult.PLAYER_NOT_FOUND else service.debugKill(victim, killer)
            }
            "revive" -> withPlayer(args.getOrNull(1), service::debugRevive)
            "discover" -> withTwoPlayers(args.getOrNull(1), args.getOrNull(2), service::debugDiscover)
            "dna" -> withTwoPlayers(args.getOrNull(1), args.getOrNull(2), service::debugDna)
            "call" -> withTwoPlayers(args.getOrNull(1), args.getOrNull(2), service::debugCallDetective)
            "loot" -> when (args.getOrNull(1)?.lowercase()) {
                "respawn" -> service.debugLoot(respawn = true)
                "clear" -> service.debugLoot(respawn = false)
                "status" -> {
                    sender.sendMessage(Component.text(service.qaStatus()))
                    return
                }
                else -> DebugMutationResult.INVALID_ARGUMENT
            }
            "cleanup" -> service.debugCleanup()
            "menu" -> withPlayer(args.getOrNull(1)) { target ->
                val view = parseView(args.getOrNull(2)) ?: return@withPlayer DebugMutationResult.INVALID_ARGUMENT
                menu.open(target, view)
                DebugMutationResult.APPLIED
            }
            "close" -> withPlayer(args.getOrNull(1)) { target ->
                target.closeInventory()
                DebugMutationResult.APPLIED
            }
            else -> DebugMutationResult.INVALID_ARGUMENT
        }
        debugResult(sender, action, result)
    }

    private fun debugResult(sender: CommandSender, action: String, result: DebugMutationResult) {
        val values = mapOf(
            "action" to locale.text(action.take(32)),
            "reason" to if (result == DebugMutationResult.APPLIED) Component.empty()
                else locale.render("debug.reason.${result.name.lowercase().replace('_', '-')}", sender),
        )
        sender.sendMessage(locale.render(if (result == DebugMutationResult.APPLIED) "debug.applied" else "debug.rejected", sender, values))
    }

    private fun withPlayer(name: String?, action: (Player) -> DebugMutationResult): DebugMutationResult =
        name?.let(::servicePlayer)?.let(action) ?: DebugMutationResult.PLAYER_NOT_FOUND

    private fun withTwoPlayers(
        first: String?,
        second: String?,
        action: (Player, Player) -> DebugMutationResult,
    ): DebugMutationResult {
        val firstPlayer = first?.let(::servicePlayer) ?: return DebugMutationResult.PLAYER_NOT_FOUND
        val secondPlayer = second?.let(::servicePlayer) ?: return DebugMutationResult.PLAYER_NOT_FOUND
        return action(firstPlayer, secondPlayer)
    }

    private fun parseTeam(raw: String?): TttTeam? = when (raw?.lowercase()) {
        "innocents" -> TttTeam.INNOCENTS
        "traitors" -> TttTeam.TRAITORS
        else -> null
    }

    private fun parseDebugItem(raw: String?): EventItemKind? = raw?.replace('-', '_')?.uppercase()
        ?.let { runCatching { EventItemKind.valueOf(it) }.getOrNull() }
        ?.takeIf { it.name.lowercase() in DEBUG_ITEMS }

    private fun parseView(raw: String?): EventsView? = when (raw?.lowercase()) {
        "main" -> EventsView.Main
        "event" -> EventsView.Ttt
        "stats" -> EventsView.Statistics
        "help" -> EventsView.Help
        "admin" -> EventsView.Admin
        "arenas" -> EventsView.Arenas
        "shop" -> EventsView.Shop
        "roster" -> EventsView.Roster
        "report" -> EventsView.Report
        else -> null
    }

    private fun sendNetwork(sender: CommandSender) {
        service.qaNetwork().ifEmpty { listOf(ArcEventsDebug.qa("server" to settings().serverId, "nodes" to 0)) }
            .forEach { sender.sendMessage(Component.text(it)) }
    }

    private fun sendArenas(sender: CommandSender) {
        service.qaArenas().ifEmpty { listOf(ArcEventsDebug.qa("server" to settings().serverId, "arenas" to 0)) }
            .forEach { sender.sendMessage(Component.text(it)) }
    }

    private fun editWeaponPoints(sender: CommandSender, args: List<String>) {
        val player = player(sender) ?: return
        val feedback = when (args.firstOrNull()?.lowercase()) {
            "add" -> weaponPoints.add(player)
            "remove" -> weaponPoints.remove(player)
            "show" -> weaponPoints.show(player)
            else -> {
                sender.sendMessage(locale.render("admin.weapon-points.usage", sender))
                return
            }
        }
        sender.sendMessage(locale.render(
            weaponPointMessage(feedback.result),
            sender,
            mapOf(
                "arena" to locale.text(feedback.arenaId ?: "-"),
                "count" to locale.text(feedback.count),
                "target" to locale.text(feedback.target),
            ),
        ))
    }

    private fun sendStartResult(sender: CommandSender, audience: StartMessageAudience) {
        service.startFromQueue(sender as? Player).thenAccept { result ->
            Tasks.scheduler.runSync {
                if (sender is Player && !sender.isOnline) return@runSync
                sender.sendMessage(locale.render(
                    reservationStartMessage(result, audience),
                    sender,
                    mapOf("action" to locale.text("start")),
                ))
            }
        }
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
    private fun arenaIds(includeAuto: Boolean = false): List<String> = buildList {
        if (includeAuto) add("auto")
        addAll(service.arenaEntries().map(ArenaPoolEntry::id))
    }.distinct()
    private fun servicePlayer(name: String): Player? = plugin.server.getPlayerExact(name)

    companion object {
        internal val DEBUG_ACTIONS = listOf(
            "help", "status", "player", "network", "arenas", "recovery", "bodies",
            "start", "bootstrap", "advance", "end", "timer", "credit", "role", "health",
            "weapon", "ammo", "item", "kill", "revive", "discover", "dna", "call",
            "loot", "menu", "close", "cleanup",
        )
        internal val ADMIN_WEAPON_ACTIONS = listOf("add", "remove", "show")
        internal val DEBUG_ITEMS = listOf(
            "traitor_blade", "traitor_radar", "traitor_smoke",
            "detective_scanner", "detective_medkit", "detective_armor",
        )
        internal val DEBUG_VIEWS = listOf("main", "event", "stats", "help", "admin", "arenas", "shop", "roster", "report")
        internal fun validOptionalInteger(raw: String?): Boolean = raw == null || raw.toIntOrNull() != null
    }
}

internal fun weaponPointMessage(result: ArenaWeaponPointAdminResult): String = when (result) {
    ArenaWeaponPointAdminResult.ADDED -> "admin.weapon-points.added"
    ArenaWeaponPointAdminResult.REMOVED -> "admin.weapon-points.removed"
    ArenaWeaponPointAdminResult.SHOWN -> "admin.weapon-points.shown"
    ArenaWeaponPointAdminResult.WRONG_NODE -> "admin.weapon-points.wrong-node"
    ArenaWeaponPointAdminResult.OUTSIDE_ARENA -> "admin.weapon-points.outside-arena"
    ArenaWeaponPointAdminResult.BUSY -> "admin.weapon-points.busy"
    ArenaWeaponPointAdminResult.UNSAFE -> "admin.weapon-points.unsafe"
    ArenaWeaponPointAdminResult.DUPLICATE -> "admin.weapon-points.duplicate"
    ArenaWeaponPointAdminResult.LIMIT_REACHED -> "admin.weapon-points.limit-reached"
    ArenaWeaponPointAdminResult.NOT_FOUND -> "admin.weapon-points.not-found"
    ArenaWeaponPointAdminResult.STORAGE_UNAVAILABLE -> "admin.weapon-points.storage-unavailable"
}
