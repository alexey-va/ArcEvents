package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.EventMode

class ArcEventsCommandTest : StringSpec({
    "admin start completion selects arenas for the requested mode" {
        val plugin = mockk<Plugin>(relaxed = true)
        val service = mockk<ArcEventsService>(relaxed = true)
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>(relaxed = true)
        every { service.selectableArenaIds(EventMode.DISASTERS) } returns listOf("disasters")
        val executor = ArcEventsCommand(
            plugin = plugin,
            service = service,
            menu = mockk(relaxed = true),
            weaponPoints = mockk(relaxed = true),
            locale = mockk<ArcEventsLocale>(relaxed = true),
            settings = { error("settings should not be read for completion") },
            reload = { Result.success(Unit) },
        )

        executor.onTabComplete(sender, command, "arcevents", arrayOf("admin", "start", "disasters")) shouldBe
            listOf("disasters")
        verify(exactly = 1) { service.selectableArenaIds(EventMode.DISASTERS) }
    }
})
