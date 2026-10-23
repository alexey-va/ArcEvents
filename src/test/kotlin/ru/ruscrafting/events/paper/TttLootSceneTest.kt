package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.Item
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.LootDisplaySettings
import java.util.UUID

class TttLootSceneTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach {
        paper = MockBukkitTestRuntime.open()
    }

    afterEach {
        paper.close()
    }

    test("an unsafe imported loot point is skipped instead of aborting match preparation") {
        val world = paper.addSimpleWorld("loot-world")
            world.loadChunk(0, 0)
        world.getBlockAt(0, 63, 0).type = Material.BARRIER
        val scene = TttLootScene(
            plugin = paper.createSimplePlugin("ArcEventsLootTest"),
            firearms = mockk(relaxed = true),
            displays = mockk(relaxed = true),
            settings = { mockk<ArcEventsConfig>(relaxed = true) },
        )

        scene.spawn(Location(world, 0.5, 64.0, 0.5), ItemStack.of(Material.IRON_NUGGET)).shouldBeNull()
        scene.size shouldBe 0
    }

    test("exact points never move while random points may find nearby solid flooring") {
        val world = paper.addSimpleWorld("placement-world")
            world.loadChunk(0, 0)
        world.getBlockAt(0, 63, 0).type = Material.BARRIER
        world.getBlockAt(1, 63, 0).type = Material.STONE
        val origin = Location(world, 0.5, 64.1, 0.5)

        TttLootPlacement.exact(origin).shouldBeNull()
        TttLootPlacement.nearby(origin)?.blockX shouldBe 1
    }

    test("packet presentation keeps pickup authority and authored scale height and rotation") {
        val scheduler = TestTaskScheduler()
        Tasks.withScheduler(scheduler) {
            val fixture = LootPresentationFixture(paper)
            fixture.scene.register(fixture.pickup, 0)
            fixture.scene.size shouldBe 1
            verify { fixture.pickup.setVisibleByDefault(false) }
            verify { fixture.displays.spawnItem(match { it.x == 0.5 && it.y == 64.75 && it.z == 0.5 },
                match { it.type == Material.IRON_NUGGET && it.amount == 1 }) }
            verify { fixture.display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED }
            verify { fixture.display.billboard = Display.Billboard.FIXED; fixture.display.viewRange = 0.8f }
            verify { fixture.display.transformation = match { it.scale == Vector3f(0.65f) } }
            scheduler.tick(2)
            val expectedRotation = Quaternionf().rotateY(Math.PI.toFloat() + 0.01f)
            verify { fixture.display.interpolationDuration = 2 }
            verify { fixture.display.transformation = match { it.leftRotation.equals(expectedRotation, 0.00001f) } }
            verify(exactly = 0) { fixture.pickup.itemStack = any() }

            fixture.scene.consume(fixture.pickup.uniqueId)
            fixture.scene.size shouldBe 0
            verify(exactly = 1) { fixture.display.remove() }
            verify(exactly = 0) { fixture.pickup.remove(); fixture.displays.close(); fixture.displays.closeAsync() }
            fixture.releases shouldBe 1
            scheduler.pendingCount() shouldBe 0
        }
    }

    test("display capture failure leaves the real pickup visible and cleanup still releases its lease") {
        Tasks.withScheduler(TestTaskScheduler()) {
            val fixture = LootPresentationFixture(paper)
            every { fixture.displays.spawnItem(any(), any()) } throws IllegalStateException("display unavailable")
            fixture.scene.register(fixture.pickup, 0)
            fixture.scene.size shouldBe 1
            verify { fixture.pickup.setVisibleByDefault(true) }
            fixture.scene.clearAsync().join()
            fixture.scene.size shouldBe 0
            verify(exactly = 1) { fixture.pickup.remove() }
            verify(exactly = 0) { fixture.displays.close(); fixture.displays.closeAsync() }
            fixture.releases shouldBe 1
        }
    }
})

private class LootPresentationFixture(paper: MockBukkitTestRuntime) {
    private val testWorld = paper.addSimpleWorld("loot-presentation").also { it.getChunkAt(0, 0).load() }
    val pickup = mockk<Item>(relaxed = true) {
        every { uniqueId } returns UUID.randomUUID()
        every { location } returns Location(testWorld, 0.5, 64.0, 0.5)
        every { world } returns testWorld
        every { isValid } returns true
        every { itemStack } returns ItemStack.of(Material.IRON_NUGGET, 3)
    }
    val display = mockk<PacketItemDisplay>(relaxed = true) {
        every { isValid } returns true
    }
    val displays = mockk<PaperPacketDisplays>(relaxed = true) {
        every { spawnItem(any(), any()) } returns display
    }
    var releases = 0
    private val tickets = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
        override fun add(key: PaperChunkKey) = PaperChunkTicketAddResult.ADDED
        override fun remove(key: PaperChunkKey): Boolean { releases++; return true }
    })
    private val config = mockk<ArcEventsConfig>(relaxed = true) {
        every { ui.lootDisplays } returns true
        every { ui.lootDisplay } returns LootDisplaySettings(true, 0.75, 0.65, 0.8, 1L, 2, 20)
        every { ui.particles } returns false
    }
    private val firearms = mockk<TttFirearms>(relaxed = true) { every { lootRarity(any()) } returns null }
    val scene = TttLootScene(paper.createSimplePlugin("LootPresentationTest"), firearms, displays,
        chunkTickets = tickets, settings = { config })
}
