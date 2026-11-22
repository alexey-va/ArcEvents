package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.paper.menu.*
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FirearmSoundPreset
import ru.ruscrafting.events.domain.apocalypse.ApocalypseSiteKind
import ru.ruscrafting.events.paper.apocalypse.ApocalypseRuntime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Staff actions keep the player as actor and use the same validation and operations as the command. */
internal class ArcEventsAdminDialog(
    private val runtime: PaperDialogRuntime,
    private val service: ArcEventsService,
    private val locale: ArcEventsLocale,
    private val settings: () -> ArcEventsConfig,
    private val command: ArcEventsCommand,
    private val apocalypse: ApocalypseRuntime,
    private val tuneGun: (Player) -> Boolean,
    private val arenaName: (String, Player) -> Component,
    private val escapeCloses: (Player) -> Boolean,
) {
    fun open(player: Player) = Pages(player).home()
    fun openReset(player: Player, target: String?) = Pages(player).resetForm(target)

    private inner class Pages(val player: Player) {
        private val host get() = settings().nodeMode == NodeMode.HOST
        private fun authorized() = player.isOnline && player.hasPermission("arcevents.play") &&
            player.hasPermission("arcevents.admin") && !apocalypse.isBusy(player.uniqueId)

        private fun access(): Boolean {
            if (authorized()) return true
            runtime.close(player)
            if (player.isOnline) player.sendEventMessage(locale.render(
                if (apocalypse.isBusy(player.uniqueId)) "apocalypse.pending" else "command.no-permission", player))
            return false
        }

        private fun text(key: String, vararg values: Pair<String, Component>) =
            locale.render("admin-dialog.$key", player, values.toMap()).decoration(TextDecoration.ITALIC, false)

        private fun button(id: String, label: Component, color: TextColor = WHITE, child: Boolean = false,
                           click: (PaperDialogClickContext) -> Unit): PaperDialogButton {
            val caption = if (child) label.append(Component.text(" ›")) else label
            return PaperDialogButton(PaperDialogActionId.of(id), caption.color(color).decoration(TextDecoration.ITALIC, false),
                width = 205, onClick = PaperDialogClickHandler { if (it.player == player && access()) click(it) })
        }

        private fun link(id: String, key: String = id, click: () -> Unit) =
            button(id, text(key), child = true) { click() }

        private fun page(id: String, title: String, body: List<Component> = emptyList(), buttons: List<PaperDialogButton> = emptyList(),
                         inputs: List<PaperDialogTextInput> = emptyList(), columns: Int = 2,
                         reopen: (() -> Unit)? = null, dismissed: () -> Unit = {}) {
            if (!access()) return
            val panels = if (body.isEmpty()) emptyList() else listOf(DialogTables.filledBody(
                rows = listOf(listOf(Component.join(JoinConfiguration.newlines(), body)
                    .color(BODY).decoration(TextDecoration.ITALIC, false))),
                headers = null, width = 390, columnWeights = listOf(1), skin = DialogTables.PanelSkin.NAVY))
            runtime.open(player, PaperDialogScreen(text(title).color(WHITE), body = panels, inputs = inputs,
                buttons = buttons, columns = columns, id = "events.admin.$id"), reopen, dismissed, escapeCloses(player))
        }

        fun home() {
            if (!access()) return
            val state = service.snapshot()
            page("home", "title", listOf(text("summary",
                "server" to locale.text(state.serverId), "phase" to locale.render("phase.${state.phase?.name?.lowercase() ?: "idle"}", player),
                "queue" to locale.text(state.queueSize), "recoveries" to locale.text(state.recoveryPending))), listOf(
                link("apocalypse") { expedition() }, link("matches") { matches() },
                link("weapons") { weapons() }, link("diagnostics") { diagnostics() },
            ), reopen = ::home)
        }

        private fun matches() {
            page("matches", "matches", buttons = buildList {
                add(link("start") { modes("start") })
                add(link("solo") { modes("solo") })
                add(link("arena") { modes("arena") })
                service.snapshot().matchId?.let { match ->
                    add(link("stop") {
                        page("stop", "stop", listOf(text("stop-detail", "match" to locale.text(match))), listOf(
                            button("confirm", text("stop"), RED) {
                                if (service.snapshot().matchId == match) operation("stop", listOf("stop"))
                                else page("stop", "stop", listOf(text("match-changed")))
                            }), columns = 1)
                    })
                }
            }, reopen = ::matches)
        }

        private fun modes(action: String) {
            val buttons = EventMode.entries.map { mode ->
                val label = locale.render(if (mode == EventMode.TTT) "menu.main.ttt-name" else "arcade.${mode.id}-name", player)
                button(mode.id, label, child = action != "solo") {
                    if (action == "solo") gameplay { command.admin(player, listOf("solo", mode.id)) }
                    else arenas(action, mode)
                }
            } + if (action == "solo") listOf(button("apocalypse", text("apocalypse")) {
                gameplay { command.admin(player, listOf("solo", "apocalypse")) }
            }) else emptyList()
            page("${action}_modes", action, buttons = buttons, reopen = { modes(action) })
        }

        private fun arenas(action: String, mode: EventMode, index: Int = 0) {
            val selected = command.selectedAdminArena(player)
            val all = (listOf("auto") + service.selectableArenaIds(mode)).distinct()
            val pages = all.chunked(10)
            val current = index.coerceIn(pages.indices)
            val buttons = pages[current].mapIndexed { slot, id ->
                val name = arenaName(id, player)
                val selectedRow = action == "arena" && id == selected
                val label = when {
                    action == "start" -> text("start-on", "arena" to name)
                    selectedRow -> Component.text("✔ ").append(name)
                    else -> name
                }
                button("map_$slot", label, if (action == "start" || selectedRow) GREEN else WHITE) {
                    when {
                        action == "start" -> gameplay { command.admin(player, listOf("start", mode.id, id)) }
                        selectedRow -> arenas(action, mode, current)
                        else -> command.admin(player, listOf("arena", id, mode.id)) { result ->
                            if (command.selectedAdminArena(player) == id) arenas(action, mode, current)
                            else result("arena", result)
                        }
                    }
                }
            }
            page("${action}_${mode.id}", action, buttons = buttons + paging(current, pages.lastIndex) { arenas(action, mode, it) },
                reopen = { arenas(action, mode, current) })
        }

        private fun paging(current: Int, last: Int, go: (Int) -> Unit) = buildList {
            if (current > 0) add(button("previous", text("previous"), BLUE) { go(current - 1) })
            if (current < last) add(button("next", text("next"), BLUE) { go(current + 1) })
        }

        private fun diagnostics(): Unit = page("diagnostics", "diagnostics", buttons = listOf(
            link("status") { report("status", listOf(service.qaStatus())) },
            link("player") { playerForm("player") },
            link("network") { report("network", service.qaNetwork()) },
            link("arenas") { report("arenas", service.qaArenas()) },
            link("recovery") { report("recovery", listOf(service.qaRecovery())) },
            button("recover", text("recover"), GREEN) { operation("recover", listOf("recover")) },
            button("reload", text("reload"), GREEN) { operation("reload", listOf("reload")) },
        ), reopen = ::diagnostics)

        private fun report(key: String, rows: List<String>, index: Int = 0) {
            // The command's diagnostic keys remain searchable. Split at field boundaries to keep long reports readable.
            val chunks = rows.flatMap { row -> row.removePrefix("ARCEVENTS_QA ").split(FIELD_BOUNDARY).chunked(10) }
            val current = index.coerceIn(0, chunks.lastIndex.coerceAtLeast(0))
            page("report_$key", key, chunks.getOrNull(current)?.map(locale::text) ?: listOf(text("empty")),
                paging(current, chunks.lastIndex) { report(key, rows, it) })
        }

        private fun playerForm(key: String, name: String = "", error: Boolean = false) {
            page("${key}_player", key, if (error) listOf(text("enter-player")) else emptyList(),
                listOf(button("submit", text(if (key == "guide") "give" else "show"), GREEN) {
                    val target = it.text(INPUT).orEmpty().trim()
                    if (target.isBlank()) playerForm(key, target, error = true)
                    else if (key == "guide") operation("guide", listOf("guide", target))
                    else report("player", listOf(service.qaPlayer(target)))
                }), listOf(PaperDialogTextInput(INPUT, text("player-name"), name, maxLength = 16)), columns = 1)
        }

        private fun weapons(): Unit = page("weapons", "weapons", buttons = buildList {
            add(link("gun") { if (!tuneGun(player)) page("gun", "gun", listOf(text("hold-gun"))) })
            add(link("sound") {
                page("sound", "sound", listOf(text("sound-detail")), FirearmSoundPreset.entries.map { preset ->
                    button(preset.name.lowercase(), locale.render("weapon.sound-preset-${preset.name.lowercase()}", player)) {
                        operation("sound", listOf("sound", preset.name.lowercase()))
                    }
                })
            })
            if (host) add(link("points") { points() })
        }, reopen = ::weapons)

        private fun points() = page("points", "points", listOf(text("points-detail")), listOf(
            button("show", text("show-points")) { gameplay { command.admin(player, listOf("weapons", "show")) } },
            button("add", text("add-point"), GREEN) { operation("points", listOf("weapons", "add")) },
            link("remove", "remove-point") {
                val location = player.location
                val position = "${location.world.name} · ${location.blockX}, ${location.blockY}, ${location.blockZ}"
                page("remove_point", "remove-point", listOf(text("remove-detail", "position" to locale.text(position))), listOf(
                    button("confirm", text("remove-point"), RED) {
                        val now = player.location
                        if (now.world == location.world && now.distanceSquared(location) == 0.0)
                            operation("points", listOf("weapons", "remove"))
                        else page("remove_point", "remove-point", listOf(text("position-changed")))
                    }), columns = 1)
            },
        ))

        private fun expedition(feedback: Component? = null) {
            val bodies = listOfNotNull(feedback, if (host) null else text("host-only", "host" to locale.text(settings().hostServer)))
            page("apocalypse", "apocalypse", bodies, if (!host) emptyList() else buildList {
                add(link("reset") { resetForm(null) })
                add(link("guide") { playerForm("guide") })
                add(link("locate") { locate() })
                add(link("day") { day() })
                if (apocalypse.isPlaying(player.uniqueId)) {
                    add(button("fly", text(if (player.allowFlight) "fly-off" else "fly-on")) { apocCommand("fly") })
                }
            }, reopen = { expedition() })
        }

        private fun locate() = page("locate", "locate", buttons = listOf(
            button("nearest", text("nearest")) { apocCommand("locate") },
        ) + ApocalypseSiteKind.entries.map { site ->
            val kind = site.name.lowercase()
            button(kind, text("sites.$kind")) { apocCommand("locate", kind) }
        })

        private fun day(value: String = "", error: Boolean = false) {
            page("day", "day", listOf(text(if (error) "day-error" else "day-detail")), listOf(
                button("apply", text("set-day"), GREEN) {
                    val input = it.text(INPUT).orEmpty().trim()
                    val number = input.toIntOrNull()
                    if (number != null && number in 1..10000) apocCommand("day", input) else day(input, true)
                }), listOf(PaperDialogTextInput(INPUT, text("day-number"), value, maxLength = 5)), columns = 1)
        }

        private fun apocCommand(vararg args: String) = gameplay { apocalypse.command(player, listOf("apocalypse") + args) }
        private fun gameplay(action: () -> Unit) { runtime.close(player); action() }

        private fun result(key: String, message: Component) = page("result_$key", key,
            listOf(locale.text(PlainTextComponentSerializer.plainText().serialize(message))))

        private fun operation(key: String, args: List<String>) {
            pending("result_$key", key) { current ->
                command.admin(player, args) { message -> if (current()) result(key, message) }
            }
        }

        fun resetForm(suggested: String?) {
            if (!access()) return
            if (!host) return expedition()
            val target = suggested ?: "arcevents_apocalypse_" + WORLD_DATE.format(Instant.now())
            page("reset", "reset", listOf(text("reset-intro", "host" to locale.text(settings().hostServer))),
                listOf(button("preview", text("preview"), child = true) { inspect(it.text(INPUT).orEmpty().trim()) }),
                listOf(PaperDialogTextInput(INPUT, text("world-name"), target.take(57), width = 310, maxLength = 57)), columns = 1)
        }

        private fun pending(id: String, key: String, action: (() -> Boolean) -> Unit) {
            var visible = true
            page(id, key, listOf(text("working")), dismissed = { visible = false })
            if (authorized()) action { visible && authorized() }
        }

        private fun inspect(target: String) {
            pending("reset_review", "preview-title") { current ->
                apocalypse.planWorldReset(target) { reply ->
                    if (!current()) return@planWorldReset
                    val fields = resetFields(reply)
                    if (reply.startsWith("ARCEVENTS_APOCALYPSE_RESET_PLAN ") && fields["arc_ready"] == "true" &&
                        fields["target"] == target && !fields["current"].isNullOrBlank() && fields["blockers"] == "-") {
                        val values = worldNames(fields.getValue("current"), target)
                        page("reset_review", "preview-title", listOf(text("world-pair", *values), text("reset-effects"),
                            text("reset-preserved"), text("reset-timing", "host" to locale.text(settings().hostServer))), listOf(
                            button("queue_reset", text("queue-reset"), RED) { queue(target) },
                            link("edit", "edit-name") { resetForm(target) },
                        ), columns = 1)
                    } else blocked(target, fields)
                }
            }
        }

        private fun queue(target: String) {
            pending("reset_review", "queue-reset") { current ->
                apocalypse.queueWorldReset(target, current) { reply ->
                    if (!current()) return@queueWorldReset
                    val fields = resetFields(reply)
                    if (reply.startsWith("ARCEVENTS_APOCALYPSE_RESET_QUEUED ") && fields["target"] == target) {
                        expedition(text("queued", "target" to locale.text(target), "host" to locale.text(settings().hostServer)))
                    } else blocked(target, fields)
                }
            }
        }

        private fun worldNames(old: String, target: String) = arrayOf("current" to locale.text(old), "target" to locale.text(target))

        private fun blocked(target: String, fields: Map<String, String>) {
            val reasons = fields["blockers"]?.split(',')?.filter { it.isNotBlank() && it != "-" }.orEmpty()
            val body = listOf(text("blocked-target", "target" to locale.text(target))) + reasons.ifEmpty { listOf("inspection_failed") }.map {
                val key = "admin-dialog.blockers.$it"
                val reason = locale.render(key, player, mapOf("current" to locale.text(fields["current"].orEmpty())))
                if (PlainTextComponentSerializer.plainText().serialize(reason) == key) text("unknown-blocker", "reason" to locale.text(it)) else reason
            }
            val old = fields["current"]
            page("reset_review", "blocked", body, buildList {
                if ("source_autoload" in reasons && !old.isNullOrBlank() &&
                    player.server.getPluginCommand("my_worlds:world")?.testPermissionSilent(player) == true) {
                    add(link("autoload", "autoload") {
                        page("autoload", "autoload", listOf(text("autoload-detail", "current" to locale.text(old))),
                            listOf(button("disable_autoload", text("disable-autoload"), RED) {
                                val worlds = player.server.getPluginCommand("my_worlds:world")
                                if (worlds != null && worlds.plugin.isEnabled && worlds.testPermissionSilent(player))
                                    worlds.execute(player, "my_worlds:world", arrayOf("setautoload", "no", old))
                                inspect(target)
                            }), columns = 1)
                    })
                }
                add(button("retry", text("retry")) { inspect(target) })
                add(link("edit", "edit-name") { resetForm(target) })
            }, columns = 1)
        }
    }

    private companion object {
        val INPUT = PaperDialogInputId.of("value")
        val WHITE = TextColor.color(0xffffff)
        val BODY = TextColor.color(0xe8dfd2)
        val GREEN = TextColor.color(0x9bd48d)
        val BLUE = TextColor.color(0x92bed8)
        val RED = TextColor.color(0xff6b61)
        val WORLD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
        val FIELD_BOUNDARY = Regex(" (?=[a-z_]+=)")
        fun resetFields(reply: String) = reply.split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
