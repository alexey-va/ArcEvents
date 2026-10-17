package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.entity.Item
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.chunk.PaperChunkTicketAddResult
import ru.arc.paper.chunk.PaperChunkTicketBackend
import ru.arc.paper.chunk.PaperChunkTicketRegistry
import ru.arc.paper.chunk.PaperChunkKey
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import ru.ruscrafting.events.config.ArcEventsConfig
import java.util.UUID
import java.util.function.BooleanSupplier

class ArcEventsServiceCloseTest : FunSpec({
    test("early native detach failure still submits every later owner and the real 300 item cleanup drain") {
        failOnUnsupportedMockBukkitOperation {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ServiceCloseConsumer")
            val hostPlugin = paper.createSimplePlugin("ServiceCloseHost")
            PaperArcRuntime.installScheduling(plugin)
            val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val hostScope = LifecycleTaskScope(BukkitTaskScheduler(hostPlugin))
            val queue = BudgetedTaskQueue(scope)
            val hostQueue = BudgetedTaskQueue(hostScope)
            val io = BoundedAsyncExecutor("service-close-test")
            try {
                val host = object : ArcNativeCleanupService {
                    override fun submit(owner: String, step: BooleanSupplier) =
                        hostQueue.submit({ true }, step::getAsBoolean).thenApply<Void> { null }
                }
                val work = RuntimeEventWork(io, scope, queue, host, "service-close")
                val config = mockk<ArcEventsConfig> { every { ui.lootDisplays } returns false }
                val tickets = PaperChunkTicketRegistry(object : PaperChunkTicketBackend {
                    override fun add(key: PaperChunkKey) = PaperChunkTicketAddResult.ADDED
                    override fun remove(key: PaperChunkKey) = true
                })
                val world = paper.server.addSimpleWorld("service-close")
                world.getChunkAt(0, 0).load()
                val scene = TttLootScene(plugin, mockk(relaxed = true), work, tickets) { config }
                var removals = 0
                repeat(300) {
                    scene.register(mockk<Item>(relaxed = true) {
                        every { uniqueId } returns UUID.randomUUID()
                        every { location } returns Location(world, .5, 64.0, .5)
                        every { isValid } returns true
                        every { remove() } answers { removals++; Unit }
                    })
                }
                val pool = mockk<ArenaPool>(relaxed = true) { every { clear() } throws IllegalStateException("pool detach failed") }
                val hud = mockk<TttHud>(relaxed = true) { every { close() } throws IllegalStateException("HUD detach failed") }
                val spawner = mockk<TttLootSpawner>(relaxed = true)
                val smoke = mockk<TttSmokeGrenades>(relaxed = true)
                val service = ArcEventsService(plugin, { config }, mockk(relaxed = true), mockk(relaxed = true),
                    mockk(relaxed = true), mockk(relaxed = true), hud, scene, smoke, mockk(relaxed = true),
                    mockk(relaxed = true), { true }, pool, mockk(relaxed = true), spawner, work = work)
                service.start()
                val failure = runCatching { service.close() }.exceptionOrNull()
                failure?.message shouldBe "pool detach failed"
                failure?.suppressed?.map { it.message } shouldBe listOf("HUD detach failed")
                verify(exactly = 1) { spawner.close() }
                verify(exactly = 1) { smoke.close() }
                val drain = work.cleanupDrain()
                drain.isDone shouldBe false
                queue.close()
                scope.close()
                var ticks = 0
                while (!drain.isDone && ticks++ < 100) paper.performTicks(1)
                drain.join()
                removals shouldBe 300
                (ticks > 1) shouldBe true
                service.close() // Duplicate close remains idempotent after the failed detach.
            } finally {
                queue.close(); scope.close()
                hostQueue.close(); hostScope.close()
                io.closeAsync().join()
                Tasks.reset()
            }
        }
        }
    }
})
