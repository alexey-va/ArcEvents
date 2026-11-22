package ru.ruscrafting.events.paper.apocalypse

import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickHandler
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.ApocalypseInfectionStage
import ru.ruscrafting.events.domain.apocalypse.ApocalypsePoint
import ru.ruscrafting.events.paper.ClientProtocolResolver
import ru.ruscrafting.events.paper.MIN_DIALOG_PROTOCOL
import ru.ruscrafting.events.paper.canManageEventMenus
import ru.ruscrafting.events.paper.sendEventMessage
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

internal enum class GuideTarget { SUPPLIES, WORKBENCH, EXIT, CITY, STRUCTURE, WEAPONS }
internal data class GuideRoute(val point: ApocalypsePoint, val claimed: Boolean = false, val available: Boolean = true) {
    val unopenedSupply: Boolean get() = available && !claimed
}

internal data class ApocalypseGuideSnapshot(
    val day: Long,
    val nextHordeDay: Long,
    val infection: ApocalypseInfectionStage,
    val hasGun: Boolean,
    val looseAmmo: Int,
    val inStarter: Boolean,
    val supplies: List<GuideRoute>,
    val exit: GuideRoute?,
    val city: GuideRoute?,
    val weapons: GuideRoute?,
    val tracked: GuideTarget? = null,
    val workbench: GuideRoute? = null,
    val structure: GuideRoute? = null,
) {
    fun route(target: GuideTarget, from: ApocalypsePoint): GuideRoute? = when (target) {
        GuideTarget.SUPPLIES -> supplies.filter { it.available }.minWithOrNull(compareBy<GuideRoute> { it.claimed }
            .thenBy { hypot(it.point.x.toDouble() - from.x, it.point.z.toDouble() - from.z) })
        GuideTarget.EXIT -> exit
        GuideTarget.CITY -> city
        GuideTarget.WEAPONS -> weapons
        GuideTarget.WORKBENCH -> workbench
        GuideTarget.STRUCTURE -> structure
    }

    fun surfaceTarget(): GuideTarget? = when {
        city != null -> GuideTarget.CITY
        structure != null -> GuideTarget.STRUCTURE
        weapons != null -> GuideTarget.WEAPONS
        else -> null
    }

    fun automaticTarget(): GuideTarget? = when {
        !inStarter -> surfaceTarget()
        supplies.any(GuideRoute::unopenedSupply) -> GuideTarget.SUPPLIES
        else -> GuideTarget.EXIT
    }

    fun nextStep() = when {
        infection != ApocalypseInfectionStage.HEALTHY -> "cure"
        inStarter && supplies.any(GuideRoute::unopenedSupply) -> "supplies"
        inStarter && exit != null -> "exit"
        !hasGun && city != null -> "city"
        !hasGun && structure != null -> "structure"
        !hasGun && weapons != null -> "weapons"
        hasGun && looseAmmo == 0 -> "ammo"
        else -> "shelter"
    }
}

