package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.UUID

class LootChunkTicketRegistryTest : StringSpec({
    "one plugin ticket protects every loot entity in a chunk until the last release" {
        val plugin = mockk<Plugin>()
        val world = mockk<World>()
        every { world.uid } returns UUID.fromString("00000000-0000-0000-0000-000000000042")
        every { world.addPluginChunkTicket(-1, 2, plugin) } returns true
        every { world.removePluginChunkTicket(-1, 2, plugin) } returns true
        val tickets = LootChunkTicketRegistry(plugin)

        val first = tickets.acquire(Location(world, -1.0, 80.0, 32.0))
        val second = tickets.acquire(Location(world, -15.0, 90.0, 47.0))

        first shouldBe LootChunkKey(world.uid, -1, 2)
        second shouldBe first
        verify(exactly = 1) { world.addPluginChunkTicket(-1, 2, plugin) }

        tickets.release(first)
        verify(exactly = 0) { world.removePluginChunkTicket(-1, 2, plugin) }
        tickets.release(second)
        verify(exactly = 1) { world.removePluginChunkTicket(-1, 2, plugin) }
    }

    "clear releases every ticket owned by the loot scene but preserves a pre-existing ticket" {
        val plugin = mockk<Plugin>()
        val world = mockk<World>()
        every { world.uid } returns UUID.fromString("00000000-0000-0000-0000-000000000043")
        every { world.addPluginChunkTicket(0, 0, plugin) } returns true
        every { world.addPluginChunkTicket(1, 0, plugin) } returns false
        every { world.removePluginChunkTicket(0, 0, plugin) } returns true
        val tickets = LootChunkTicketRegistry(plugin)

        tickets.acquire(Location(world, 1.0, 70.0, 1.0))
        tickets.acquire(Location(world, 17.0, 70.0, 1.0))
        tickets.clear()

        verify(exactly = 1) { world.removePluginChunkTicket(0, 0, plugin) }
        verify(exactly = 0) { world.removePluginChunkTicket(1, 0, plugin) }
    }
})
