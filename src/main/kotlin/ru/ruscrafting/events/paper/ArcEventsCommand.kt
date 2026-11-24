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
import ru.ruscrafting.events.domain.FirearmSoundPreset
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.ZombieKind
import ru.ruscrafting.events.domain.ZombiesDifficulty
import ru.ruscrafting.events.domain.ZombiesGear
import ru.ruscrafting.events.paper.apocalypse.ApocalypseWorldResetPlan

internal fun isArcEventsCommandLabel(label: String?): Boolean = when (label?.lowercase()) {
    "arcevents", "events", "arcevents:arcevents", "arcevents:events" -> true
    else -> false
}

class ArcEventsCommand internal constructor(
    private val plugin: Plugin,
    private val service: ArcEventsService,
    private val menu: ArcEventsMenu,
    private val weaponPoints: ArenaWeaponPointEditor,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val reload: () -> java.util.concurrent.CompletableFuture<Result<Unit>>,
    private val apocalypse: ru.ruscrafting.events.paper.apocalypse.ApocalypseRuntime? = null,
    private val campaigns: ru.ruscrafting.events.paper.apocalypse.ApocalypseCampaignControl? = null,
) : TabExecutor {
    private val selectedAdminArenas = mutableMapOf<Pair<String, EventMode>, String>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val burst = args.getOrNull(0).equals("burst", true)
        val tune = args.getOrNull(0).equals("admin", true) && args.getOrNull(1).equals("gun", true)
        if (burst || tune) {
            if (tune && !sender.hasPermission("arcevents.admin")) deny(sender)
            else player(sender)?.let { menu.openFirearmSettings(it, tune, args.drop(if (tune) 2 else 1)) }
            return true
        }
        if (sender is Player && (campaigns?.command(sender, args.toList()) == true || apocalypse?.command(sender, args.toList()) == true)) return true
        if (args.isEmpty()) {
            if (sender is Player) menu.openRoot(sender) else sender.sendEventMessage(locale.render("command.help", sender))
            return true
        }
        when (args[0].lowercase()) {
            "menu" -> player(sender)?.let(menu::openRoot)
            "join" -> player(sender)?.let(service::joinQueue)
            "fishing" -> player(sender)?.let { sendStartResult(it, StartMessageAudience.PLAYER, mode = EventMode.FISHING) }
            "zombies" -> player(sender)?.let { menu.openRoot(it, EventsView.Arcade(EventMode.ZOMBIES)) }
            "start" -> player(sender)?.let { player ->
                if (parseMode(args.getOrNull(1)) == EventMode.FISHING || settings().eventControls.creatorControlsEnabled || player.hasPermission("arcevents.start") ||
                    player.hasPermission("arcevents.admin")
                ) {
                    parseMode(args.getOrNull(1))?.let { sendStartResult(player, StartMessageAudience.PLAYER, mode = it) }
                        ?: if (args.getOrNull(1) == null) sendStartResult(player, StartMessageAudience.PLAYER)
                        else sender.sendEventMessage(locale.render("arcade.unsupported-mode", sender, mapOf("mode" to locale.text(args[1]))))
                } else deny(player)
            }
            "leave" -> player(sender)?.let(service::leave)
            "spawn" -> player(sender)?.let(service::requestMapSpawnReturn)
            "status" -> player(sender)?.let(service::status)
            "shop" -> player(sender)?.let { menu.openRoot(it, EventsView.Shop) }
            "roster" -> player(sender)?.let { menu.openRoot(it, EventsView.Roster) }
            "report" -> player(sender)?.let { menu.openRoot(it, EventsView.Report) }
            "team" -> team(sender, args.drop(1))
            "reload" -> sendReload(sender)
            "admin" -> admin(sender, args.drop(1))
            "qa" -> qa(sender, args.drop(1))
            "debug" -> debug(sender, args.drop(1))
            "help" -> sender.sendEventMessage(locale.render("command.help", sender))
            else -> sender.sendEventMessage(locale.render("command.unknown", sender, mapOf("input" to Component.text(args[0].take(32)))))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("arcevents.play")) return emptyList()
        val root = args.firstOrNull()?.lowercase()
        if (args.size > 1) {
            if (root in setOf("admin", "qa", "debug") && !sender.hasPermission("arcevents.$root")) return emptyList()
            if (root == "debug" && (!settings().debugEnabled ||
                    args.size > 2 && !settings().debugMutationsAllowed && args[1].lowercase() !in DEBUG_READ_ACTIONS)) return emptyList()
        }
        val options = when (args.size) {
            1 -> buildList {
                if (sender is Player) {
                    addAll(listOf("menu", "join", "fishing", "zombies", "apocalypse", "leave", "spawn", "status", "shop", "roster", "report", "start", "burst"))
                    if (sender.hasPermission("arcevents.teamchat")) add("team")
                }
                add("help")
                if (sender.hasPermission("arcevents.admin")) addAll(listOf("admin", "reload"))
                if (sender.hasPermission("arcevents.qa")) add("qa")
                if (sender.hasPermission("arcevents.debug") && settings().debugEnabled) add("debug")
            }
            2 -> when (args[0].lowercase()) {
                "apocalypse" -> if (sender is Player && campaigns != null) buildList {
                    addAll(listOf("common", "my", "friends", "join", "objectives", "leave"))
                    if (campaigns.canManage(sender)) addAll(listOf("campaign", "invite", "companion", "revoke"))
                } else emptyList()
                "start" -> when {
                    sender !is Player -> emptyList()
                    settings().eventControls.creatorControlsEnabled || sender.hasPermission("arcevents.start") || sender.hasPermission("arcevents.admin") -> EventMode.entries.map(EventMode::id)
                    else -> listOf(EventMode.FISHING.id)
                }
                "admin" -> listOf("status", "player", "network", "arenas", "arena", "recovery", "start", "stop", "reload", "recover", "sound", "guide", "apocalypse-reset-plan", "apocalypse-reset") +
                    if (sender is Player) listOf("menu", "weapons", "gun", "solo", "apocalypse") + EventMode.entries.map(EventMode::id) else listOf("zombies")
                "burst" -> if (sender is Player) listOf("1", "3", "6", "full", "default", "menu") else emptyList()
                "qa" -> listOf("status", "player", "network", "arenas", "recovery")
                "debug" -> if (settings().debugMutationsAllowed) DEBUG_ACTIONS else DEBUG_READ_ACTIONS
                else -> emptyList()
            }
            else -> nestedCompletions(sender, args)
        }
        return options.filter { it.startsWith(args.lastOrNull().orEmpty(), ignoreCase = true) }.sorted()
    }

    private fun nestedCompletions(sender: CommandSender, args: Array<out String>): List<String> {
        val root = args.getOrNull(0)?.lowercase()
        val action = args.getOrNull(1)?.lowercase()
        if (root == "apocalypse" && args.size == 3 && sender is Player && campaigns != null) {
            if (action == "join") return campaigns.friends(sender)
            if (action in setOf("invite", "companion", "revoke") && campaigns.canManage(sender))
                return plugin.server.onlinePlayers.filter { it != sender }.map(Player::getName)
        }
        if (args.size == 3 && root in setOf("qa", "admin") && action == "player") return servicePlayerNames()
        if (root == "admin") {
            if (action == "zombies") return zombiesPracticeCompletions(sender, args, admin = true)
            if (args.size == 3 && action == "menu" && sender is Player) return EventMode.entries.map(EventMode::id) + "apocalypse"
            if (args.size == 3 && action == "solo" && sender is Player) return EventMode.entries.map(EventMode::id) + "apocalypse"
            if (args.size == 3 && action == "gun" && sender is Player) return listOf("default")
            if (args.size == 3 && action == "guide") return plugin.server.onlinePlayers.map { it.name }
            if (args.size == 3 && action == "start") return EventMode.entries.map(EventMode::id)
            if (args.size == 4 && action == "start") return parseMode(args[2])?.let { arenaIds(it, includeAuto = true) }.orEmpty()
            if (args.size == 3 && action == "arena") return arenaIds(includeAuto = true)
            if (args.size == 4 && action == "arena") return EventMode.entries.map(EventMode::id)
            if (args.size == 3 && action == "weapons" && sender is Player) return ADMIN_WEAPON_ACTIONS
            if (args.size == 3 && action == "sound") return ADMIN_SOUND_PRESETS
            if (args.size == 4 && action == "apocalypse-reset") return listOf("confirm")
        }
        if (root != "debug") return emptyList()
        if (action == "zombies") return zombiesPracticeCompletions(sender, args, admin = false)
        return when (args.size) {
            3 -> when (action) {
                "player", "credit", "role", "health", "weapon", "ammo", "item", "kill", "revive", "discover", "dna", "call", "menu", "close" -> servicePlayerNames()
                "bootstrap" -> (EventMode.entries.map(EventMode::id) + arenaIds(includeAuto = true) + servicePlayerNames()).distinct()
                "end" -> listOf("innocents", "traitors")
                "timer" -> listOf("5", "30", "60", "300")
                "loot" -> listOf("status", "respawn", "clear")
                else -> emptyList()
            }
            4 -> when (action) {
                "bootstrap" -> (parseMode(args[2])?.let { arenaIds(it, includeAuto = true) }.orEmpty() +
                    servicePlayerNames().filterNot { it.equals(args[2], true) }).distinct()
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
                "bootstrap" -> servicePlayerNames().filterNot { name -> args.drop(2).dropLast(1).any { it.equals(name, true) } }
                "weapon" -> listOf("0", "1", "6", "8", "12", "24")
                else -> emptyList()
            }
            else -> if (action == "bootstrap") servicePlayerNames().filterNot { name -> args.drop(2).dropLast(1).any { it.equals(name, true) } } else emptyList()
        }
    }

    private fun zombiesPracticeCompletions(sender: CommandSender, args: Array<out String>, admin: Boolean): List<String> = when (args.size) {
        3 -> ZOMBIES_PRACTICE_ACTIONS + when {
            !admin -> listOf("difficulty")
            sender is Player -> listOf("help", "start", "stop")
            else -> listOf("help", "stop")
        }
        4 -> when (args[2].lowercase()) {
            "difficulty" -> if (!admin) ZOMBIES_DIFFICULTIES else emptyList()
            "start" -> if (admin && sender is Player) ZOMBIES_DIFFICULTIES else emptyList()
            "wave" -> service.zombiesPracticeWaveNumbers()
            "spawn" -> ZombieKind.entries.map { it.name.lowercase() }
            "grant", "gear" -> service.zombiesPracticePlayerNames()
            else -> emptyList()
        }
        5 -> if (args[2].equals("gear", true)) ZombiesGear.entries.map { it.name.lowercase() } else emptyList()
        else -> emptyList()
    }

    private fun team(sender: CommandSender, args: List<String>) {
        val player = player(sender) ?: return
        if (!player.hasPermission("arcevents.teamchat")) return deny(sender)
        if (args.isEmpty()) {
            sender.sendEventMessage(locale.render("team.usage", sender))
            return
        }
        service.teamChat(player, args.joinToString(" "))
    }

    internal fun admin(sender: CommandSender, args: List<String>, reply: (Component) -> Unit = sender::sendEventMessage) {
        if (!sender.hasPermission("arcevents.admin")) return reply(locale.render("command.no-permission", sender))
        val action = args.firstOrNull()?.lowercase()
        if (action == null || action == "menu" || args.size == 1 && (action == "apocalypse" || parseMode(action) != null)) {
            if (sender !is Player) return reply(locale.render("command.help", sender))
            if (action == "menu" && args.size > 2) return reply(locale.render("command.admin-mode-usage", sender))
            val selection = if (action == "menu") args.getOrNull(1)?.lowercase() else action
            openAdminPanel(sender, selection, reply)
            return
        }
        when (action) {
            "zombies" -> zombiesPractice(sender, args.drop(1), admin = true, reply = reply)
            "apocalypse-reset-plan" -> {
                val target = args.drop(1).singleOrNull()
                if (target == null || apocalypse == null) {
                    val reason = if (target == null) "usage" else "host_unavailable"
                    sender.sendEventMessage(Component.text(ApocalypseWorldResetPlan.blocked(reason)))
                } else apocalypse.planWorldReset(target) { result ->
                    if (sender.hasPermission("arcevents.admin") && (sender !is Player || sender.isOnline))
                        sender.sendEventMessage(Component.text(result))
                }
            }
            "apocalypse-reset" -> {
                val target = args.getOrNull(1)
                if (sender is Player && args.size <= 2) {
                    menu.openAdminReset(sender, target)
                    return
                }
                if (target == null || args.size != 3 || !args[2].equals("confirm", true)) {
                    return reply(locale.render("admin-dialog.reset-usage", sender))
                }
                val owner = apocalypse ?: return reply(Component.text(ApocalypseWorldResetPlan.blocked("host_unavailable")))
                val authorized = { sender.hasPermission("arcevents.admin") && (sender !is Player || sender.isOnline) }
                owner.queueWorldReset(target, authorized) { result -> if (authorized()) reply(Component.text(result)) }
            }
            "guide" -> {
                val target = args.getOrNull(1)?.let(plugin.server::getPlayerExact)
                val given = target != null && apocalypse?.giveGuide(target, hold = true) == true
                reply(locale.render("apocalypse.guide." + if (given) "item-given" else "item-unavailable", sender))
            }
            "status" -> sender.sendEventMessage(Component.text(service.qaStatus()))
            "player" -> sender.sendEventMessage(Component.text(service.qaPlayer(args.getOrNull(1).orEmpty().take(16))))
            "network" -> sendNetwork(sender)
            "arenas" -> sendArenas(sender)
            "arena" -> {
                val arena = args.getOrNull(1)?.lowercase() ?: "auto"
                val mode = parseMode(args.getOrNull(2)) ?: EventMode.TTT
                val accepted = arena == "auto" || arena in service.selectableArenaIds(mode)
                if (accepted) selectedAdminArenas[senderSelectionKey(sender) to mode] = arena
                reply(locale.render(if (accepted) "admin.arena-selected" else "admin.arena-selection-failed", sender, mapOf(
                    "arena" to locale.text(arena),
                )))
            }
            "weapons" -> editWeaponPoints(sender, args.drop(1), reply)
            "sound" -> selectFirearmSound(sender, args.drop(1), reply)
            "recovery" -> sender.sendEventMessage(Component.text(service.qaRecovery()))
            "solo" -> {
                val tester = player(sender) ?: return
                val id = args.getOrNull(1)
                val mode = parseMode(id)
                when {
                    args.size != 2 -> sender.sendEventMessage(locale.render("admin.solo-usage", sender))
                    id.equals("apocalypse", true) -> if (apocalypse != null) apocalypse.enter(tester)
                        else sender.sendEventMessage(locale.render("apocalypse.unavailable", sender))
                    mode != null -> sendStartResult(tester, StartMessageAudience.PLAYER, mode = mode,
                        operation = service.startSoloTest(tester, mode))
                    else -> sender.sendEventMessage(locale.render("admin.solo-usage", sender))
                }
            }
            "start" -> {
                val mode = parseMode(args.getOrNull(1)) ?: EventMode.TTT.takeIf { args.getOrNull(1) == null }
                if (mode == null) {
                    sender.sendEventMessage(locale.render("arcade.unsupported-mode", sender, mapOf("mode" to locale.text(args[1]))))
                    return
                }
                val explicitArena = args.getOrNull(if (args.getOrNull(1) != null && parseMode(args[1]) != null) 2 else 1)?.lowercase()
                val arena = explicitArena ?: selectedAdminArenas[senderSelectionKey(sender) to mode]
                if (arena != null && arena != "auto" && arena !in service.selectableArenaIds(mode)) {
                    sender.sendEventMessage(locale.render("admin.arena-selection-failed", sender, mapOf("arena" to locale.text(arena))))
                } else sendStartResult(sender, StartMessageAudience.ADMIN, arena, mode)
            }
            "stop" -> reply(locale.render(stopMessage(service.stopByAdmin()), sender))
            "reload" -> sendReload(sender, reply)
            "recover" -> reply(locale.render("admin.recovery-started", sender, mapOf("players" to locale.text(service.retryRecovery()))))
            else -> sender.sendEventMessage(locale.render("command.help", sender))
        }
    }

    private fun openAdminPanel(player: Player, selection: String?, reply: (Component) -> Unit) {
        val id = player.uniqueId
        val expedition = selection == "apocalypse" || selection == null && apocalypse?.isPlaying(id) == true
        val mode = if (selection != null) parseMode(selection) else service.currentMode()?.takeIf { service.isParticipant(id) }
        if (!expedition && mode == null) return reply(locale.render("command.admin-mode-usage", player))
        menu.dialogRuntime.beginFlow(player)
        if (expedition) menu.openApocalypseAdmin(player) else menu.openModeAdmin(player, requireNotNull(mode))
    }

    private fun selectFirearmSound(sender: CommandSender, args: List<String>, reply: (Component) -> Unit) {
        val input = args.singleOrNull()?.lowercase()
        val preset = when (input) {
            "1", "sharp" -> FirearmSoundPreset.SHARP
            "2", "heavy" -> FirearmSoundPreset.HEAVY
            "3", "echo" -> FirearmSoundPreset.ECHO
            "original", "default" -> FirearmSoundPreset.ORIGINAL
            else -> null
        }
        if (preset == null) {
            reply(locale.render("weapon.sound-usage", sender))
            return
        }
        service.selectFirearmSound(preset).whenComplete { selected, failure ->
            if (plugin.isEnabled) Tasks.scheduler.runSync {
                if (!plugin.isEnabled) return@runSync
                reply(if (failure != null || selected == null) {
                    locale.render("command.failed", sender, mapOf("reason" to locale.render("reason.network", sender)))
                } else locale.render("weapon.sound-selection", sender,
                    mapOf("preset" to locale.render("weapon.sound-preset-${selected.name.lowercase()}", sender))))
            }
        }
    }

    private fun sendReload(sender: CommandSender, reply: (Component) -> Unit = sender::sendEventMessage) {
        if (!sender.hasPermission("arcevents.admin")) return deny(sender)
        reload().whenComplete { outcome, failure ->
            if (plugin.isEnabled) Tasks.scheduler.runSync {
                if (!plugin.isEnabled) return@runSync
                val result = outcome ?: Result.failure(failure ?: IllegalStateException("Reload failed"))
                reply(locale.render(if (result.isSuccess) "command.reload-ok" else "command.reload-failed", sender, mapOf(
                    "reason" to locale.text(result.exceptionOrNull()?.message ?: "unknown"),
                )))
            }
        }
    }

    private fun qa(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcevents.qa")) return deny(sender)
        when (args.firstOrNull()?.lowercase() ?: "status") {
            "status" -> sender.sendEventMessage(Component.text(service.qaStatus()))
            "player" -> sender.sendEventMessage(Component.text(service.qaPlayer(args.getOrNull(1).orEmpty().take(16))))
            "network" -> sendNetwork(sender)
            "arenas" -> sendArenas(sender)
            "recovery" -> sender.sendEventMessage(Component.text(service.qaRecovery()))
            else -> sender.sendEventMessage(Component.text(service.qaStatus()))
        }
    }

    private fun debug(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission("arcevents.debug")) return deny(sender)
        if (!settings().debugEnabled) {
            sender.sendEventMessage(locale.render("debug.disabled", sender))
            return
        }
        val action = args.firstOrNull()?.lowercase() ?: "help"
        when (action) {
            "help" -> return sender.sendEventMessage(locale.render("debug.usage", sender))
            "status" -> return sender.sendEventMessage(Component.text(service.qaStatus()))
            "player" -> return sender.sendEventMessage(Component.text(service.qaPlayer(args.getOrNull(1).orEmpty().take(16))))
            "network" -> return sendNetwork(sender)
            "arenas" -> return sendArenas(sender)
            "recovery" -> return sender.sendEventMessage(Component.text(service.qaRecovery()))
            "bodies" -> {
                service.qaBodies().ifEmpty { listOf(ArcEventsDebug.qa("server" to settings().serverId, "bodies" to 0)) }
                    .forEach { sender.sendEventMessage(Component.text(it)) }
                return
            }
        }
        if (!settings().debugMutationsAllowed) {
            return debugResult(sender, action, DebugMutationResult.MUTATIONS_DISABLED)
        }
        if (action == "zombies") return zombiesPractice(sender, args.drop(1), admin = false)
        if (action == "start") {
            sendStartResult(sender, StartMessageAudience.DEBUG)
            return
        }
        if (action == "bootstrap") {
            val request = parseDebugBootstrap(args.drop(1)) { mode -> arenaIds(mode, includeAuto = true) }
            val requested = request.players
            if (request.mode == EventMode.ZOMBIES && requested.isEmpty()) {
                return debugResult(sender, action, DebugMutationResult.INSUFFICIENT_PLAYERS)
            }
            val players = if (requested.isEmpty()) plugin.server.onlinePlayers.toList() else requested.mapNotNull(::servicePlayer)
            if (requested.isNotEmpty() && players.size != requested.distinct().size) {
                debugResult(sender, action, DebugMutationResult.PLAYER_NOT_FOUND)
            } else service.debugStartLocalAsync(players, request.arena, request.mode).whenComplete { result, failure ->
                if (plugin.isEnabled) Tasks.scheduler.runSync {
                    if (plugin.isEnabled) debugResult(sender, action, if (failure == null) result else DebugMutationResult.INTERNAL_ERROR)
                }
            }
            return
        }
        if (action == "loot" && args.getOrNull(1)?.lowercase() in setOf("respawn", "clear")) {
            service.debugLootAsync(respawn = args[1].equals("respawn", ignoreCase = true)).whenComplete { result, failure ->
                if (plugin.isEnabled) Tasks.scheduler.runSync {
                    if (plugin.isEnabled) debugResult(sender, action, if (failure == null) result else DebugMutationResult.INTERNAL_ERROR)
                }
            }
            return
        }
        val result = when (action) {
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
                "status" -> {
                    sender.sendEventMessage(Component.text(service.qaStatus()))
                    return
                }
                else -> DebugMutationResult.INVALID_ARGUMENT
            }
            "cleanup" -> if (args.size == 1) service.debugCleanup() else DebugMutationResult.INVALID_ARGUMENT
            "menu" -> withPlayer(args.getOrNull(1)) { target ->
                val view = parseView(args.getOrNull(2)) ?: return@withPlayer DebugMutationResult.INVALID_ARGUMENT
                menu.openRoot(target, view)
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

    private fun zombiesPractice(
        sender: CommandSender,
        args: List<String>,
        admin: Boolean,
        reply: (Component) -> Unit = sender::sendEventMessage,
    ) {
        val action = args.firstOrNull()?.lowercase()
        if (action == null || admin && action == "help" && args.size == 1) {
            return reply(locale.render(if (admin) "admin.zombies-usage" else "debug.zombies-usage", sender))
        }
        if (action == "status" && args.size == 1) {
            return reply(Component.text(service.zombiesPracticeStatus()))
        }
        val argument = args.getOrNull(1)?.takeIf { args.size == 2 }
        if (admin && action == "start") {
            val difficulty = parseZombiesDifficulty(argument)
                ?: return debugResult(sender, "zombies start", DebugMutationResult.INVALID_ARGUMENT, reply)
            val tester = player(sender) ?: return
            service.startZombiesPractice(tester, difficulty).whenComplete { result, failure ->
                if (plugin.isEnabled) Tasks.scheduler.runSync {
                    if (plugin.isEnabled && tester.isOnline) {
                        debugResult(tester, "zombies start", if (failure == null) result else DebugMutationResult.INTERNAL_ERROR, reply)
                    }
                }
            }
            return
        }
        val result = when (action) {
            "difficulty" -> parseZombiesDifficulty(argument).takeUnless { admin }
                ?.let(service::debugZombiesDifficulty) ?: DebugMutationResult.INVALID_ARGUMENT
            "stop" -> if (admin && args.size == 1) service.stopZombiesPractice() else DebugMutationResult.INVALID_ARGUMENT
            "wave" -> argument?.toIntOrNull()?.let(service::zombiesPracticeWave) ?: DebugMutationResult.INVALID_ARGUMENT
            "spawn" -> ZombieKind.entries.firstOrNull { it.name.equals(argument, true) }
                ?.let(service::zombiesPracticeSpawn) ?: DebugMutationResult.INVALID_ARGUMENT
            "airdrop" -> if (args.size == 1) service.zombiesPracticeAirdrop() else DebugMutationResult.INVALID_ARGUMENT
            "grant" -> if (argument != null) withPlayer(argument, service::zombiesPracticeGrant) else DebugMutationResult.INVALID_ARGUMENT
            "gear" -> {
                val gear = ZombiesGear.entries.firstOrNull { it.name.equals(args.getOrNull(2), true) }
                if (args.size == 3 && gear != null) withPlayer(args[1]) { service.zombiesPracticeGear(it, gear) }
                else DebugMutationResult.INVALID_ARGUMENT
            }
            else -> DebugMutationResult.INVALID_ARGUMENT
        }
        debugResult(sender, "zombies $action", result, reply)
    }

    private fun debugResult(
        sender: CommandSender,
        action: String,
        result: DebugMutationResult,
        reply: (Component) -> Unit = sender::sendEventMessage,
    ) {
        val values = mapOf(
            "action" to locale.text(action.take(32)),
            "reason" to if (result == DebugMutationResult.APPLIED) Component.empty()
                else locale.render("debug.reason.${result.name.lowercase().replace('_', '-')}", sender),
        )
        reply(locale.render(if (result == DebugMutationResult.APPLIED) "debug.applied" else "debug.rejected", sender, values))
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
        "gungame" -> EventsView.Arcade(EventMode.GUN_GAME)
        "disasters" -> EventsView.Arcade(EventMode.DISASTERS)
        "fishing" -> EventsView.Arcade(EventMode.FISHING)
        "zombies" -> EventsView.Arcade(EventMode.ZOMBIES)
        "stats" -> EventsView.Statistics
        "help" -> EventsView.Help
        "arenas" -> EventsView.Arenas
        "shop" -> EventsView.Shop
        "roster" -> EventsView.Roster
        "report" -> EventsView.Report
        else -> null
    }

    private fun sendNetwork(sender: CommandSender) {
        service.qaNetwork().ifEmpty { listOf(ArcEventsDebug.qa("server" to settings().serverId, "nodes" to 0)) }
            .forEach { sender.sendEventMessage(Component.text(it)) }
    }

    private fun sendArenas(sender: CommandSender) {
        service.qaArenas().ifEmpty { listOf(ArcEventsDebug.qa("server" to settings().serverId, "arenas" to 0)) }
            .forEach { sender.sendEventMessage(Component.text(it)) }
    }

    private fun editWeaponPoints(sender: CommandSender, args: List<String>, reply: (Component) -> Unit) {
        val player = player(sender) ?: return
        val operation = when (args.firstOrNull()?.lowercase()) {
            "add" -> weaponPoints.addAsync(player)
            "remove" -> weaponPoints.removeAsync(player)
            "show" -> java.util.concurrent.CompletableFuture.completedFuture(weaponPoints.show(player))
            else -> {
                reply(locale.render("admin.weapon-points.usage", sender))
                return
            }
        }
        operation.whenComplete { value, failure ->
            if (plugin.isEnabled) Tasks.scheduler.runSync {
                if (!plugin.isEnabled || !player.isOnline) return@runSync
                val feedback = value ?: ArenaWeaponPointFeedback(ArenaWeaponPointAdminResult.STORAGE_UNAVAILABLE)
                reply(locale.render(
                    weaponPointMessage(feedback.result), sender, mapOf(
                        "arena" to locale.text(feedback.arenaId ?: "-"),
                        "count" to locale.text(feedback.count),
                        "target" to locale.text(feedback.target),
                    ),
                ))
            }
        }
    }

    private fun sendStartResult(
        sender: CommandSender,
        audience: StartMessageAudience,
        preferredArenaId: String? = null,
        mode: EventMode = EventMode.TTT,
        operation: java.util.concurrent.CompletableFuture<ReservationStartResult> = service.startFromQueue(sender as? Player, preferredArenaId, mode),
    ) {
        operation.thenAccept { result ->
            Tasks.scheduler.runSync {
                if (sender is Player && !sender.isOnline) return@runSync
                if (result == ReservationStartResult.STARTED && audience == StartMessageAudience.ADMIN) {
                    selectedAdminArenas.remove(senderSelectionKey(sender) to mode)
                }
                reservationStartMessage(result, audience)?.let { key ->
                    sender.sendEventMessage(locale.render(key, sender, mapOf("action" to locale.text("start"))))
                }
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
        sender.sendEventMessage(locale.render("command.player-only", sender))
        return null
    }

    private fun deny(sender: CommandSender) { sender.sendEventMessage(locale.render("command.no-permission", sender)) }

    private fun servicePlayerNames(): List<String> = plugin.server.onlinePlayers.map(Player::getName)
    private fun arenaIds(mode: EventMode = EventMode.TTT, includeAuto: Boolean = false): List<String> = buildList {
        if (includeAuto) add("auto")
        addAll(service.selectableArenaIds(mode))
    }.distinct()
    private fun senderSelectionKey(sender: CommandSender): String = (sender as? Player)?.uniqueId?.toString()
        ?: "console:${sender.name.lowercase()}"
    internal fun selectedAdminArena(player: Player, mode: EventMode): String = selectedAdminArenas[senderSelectionKey(player) to mode] ?: "auto"
    private fun servicePlayer(name: String): Player? = plugin.server.getPlayerExact(name)
    private fun parseMode(raw: String?): EventMode? = raw?.lowercase()?.let(EventMode::fromId)
    private fun parseZombiesDifficulty(raw: String?): ZombiesDifficulty? = ZombiesDifficulty.entries.firstOrNull { it.name.equals(raw, true) }

    companion object {
        private val DEBUG_READ_ACTIONS = listOf("help", "status", "player", "network", "arenas", "recovery", "bodies")
        internal val DEBUG_ACTIONS = DEBUG_READ_ACTIONS + listOf(
            "start", "bootstrap", "advance", "end", "timer", "credit", "role", "health",
            "weapon", "ammo", "item", "kill", "revive", "discover", "dna", "call",
            "loot", "menu", "close", "cleanup", "zombies",
        )
        private val ZOMBIES_PRACTICE_ACTIONS = listOf("status", "wave", "spawn", "airdrop", "grant", "gear")
        private val ZOMBIES_DIFFICULTIES = ZombiesDifficulty.entries.map { it.name.lowercase() }
        internal val ADMIN_WEAPON_ACTIONS = listOf("add", "remove", "show")
        internal val ADMIN_SOUND_PRESETS = listOf("1", "2", "3", "original", "default", "sharp", "heavy", "echo")
        internal val DEBUG_ITEMS = listOf(
            "traitor_blade", "traitor_radar", "traitor_smoke",
            "detective_scanner", "detective_medkit", "detective_armor",
        )
        internal val DEBUG_VIEWS = listOf("main", "event", "zombies", "stats", "help", "arenas", "shop", "roster", "report")
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

internal data class DebugBootstrapArguments(val mode: EventMode, val arena: String?, val players: List<String>)

/** Mode precedes the optional arena; omitted modes retain the original TTT command syntax. */
internal fun parseDebugBootstrap(arguments: List<String>, arenaIds: (EventMode) -> List<String>): DebugBootstrapArguments {
    var index = 0
    val mode = arguments.getOrNull(index)?.let(EventMode::fromId)?.also { index++ } ?: EventMode.TTT
    val arena = arguments.getOrNull(index)?.lowercase()?.takeIf { it in arenaIds(mode) }?.also { index++ }
    return DebugBootstrapArguments(mode, arena, arguments.drop(index))
}
