package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.Material
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArenaSettings
import ru.ruscrafting.events.config.EventBounds
import ru.ruscrafting.events.config.EventLocation
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.config.TttSettings
import java.nio.file.Files
import java.util.UUID

class ArenaWeaponPointEditorTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    test("admin adds an exact safe point only while the arena is idle") {
        val root = Files.createTempDirectory("arcevents-editor-")
        try {
            val world = paper.addSimpleWorld("arena")
            world.getBlockAt(1, 64, 2).type = Material.STONE
            val player = paper.addPlayer("Admin")
            player.teleport(Location(world, 1.8, 65.0, 2.2))
            val arena = arena()
            val config = config(NodeMode.HOST, arena)
            val pool = ArenaPool({ config }) { _, _ -> true }

            ArenaWeaponPointStore(root).use { store ->
                val editor = ArenaWeaponPointEditor({ config }, pool, store)
                editor.add(player) shouldBe ArenaWeaponPointFeedback(
                    ArenaWeaponPointAdminResult.ADDED,
                    arenaId = "test",
                    count = 1,
                    target = 30,
                )
                store.points(arena) shouldBe listOf(EventLocation("arena", 1.5, 65.1, 2.5))
                editor.add(player).result shouldBe ArenaWeaponPointAdminResult.DUPLICATE

                mandatoryWeaponPointsReady(paper.server, store, arena) shouldBe true
                world.getBlockAt(1, 64, 2).type = Material.BARRIER
                mandatoryWeaponPointsReady(paper.server, store, arena) shouldBe false
                world.getBlockAt(1, 64, 2).type = Material.STONE

                pool.reserve(UUID.randomUUID())?.id shouldBe "test"
                editor.remove(player).result shouldBe ArenaWeaponPointAdminResult.BUSY
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    test("editor rejects relay nodes and unsafe floors") {
        val root = Files.createTempDirectory("arcevents-editor-reject-")
        try {
            val world = paper.addSimpleWorld("arena")
            world.getBlockAt(1, 64, 2).type = Material.BARRIER
            val player = paper.addPlayer("Admin")
            player.teleport(Location(world, 1.5, 65.0, 2.5))
            val arena = arena()

            ArenaWeaponPointStore(root).use { store ->
                val host = config(NodeMode.HOST, arena)
                val editor = ArenaWeaponPointEditor({ host }, ArenaPool({ host }) { _, _ -> true }, store)
                editor.add(player).result shouldBe ArenaWeaponPointAdminResult.UNSAFE

                player.teleport(Location(world, 101.5, 65.0, 2.5))
                editor.add(player).result shouldBe ArenaWeaponPointAdminResult.OUTSIDE_ARENA

                val relay = config(NodeMode.RELAY, arena)
                ArenaWeaponPointEditor({ relay }, ArenaPool({ relay }) { _, _ -> true }, store)
                    .add(player).result shouldBe ArenaWeaponPointAdminResult.WRONG_NODE
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}) {
    companion object {
        private fun config(mode: NodeMode, arena: ArenaSettings): ArcEventsConfig = mockk {
            every { nodeMode } returns mode
            every { arenas } returns listOf(arena)
            every { ttt } returns mockk<TttSettings> { every { maximumPlayers } returns 16 }
        }

        private fun arena() = ArenaSettings(
            id = "test",
            enabled = true,
            world = "arena",
            template = "",
            lobby = EventLocation("arena", 1.5, 65.1, 2.5),
            spectator = EventLocation("arena", 2.5, 65.1, 2.5),
            bounds = EventBounds(
                EventLocation("arena", 0.0, 60.0, 0.0),
                EventLocation("arena", 100.0, 100.0, 100.0),
            ),
            spawns = listOf(EventLocation("arena", 3.5, 65.1, 2.5)),
            lootSpawns = (0 until 32).map { EventLocation("arena", it + 0.5, 65.1, 2.5) },
            weaponCount = 30,
        )
    }
}
