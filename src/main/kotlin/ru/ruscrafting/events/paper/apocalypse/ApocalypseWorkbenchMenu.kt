package ru.ruscrafting.events.paper.apocalypse

import com.google.gson.JsonParser
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickHandler
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.source.*
import ru.ruscrafting.events.paper.ClientProtocolResolver
import ru.ruscrafting.events.paper.MIN_DIALOG_PROTOCOL
import java.util.UUID

internal enum class WorkbenchFeedback { CRAFTED, MISSING, FULL, UNAVAILABLE }

/** A visit is tied to one installed machine. Only the expedition may commit a craft. */
internal class ApocalypseWorkbenchMenu(
    plugin: Plugin,
    private val locale: ArcEventsLocale,
    private val catalog: ApocalypseWorkbenchRecipes,
    private val crafting: ApocalypseWorkbenchCrafting,
    private val dialogs: PaperDialogRuntime = PaperDialogRuntime(plugin),
    private val dialogsEnabled: () -> Boolean,
) : AutoCloseable {
    private class Visit(
        val station: ApocalypseWorkbenchStation,
        val canUse: () -> Boolean,
        val onCraft: (Player, String) -> Unit,
        val onVanillaCraft: ((Player) -> Unit)?,
        val book: Boolean,
    ) {
        var page = 0
        var recipe: String? = null
        var pending: String? = null
        var nonce = ""
        var bookActions: Map<String, () -> Unit> = emptyMap()
    }

    private val protocols = ClientProtocolResolver()
    private val visits = mutableMapOf<UUID, Visit>()
    private val byStation = ApocalypseWorkbenchStation.entries.associateWith { station ->
        catalog.recipes(station).sortedWith(compareBy({ it.unavailableInput != null },
            { it.kind != ApocalypseWorkbenchRecipeKind.STATION }, { it.kind != ApocalypseWorkbenchRecipeKind.GUN }))
    }
    private val gunNames = catalog.all.mapNotNull { recipe ->
        recipe.result.gunId?.let { id -> recipe.nameEn?.let { id to it } }
    }.toMap()

    fun open(player: Player, station: ApocalypseWorkbenchStation, canUse: () -> Boolean, onCraft: (Player, String) -> Unit,
             onVanillaCraft: ((Player) -> Unit)? = null) {
        if (!player.isOnline || !canUse()) return
        dialogs.beginFlow(player)
        val visit = Visit(station, canUse, onCraft, onVanillaCraft, !dialogsEnabled() || protocols.resolve(player) < MIN_DIALOG_PROTOCOL)
        visits[player.uniqueId] = visit
        listing(player, visit, 0)
    }

    fun command(player: Player, arguments: List<String>): Boolean {
        val visit = visits[player.uniqueId]
        val action = if (arguments.size == 2 && visit?.book == true && arguments[0] == visit.nonce)
            visit.bookActions[arguments[1]] else null
        if (visit == null || action == null || !current(player, visit)) {
            player.sendMessage(text(player, "reopen"))
            return true
        }
        visit.bookActions = emptyMap()
        action()
        return true
    }

    fun complete(player: Player, recipeId: String, result: WorkbenchFeedback) {
        val visit = visits[player.uniqueId] ?: return
        if (visit.pending != recipeId) return
        visit.pending = null
        val recipe = catalog.find(recipeId) ?: return
        if (visit.recipe == recipeId && current(player, visit)) detail(player, visit, recipe, result)
    }

    fun forget(player: Player) {
        visits.remove(player.uniqueId)
        dialogs.close(player)
    }

    private fun current(player: Player, visit: Visit): Boolean {
        if (!player.isOnline || visits[player.uniqueId] !== visit) return false
        if (visit.canUse()) return true
        forget(player)
        return false
    }

    private fun listing(player: Player, visit: Visit, page: Int) {
        if (!current(player, visit)) return
        visit.recipe = null
        visit.page = page
        val recipes = byStation[visit.station].orEmpty()
        val size = if (visit.book) BOOK_PAGE else NATIVE_PAGE
        val pages = ((recipes.size + size - 1) / size).coerceAtLeast(1)
        val visible = recipes.drop(page * size).take(size)
        val title = station(player, visit.station)
        val schematics = recipes.filter { it.kind == ApocalypseWorkbenchRecipeKind.GUN }
        val summary = arrayOf("available" to locale.text(schematics.count { it.unavailableInput == null }))
        val help = text(player, "about.${visit.station.name.lowercase()}", *summary)
        val choices = visible.mapIndexed { index, recipe ->
            val name = when (recipe.id) {
                "create:item_application/andesite_casing_from_log" -> text(player, "casing-log")
                "create:item_application/andesite_casing_from_wood" -> text(player, "casing-wood")
                else -> name(player, recipe)
            }
            Choice("recipe_$index", name, recipe.unavailableInput != null) { detail(player, visit, recipe) }
        }
        val navigation = buildList {
            if (visit.station == ApocalypseWorkbenchStation.CRAFTING && visit.onVanillaCraft != null) {
                add(Choice("native_crafting", text(player, if (visit.book) "book.vanilla" else "vanilla")) {
                    if (current(player, visit)) {
                        forget(player)
                        visit.onVanillaCraft.invoke(player)
                    }
                })
            }
            if (page > 0) add(Choice("previous", text(player, if (visit.book) "book.previous" else "previous")) { listing(player, visit, page - 1) })
            if (page + 1 < pages) add(Choice("next", text(player, if (visit.book) "book.next" else "next")) { listing(player, visit, page + 1) })
        }
        if (visit.book) {
            val book = BookLinks(visit)
            val pageNumber = text(player, "book.page", "page" to locale.text(page + 1), "pages" to locale.text(pages))
            val index = listOf(text(player, "book.title", "name" to title), pageNumber) +
                choices.map { book.link(text(player, if (it.locked) "book.locked" else "book.choice", "name" to it.label), it) } +
                navigation.map { book.link(it.label, it) }
            val instructions = text(player, "book.about.${visit.station.name.lowercase()}", *summary)
            showBook(player, title, listOf(lines(index), lines(listOf(text(player, "book.title", "name" to title), Component.empty(), instructions))), book)
            return
        }
        val screen = catalogScreen(player, visit.station, page, pages, help, choices, navigation)
        dialogs.open(player, screen, { listing(player, visit, page) },
            { visits.remove(player.uniqueId, visit) }, true)
    }

    private fun detail(player: Player, visit: Visit, recipe: ApocalypseWorkbenchRecipe, feedback: WorkbenchFeedback? = null) {
        if (!current(player, visit) || !catalog.availableAt(recipe.id, visit.station)) return
        visit.recipe = recipe.id
        val storage = player.inventory.storageContents
        val plan = crafting.plan(recipe, storage, player)
        val ingredients = (plan as? ApocalypseWorkbenchCraftResult.Missing)?.ingredients ?: crafting.ingredients(recipe, storage)
        val state = when {
            visit.pending != null -> "pending"
            recipe.unavailableInput != null -> "unobtainable"
            plan is ApocalypseWorkbenchCraftResult.Full -> "full"
            plan is ApocalypseWorkbenchCraftResult.Missing -> "missing"
            else -> "ready"
        }
        val craft = if (state == "ready") Choice("craft", text(player, "craft")) {
            if (current(player, visit) && visit.recipe == recipe.id && visit.pending == null) {
                visit.pending = recipe.id
                detail(player, visit, recipe)
                visit.onCraft(player, recipe.id)
            }
        } else null
        if (visit.book) {
            val book = BookLinks(visit)
            val title = name(player, recipe)
            val pages = buildList {
                ingredients.chunked(2).forEachIndexed { index, group ->
                    add(lines(buildList {
                        add(if (index == 0) text(player, "book.title", "name" to title) else text(player, "book.parts"))
                        for (part in group) {
                            add(Component.empty())
                            add(text(player, "book.item", "name" to ingredient(player, part.ingredient)))
                            add(text(player, "book.numbers", "have" to locale.text(part.available), "need" to locale.text(part.required)))
                            if (!part.satisfied) add(text(player, "book.shortage", "count" to locale.text(part.required - part.available)))
                        }
                    }))
                }
                if (recipe.fuelUnits > 0) add(text(player, "book.rhenium-source"))
                add(lines(buildList {
                    add(text(player, "book.output", "count" to locale.text(recipe.result.count), "name" to title))
                    recipe.byproducts.forEach { byproduct ->
                        add(text(player, "book.byproduct", "count" to locale.text(byproduct.count),
                            "name" to text(player, "items.${key(byproduct.itemId)}")))
                    }
                    notice(player, recipe, feedback, true)?.let(::add)
                    add(Component.empty())
                    add(text(player, "book.state.$state"))
                    if (craft != null) add(book.link(text(player, "book.craft"), craft))
                    add(book.link(text(player, "book.back"), Choice("back", Component.empty()) { listing(player, visit, visit.page) }))
                }))
            }
            showBook(player, title, pages, book)
            return
        }
        dialogs.open(player, recipeScreen(player, recipe, ingredients, state, feedback, craft),
            { detail(player, visit, recipe) }, { if (visit.recipe == recipe.id) visit.recipe = null })
    }

    private class Choice(val id: String, val label: Component, val locked: Boolean = false, val action: () -> Unit)

    private fun catalogScreen(player: Player, station: ApocalypseWorkbenchStation, page: Int, pages: Int,
                              help: Component, choices: List<Choice>, navigation: List<Choice>): PaperDialogScreen {
        val title = station(player, station)
        val heading = if (pages > 1) text(player, "catalog-title", "name" to title, "page" to locale.text(page + 1),
            "pages" to locale.text(pages)) else text(player, "title", "name" to title)
        val locked = choices.any { it.locked }
        val width = if (locked) 256 else 186
        return PaperDialogScreen(title = heading, body = listOf(panel(help)),
            buttons = choices.map { button(it, text(player, if (it.locked) "locked" else "choice", "name" to it.label), width,
                if (it.locked) text(player, "state.unobtainable") else Component.empty()) } + navigation.map { button(it, it.label, width) },
            exitButton = footer(player, false), columns = if (locked) 1 else 2, id = "events.apocalypse.workbench.catalog")
    }

    private fun recipeScreen(player: Player, recipe: ApocalypseWorkbenchRecipe, ingredients: List<ApocalypseWorkbenchIngredientStatus>,
                              state: String, feedback: WorkbenchFeedback?, craft: Choice?): PaperDialogScreen {
        val cells = ingredients.map {
            val short = (it.required - it.available).coerceAtLeast(0)
            listOf(text(player, "cell", "value" to ingredient(player, it.ingredient)),
                text(player, "cell", "value" to locale.text(it.available)),
                text(player, "cell", "value" to locale.text(it.required)),
                if (short > 0) text(player, "shortage", "count" to locale.text(short)) else text(player, "enough"))
        }
        val headers = listOf("part", "have", "need", "missing").map { text(player, "columns.$it") }
        val status = buildList {
            add(text(player, "output", "count" to locale.text(recipe.result.count)))
            for (byproduct in recipe.byproducts) {
                add(text(player, "byproduct", "name" to text(player, "items.${key(byproduct.itemId)}"),
                    "count" to locale.text(byproduct.count)))
            }
            if (recipe.fuelUnits > 0) add(text(player, "rhenium-source"))
            notice(player, recipe, feedback, false)?.let(::add)
            if (feedback != WorkbenchFeedback.FULL || state != "full") add(text(player, "state.$state"))
        }
        return PaperDialogScreen(title = text(player, "recipe-title", "name" to name(player, recipe)),
            body = listOf(panel(lines(status)), DialogTables.filledBody(cells, headers, WIDTH, listOf(6, 2, 2, 3), DialogTables.PanelSkin.NAVY)),
            buttons = listOfNotNull(craft?.let { button(it, it.label, 180) }), exitButton = footer(player, true),
            columns = 1, id = "events.apocalypse.workbench.recipe")
    }

    private fun notice(player: Player, recipe: ApocalypseWorkbenchRecipe, feedback: WorkbenchFeedback?, book: Boolean) =
        feedback?.let { text(player, (if (book) "book." else "") + "feedback.${it.name.lowercase()}",
            "name" to name(player, recipe), "count" to locale.text(recipe.result.count)) }

    private fun name(player: Player, recipe: ApocalypseWorkbenchRecipe): Component {
        val item = recipe.result
        recipe.nameEn?.let { return locale.text(it) }
        item.ammoId?.let { return text(player, "ammo.${it.substringAfter(':')}") }
        if (recipe.kind == ApocalypseWorkbenchRecipeKind.STATION) {
            val blockId = item.nbt?.let { JsonParser.parseString(it).asJsonObject["BlockId"]?.asString } ?: item.itemId
            return station(player, requireNotNull(ApocalypseWorkbenchStation.fromSource(blockId)))
        }
        val id = if (item.itemId == "tacz:ammo_box") recipe.id else item.itemId
        return text(player, "items.${key(id)}")
    }

    private fun ingredient(player: Player, ingredient: ApocalypseWorkbenchIngredient): Component {
        ingredient.sourceTag?.let { return text(player, "tags.${key(it)}") }
        val identity = ingredient.alternatives.first()
        identity.gunId?.let { gunNames[it] }?.let { return locale.text(it) }
        return text(player, "items.${key(identity.itemId)}")
    }

    private fun key(id: String) = id.replace(':', '_').replace('/', '_')
    private fun station(player: Player, station: ApocalypseWorkbenchStation) = text(player, "stations.${station.name.lowercase()}")
    private fun panel(content: Component): PaperDialogBody =
        DialogTables.filledBody(listOf(listOf(content)), null, WIDTH, listOf(1), DialogTables.PanelSkin.NAVY)

    private fun text(player: Player, key: String, vararg values: Pair<String, Component>) =
        locale.render("apocalypse.workbench.$key", player, values.toMap()).decoration(TextDecoration.ITALIC, false)

    private fun button(choice: Choice, label: Component, width: Int = 172, tooltip: Component = Component.empty()) =
        PaperDialogButton(PaperDialogActionId.of(choice.id), label.decoration(TextDecoration.ITALIC, false), tooltip = tooltip, width = width,
            onClick = PaperDialogClickHandler { choice.action() })

    private fun footer(player: Player, back: Boolean) = PaperDialogButton(PaperDialogActionId.of(if (back) "back" else "close"),
        locale.render(if (back) "menu.common.back-name" else "menu.common.close-name", player).decoration(TextDecoration.ITALIC, false),
        width = 172, closeDialogBeforeAction = !back, onClick = PaperDialogClickHandler {})

    private class BookLinks(val visit: Visit) {
        val nonce = UUID.randomUUID().toString()
        val actions = linkedMapOf<String, () -> Unit>()
        fun link(label: Component, choice: Choice): Component {
            actions[choice.id] = choice.action
            return label.clickEvent(ClickEvent.runCommand("/arcevents apocalypse bench $nonce ${choice.id}"))
        }
    }

    private fun showBook(player: Player, title: Component, pages: List<Component>, book: BookLinks) {
        book.visit.nonce = book.nonce
        book.visit.bookActions = book.actions
        player.openBook(Book.book(title, locale.render("apocalypse.guide.author", player), pages))
    }

    private fun lines(components: List<Component>) = Component.join(JoinConfiguration.newlines(), components)

    override fun close() {
        visits.clear()
        dialogs.close()
    }

    private companion object {
        const val WIDTH = 330
        const val NATIVE_PAGE = 8
        const val BOOK_PAGE = 3
    }
}
