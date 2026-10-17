package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.World
import org.bukkit.plugin.Plugin
import java.util.UUID
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult

class LootChunkTicketRegistryTest : StringSpec({
    "one plugin ticket protects every loot entity in a chunk until the last release" {
        val plugin = mockk<Plugin>()
        val world = mockk<World>()
        every { world.uid } returns UUID.fromString("00000000-0000-0000-0000-000000000042")
        every { world.addPluginChunkTicket(-1, 2, plugin) } returns true
        every { world.removePluginChunkTicket(-1, 2, plugin) } returns true
        val tickets = testTickets(plugin, world)

        val first = (tickets.acquire(PaperChunkKey(world.uid, -1, 2)) as PaperChunkTicketAcquireResult.Acquired).lease
        val second = (tickets.acquire(PaperChunkKey(world.uid, -1, 2)) as PaperChunkTicketAcquireResult.Acquired).lease

        first.key shouldBe PaperChunkKey(world.uid, -1, 2)
        second.key shouldBe first.key
        verify(exactly = 1) { world.addPluginChunkTicket(-1, 2, plugin) }

        first.close()
        verify(exactly = 0) { world.removePluginChunkTicket(-1, 2, plugin) }
        second.close()
        verify(exactly = 1) { world.removePluginChunkTicket(-1, 2, plugin) }
    }

    "clear releases every ticket owned by the loot scene but preserves a pre-existing ticket" {
        val plugin = mockk<Plugin>()
        val world = mockk<World>()
        every { world.uid } returns UUID.fromString("00000000-0000-0000-0000-000000000043")
        every { world.addPluginChunkTicket(0, 0, plugin) } returns true
        every { world.addPluginChunkTicket(1, 0, plugin) } returns false
        every { world.removePluginChunkTicket(0, 0, plugin) } returns true
        val tickets = testTickets(plugin, world)

        val first = (tickets.acquire(PaperChunkKey(world.uid, 0, 0)) as PaperChunkTicketAcquireResult.Acquired).lease
        val borrowed = (tickets.acquire(PaperChunkKey(world.uid, 1, 0)) as PaperChunkTicketAcquireResult.Acquired).lease
        first.close()
        borrowed.close()

        verify(exactly = 1) { world.removePluginChunkTicket(0, 0, plugin) }
        verify(exactly = 0) { world.removePluginChunkTicket(1, 0, plugin) }
    }
})

private fun testTickets(plugin: Plugin, world: World) = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
    override fun add(key: PaperChunkKey) = if (world.addPluginChunkTicket(key.x, key.z, plugin))
        PaperChunkTicketAddResult.ADDED else PaperChunkTicketAddResult.ALREADY_PRESENT
    override fun remove(key: PaperChunkKey) = world.removePluginChunkTicket(key.x, key.z, plugin)
})
