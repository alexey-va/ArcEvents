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
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FirearmSoundPreset
import ru.ruscrafting.events.domain.MatchPhase
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
    fun openMode(player: Player, mode: EventMode) = Pages(player, CatalogMode.of(mode)).match(mode)
    fun openApocalypse(player: Player) = Pages(player, CatalogMode.APOCALYPSE).expedition()
    fun openReset(player: Player, target: String?) = Pages(player, CatalogMode.APOCALYPSE).resetForm(target)

    private inner class Pages(val player: Player, val catalog: CatalogMode) {
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
                           tooltip: Component = Component.empty(),
                           click: (PaperDialogClickContext) -> Unit): PaperDialogButton {
            val caption = if (child) label.append(Component.text(" ›")) else label
            return PaperDialogButton(PaperDialogActionId.of(id), caption.color(color).decoration(TextDecoration.ITALIC, false),
                tooltip = tooltip.decoration(TextDecoration.ITALIC, false), width = 205,
                onClick = PaperDialogClickHandler { if (it.player == player && access()) click(it) })
        }

        private fun link(id: String, key: String = id, click: () -> Unit) =
            button(id, text(key), child = true) { click() }

        private fun page(id: String, title: String, body: List<Component> = emptyList(), buttons: List<PaperDialogButton> = emptyList(),
                         inputs: List<PaperDialogTextInput> = emptyList(), columns: Int = 2,
                         facts: List<Pair<Component, Component>> = emptyList(),
                         reopen: (() -> Unit)? = null, dismissed: () -> Unit = {}) {
            if (!access()) return
            val panels = buildList {
                if (facts.isNotEmpty()) add(DialogTables.factsBody(facts, DialogTables.FactsLayout.ROWS,
                    DialogTables.PanelSpec(catalog.skin, maxWidth = 350, columns = 1, fitContent = true)))
                if (body.isNotEmpty()) add(DialogTables.filledBody(
                    rows = listOf(listOf(Component.join(JoinConfiguration.newlines(), body)
                        .color(BODY).decoration(TextDecoration.ITALIC, false))),
                    headers = null, width = 390, columnWeights = listOf(1), skin = catalog.skin))
            }
            val heading = text("heading", "mode" to locale.render(catalog.nameKey, player), "section" to text(title))
            val screenId = "events.admin.${catalog.id}" + if (id.isEmpty()) "" else ".$id"
            runtime.open(player, PaperDialogScreen(heading.color(WHITE), body = panels, inputs = inputs,
                buttons = buttons, columns = columns, id = screenId), reopen, dismissed, escapeCloses(player))
        }

        fun match(mode: EventMode, feedback: Component? = null) {
            if (!access()) return
            val state = service.snapshot()
            val active = state.takeIf { host && it.matchId != null && service.currentMode() == mode }
            val unavailable = service.modeUnavailable(mode)
            val selected = if (mode.usesSharedArenas) command.selectedAdminArena(player, mode) else mode.primaryArenaId
            val status = active?.phase?.let { locale.render("phase.${it.name.lowercase()}", player) } ?: when (unavailable) {
                null -> text("ready")
                ReservationStartResult.BUSY -> text("busy")
                else -> text("unavailable")
            }
            val facts = buildList {
                add(text("status") to status)
                active?.arenaId?.let { add(text("active-map") to arenaName(it, player)) }
                if (mode.usesSharedArenas || active?.arenaId == null)
                    add(text(if (mode.usesSharedArenas) "next-map" else "map") to arenaName(selected, player))
                if (active != null) add(text("participants") to locale.text(active.participants))
                if (mode != EventMode.FISHING) add(text("queue") to locale.text(state.queueSize))
                when (mode) {
                    EventMode.DISASTERS -> add(text("disaster-rounds") to locale.text(settings().arcade.disasters.disasterRounds))
                    EventMode.ZOMBIES -> add(text("waves") to locale.text(settings().zombies.waves))
                    EventMode.FISHING -> add(text("expedition-size") to text("one-player"))
                    else -> Unit
                }
            }
            page("", "management", listOfNotNull(feedback), facts = facts, buttons = buildList {
                if (unavailable == null && !apocalypse.isPlaying(player.uniqueId)) {
                    add(button("start", text(if (mode == EventMode.FISHING) "start-fishing" else "start"), GREEN) {
                        gameplay { command.admin(player, listOf("start", mode.id, selected)) }
                    })
                    if (state.matchId == null && !service.isParticipant(player.uniqueId)) {
                        add(button("solo", locale.render("practice.${mode.id}.action", player), VIOLET,
                            tooltip = locale.render("practice.${mode.id}.roster", player)) {
                            gameplay { command.admin(player, listOf("solo", mode.id)) }
                        })
                    }
                }
                if (mode.usesSharedArenas && (selected != "auto" || service.selectableArenaIds(mode).isNotEmpty()))
                    add(link("arena") { arenas(mode) })
                when (mode) {
                    EventMode.TTT, EventMode.GUN_GAME, EventMode.ZOMBIES, EventMode.FISHING -> addAll(firearmControls())
                    EventMode.DISASTERS -> Unit
                }
                if (mode == EventMode.TTT && host) add(link("points") { points() })
                if (active?.matchId != null && active.phase in STOPPABLE_PHASES) add(stopButton(mode, active))
            }, reopen = { match(mode) })
        }

        private fun stopButton(mode: EventMode, active: ServiceSnapshot): PaperDialogButton {
            val reserved = active.phase == MatchPhase.RESERVED
            val label = if (reserved) "cancel-reservation" else "stop"
            fun stillCurrent(): Boolean {
                val now = service.snapshot()
                return host && service.currentMode() == mode && now.matchId == active.matchId &&
                    now.phase in STOPPABLE_PHASES && (now.phase == MatchPhase.RESERVED) == reserved
            }
            fun changed() = page("stop", label, listOf(text("match-changed")))
            return button("stop", text(label), RED, child = true) {
                if (!stillCurrent()) changed()
                else page("stop", label, listOf(
                    text(if (reserved) "reservation-detail" else "stop-detail", "match" to locale.text(active.matchId))
                ), listOf(button("confirm", text(label), RED) {
                    if (stillCurrent()) command.admin(player, listOf("stop")) { message -> match(mode, message) }
                    else changed()
                }), columns = 1)
            }
        }

        private fun arenas(mode: EventMode, index: Int = 0) {
            val selected = command.selectedAdminArena(player, mode)
            val all = (listOf("auto") + service.selectableArenaIds(mode)).distinct()
            val pages = all.chunked(10)
            val current = index.coerceIn(pages.indices)
            val buttons = pages[current].mapIndexed { slot, id ->
                val name = arenaName(id, player)
                val selectedRow = id == selected
                val label = if (selectedRow) Component.text("✔ ").append(name) else name
                button("map_$slot", label, if (selectedRow) GREEN else WHITE) {
                    if (selectedRow) arenas(mode, current)
                    else command.admin(player, listOf("arena", id, mode.id)) { message ->
                        if (command.selectedAdminArena(player, mode) == id) arenas(mode, current)
                        else result("arena", message)
                    }
                }
            }
            page("arena", "arena", buttons = buttons + paging(current, pages.lastIndex) { arenas(mode, it) },
                reopen = { arenas(mode, current) })
        }

        private fun paging(current: Int, last: Int, go: (Int) -> Unit) = buildList {
            if (current > 0) add(button("previous", text("previous"), BLUE) { go(current - 1) })
            if (current < last) add(button("next", text("next"), BLUE) { go(current + 1) })
        }

        private fun guideForm(name: String = "", error: Boolean = false) {
            page("guide_player", "guide", if (error) listOf(text("enter-player")) else emptyList(),
                listOf(button("submit", text("give"), GREEN) {
                    val target = it.text(INPUT).orEmpty().trim()
                    if (target.isBlank()) guideForm(target, error = true)
                    else operation("guide", listOf("guide", target))
                }), listOf(PaperDialogTextInput(INPUT, text("player-name"), name, maxLength = 16)), columns = 1)
        }

        private fun firearmControls() = listOf(
            link("gun") { if (!tuneGun(player)) page("gun", "gun", listOf(text("hold-gun"))) },
            link("sound") {
                page("sound", "sound", listOf(text("sound-detail")), FirearmSoundPreset.entries.map { preset ->
                    button(preset.name.lowercase(), locale.render("weapon.sound-preset-${preset.name.lowercase()}", player)) {
                        operation("sound", listOf("sound", preset.name.lowercase()))
                    }
                })
            },
        )

        private fun weaponPointArena(): String? {
            if (!host) return null
            val location = player.location
            val point = EventLocation(location.world.name, location.x, location.y, location.z)
            return settings().arenas.firstOrNull { it.enabled && it.bounds?.contains(point) == true }
                ?.takeIf { EventMode.TTT.acceptsArena(it.id) && it.weaponCount > 0 }?.id
        }

        private fun points() {
            val arena = weaponPointArena()
            if (arena == null) {
                page("points", "points", listOf(text("points-outside")), reopen = ::points)
                return
            }
            page("points", "points", listOf(text("points-detail", "arena" to arenaName(arena, player))), listOf(
                button("show", text("show-points")) {
                    if (weaponPointArena() == arena) gameplay { command.admin(player, listOf("weapons", "show")) }
                    else points()
                },
                button("add", text("add-point"), GREEN) {
                    if (weaponPointArena() == arena) operation("points", listOf("weapons", "add")) else points()
                },
                link("remove", "remove-point") {
                    if (weaponPointArena() != arena) return@link points()
                    val location = player.location
                    val position = "${location.world.name} · ${location.blockX}, ${location.blockY}, ${location.blockZ}"
                    page("remove_point", "remove-point", listOf(text("remove-detail", "position" to locale.text(position))), listOf(
                        button("confirm", text("remove-point"), RED) {
                            val now = player.location
                            if (weaponPointArena() == arena && now.world == location.world && now.distanceSquared(location) == 0.0)
                                operation("points", listOf("weapons", "remove"))
                            else page("remove_point", "remove-point", listOf(text("position-changed")))
                        }), columns = 1)
                },
            ), reopen = ::points)
        }

        fun expedition(feedback: Component? = null) {
            if (!access()) return
            val bodies = listOfNotNull(feedback, if (host) null else text("host-only", "host" to locale.text(settings().hostServer)))
            page("", "management", bodies, buildList {
                val playing = apocalypse.isPlaying(player.uniqueId)
                if (!playing && !service.isParticipant(player.uniqueId))
                    add(button("enter", text("enter"), GREEN) { gameplay { command.admin(player, listOf("solo", "apocalypse")) } })
                if (!host) return@buildList
                add(link("guide") { guideForm() })
                add(link("locate") { locate() })
                add(link("day") { day() })
                if (playing) {
                    val flight = player.allowFlight
                    add(button("fly", text(if (flight) "fly-off" else "fly-on")) {
                        if (apocalypse.isPlaying(player.uniqueId) && player.allowFlight == flight) apocCommand("fly")
                        else expedition()
                    })
                }
                addAll(firearmControls())
                if (apocalypse.campaignOwnerOf(player.uniqueId) == null) add(link("reset") { resetForm(null) })
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
                button("review", text("review-day"), child = true) {
                    val input = it.text(INPUT).orEmpty().trim()
                    val number = input.toIntOrNull()
                    if (number == null || number !in 1..10000) day(input, true)
                    else page("day_confirm", "day", listOf(text("day-confirm", "day" to locale.text(number))),
                        listOf(button("apply", text("set-day"), GREEN) { apocCommand("day", number.toString()) }), columns = 1)
                }), listOf(PaperDialogTextInput(INPUT, text("day-number"), value.take(5), maxLength = 5)), columns = 1)
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
            if (!host || apocalypse.campaignOwnerOf(player.uniqueId) != null) return expedition()
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
        val VIOLET = TextColor.color(0xc4a7e7)
        val BLUE = TextColor.color(0x92bed8)
        val RED = TextColor.color(0xff6b61)
        val WORLD_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC)
        val STOPPABLE_PHASES = setOf(MatchPhase.RESERVED, MatchPhase.PREPARING, MatchPhase.COUNTDOWN, MatchPhase.ACTIVE, MatchPhase.INTERMISSION)
        fun resetFields(reply: String) = reply.split(' ').drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
