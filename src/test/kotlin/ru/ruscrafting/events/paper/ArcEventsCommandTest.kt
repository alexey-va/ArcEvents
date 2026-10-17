package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.domain.EventMode

class ArcEventsCommandTest : StringSpec({
    val plugin = mockk<Plugin>(relaxed = true)
    val service = mockk<ArcEventsService>(relaxed = true)
    val settings = mockk<ArcEventsConfig>(relaxed = true)
    val command = mockk<Command>()
    val executor = ArcEventsCommand(plugin, service, mockk(), mockk(), mockk(), { settings }, { Result.success(Unit) })

    "admin start completes a mode before an arena and stops at the final argument" {
        val sender = mockk<CommandSender>(relaxed = true)
        every { sender.hasPermission("arcevents.play") } returns true
        every { sender.hasPermission("arcevents.admin") } returns true
        every { service.selectableArenaIds(EventMode.DISASTERS) } returns listOf("island")

        executor.onTabComplete(sender, command, "arcevents", arrayOf("ADMIN", "start", "dis")) shouldBe listOf("disasters")
        executor.onTabComplete(sender, command, "arcevents", arrayOf("admin", "START", "disasters", "I")) shouldBe listOf("island")
        executor.onTabComplete(sender, command, "arcevents", arrayOf("admin", "start", "unknown", "")) shouldBe emptyList()
        executor.onTabComplete(sender, command, "arcevents", arrayOf("admin", "start", "disasters", "island", "")) shouldBe emptyList()
        verify(exactly = 1) { service.selectableArenaIds(EventMode.DISASTERS) }
    }

    "nested staff completion is permission gated and ordinary start only offers fishing" {
        val player = mockk<Player>(relaxed = true)
        every { player.hasPermission("arcevents.play") } returns true
        listOf(
            arrayOf("admin", ""), arrayOf("admin", "player", ""), arrayOf("qa", "player", ""),
            arrayOf("debug", ""), arrayOf("debug", "kill", ""), arrayOf("help", ""), arrayOf("unknown", ""),
        ).forEach { executor.onTabComplete(player, command, "arcevents", it) shouldBe emptyList() }
        executor.onTabComplete(player, command, "arcevents", arrayOf("start", "")) shouldBe listOf("fishing")
        every { player.hasPermission("arcevents.admin") } returns true
        executor.onTabComplete(player, command, "arcevents", arrayOf("start", "G")) shouldBe listOf("gungame")
        every { player.hasPermission("arcevents.play") } returns false
        executor.onTabComplete(player, command, "arcevents", arrayOf("admin", "")) shouldBe emptyList()
    }

    "debug completion follows the runtime gate and keeps players only in player arguments" {
        val sender = mockk<CommandSender>(relaxed = true)
        every { sender.hasPermission("arcevents.play") } returns true
        every { sender.hasPermission("arcevents.debug") } returns true
        val target = mockk<Player> { every { name } returns "Target" }
        every { plugin.server.onlinePlayers } returns listOf(target)
        every { settings.debugEnabled } returns false
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "")) shouldBe emptyList()
        every { settings.debugEnabled } returns true
        every { settings.debugMutationsAllowed } returns false
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "ki")) shouldBe emptyList()
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "kill", "")) shouldBe emptyList()
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "player", "TA")) shouldBe listOf("Target")
        every { settings.debugMutationsAllowed } returns true
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "timer", "T")) shouldBe emptyList()
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "bootstrap", "auto", "Target")) shouldBe listOf("Target")
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "bootstrap", "auto", "target", "")) shouldBe emptyList()
        executor.onTabComplete(sender, command, "arcevents", arrayOf("debug", "role", "Target", "traitor", "")) shouldBe emptyList()
    }
})
