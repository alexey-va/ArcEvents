package ru.ruscrafting.events.paper

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import io.mockk.verifyOrder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.ArcadePlayer
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.MatchEndReason
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttMatch
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import ru.ruscrafting.events.domain.TttTeam
import ru.ruscrafting.events.network.QueueState
import java.util.UUID
import java.util.concurrent.CompletableFuture
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
    test("arcade cards open and a stale inventory transfer check never joins a new queue") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcEventsModeMenuTest"); PaperArcRuntime.installScheduling(plugin)
            val player = paper.addPlayer("ModeQA"); val root = Files.createTempDirectory("arcevents-mode-menu-")
            try {
                Files.createDirectories(root.resolve("lang")); copyResource("config.yml", root.resolve("config.yml")); copyResource("lang/ru.yml", root.resolve("lang/ru.yml")); copyResource("lang/en.yml", root.resolve("lang/en.yml"))
                val settings = ArcEventsConfig.load(root); val locale = ArcEventsLocale(root) { settings }; val layouts = ArcEventsMenuLayouts(root)
                val service = mockk<ArcEventsService> {
                    every { snapshot() } returns testSnapshot(0)
                    every { defaultArenaId(EventMode.GUN_GAME) } returns "deadwood"
                    every { isParticipant(any()) } returns false
                    every { modeAvailable(any()) } returns true
                    every { modeUnavailable(any()) } returns null
                    every { queueControl(player.uniqueId) } returns CompletableFuture.completedFuture(QueueControlSnapshot(null, null))
                    every { arcadeSnapshot() } returns null
                }
                ArcEventsMenu(plugin, service, mockk<TttItems>(), locale, { settings }, layouts).use { menu ->
                    menu.open(player)
                    for ((slot, mode) in listOf(2 to EventMode.GUN_GAME, 6 to EventMode.DISASTERS)) {
                        paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL)); paper.performTicks(2)
                        player.openInventory.topInventory.getItem(layouts.slot(EventsView.Arcade(mode), "overview"))?.type shouldBe if (mode == EventMode.GUN_GAME) Material.IRON_SWORD else Material.LIGHTNING_ROD
                        val arcadeInventory = player.openInventory.topInventory
                        paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, layouts.slot(EventsView.Arcade(mode), "help"), ClickType.LEFT, InventoryAction.PICKUP_ALL)); paper.performTicks(2)
                        player.openInventory.topInventory shouldBe arcadeInventory
                        menu.open(player)
                    }
                    val routeId = java.util.UUID.randomUUID().toString()
                    val view = EventsView.Arcade(EventMode.DISASTERS)
                    every { service.modeUnavailable(EventMode.DISASTERS) } returns ReservationStartResult.BUSY
                    every { service.queueControl(player.uniqueId) } returns CompletableFuture.completedFuture(
                        QueueControlSnapshot(QueueState.RETURN_PENDING, null, matchId = routeId))
                    menu.open(player, view)
                    paper.performTicks(2)
                    val center = layouts.slot(view, "center")
                    player.openInventory.topInventory.getItem(center)?.type shouldBe Material.COMPASS
                    every { service.retryRoute(player, routeId) } returns CompletableFuture.completedFuture(Unit)
                    every { service.modeUnavailable(EventMode.DISASTERS) } returns null
                    every { service.queueControl(player.uniqueId) } returns CompletableFuture.completedFuture(QueueControlSnapshot(null, null))
                    paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, center, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                    paper.performTicks(3)
                    verify(exactly = 1) { service.retryRoute(player, routeId) }
                    verify(exactly = 0) { service.joinQueue(player, any()) }
                    verify(exactly = 0) { service.leaveQueue(player) }
                    verify(exactly = 0) { service.startFromQueue(any(), any(), any()) }
                    player.openInventory.topInventory.getItem(center)?.type shouldBe Material.LIME_DYE
                }
            } finally { Tasks.reset(); root.toFile().deleteRecursively() }
        } }
    }

    test("participant shortcut shows only its mode administration in the legacy inventory and rechecks permission") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcEventsParticipantRootTest"); PaperArcRuntime.installScheduling(plugin)
            val player = paper.addPlayer("ParticipantQA"); val root = Files.createTempDirectory("arcevents-participant-root-")
            try {
                player.addAttachment(plugin, "arcevents.play", true)
                val grant = requireNotNull(player.addAttachment(plugin, "arcevents.admin", true))
                Files.createDirectories(root.resolve("lang")); copyResource("config.yml", root.resolve("config.yml")); copyResource("lang/ru.yml", root.resolve("lang/ru.yml")); copyResource("lang/en.yml", root.resolve("lang/en.yml"))
                val settings = ArcEventsConfig.load(root); val locale = ArcEventsLocale(root) { settings }; val layouts = ArcEventsMenuLayouts(root)
                val service = mockk<ArcEventsService> {
                    every { isParticipant(player.uniqueId) } returns true
                    every { currentMode() } returns EventMode.DISASTERS
                    every { snapshot() } returns testSnapshot(3)
                    every { modeAvailable(any()) } returns true
                    every { modeUnavailable(any()) } returns null
                    every { queueControl(player.uniqueId) } returns CompletableFuture.completedFuture(QueueControlSnapshot(null, null))
                    every { arcadeSnapshot() } returns mockk {
                        every { mode } returns EventMode.DISASTERS
                        every { participants } returns mapOf(player.uniqueId to ArcadePlayer(player.uniqueId, player.name, "test", ParticipantStatus.ALIVE))
                    }
                }
                ArcEventsMenu(plugin, service, mockk<TttItems>(), locale, { settings }, layouts).use { menu ->
                    menu.open(player)
                    player.openInventory.topInventory.contents.filterNotNull().none { it.type == Material.COMMAND_BLOCK } shouldBe true
                    val view = EventsView.Arcade(EventMode.DISASTERS)
                    val slot = layouts.slot(view, "admin")
                    fun clickAdmin() {
                        paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER,
                            slot, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                        paper.performTicks(2)
                    }
                    menu.openRoot(player); paper.performTicks(2)
                    val inventory = player.openInventory.topInventory
                    inventory.getItem(layouts.slot(view, "overview"))?.type shouldBe Material.LIGHTNING_ROD
                    inventory.getItem(layouts.slot(view, "center"))?.type shouldBe Material.ENDER_PEARL
                    inventory.getItem(slot)?.type shouldBe Material.COMMAND_BLOCK
                    grant.setPermission("arcevents.admin", false)
                    clickAdmin()
                    val denied = generateSequence { player.nextMessage() }.joinToString()
                    denied.contains("У вас нет доступа") shouldBe true
                    denied.contains("/events admin") shouldBe false
                    menu.openRoot(player); paper.performTicks(2)
                    (player.openInventory.topInventory.getItem(slot)?.type == Material.COMMAND_BLOCK) shouldBe false
                    grant.setPermission("arcevents.admin", true)
                    menu.openRoot(player); paper.performTicks(2)
                    clickAdmin()
                    val commands = generateSequence { player.nextMessage() }.joinToString()
                    commands.contains("/events admin start disasters") shouldBe true
                    commands.contains("/events admin solo disasters") shouldBe true
                }
            } finally { Tasks.reset(); root.toFile().deleteRecursively() }
        } }
    }

    test("TTT arena picker highlights the ready default and respects a creator's explicit selection") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcEventsMapMenuTest")
            PaperArcRuntime.installScheduling(plugin)
            val player = paper.addPlayer("MapQA")
            val root = Files.createTempDirectory("arcevents-map-menu-")
            try {
                val settings = ArcEventsConfig.load(root)
                val locale = ArcEventsLocale(root) { settings }
                val layouts = ArcEventsMenuLayouts(root)
                val service = mockk<ArcEventsService> {
                    every { queueControl(player.uniqueId) } returns CompletableFuture.completedFuture(
                        QueueControlSnapshot(QueueState.QUEUED, player.uniqueId))
                    every { selectableArenaIds() } returns listOf("inferno", "riot-in-prison", "custom-quarry")
                    every { defaultArenaId(EventMode.TTT) } returns "riot-in-prison"
                }
                ArcEventsMenu(plugin, service, mockk<TttItems>(), locale, { settings },
                    layouts).use { menu ->
                    val view = EventsView.EventArenas
                    val (inferno, prison, custom) = layouts.region(view, "arenas")
                    menu.open(player, view)
                    paper.performTicks(2)
                    val initial = player.openInventory.topInventory
                    initial.getItem(prison)?.type shouldBe Material.LIME_CONCRETE
                    val plain = PlainTextComponentSerializer.plainText()
                    plain.serialize(initial.getItem(prison)!!.itemMeta.displayName()!!) shouldBe "Riot in Prison"
                    plain.serialize(initial.getItem(custom)!!.itemMeta.displayName()!!) shouldBe "custom-quarry"
                    paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER,
                        inferno, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                    paper.performTicks(3)
                    player.openInventory.topInventory.getItem(inferno)?.type shouldBe Material.LIME_CONCRETE
                    player.openInventory.topInventory.getItem(prison)?.type shouldBe Material.FILLED_MAP
                }
            } finally { Tasks.reset(); root.toFile().deleteRecursively() }
        } }
    }

    test("Back from chest-only menus starts a fresh native dialog flow") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcEventsChestBackTest")
            PaperArcRuntime.installScheduling(plugin)
            val player = spyk(paper.addPlayer("ChestBackQA"))
            every { player.protocolVersion } returns MIN_DIALOG_PROTOCOL
            player.addAttachment(plugin, "arcevents.admin", true)
            val root = Files.createTempDirectory("arcevents-chest-back-")
            try {
                Files.writeString(root.resolve("config.yml"), "ui:\n  dialogs-enabled: true\n")
                val settings = ArcEventsConfig.load(root)
                val locale = ArcEventsLocale(root) { settings }
                val layouts = ArcEventsMenuLayouts(root)
                val id = player.uniqueId
                val participant = TttParticipant(id, player.name, "test", TttRole.INNOCENT, ParticipantStatus.ALIVE, 0)
                val round = TttMatch(UUID.randomUUID(), 0, MatchPhase.ACTIVE, mapOf(id to participant), 1)
                val report = RoundReportView(round.matchId, TttTeam.INNOCENTS, MatchEndReason.ELIMINATION,
                    30, emptyList(), emptyList())
                val service = mockk<ArcEventsService>(relaxed = true) {
                    every { snapshot() } returns testSnapshot(3)
                    every { queueControl(id) } returns CompletableFuture.completedFuture(QueueControlSnapshot(QueueState.QUEUED, id))
                    every { participant(id) } returns participant
                    every { currentMatch() } returns round
                    every { fishingAdventure(player) } returns null
                    every { roster(id) } returns RosterView(emptyList())
                    every { report() } returns report
                }
                val dialogs = mockk<PaperDialogRuntime>(relaxed = true)
                val returns = listOf(
                    EventsView.Arenas to TTT_DIALOG_ID,
                    EventsView.EventArenas to TTT_DIALOG_ID,
                    EventsView.Shop to TTT_DIALOG_ID,
                    EventsView.Roster to TTT_DIALOG_ID,
                    EventsView.CombatLog(0) to "events.report",
                )
                ArcEventsMenu(plugin, service, mockk<TttItems>(), locale, { settings }, layouts, dialogs = dialogs).use { menu ->
                    var previousInventory: Inventory? = null
                    for ((view, dialogId) in returns) {
                        withClue("Back in $view") {
                            menu.open(player, view)
                            paper.performTicks(2)
                            val inventory = player.openInventory
                            (inventory.topInventory === previousInventory) shouldBe false
                            previousInventory = inventory.topInventory
                            clearMocks(dialogs)
                            val slot = layouts.slot(view, "back")
                            val event = InventoryClickEvent(inventory, InventoryType.SlotType.CONTAINER,
                                slot, ClickType.LEFT, InventoryAction.PICKUP_ALL)
                            paper.callEvent(event)
                            paper.performTicks(2)

                            event.isCancelled shouldBe true
                            verifyOrder {
                                dialogs.beginFlow(player)
                                dialogs.open(player, match { it.id == dialogId }, any(), any(), any())
                            }
                        }
                    }
                }
            } finally { Tasks.reset(); root.toFile().deleteRecursively() }
        } }
    }

    test("TTT arena requests stay with their current native dialog or chest") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcEventsArenaNavigationTest")
            PaperArcRuntime.installScheduling(plugin)
            val player = spyk(paper.addPlayer("ArenaNavQA"))
            every { player.protocolVersion } returns MIN_DIALOG_PROTOCOL
            val root = Files.createTempDirectory("arcevents-arena-navigation-")
            try {
                Files.writeString(root.resolve("config.yml"), "ui:\n  dialogs-enabled: true\n")
                val settings = ArcEventsConfig.load(root)
                val locale = ArcEventsLocale(root) { settings }
                val layouts = ArcEventsMenuLayouts(root)
                val id = player.uniqueId
                val queued = QueueControlSnapshot(QueueState.QUEUED, id, 3)
                var queueControl = CompletableFuture.completedFuture(queued)
                val service = mockk<ArcEventsService>(relaxed = true) {
                    every { snapshot() } returns testSnapshot(3)
                    every { queueControl(id) } answers { queueControl }
                    every { modeUnavailable(EventMode.TTT) } returns null
                    every { defaultArenaId() } returns "inferno"
                    every { selectableArenaIds() } returns listOf("inferno")
                }
                val screen = slot<PaperDialogScreen>()
                val dismiss = slot<() -> Unit>()
                val dialogs = mockk<PaperDialogRuntime>(relaxed = true) {
                    every { open(player, capture(screen), any(), capture(dismiss), any()) } returns Unit
                }
                val click = mockk<PaperDialogClickContext>()
                every { click.player } returns player
                val arenaButton = "action_menu_event_arena_name"
                fun requestArenas(): CompletableFuture<QueueControlSnapshot> {
                    val pending = CompletableFuture<QueueControlSnapshot>()
                    queueControl = pending
                    screen.captured.buttons.single { it.id.value == arenaButton }.onClick.handle(click)
                    return pending
                }

                ArcEventsMenu(plugin, service, mockk<TttItems>(), locale, { settings }, layouts, dialogs = dialogs).use { menu ->
                    fun openTtt() {
                        queueControl = CompletableFuture.completedFuture(queued)
                        menu.openRoot(player, EventsView.Ttt)
                        paper.performTicks(2)
                    }
                    openTtt()
                    val failed = requestArenas()
                    verify(exactly = 0) { dialogs.close(player) }
                    screen.captured.id shouldBe TTT_DIALOG_ID
                    screen.captured.buttons.isEmpty() shouldBe true
                    queueControl = CompletableFuture.completedFuture(queued)
                    failed.completeExceptionally(IllegalStateException("queue unavailable"))
                    paper.performTicks(2)
                    screen.captured.buttons.any { it.id.value == arenaButton } shouldBe true
                    verify(exactly = 1) { dialogs.beginFlow(player) }

                    val dismissed = requestArenas()
                    dismiss.captured.invoke()
                    val foreign = Bukkit.createInventory(null, 9)
                    player.openInventory(foreign)
                    dismissed.complete(queued)
                    paper.performTicks(2)
                    player.openInventory.topInventory shouldBe foreign

                    openTtt()
                    val offline = requestArenas()
                    every { player.isOnline } returns false
                    offline.complete(queued)
                    paper.performTicks(2)
                    verify(exactly = 0) { service.selectableArenaIds() }
                    every { player.isOnline } returns true

                    openTtt()
                    requestArenas().complete(queued)
                    paper.performTicks(2)
                    val picker = player.openInventory
                    val auto = layouts.slot(EventsView.EventArenas, "auto")
                    picker.topInventory.getItem(auto)?.type shouldBe Material.COMPASS
                    verify(exactly = 1) { dialogs.close(player) }

                    val selected = CompletableFuture<QueueControlSnapshot>()
                    queueControl = selected
                    paper.callEvent(InventoryClickEvent(picker, InventoryType.SlotType.CONTAINER,
                        auto, ClickType.LEFT, InventoryAction.PICKUP_ALL))
                    paper.performTicks(2)
                    player.closeInventory()
                    player.openInventory(foreign)
                    queueControl = CompletableFuture.completedFuture(queued)
                    selected.complete(queued)
                    paper.performTicks(2)
                    player.openInventory.topInventory shouldBe foreign
                    verify(exactly = 1) { service.selectableArenaIds() }
                }
            } finally { Tasks.reset(); root.toFile().deleteRecursively() }
        } }
    }

    test("deferred queue control enables the owner to start Gun Game") {
        failOnUnsupportedMockBukkitOperation { MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArcEventsOwnerMenuTest"); PaperArcRuntime.installScheduling(plugin)
            val player = paper.addPlayer("OwnerQA"); val root = Files.createTempDirectory("arcevents-owner-menu-")
            try {
                Files.createDirectories(root.resolve("lang")); copyResource("config.yml", root.resolve("config.yml")); copyResource("lang/ru.yml", root.resolve("lang/ru.yml")); copyResource("lang/en.yml", root.resolve("lang/en.yml"))
                val settings = ArcEventsConfig.load(root); val locale = ArcEventsLocale(root) { settings }; val layouts = ArcEventsMenuLayouts(root); val deferred = CompletableFuture<QueueControlSnapshot>()
                val service = mockk<ArcEventsService> {
                    every { snapshot() } returns testSnapshot(3)
                    every { defaultArenaId(EventMode.GUN_GAME) } returns "deadwood"
                    every { modeAvailable(any()) } returns true
                    every { modeUnavailable(any()) } returns null
                    every { isParticipant(any()) } returns false
                    every { queueControl(player.uniqueId) } returns deferred
                    every { arcadeSnapshot() } returns null
                    every { startFromQueue(any(), any(), EventMode.GUN_GAME) } returns CompletableFuture.completedFuture(ReservationStartResult.STARTED)
                }
                ArcEventsMenu(plugin, service, mockk<TttItems>(), locale, { settings }, layouts).use { menu ->
                    menu.open(player, EventsView.Arcade(EventMode.GUN_GAME)); paper.performTicks(1); deferred.complete(QueueControlSnapshot(QueueState.QUEUED, player.uniqueId, 3)); paper.performTicks(2)
                    paper.callEvent(InventoryClickEvent(player.openInventory, InventoryType.SlotType.CONTAINER, layouts.slot(EventsView.Arcade(EventMode.GUN_GAME), "right"), ClickType.LEFT, InventoryAction.PICKUP_ALL)); paper.performTicks(3)
                    verify(exactly = 1) { service.startFromQueue(player, null, EventMode.GUN_GAME) }
                }
            } finally { Tasks.reset(); root.toFile().deleteRecursively() }
        } }
    }
})

private fun testSnapshot(queueSize: Int) = ServiceSnapshot("test", NodeMode.HOST, "test", true, true, true, null, queueSize, null, null, 0, 0, 0, 0, 0, 0)

private fun copyResource(name: String, target: java.nio.file.Path) {
    requireNotNull(ArcEventsMenuMockBukkitTest::class.java.classLoader.getResourceAsStream(name)).use { source ->
        Files.copy(source, target)
    }
}
