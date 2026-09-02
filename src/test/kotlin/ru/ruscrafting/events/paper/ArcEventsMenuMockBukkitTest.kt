package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import java.nio.file.Files

class ArcEventsMenuMockBukkitTest : FunSpec({
    test("unsafe hotbar swap is cancelled without dispatching the configured main action") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcEventsMenuTest")
            PaperArcRuntime.installScheduling(plugin)
            val player = paper.addPlayer("MenuQA")
            val dataRoot = Files.createTempDirectory("arcevents-menu-")
            try {
                Files.createDirectories(dataRoot.resolve("lang"))
                copyResource("config.yml", dataRoot.resolve("config.yml"))
                copyResource("lang/ru.yml", dataRoot.resolve("lang/ru.yml"))
                copyResource("lang/en.yml", dataRoot.resolve("lang/en.yml"))
                val settings = ArcEventsConfig.load(dataRoot)
                val locale = ArcEventsLocale(dataRoot) { settings }
                val layouts = ArcEventsMenuLayouts(dataRoot)
                val service = mockk<ArcEventsService> {
                    every { snapshot() } returns ServiceSnapshot(
                        serverId = "test",
                        nodeMode = NodeMode.HOST,
                        hostServer = "test",
                        redisConnected = true,
                        hostAvailable = true,
                        arenaReady = true,
                        arenaId = null,
                        queueSize = 0,
                        matchId = null,
                        phase = null,
                        participants = 0,
                        alive = 0,
                        traitors = 0,
                        detectives = 0,
                        recoveryPending = 0,
                        secondsRemaining = 0,
                    )
                }
                ArcEventsMenu(
                    plugin,
                    service,
                    mockk<TttItems>(),
                    locale,
                    { settings },
                    { Result.success(Unit) },
                    layouts,
                ).use { menu ->
                    menu.open(player)
                    val inventory = player.openInventory.topInventory
                    inventory.getItem(layouts.slot(EventsView.Main, "ttt"))?.type shouldBe Material.SPYGLASS

                    val event = InventoryClickEvent(
                        player.openInventory,
                        InventoryType.SlotType.CONTAINER,
                        layouts.slot(EventsView.Main, "ttt"),
                        ClickType.NUMBER_KEY,
                        InventoryAction.HOTBAR_SWAP,
                    )
                    paper.callEvent(event)
                    paper.performTicks(2)

                    event.isCancelled shouldBe true
                    player.openInventory.topInventory shouldBe inventory
                    verify(exactly = 0) { service.queueControl(player.uniqueId) }
                }
            } finally {
                Tasks.reset()
                dataRoot.toFile().deleteRecursively()
            }
        } }
    }
})

private fun copyResource(name: String, target: java.nio.file.Path) {
    requireNotNull(ArcEventsMenuMockBukkitTest::class.java.classLoader.getResourceAsStream(name)).use { source ->
        Files.copy(source, target)
    }
}
