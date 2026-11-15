package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import net.kyori.adventure.inventory.Book
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogClickContext
import ru.ruscrafting.events.paper.MIN_DIALOG_PROTOCOL
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchRecipes
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchRecipe
import ru.ruscrafting.events.domain.apocalypse.source.ApocalypseWorkbenchStation
import java.nio.file.Files
import java.util.UUID

class ApocalypseWorkbenchMenuTest : StringSpec({
    "book buttons are one use, bound to the physical bench and hidden when the result cannot fit" {
        val root = Files.createTempDirectory("apocalypse-bench-book-")
        try {
            val settings = ArcEventsConfig.load(root)
            val locale = ArcEventsLocale(root) { settings }
            val player = mockk<Player>(relaxed = true)
            every { player.uniqueId } returns UUID.randomUUID()
            every { player.isOnline } returns true
            every { player.inventory.storageContents } returns arrayOfNulls<ItemStack>(36)
            val book = slot<Book>()
            every { player.openBook(capture(book)) } returns Unit
            val catalog = ApocalypseWorkbenchRecipes.load()
            val crafting = mockk<ApocalypseWorkbenchCrafting>()
            var result: ApocalypseWorkbenchCraftResult = ApocalypseWorkbenchCraftResult.Full
            every { crafting.plan(any(), any(), player) } answers { result }
            every { crafting.ingredients(any(), any()) } answers {
                firstArg<ApocalypseWorkbenchRecipe>().ingredients.map { ApocalypseWorkbenchIngredientStatus(it, it.count, it.count) }
            }
            val runtime = mockk<PaperDialogRuntime>(relaxed = true)
            val screen = slot<PaperDialogScreen>()
            every { runtime.open(player, capture(screen), any(), any(), any()) } returns Unit
            every { player.protocolVersion } returns MIN_DIALOG_PROTOCOL
            var native = false
            val menu = ApocalypseWorkbenchMenu(mockk(), locale, catalog, crafting, runtime) { native }
            var reachable = true
            var commits = 0
            fun open() = menu.open(player, ApocalypseWorkbenchStation.T1, { reachable }, { _, _ -> commits++ })
            fun commands(node: Component): List<String> = listOfNotNull(node.clickEvent()?.takeIf {
                it.action() == ClickEvent.Action.RUN_COMMAND
            }?.value()) + node.children().flatMap(::commands)
            fun action(id: String) = book.captured.pages().flatMap(::commands).firstOrNull { it.substringAfterLast(' ') == id }
                ?.split(' ')?.takeLast(2)

            open()
            val detail = requireNotNull(action("recipe_0"))
            menu.command(player, detail)
            action("craft") shouldBe null
            menu.command(player, requireNotNull(action("back")))
            result = ApocalypseWorkbenchCraftResult.Ready(emptyList(), emptyList())
            menu.command(player, requireNotNull(action("recipe_0")))
            val craft = requireNotNull(action("craft"))
            menu.command(player, detail)
            commits shouldBe 0
            reachable = false
            menu.command(player, craft)
            commits shouldBe 0
            reachable = true
            open()
            menu.command(player, requireNotNull(action("recipe_0")))
            val fresh = requireNotNull(action("craft"))
            menu.command(player, fresh)
            menu.command(player, fresh)
            commits shouldBe 1
            menu.forget(player)
            menu.command(player, fresh)
            commits shouldBe 1

            var vanillaGrids = 0
            fun ordinaryTable() = menu.open(player, ApocalypseWorkbenchStation.CRAFTING, { reachable },
                { _, _ -> }, { vanillaGrids++ })
            ordinaryTable()
            val bookGrid = requireNotNull(action("native_crafting"))
            menu.command(player, bookGrid)
            menu.command(player, bookGrid)
            vanillaGrids shouldBe 1
            native = true
            ordinaryTable()
            val guarded = screen.captured.buttons.single { it.id.value == "native_crafting" }
            reachable = false
            guarded.onClick.handle(mockk<PaperDialogClickContext>())
            vanillaGrids shouldBe 1
            reachable = true
            ordinaryTable()
            val allowed = screen.captured.buttons.single { it.id.value == "native_crafting" }
            repeat(2) { allowed.onClick.handle(mockk<PaperDialogClickContext>()) }
            vanillaGrids shouldBe 2
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
