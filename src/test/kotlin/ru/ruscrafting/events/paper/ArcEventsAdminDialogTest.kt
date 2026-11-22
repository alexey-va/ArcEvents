package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventBounds
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.MatchPhase
import ru.ruscrafting.events.paper.apocalypse.ApocalypseRuntime
import java.util.UUID

class ArcEventsAdminDialogTest : StringSpec({
    "native management checks staff permission, host, fresh preview and the exact confirmed world" {
        val target = "arcevents_apocalypse_season"
        val old = "arcevents_apocalypse_v2"
        val player = mockk<Player>(relaxed = true) {
            every { isOnline } returns true
            every { hasPermission("arcevents.play") } returns true
        }
        val settings = mockk<ArcEventsConfig> {
            every { nodeMode } returns NodeMode.HOST
            every { hostServer } returns "parkour"
        }
        val locale = mockk<ArcEventsLocale> {
            every { render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
            every { text(any()) } answers { Component.text(firstArg<Any>().toString()) }
        }
        val runtime = mockk<PaperDialogRuntime>(relaxed = true)
        val apoc = mockk<ApocalypseRuntime>(relaxed = true) {
            every { campaignOwnerOf(any()) } returns null
        }
        val service = mockk<ArcEventsService>(relaxed = true)
        every { service.snapshot() } returns ServiceSnapshot("parkour", NodeMode.HOST, "parkour", true, true, true,
            null, 0, null, null, 0, 0, 0, 0, 0, 0)
        val menu = ArcEventsAdminDialog(runtime, service, locale, { settings }, mockk(), apoc, { false },
            { id, _ -> Component.text(id) }, { false })
        lateinit var screen: PaperDialogScreen
        lateinit var dismiss: () -> Unit
        every { runtime.open(player, any(), any(), any(), any()) } answers {
            screen = secondArg()
            dismiss = arg(3)
        }
        val click = mockk<PaperDialogClickContext> {
            every { this@mockk.player } returns player
            every { text(any()) } returns target
        }
        fun choose(id: String) = screen.buttons.single { it.id.value == id }.onClick.handle(click)
        fun actions() = screen.buttons.map { it.id.value }
        lateinit var preview: (String) -> Unit
        every { apoc.planWorldReset(target, any()) } answers { preview = secondArg() }
        val ready = "ARCEVENTS_APOCALYPSE_RESET_PLAN arc_ready=true current=$old target=$target blockers=-"
        lateinit var authorize: () -> Boolean
        lateinit var result: (String) -> Unit
        every { apoc.queueWorldReset(target, any(), any()) } answers {
            authorize = secondArg()
            result = thirdArg()
        }

        menu.openApocalypse(player)
        verify(exactly = 0) { runtime.open(player, any(), any(), any(), any()) }
        every { player.hasPermission("arcevents.admin") } returns true
        menu.openApocalypse(player)
        actions() shouldBe listOf("enter", "guide", "locate", "day", "gun", "sound", "reset")
        choose("day")
        every { click.text(any()) } returns "100001"
        choose("review")
        actions() shouldBe listOf("review")
        screen.inputs.single().initial.length shouldBe 5
        every { click.text(any()) } returns "7"
        choose("review")
        actions() shouldBe listOf("apply")
        verify(exactly = 0) { apoc.command(any(), any()) }
        choose("apply")
        verify(exactly = 1) { apoc.command(player, listOf("apocalypse", "day", "7")) }
        every { apoc.isPlaying(player.uniqueId) } returns true
        every { apoc.campaignOwnerOf(player.uniqueId) } returns UUID.randomUUID()
        menu.openApocalypse(player)
        ("enter" in actions()) shouldBe false
        ("reset" in actions()) shouldBe false
        every { apoc.isPlaying(player.uniqueId) } returns false
        every { apoc.campaignOwnerOf(player.uniqueId) } returns null
        choose("fly")
        verify(exactly = 0) { apoc.command(player, listOf("apocalypse", "fly")) }
        every { click.text(any()) } returns target
        menu.openApocalypse(player)
        choose("reset")
        screen.inputs.single().initial.startsWith("arcevents_apocalypse_") shouldBe true
        choose("preview")
        actions() shouldBe emptyList()
        preview("ARCEVENTS_APOCALYPSE_RESET_PLAN arc_ready=false blockers=world_occupied")
        actions() shouldBe listOf("retry", "edit")
        verify(exactly = 0) { apoc.queueWorldReset(any(), any(), any()) }
        choose("edit")
        screen.inputs.single().initial shouldBe target
        choose("preview")
        val pendingDismiss = dismiss
        pendingDismiss()
        preview(ready)
        actions() shouldBe emptyList()

        menu.openReset(player, target)
        choose("preview")
        val worlds = mockk<PluginCommand>(relaxed = true) {
            every { testPermissionSilent(player) } returns true
            every { plugin.isEnabled } returns true
        }
        every { player.server.getPluginCommand("my_worlds:world") } returns worlds
        preview("ARCEVENTS_APOCALYPSE_RESET_PLAN arc_ready=false current=$old target=$target blockers=source_autoload")
        actions() shouldBe listOf("autoload", "retry", "edit")
        choose("autoload")
        verify(exactly = 0) { worlds.execute(any(), any(), any()) }
        choose("disable_autoload")
        verify(exactly = 1) { worlds.execute(player, "my_worlds:world", arrayOf("setautoload", "no", old)) }
        verify(exactly = 0) { apoc.queueWorldReset(any(), any(), any()) }
        preview(ready)
        actions() shouldBe listOf("queue_reset", "edit")
        choose("queue_reset")
        authorize() shouldBe true
        every { player.hasPermission("arcevents.admin") } returns false
        authorize() shouldBe false
        every { player.hasPermission("arcevents.admin") } returns true
        result("ARCEVENTS_APOCALYPSE_RESET_QUEUED current=$old target=$target")
        screen.id shouldBe "events.admin.apocalypse"
        verify(exactly = 1) { apoc.queueWorldReset(target, any(), any()) }

        every { settings.nodeMode } returns NodeMode.RELAY
        menu.openReset(player, target)
        screen.id shouldBe "events.admin.apocalypse"
        actions() shouldBe listOf("enter")
    }

    "mode panels act on their own maps, equipment and exact live match or reservation" {
        val player = mockk<Player>(relaxed = true) {
            every { isOnline } returns true
            every { hasPermission("arcevents.play") } returns true
            every { hasPermission("arcevents.admin") } returns true
        }
        val settings = mockk<ArcEventsConfig>(relaxed = true) { every { nodeMode } returns NodeMode.HOST }
        val locale = mockk<ArcEventsLocale> {
            every { render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
            every { text(any()) } answers { Component.text(firstArg<Any>().toString()) }
        }
        val runtime = mockk<PaperDialogRuntime>(relaxed = true)
        val service = mockk<ArcEventsService>(relaxed = true)
        val idle = ServiceSnapshot("parkour", NodeMode.HOST, "parkour", true, true, true,
            null, 1, null, null, 0, 0, 0, 0, 0, 0)
        every { service.snapshot() } returns idle
        every { service.modeUnavailable(any()) } returns null
        every { service.selectableArenaIds(any()) } answers { listOf(firstArg<EventMode>().primaryArenaId) }
        val command = mockk<ArcEventsCommand>(relaxed = true)
        every { command.selectedAdminArena(player, any()) } returns "auto"
        val menu = ArcEventsAdminDialog(runtime, service, locale, { settings }, command, mockk(relaxed = true),
            { false }, { id, _ -> Component.text(id) }, { false })
        lateinit var screen: PaperDialogScreen
        every { runtime.open(player, any(), any(), any(), any()) } answers { screen = secondArg() }
        val click = mockk<PaperDialogClickContext> { every { this@mockk.player } returns player }
        fun choose(id: String) = screen.buttons.single { it.id.value == id }.onClick.handle(click)
        fun actions() = screen.buttons.map { it.id.value }

        for (mode in EventMode.entries) {
            menu.openMode(player, mode)
            screen.id shouldBe "events.admin.${mode.id}"
            actions() shouldBe buildList {
                add("start")
                add("solo")
                if (mode.usesSharedArenas) add("arena")
                if (mode != EventMode.DISASTERS) addAll(listOf("gun", "sound"))
                if (mode == EventMode.TTT) add("points")
            }
            choose("start")
            verify(exactly = 1) { command.admin(player, listOf("start", mode.id, if (mode.usesSharedArenas) "auto" else mode.primaryArenaId), any()) }
            menu.openMode(player, mode)
            choose("solo")
            verify(exactly = 1) { command.admin(player, listOf("solo", mode.id), any()) }
        }
        menu.openMode(player, EventMode.GUN_GAME)
        choose("arena")
        choose("map_0")
        verify(exactly = 0) { command.admin(player, listOf("arena", "auto", "gungame"), any()) }
        every { command.admin(player, listOf("arena", "deadwood", "gungame"), any()) } answers {
            every { command.selectedAdminArena(player, EventMode.GUN_GAME) } returns "deadwood"
            thirdArg<(Component) -> Unit>()(Component.text("ok"))
        }
        choose("map_1")
        screen.id shouldBe "events.admin.gungame.arena"
        menu.openMode(player, EventMode.GUN_GAME)
        choose("start")
        verify(exactly = 1) { command.admin(player, listOf("start", "gungame", "deadwood"), any()) }

        val world = mockk<World> { every { name } returns "arena-test" }
        val tttArena = mockk<ArenaSettings> {
            every { id } returns "riot-in-prison"
            every { enabled } returns true
            every { weaponCount } returns 10
            every { bounds } returns EventBounds(EventLocation("arena-test", 0.0, 60.0, 0.0), EventLocation("arena-test", 20.0, 80.0, 20.0))
        }
        every { settings.arenas } returns listOf(tttArena)
        every { player.location } returns Location(world, 4.0, 65.0, 4.0)
        menu.openMode(player, EventMode.TTT)
        choose("points")
        choose("remove")
        every { player.location } returns Location(world, 32.0, 65.0, 4.0)
        choose("confirm")
        verify(exactly = 0) { command.admin(player, listOf("weapons", "remove"), any()) }
        every { tttArena.id } returns "deadwood"
        every { player.location } returns Location(world, 4.0, 65.0, 4.0)
        menu.openMode(player, EventMode.TTT)
        choose("points")
        actions() shouldBe emptyList()

        val first = idle.copy(matchId = UUID.randomUUID(), phase = MatchPhase.ACTIVE, participants = 2)
        every { service.snapshot() } returns first
        every { service.currentMode() } returns EventMode.GUN_GAME
        every { service.modeUnavailable(any()) } returns ReservationStartResult.BUSY
        menu.openMode(player, EventMode.DISASTERS)
        actions() shouldBe emptyList()
        menu.openMode(player, EventMode.GUN_GAME)
        every { service.currentMode() } returns EventMode.ZOMBIES
        choose("stop")
        actions() shouldBe emptyList()

        every { service.currentMode() } returns EventMode.GUN_GAME
        menu.openMode(player, EventMode.GUN_GAME)
        choose("stop")
        every { service.snapshot() } returns first.copy(matchId = UUID.randomUUID())
        choose("confirm")
        actions() shouldBe emptyList()
        every { service.snapshot() } returns first.copy(phase = MatchPhase.RESERVED)
        menu.openMode(player, EventMode.GUN_GAME)
        choose("stop")
        every { service.snapshot() } returns first
        choose("confirm")
        actions() shouldBe emptyList()
        verify(exactly = 0) { command.admin(player, listOf("stop"), any()) }

        menu.openMode(player, EventMode.GUN_GAME)
        choose("stop")
        choose("confirm")
        verify(exactly = 1) { command.admin(player, listOf("stop"), any()) }
        every { service.snapshot() } returns first.copy(phase = MatchPhase.RESERVED)
        menu.openMode(player, EventMode.GUN_GAME)
        choose("stop")
        choose("confirm")
        verify(exactly = 2) { command.admin(player, listOf("stop"), any()) }

        every { settings.nodeMode } returns NodeMode.RELAY
        menu.openMode(player, EventMode.GUN_GAME)
        ("stop" in actions()) shouldBe false
    }
})
