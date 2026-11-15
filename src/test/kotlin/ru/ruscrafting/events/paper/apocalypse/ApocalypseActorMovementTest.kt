package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Zombie
import org.bukkit.util.Vector
import org.mockbukkit.mockbukkit.entity.EntityMock
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.NodeMode
import java.util.UUID

class ApocalypseActorMovementTest : StringSpec({
    "grounded zombies in a flat crowd track flying and grounded participants without a shared jump" {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("HordeMovement")
            val world = paper.addSimpleWorld("expedition")
            world.getBlockAt(0, 63, 0).type = Material.STONE
            val observer = paper.addPlayer("Observer")
            observer.teleport(Location(world, 3.0, 70.0, .5))
            observer.allowFlight = true
            observer.isFlying = true
            val survivor = paper.addPlayer("Survivor")
            survivor.teleport(Location(world, 3.0, 64.0, .5))
            val velocity = Vector(.05, 0.0, 0.0)
            val crowd = listOf(.3, .7).map { x ->
                world.spawn(Location(world, x, 64.0, .5), Zombie::class.java).also {
                    (it as EntityMock).setOnGround(true)
                    it.velocity = velocity.clone()
                }
            }
            (crowd[1] in crowd[0].getNearbyEntities(.7, .5, .7)) shouldBe true
            val settings = mockk<ArcEventsConfig> { every { nodeMode } returns NodeMode.RELAY }
            PaperArcRuntime.installScheduling(plugin)
            try {
                ApocalypseRuntime(plugin, { settings }, mockk(), mockk(), mockk(), mockk(), mockk(),
                    occupied = { false }, queued = mockk(), returnToOrigin = { _, _ -> false }, chunkTickets = mockk()).use { runtime ->
                    // Isolate the scheduled actor pass from horde creation and durable expedition admission.
                    val actorsField = runtime.javaClass.getDeclaredField("actors").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    val actors = actorsField.get(runtime) as MutableMap<String, UUID>
                    crowd.forEach { actors[it.uniqueId.toString()] = it.uniqueId }
                    val tick = runtime.javaClass.getDeclaredMethod("tickActors", List::class.java).apply { isAccessible = true }
                    for (participants in listOf(listOf(observer), listOf(observer, survivor))) {
                        tick.invoke(runtime, participants)
                        crowd.forEach {
                            it.target shouldBe participants.last()
                            it.velocity shouldBe velocity
                        }
                    }
                }
            } finally {
                Tasks.reset()
            }
        }
    }
})
