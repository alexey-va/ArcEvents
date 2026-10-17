package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.Item
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.chunk.*
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.events.config.ArcEventsConfig
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.function.BooleanSupplier

class TttLootCleanupAsyncTest : FunSpec({
    listOf(false, true).forEach { shutdown ->
        listOf(false, true).forEach { failOne ->
            test("300 item cleanup ${if (shutdown) "finishes synchronously at stop" else "survives consumer shutdown"} and ${if (failOne) "reports failure after every release" else "completes only after every release"}") {
                val paper = MockBukkitTestRuntime.open()
                val hostPlugin = paper.createSimplePlugin("LootCleanupHost")
                val consumerPlugin = paper.createSimplePlugin("LootCleanupConsumer")
                val hostScope = LifecycleTaskScope(BukkitTaskScheduler(hostPlugin))
                val consumerScope = LifecycleTaskScope(BukkitTaskScheduler(consumerPlugin))
                val hostQueue = BudgetedTaskQueue(hostScope, maximumSteps = 128)
                val consumerQueue = BudgetedTaskQueue(consumerScope)
                val io = BoundedAsyncExecutor("loot-cleanup-test")
                try {
                    val nativeThread = Thread.currentThread()
                    var steps = 0
                    val host = object : ArcNativeCleanupService {
                        override fun submit(owner: String, step: BooleanSupplier) = hostQueue.submit({ true }) {
                            check(Thread.currentThread() === nativeThread)
                            steps++
                            step.asBoolean
                        }.thenApply<Void> { null }
                    }
                    val work = RuntimeEventWork(io, consumerScope, consumerQueue, host, "loot-test")
                    var releases = 0
                    val tickets = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
                        override fun add(key: PaperChunkKey) = PaperChunkTicketAddResult.ADDED
                        override fun remove(key: PaperChunkKey): Boolean { releases++; return true }
                    })
                    val world = paper.server.addSimpleWorld("loot-cleanup")
                    world.getChunkAt(0, 0).load()
                    var removed = 0
                    val config = mockk<ArcEventsConfig> { every { ui.lootDisplays } returns false }
                    val scene = TttLootScene(consumerPlugin, mockk(relaxed = true), work, tickets) { config }
                    repeat(300) { index ->
                        val item = mockk<Item>(relaxed = true) {
                            every { uniqueId } returns UUID.randomUUID()
                            every { location } returns Location(world, .5, 64.0, .5)
                            every { isValid } returns true
                            every { remove() } answers {
                                check(Thread.currentThread() === nativeThread)
                                removed++
                                if (failOne && index == 17) error("native item cleanup failure")
                            }
                        }
                        scene.register(item)
                    }
                    scene.size shouldBe 300
                    if (shutdown) work.beginShutdown()
                    val clear = scene.clearAsync()
                    val drain = work.cleanupDrain()
                    scene.size shouldBe 0
                    clear.isDone shouldBe shutdown
                    drain.isDone shouldBe shutdown
                    consumerQueue.close()
                    consumerScope.close()
                    var ticks = 0
                    while (!drain.isDone && ticks < 100) {
                        steps = 0
                        paper.performTicks(1)
                        (steps <= 128) shouldBe true
                        ticks++
                    }
                    if (shutdown) { ticks shouldBe 0; steps shouldBe 0 }
                    else (ticks > 1) shouldBe true
                    removed shouldBe 300
                    releases shouldBe 1
                    clear.isCompletedExceptionally shouldBe failOne
                    drain.isCompletedExceptionally shouldBe failOne
                    clear.isDone shouldBe true
                    drain.isDone shouldBe true
                } finally {
                    consumerQueue.close(); consumerScope.close()
                    hostQueue.close(); hostScope.close()
                    io.closeAsync().join()
                    paper.close()
                }
            }
        }
    }
})
