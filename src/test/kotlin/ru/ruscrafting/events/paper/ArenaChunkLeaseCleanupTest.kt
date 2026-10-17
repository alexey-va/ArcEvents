package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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
