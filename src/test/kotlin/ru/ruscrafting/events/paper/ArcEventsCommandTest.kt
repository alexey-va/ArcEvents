package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.EventMode
import ru.ruscrafting.events.domain.FirearmSoundPreset

class ArcEventsCommandTest : StringSpec({
    val plugin = mockk<Plugin>(relaxed = true) { every { isEnabled } returns true }
    val service = mockk<ArcEventsService>(relaxed = true)
    val settings = mockk<ArcEventsConfig>(relaxed = true)
    val command = mockk<Command>()
    val executor = ArcEventsCommand(plugin, service, mockk(), mockk(), mockk(), { settings }, { java.util.concurrent.CompletableFuture.completedFuture(Result.success(Unit)) })
    lateinit var scheduler: TestTaskScheduler
    beforeTest { scheduler = TestTaskScheduler(); Tasks.install(scheduler) }
    afterTest { Tasks.reset() }

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

    "sound selection rejects missing permission and invalid arguments before changing the shared preset" {
        val soundService = mockk<ArcEventsService>(relaxed = true)
        val locale = mockk<ArcEventsLocale> {
            every { render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
        }
        val soundCommand = ArcEventsCommand(plugin, soundService, mockk(), mockk(), locale, { settings },
            { java.util.concurrent.CompletableFuture.completedFuture(Result.success(Unit)) })
        val player = mockk<Player>(relaxed = true)
        soundCommand.onCommand(player, command, "arcevents", arrayOf("admin", "sound", "1")) shouldBe true
        verify(exactly = 1) { locale.render("command.no-permission", player, emptyMap()) }

        every { player.hasPermission("arcevents.admin") } returns true
        for (args in listOf(arrayOf("admin", "sound"), arrayOf("admin", "sound", "unknown"),
            arrayOf("admin", "sound", "1", "2"))) {
            soundCommand.onCommand(player, command, "arcevents", args) shouldBe true
        }
        verify(exactly = 3) { locale.render("weapon.sound-usage", player, emptyMap()) }
        verify(exactly = 0) { soundService.selectFirearmSound(any()) }
    }

    "players and console use the same global sound aliases and default restores original" {
        val soundService = mockk<ArcEventsService>()
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission("arcevents.admin") } returns true
        }
        val locale = mockk<ArcEventsLocale> {
            every { render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
        }
        val soundCommand = ArcEventsCommand(plugin, soundService, mockk(), mockk(), locale, { settings },
            { java.util.concurrent.CompletableFuture.completedFuture(Result.success(Unit)) })
        val console = mockk<CommandSender>(relaxed = true) {
            every { hasPermission("arcevents.admin") } returns true
        }
        for (sender in listOf(player, console)) {
            for ((input, preset) in listOf(
                "1" to FirearmSoundPreset.SHARP, "SHARP" to FirearmSoundPreset.SHARP,
                "2" to FirearmSoundPreset.HEAVY, "heavy" to FirearmSoundPreset.HEAVY,
                "3" to FirearmSoundPreset.ECHO, "echo" to FirearmSoundPreset.ECHO,
                "ORIGINAL" to FirearmSoundPreset.ORIGINAL, "DEFAULT" to FirearmSoundPreset.ORIGINAL,
            )) {
                every { soundService.selectFirearmSound(preset) } returns java.util.concurrent.CompletableFuture.completedFuture(preset)
                soundCommand.onCommand(sender, command, "arcevents", arrayOf("ADMIN", "SOUND", input)) shouldBe true
                scheduler.executeImmediate()
                verify { locale.render("weapon.sound-selection", sender,
                    mapOf("preset" to Component.text("weapon.sound-preset-${preset.name.lowercase()}"))) }
            }
        }
        FirearmSoundPreset.entries.forEach { preset ->
            verify(exactly = 4) { soundService.selectFirearmSound(preset) }
        }
    }

    "sound selection waits for persistence and reports storage failure without success" {
        val soundService = mockk<ArcEventsService>()
        val sender = mockk<CommandSender>(relaxed = true) { every { hasPermission("arcevents.admin") } returns true }
        val locale = mockk<ArcEventsLocale> {
            every { render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
        }
        val pending = java.util.concurrent.CompletableFuture<FirearmSoundPreset>()
        every { soundService.selectFirearmSound(FirearmSoundPreset.SHARP) } returns pending
        val soundCommand = ArcEventsCommand(plugin, soundService, mockk(), mockk(), locale, { settings },
            { java.util.concurrent.CompletableFuture.completedFuture(Result.success(Unit)) })
        soundCommand.onCommand(sender, command, "arcevents", arrayOf("admin", "sound", "1")) shouldBe true
        scheduler.executeImmediate()
        verify(exactly = 0) { locale.render(any(), any(), any()) }
        pending.completeExceptionally(IllegalStateException("Redis unavailable"))
        scheduler.executeImmediate()
        verify(exactly = 1) { locale.render("command.failed", sender, mapOf("reason" to Component.text("reason.network"))) }
        verify(exactly = 0) { locale.render("weapon.sound-selection", any(), any()) }
    }

    "sound completion is available to admins including console and stops after the selected preset" {
        val player = mockk<Player>(relaxed = true) {
            every { hasPermission("arcevents.play") } returns true
        }
        executor.onTabComplete(player, command, "arcevents", arrayOf("admin", "sound", "")) shouldBe emptyList()
        every { player.hasPermission("arcevents.admin") } returns true
        executor.onTabComplete(player, command, "arcevents", arrayOf("admin", "so")) shouldBe listOf("sound")
        executor.onTabComplete(player, command, "arcevents", arrayOf("admin", "sound", "")) shouldBe
            listOf("1", "2", "3", "default", "echo", "heavy", "original", "sharp")
        executor.onTabComplete(player, command, "arcevents", arrayOf("ADMIN", "SOUND", "H")) shouldBe listOf("heavy")
        executor.onTabComplete(player, command, "arcevents", arrayOf("admin", "sound", "1", "")) shouldBe emptyList()
        val console = mockk<CommandSender>(relaxed = true) {
            every { hasPermission("arcevents.play") } returns true
            every { hasPermission("arcevents.admin") } returns true
        }
        executor.onTabComplete(console, command, "arcevents", arrayOf("admin", "so")) shouldBe listOf("sound")
        executor.onTabComplete(console, command, "arcevents", arrayOf("admin", "sound", "")) shouldBe
            listOf("1", "2", "3", "default", "echo", "heavy", "original", "sharp")
    }
})
