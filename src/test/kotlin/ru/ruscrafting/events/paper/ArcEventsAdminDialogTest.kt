package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.command.PluginCommand
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.paper.apocalypse.ApocalypseRuntime

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
        val apoc = mockk<ApocalypseRuntime>(relaxed = true)
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

        menu.open(player)
        verify(exactly = 0) { runtime.open(player, any(), any(), any(), any()) }
        every { player.hasPermission("arcevents.admin") } returns true
        menu.open(player)
        actions() shouldBe listOf("apocalypse", "matches", "weapons", "diagnostics")
        choose("apocalypse")
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
        actions() shouldBe emptyList()
    }
})
