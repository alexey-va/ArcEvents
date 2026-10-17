package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.plugin.Plugin
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.chunk.PaperChunkTicketAcquireResult
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ArenaChunkLeaseCleanupTest : FunSpec({
    listOf("already completed", "delayed", "stale generation").forEach { completion ->
        test("arena retains a $completion chunk in its Paper completion before another queue tick") {
            failOnUnsupportedMockBukkitOperation {
                MockBukkitTestRuntime.open().use { paper ->
                    val plugin = paper.createSimplePlugin("ArenaChunkCompletion")
                    val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
                    val queue = BudgetedTaskQueue(scope)
                    val nativeThread = Thread.currentThread()
                    val worldId = UUID.randomUUID()
                    val world = mockk<World> {
                        every { uid } returns worldId
                        every { isChunkLoaded(0, 0) } returns true
                    }
                    val chunk = mockk<Chunk>()
                    every { chunk.world } returns world
                    every { chunk.x } returns 0
                    every { chunk.z } returns 0
                    val loaded = if (completion == "already completed") CompletableFuture.completedFuture(chunk)
                        else CompletableFuture<Chunk>()
                    var requested = false
                    every { world.getChunkAtAsync(0, 0, false) } answers {
                        requested = true
                        loaded
                    }
                    val owner = mockk<Plugin>(relaxed = true) {
                        every { server.worldContainer } returns plugin.dataFolder
                        every { dataFolder } returns plugin.dataFolder
                        every { server.getWorld(worldId) } returns world
                    }
                    var current = true
                    var acquired = 0
                    var removed = 0
                    val tickets = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
                        override fun add(key: PaperChunkKey): PaperChunkTicketAddResult {
                            check(Thread.currentThread() === nativeThread)
                            acquired++
                            return PaperChunkTicketAddResult.ADDED
                        }
                        override fun remove(key: PaperChunkKey): Boolean { removed++; return true }
                    })
                    val work = object : EventWork {
                        override fun <T> compute(action: () -> T) = CompletableFuture.completedFuture(action())
                        override fun <T> onMain(action: () -> T) = CompletableFuture.completedFuture(action())
                        override fun <T> callNative(action: () -> T) = queue.call({ current }, action)
                        override fun capture(isCurrent: () -> Boolean, step: () -> Boolean) = queue.submit(isCurrent, step)
                        override fun isCurrent() = current
                    }
                    val provisioner = ArenaWorldProvisioner(owner, tickets)
                    val owned = mutableListOf<ru.arc.paper.chunk.PaperChunkTicketLease>()
                    try {
                        val loading = provisioner.loadChunks(world, listOf(0 to 0), false, owned, work)
                        var ticks = 0
                        while (!requested && !loading.isDone && ticks++ < 10) paper.performTicks(1)
                        requested shouldBe true
                        if (completion == "stale generation") {
                            current = false
                            provisioner.close()
                            queue.close()
                            scope.close()
                        }
                        loaded.complete(chunk)
                        if (completion != "stale generation" && loading.isCompletedExceptionally) loading.join()
                        // No scheduler tick is allowed between Paper's completion and
                        // acquiring the plugin ticket that prevents immediate unloading.
                        acquired shouldBe if (completion == "stale generation") 0 else 1
                        loading.isDone shouldBe true
                        loading.isCompletedExceptionally shouldBe (completion == "stale generation")
                        if (current) {
                            val release = provisioner.releaseChunkLeases(owned, work)
                            ticks = 0
                            while (!release.isDone && ticks++ < 10) paper.performTicks(1)
                            release.isDone shouldBe true
                            release.join()
                            removed shouldBe 1
                            tickets.activeChunkCount shouldBe 0
                        }
                    } finally {
                        provisioner.close()
                        queue.close()
                        scope.close()
                    }
                }
            }
        }
    }

    test("arena release attempts all 300 leases before reporting a native cleanup failure") {
        failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ArenaLeaseCleanup")
            val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val queue = BudgetedTaskQueue(scope)
            var steps = 0
            var removed = 0
            val tickets = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
                override fun add(key: PaperChunkKey) = PaperChunkTicketAddResult.ADDED
                override fun remove(key: PaperChunkKey): Boolean {
                    removed++
                    if (key.x == 17) error("native ticket removal failed")
                    return true
                }
            })
            val work = object : EventWork {
                override fun <T> compute(action: () -> T) = CompletableFuture.completedFuture(action())
                override fun <T> onMain(action: () -> T) = CompletableFuture.completedFuture(action())
                override fun capture(isCurrent: () -> Boolean, step: () -> Boolean) = queue.submit(isCurrent) { steps++; step() }
                override fun isCurrent() = true
            }
            val worldId = UUID.randomUUID()
            val leases = (0 until 300).map { x ->
                (tickets.acquire(PaperChunkKey(worldId, x, 0)) as PaperChunkTicketAcquireResult.Acquired).lease
            }.toMutableList()
            // ServerMock does not implement getWorldContainer; this test exercises leases,
            // so provide that native path without replacing the scheduler or ticket registry.
            val provisionerPlugin = mockk<Plugin>(relaxed = true) {
                every { server.worldContainer } returns plugin.dataFolder
                every { dataFolder } returns plugin.dataFolder
            }
            val provisioner = ArenaWorldProvisioner(provisionerPlugin, tickets)
            val release = provisioner.releaseChunkLeases(leases, work)
            release.isDone shouldBe false
            var ticks = 0
            while (!release.isDone && ticks < 100) {
                steps = 0
                paper.performTicks(1)
                (steps <= 128) shouldBe true
                ticks++
            }
            (ticks > 1) shouldBe true
            removed shouldBe 300
            release.isCompletedExceptionally shouldBe true
            leases.size shouldBe 1 // Keep the uncertain release visible; idempotent close is not a retry.
            queue.close()
            scope.close()
            provisioner.close()
            provisioner.closeCompletion.isCompletedExceptionally shouldBe true
        }
        }
    }
})
