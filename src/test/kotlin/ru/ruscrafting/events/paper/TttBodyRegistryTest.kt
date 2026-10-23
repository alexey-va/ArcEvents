package ru.ruscrafting.events.paper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.joml.Vector3f
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.domain.ParticipantStatus
import ru.ruscrafting.events.domain.TttParticipant
import ru.ruscrafting.events.domain.TttRole
import java.util.UUID
import java.util.function.Consumer

class TttBodyRegistryTest : FunSpec({
    test("native evidence identifies the body while anonymous packet parts reveal and retire together") {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                val fixture = BodyRegistryFixture(paper)
                val record = fixture.spawn()
                fixture.registry.bodyId(fixture.anchor.uniqueId) shouldBe record.bodyId
                fixture.registry.readBodyId(fixture.anchor) shouldBe record.bodyId
                record.entityId shouldBe fixture.anchor.uniqueId
                record.headEntityId shouldBe fixture.head.uniqueId
                record.labelEntityId shouldBe fixture.label.uniqueId
                record.visualEntityIds.size shouldBe 6
                record.discovered shouldBe false
                fixture.registry.bodyId(fixture.head.uniqueId) shouldBe null
                verify { fixture.anchor.interactionWidth = 1.25f; fixture.anchor.interactionHeight = 0.75f }
                verify { fixture.anchor.isResponsive = true; fixture.anchor.isPersistent = false }
                verify { fixture.displays.spawnText(match { it.y == 64.95 }, Component.text("???")) }
                verify { fixture.displays.spawnItem(match { it.yaw == 90f && it.pitch == 0f }, match { it.type == Material.SKELETON_SKULL }) }
                verify { fixture.label.billboard = Display.Billboard.CENTER; fixture.label.isSeeThrough = false }
                verify { fixture.label.lineWidth = 180; fixture.label.viewRange = 0.7f }
                verify { fixture.head.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.HEAD }
                verify { fixture.head.transformation = match { it.scale == Vector3f(0.82f) } }
                fixture.blocks.forEach { block -> verify { block.viewRange = 0.8f; block.shadowRadius = 0f } }

                fixture.registry.reveal(record.bodyId, Component.text("Victim"))
                verify { fixture.label.text(Component.text("Victim")) }
                verify { fixture.head.itemStack = match { it.type == Material.PLAYER_HEAD } }
                fixture.registry.clearAsync().join()
                fixture.registry.size shouldBe 0
                verify(exactly = 1) { fixture.anchor.remove(); fixture.label.remove(); fixture.head.remove() }
                fixture.blocks.forEach { block -> verify(exactly = 1) { block.remove() } }
                verify(exactly = 0) { fixture.displays.close(); fixture.displays.closeAsync() }
                scheduler.pendingCount() shouldBe 0
            }
        }
    }

    test("a partially created body removes its native evidence and every created packet part") {
        MockBukkitTestRuntime.open().use { paper ->
            Tasks.withScheduler(TestTaskScheduler()) {
                val fixture = BodyRegistryFixture(paper)
                every { fixture.displays.spawnBlock(any(), any()) } throws IllegalStateException("block capture failed")
                shouldThrow<IllegalStateException> { fixture.spawn() }.message shouldBe "block capture failed"
                fixture.registry.size shouldBe 0
                verify(exactly = 1) { fixture.anchor.remove(); fixture.label.remove(); fixture.head.remove() }
                verify(exactly = 0) { fixture.displays.close(); fixture.displays.closeAsync() }
            }
        }
    }
})

private class BodyRegistryFixture(paper: MockBukkitTestRuntime) {
    private val plugin = paper.createSimplePlugin("BodyPacketTest")
    private val victim = paper.addPlayer("Victim")
    val anchor = mockk<Interaction>(relaxed = true) {
        every { uniqueId } returns UUID.randomUUID()
        every { persistentDataContainer } returns victim.persistentDataContainer
    }
    val label = mockk<PacketTextDisplay>(relaxed = true) { every { uniqueId } returns UUID.randomUUID() }
    val head = mockk<PacketItemDisplay>(relaxed = true) { every { uniqueId } returns UUID.randomUUID() }
    val blocks = mutableListOf<PacketBlockDisplay>()
    val displays = mockk<PaperPacketDisplays>(relaxed = true) {
        every { spawnText(any(), any()) } returns label
        every { spawnItem(any(), any()) } returns head
        every { spawnBlock(any(), any()) } answers {
            mockk<PacketBlockDisplay>(relaxed = true) { every { uniqueId } returns UUID.randomUUID() }.also(blocks::add)
        }
    }
    private val testWorld = mockk<World>(relaxed = true) {
        every { spawn(any<Location>(), Interaction::class.java, any<Consumer<in Interaction>>()) } answers {
            thirdArg<Consumer<in Interaction>>().accept(anchor)
            anchor
        }
    }
    private val player = mockk<Player> {
        every { location } returns Location(testWorld, 0.5, 64.0, 0.5, 90f, 25f)
    }
    private val locale = mockk<ArcEventsLocale> {
        every { render("body.unidentified", null, any()) } returns Component.text("???")
    }
    val registry = TttBodyRegistry(plugin, locale, displays, { 42L })

    fun spawn(): BodyRecord = registry.spawn(
        UUID.randomUUID(), player,
        TttParticipant(victim.uniqueId, victim.name, "host", TttRole.TRAITOR, ParticipantStatus.DEAD, 0),
        null, null, 600L,
    )
}
