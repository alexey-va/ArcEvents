package ru.ruscrafting.events.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import ru.arc.config.Config
import ru.arc.menu.MenuCatalog
import ru.arc.menu.MenuCatalogRepository
import ru.arc.menu.MenuContract
import ru.arc.menu.MenuElementId
import ru.arc.menu.MenuId
import ru.arc.menu.MenuLayoutParser
import ru.arc.menu.MenuRegionId
import java.nio.file.Path

class ArcEventsMenuLayouts(dataRoot: Path) {
    private val repository = MenuCatalogRepository(loadConfiguration(dataRoot))

    fun prepare(dataRoot: Path): MenuCatalog = loadConfiguration(dataRoot)
    fun replace(candidate: MenuCatalog) = repository.replace(candidate)
    fun create(holder: InventoryHolder, view: EventsView, title: Component): Inventory =
        Bukkit.createInventory(holder, repository.current().require(menu(view)).rows * 9, title)
    fun slot(view: EventsView, element: String): Int =
        repository.current().require(menu(view)).slot(MenuElementId.of(element)).index
    fun region(view: EventsView, region: String): List<Int> =
        repository.current().require(menu(view)).region(MenuRegionId.of(region)).map { it.index }

    companion object {
        val MAIN = MenuId.of("main")
        val HELP = MenuId.of("help")
        val TTT = MenuId.of("ttt")
        val STATISTICS = MenuId.of("statistics")
        val ADMIN = MenuId.of("admin")
        val ARENAS = MenuId.of("arenas")
        val SHOP = MenuId.of("shop")
        val ROSTER = MenuId.of("roster")
        val BODY = MenuId.of("body")
        val REPORT = MenuId.of("report")
        val COMBAT = MenuId.of("combat")

        private fun elements(vararg ids: String) = ids.mapTo(linkedSetOf(), MenuElementId::of)
        private fun regions(vararg ids: String) = ids.mapTo(linkedSetOf(), MenuRegionId::of)

        val CONTRACTS = linkedMapOf(
            MAIN to MenuContract(requiredElements = elements("ttt", "statistics", "help", "admin")),
            HELP to MenuContract(requiredElements = elements("innocent", "traitor", "detective", "flow", "evidence", "weapons", "controls", "back")),
            TTT to MenuContract(requiredElements = elements("overview", "left", "center", "right", "arena", "help", "back", "evacuate")),
            STATISTICS to MenuContract(requiredElements = elements("summary", "back")),
            ADMIN to MenuContract(requiredElements = elements("status", "arenas", "start", "stop", "reload", "recover", "back")),
            ARENAS to MenuContract(requiredElements = elements("auto", "back"), requiredRegions = regions("arenas")),
            SHOP to MenuContract(requiredElements = elements("credits", "back"), requiredRegions = regions("offers")),
            ROSTER to MenuContract(requiredElements = elements("back"), requiredRegions = regions("players")),
            BODY to MenuContract(requiredElements = elements("victim", "time", "cause", "dna", "call", "roster", "back")),
            REPORT to MenuContract(requiredElements = elements("summary", "combat", "back"), requiredRegions = regions("participants")),
            COMBAT to MenuContract(requiredElements = elements("back", "previous", "next"), requiredRegions = regions("entries")),
        )

        fun menu(view: EventsView): MenuId = when (view) {
            EventsView.Main -> MAIN
            EventsView.Help, EventsView.EventHelp -> HELP
            EventsView.Ttt -> TTT
            EventsView.Statistics -> STATISTICS
            EventsView.Admin -> ADMIN
            EventsView.Arenas, EventsView.EventArenas -> ARENAS
            EventsView.Shop -> SHOP
            EventsView.Roster -> ROSTER
            is EventsView.Body -> BODY
            EventsView.Report -> REPORT
            is EventsView.CombatLog -> COMBAT
        }

        fun loadConfiguration(dataRoot: Path): MenuCatalog =
            MenuLayoutParser.require(Config(dataRoot, "config.yml"), "ui.layouts", CONTRACTS).also { catalog ->
                mapOf(ARENAS to ("arenas" to 8), SHOP to ("offers" to 3), ROSTER to ("players" to 16),
                    REPORT to ("participants" to 16), COMBAT to ("entries" to 16)).forEach { (menu, requirement) ->
                    val actual = catalog.require(menu).region(MenuRegionId.of(requirement.first)).size
                    require(actual >= requirement.second) {
                        "ArcEvents menu '$menu' region '${requirement.first}' needs at least ${requirement.second} slots, got $actual"
                    }
                }
            }
    }
}