/** A read-only field guide. Tracking and leaving remain owned by the active expedition. */
internal class ApocalypseGuide(
    plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val onRefresh: (Player) -> Unit,
    private val onTrack: (Player, GuideTarget) -> Unit,
    private val onLeave: (Player) -> Unit,
    private val dialogsEnabled: () -> Boolean = { true },
    private val canFly: (Player) -> Boolean = { false },
    private val toggleFly: (Player) -> Unit = {},
    private val dialogs: PaperDialogRuntime = PaperDialogRuntime(plugin),
    private val onAdmin: (Player) -> Unit = {},
    private val ownsDialogs: Boolean = true,
    private val canManageCampaign: (Player) -> Boolean = { false },
    private val onCampaign: (Player) -> Unit = {},
    private val onObjectives: ((Player) -> Unit)? = null,
) : AutoCloseable {
    private val protocols = ClientProtocolResolver()
    private val itemKey = NamespacedKey(plugin, "apocalypse_guide")

    fun item(player: Player): ItemStack = ItemStack.of(Material.BOOK).also { book ->
        book.editMeta { meta ->
            meta.displayName(text(player, "item-name"))
            meta.lore(locale.lore("apocalypse.guide.item-lore", player).map { it.decoration(TextDecoration.ITALIC, false) })
            meta.persistentDataContainer.set(itemKey, PersistentDataType.BYTE, 1)
        }
    }

    fun isItem(item: ItemStack?): Boolean = item?.type == Material.BOOK &&
        item.itemMeta.persistentDataContainer.get(itemKey, PersistentDataType.BYTE) == 1.toByte()

    fun entryNotice(player: Player, inStarter: Boolean) {
        val link = text(player, "open").clickEvent(ClickEvent.runCommand(GUIDE_COMMAND))
        val controls = Component.empty().append(link).append(text(player, "exit-command"))
        player.sendMessage(lines(text(player, if (inStarter) "starter-arrival" else "continuing"), controls))
    }

    fun announceRoute(player: Player, snapshot: ApocalypseGuideSnapshot) {
        val target = snapshot.tracked ?: return
        val location = player.location
        val from = ApocalypsePoint(location.blockX, location.blockY, location.blockZ)
        val point = snapshot.route(target, from)?.point ?: return
        val notice = text(player, "route-notice", "target" to text(player, "target.${target.name.lowercase()}"),
            "bearing" to bearing(player, from, point), "x" to locale.text(point.x),
            "y" to locale.text(point.y), "z" to locale.text(point.z))
        player.sendMessage(notice)
    }

    fun beginFlow(player: Player) {
        if (supportsDialogs(player)) dialogs.beginFlow(player)
    }

    private fun supportsDialogs(player: Player) = dialogsEnabled() && protocols.resolve(player) >= MIN_DIALOG_PROTOCOL

    fun show(player: Player, snapshot: ApocalypseGuideSnapshot) {
        if (!player.isOnline) return
        val location = player.location
        val from = ApocalypsePoint(location.blockX, location.blockY, location.blockZ)
        fun page(key: String, vararg values: Pair<String, Component>) = text(player, key, *values)
        val nextStep = snapshot.nextStep()
        val supplyCounts = arrayOf("opened" to locale.text(snapshot.supplies.count { it.claimed }),
            "total" to locale.text(snapshot.supplies.size))
        val order = if (snapshot.inStarter) GuideTarget.entries else listOf(GuideTarget.CITY, GuideTarget.STRUCTURE,
            GuideTarget.WEAPONS, GuideTarget.WORKBENCH, GuideTarget.EXIT, GuideTarget.SUPPLIES)
        val routes = order.mapNotNull { target -> snapshot.route(target, from)?.let { target to it } }
        val admin = player.canManageEventMenus()
        val campaignOwner = player.hasPermission("arcevents.play") && canManageCampaign(player)
        if (!supportsDialogs(player)) {
            // Books use dark ink on parchment and short pages to preserve vanilla's 114 px text width.
            val leaveLink = page("book.leave").clickEvent(ClickEvent.runCommand("/arcevents leave"))
            val adminLink = if (admin) page("book.admin-panel").clickEvent(ClickEvent.runCommand("/events admin apocalypse")) else null
            val pages = buildList {
                add(lines(listOfNotNull(
                    page("book.title"), page("book.status", "day" to locale.text(snapshot.day), "next" to locale.text(snapshot.nextHordeDay)),
                    Component.empty(), if (nextStep == "supplies") page("book-step.check-barrels", *supplyCounts) else page("book-step.$nextStep"),
                    Component.empty(), adminLink, leaveLink,
                )))
                if (campaignOwner || onObjectives != null) {
                    fun link(key: String, command: String) = locale.render("apocalypse.campaign.book-link", player,
                        mapOf("title" to locale.render(key, player))).clickEvent(ClickEvent.runCommand(command))
                    add(lines(listOfNotNull(page("book.title"), Component.empty(),
                        if (onObjectives != null) link("apocalypse.objectives.journal-title", "/arcevents apocalypse objectives") else null,
                        if (campaignOwner) link("apocalypse.campaign.manage-title", "/arcevents apocalypse campaign") else null)))
                }
                for ((target, route) in routes) {
                    val id = target.name.lowercase()
                    val p = route.point
                    val direction = bearing(player, from, p, book = true)
                    add(lines(page("book.heading", "target" to page("target.$id")), page("book-route.$id"), Component.empty(),
                        page("book.address", "bearing" to direction, "x" to locale.text(p.x), "y" to locale.text(p.y), "z" to locale.text(p.z)),
                        page("book.track").clickEvent(ClickEvent.runCommand("$TRACK_COMMAND $id"))))
                }
                add(lines(page("book.weapons"), Component.empty(), page("book.controls")))
                if (snapshot.workbench != null) {
                    add(page("book.workbench"))
                    add(page("book.parts"))
                }
                add(lines(page("book.health"), Component.empty(), page("book.horde")))
                add(lines(listOfNotNull(page("book.title"), Component.empty(),
                    page("book.refresh").clickEvent(ClickEvent.runCommand(GUIDE_COMMAND)),
                    if (canFly(player)) page(if (player.allowFlight) "book.fly-off" else "book.fly-on")
                        .clickEvent(ClickEvent.runCommand("/arcevents apocalypse fly")) else null,
                    Component.empty(), leaveLink)))
            }
            player.openBook(Book.book(page("book.title"), page("author"), pages))
            return
        }

        val status = page("status", "day" to locale.text(snapshot.day),
            "infection" to locale.render("apocalypse.infection.${snapshot.infection.name.lowercase()}", player))
        val next = if (nextStep == "supplies") page("step.check-barrels", *supplyCounts) else page("step.$nextStep")
        val rootButtons = buildList {
            if (routes.isNotEmpty()) add(chapterButton(player, "map") { openMap(it, snapshot, routes) })
            add(chapterButton(player, "equipment") { openEquipment(it, snapshot) })
            add(chapterButton(player, "hazards") { openHazards(it, snapshot) })
            onObjectives?.let { open ->
                add(button("objectives", page("page-link", "title" to locale.render("apocalypse.objectives.journal-title", player)),
                    locale.render("apocalypse.objectives.journal-tip", player), action = open))
            }
            if (campaignOwner) {
                add(button("campaign", locale.render("apocalypse.campaign.manage-link", player),
                    locale.render("apocalypse.campaign.manage-tip", player)) {
                    if (it.hasPermission("arcevents.play") && canManageCampaign(it)) onCampaign(it)
                    else {
                        dialogs.close(it)
                        it.sendEventMessage(locale.render("apocalypse.campaign-control.manage-denied", it))
                    }
                })
            }
            add(leaveButton(player))
            if (admin) {
                add(button("admin", page("page-link", "title" to locale.render("menu.event.admin-panel-name", player)),
                    lines(locale.lore("menu.event.admin-panel-lore", player))) {
                    if (it.canManageEventMenus()) onAdmin(it) else show(it, snapshot)
                })
            }
        }
        dialogs.open(player, PaperDialogScreen(title = page("title"), body = listOf(note(lines(status, Component.empty(), next))),
            buttons = rootButtons, exitButton = closeButton(player), columns = 2, id = "events.apocalypse.guide"),
            reopen = { onRefresh(player) }, onDismiss = {}, closeOnEscape = true)
    }

    private fun openMap(player: Player, snapshot: ApocalypseGuideSnapshot, routes: List<Pair<GuideTarget, GuideRoute>>) {
        val buttons = routes.map { (target, route) ->
            val id = target.name.lowercase()
            val name = text(player, "target.$id")
            val label = text(player, if (target == snapshot.tracked) "marked-link" else "page-link", "title" to name)
            button("place_$id", label, text(player, "route.$id")) { openPlace(it, snapshot, target, route) }
        }
        val legend = text(player, if (snapshot.inStarter) "map-inside" else "map-outside")
        openPage(player, "map", text(player, "map-title"), listOf(note(legend)), buttons)
    }

    private fun openPlace(player: Player, snapshot: ApocalypseGuideSnapshot, target: GuideTarget, route: GuideRoute) {
        val id = target.name.lowercase()
        val at = player.location
        val from = ApocalypsePoint(at.blockX, at.blockY, at.blockZ)
        val position = route.point
        val entry = buildList {
            add(text(player, "route.$id"))
            add(Component.empty())
            add(bearing(player, from, position))
            add(text(player, "address", "x" to locale.text(position.x),
                "y" to locale.text(position.y), "z" to locale.text(position.z)))
            if (target == GuideTarget.SUPPLIES) {
                snapshot.supplies.filter { it.point != position && (it.available || it.claimed) }.forEach { barrel ->
                    val p = barrel.point
                    add(text(player, if (barrel.claimed) "barrel-visited" else "barrel",
                        "x" to locale.text(p.x), "y" to locale.text(p.y), "z" to locale.text(p.z)))
                }
            }
        }
        val track = button("track_$id", text(player, "mark"), text(player, "mark-tip"), close = true) { onTrack(it, target) }
        openPage(player, "map.$id", text(player, "target.$id"), listOf(note(lines(entry))), listOf(track))
    }

    private fun openEquipment(player: Player, snapshot: ApocalypseGuideSnapshot) {
        val kit = text(player, if (snapshot.hasGun) "kit-gun" else "kit-axe", "ammo" to locale.text(snapshot.looseAmmo))
        val pages = listOfNotNull(
            note(lines(text(player, "weapons-title"), kit, text(player, "weapons-help"), Component.empty(), text(player, "controls"))),
            snapshot.workbench?.let { note(lines(text(player, "workbench-title"), text(player, "workbench-help"))) },
        )
        val flight = if (canFly(player)) button("admin_fly", text(player, if (player.allowFlight) "fly-off" else "fly-on"),
            text(player, "fly-tip"), close = true, action = toggleFly) else null
        openPage(player, "equipment", text(player, "equipment-title"), pages, listOfNotNull(flight))
    }

    private fun openHazards(player: Player, snapshot: ApocalypseGuideSnapshot) {
        val infection = locale.render("apocalypse.infection.${snapshot.infection.name.lowercase()}", player)
        val health = lines(text(player, "health-title", "stage" to infection), text(player, "health-help"))
        val horde = lines(text(player, "horde-title", "day" to locale.text(snapshot.nextHordeDay)), text(player, "horde-help"))
        openPage(player, "hazards", text(player, "hazards-title"), listOf(note(health), note(horde)), emptyList())
    }

    fun showObjectives(player: Player, pages: List<Component>, bookPages: List<Component>) {
        if (!player.isOnline) return
        val title = locale.render("apocalypse.objectives.journal-title", player)
        if (supportsDialogs(player)) {
            dialogs.beginFlow(player)
            dialogs.open(player, PaperDialogScreen(title = text(player, "chapter-title", "title" to title),
                body = pages.map(::note), buttons = emptyList(), id = "events.apocalypse.guide.objectives"))
        } else {
            player.openBook(Book.book(title, text(player, "author"), bookPages))
        }
    }

    private fun openPage(player: Player, id: String, title: Component, body: List<PaperDialogBody>, buttons: List<PaperDialogButton>) {
        dialogs.open(player, PaperDialogScreen(title = text(player, "chapter-title", "title" to title), body = body, buttons = buttons,
            exitButton = button("back", locale.render("menu.common.back-name", player), Component.empty()) {},
            columns = 2, id = "events.apocalypse.guide.$id"))
    }

    private fun chapterButton(player: Player, id: String, action: (Player) -> Unit): PaperDialogButton =
        button(id, text(player, "page-link", "title" to text(player, "$id-title")), text(player, "$id-tip"), action = action)

    private fun note(content: Component): PaperDialogBody =
        DialogTables.filledBody(listOf(listOf(content)), null, WIDTH, listOf(1), DialogTables.PanelSkin.COCOA)

    private fun bearing(player: Player, from: ApocalypsePoint, to: ApocalypsePoint, book: Boolean = false): Component {
        val dx = to.x.toDouble() - from.x
        val dz = to.z.toDouble() - from.z
        val distance = hypot(dx, dz).roundToInt()
        if (distance <= 2) {
            val vertical = to.y - from.y
            val key = when {
                vertical > 1 -> "above"
                vertical < -1 -> "below"
                else -> "here"
            }
            return text(player, if (book) "book.$key" else key, "distance" to locale.text(kotlin.math.abs(vertical)))
        }
        val index = Math.floorMod(floor(Math.toDegrees(atan2(dx, -dz)) / 45.0 + .5).toInt(), 8)
        val key = if (book) "compass" else "direction"
        val format = if (book) "book.bearing" else "bearing"
        return text(player, format, "direction" to text(player, "$key.${DIRECTIONS[index]}"),
            "distance" to locale.text(distance))
    }

    private fun leaveButton(player: Player) = button("leave", text(player, "leave"), text(player, "leave-tip"), close = true, action = onLeave)

    private fun closeButton(player: Player) = button("close", locale.render("menu.common.close-name", player), Component.empty(), close = true) {}

    private fun button(id: String, label: Component, tooltip: Component, close: Boolean = false, action: (Player) -> Unit) =
        PaperDialogButton(PaperDialogActionId.of(id), label.decoration(TextDecoration.ITALIC, false),
            tooltip = tooltip, width = 151, closeDialogBeforeAction = close,
            onClick = PaperDialogClickHandler { if (it.player.isOnline) action(it.player) })

    private fun text(player: Player, key: String, vararg values: Pair<String, Component>) =
        locale.render("apocalypse.guide.$key", player, values.toMap()).decoration(TextDecoration.ITALIC, false)

    private fun lines(vararg components: Component) = lines(components.toList())
    private fun lines(components: List<Component>) = Component.join(JoinConfiguration.newlines(), components)

    override fun close() {
        if (ownsDialogs) dialogs.close()
    }

    companion object {
        private const val WIDTH = 310
        private const val GUIDE_COMMAND = "/arcevents apocalypse guide"
        private const val TRACK_COMMAND = "/arcevents apocalypse track"
        private val DIRECTIONS = listOf("n", "ne", "e", "se", "s", "sw", "w", "nw")
    }
}
