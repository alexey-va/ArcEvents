package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ArenaStageUnloadTest : FunSpec({
    listOf("released", "occupied", "replaced", "shutdown", "permanent veto").forEach { result ->
        test("native stage unload after a Paper veto: $result") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val plugin = paper.createSimplePlugin("ArenaStageUnloadTest")
                    val root = Files.createDirectories(plugin.dataFolder.toPath()).toAbsolutePath().normalize()
                    val stage = root.resolve("${ArenaStagingFiles.PREFIX}fixture")
                    val worldId = UUID.randomUUID()
                    val players = mutableListOf<Player>()
                    val world = mockk<World>(relaxed = true) {
                        every { uid } returns worldId
                        every { name } returns stage.fileName.toString()
                        every { worldFolder } returns stage.toFile()
                    }
                    every { world.players } answers { players }
                    var registered: World? = world
                    val owner = mockk<Plugin>(relaxed = true) {
                        every { dataFolder } returns root.toFile()
                        every { server.worldContainer } returns root.toFile()
                        every { server.worlds } returns emptyList()
                        every { server.getWorld(worldId) } answers { registered }
                        every { server.getWorld(stage.fileName.toString()) } answers { registered }
                    }
                    val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val native = BudgetedTaskQueue(scope)
                    val runtime = RuntimeEventWork(mockk(), scope, native, mockk(), plugin.name)
                    val work = object : EventWork by runtime {
                        override fun <T> compute(action: () -> T) = CompletableFuture.completedFuture(action())
                    }
                    val provisioner = ArenaWorldProvisioner(owner)
                    val server = owner.server
                    val attempts = mutableListOf<Int>()
                    var tick = 0
                    every { server.unloadWorld(world, true) } answers {
                        attempts += tick
                        (result == "released" && attempts.size == 2).also { if (it) registered = null }
                    }
                    every { server.unloadWorld(world, false) } returns true
                    try {
                        provisioner.ensureLoadedAsync(mockk { every { arenas } returns emptyList() }, work).join()
                        provisioner.registerStageWorld(stage, world)
                        val unload = provisioner.unloadPreparedStage(stage, world, work)
                        while (!unload.isDone && tick < 300) {
                            tick++
                            paper.performTicks(1)
                            if (attempts.size == 1 && attempts.single() == tick) when (result) {
                                "occupied" -> players += mockk<Player>()
                                "replaced" -> registered = mockk<World>()
                                "shutdown" -> { work.beginShutdown(); provisioner.close() }
                            }
                        }
                        unload.isDone shouldBe true
                        unload.isCompletedExceptionally shouldBe (result != "released")
                        attempts.size shouldBe when (result) {
                            "released" -> 2
                            "permanent veto" -> 40
                            else -> 1
                        }
                        attempts.distinct() shouldBe attempts
                        verify(exactly = 1) { world.save() }
                        verify(exactly = if (result == "shutdown") 1 else 0) { server.unloadWorld(world, false) }
                    } finally {
                        work.beginShutdown()
                        provisioner.close()
                        native.close()
                        scope.close()
                    }
                }
            }
        }
    }
})
