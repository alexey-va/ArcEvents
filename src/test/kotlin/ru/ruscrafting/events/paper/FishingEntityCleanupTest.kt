package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.BoundingBox
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class FishingEntityCleanupTest : FunSpec({
    test("300 owned entities are removed over host ticks with exact match and old-stage filtering") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("FishingCleanupHost")
            val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val queue = BudgetedTaskQueue(scope, maximumSteps = 128)
            try {
                val ownerKey = NamespacedKey(plugin, "owner")
                val matchKey = NamespacedKey(plugin, "match")
                val stageKey = NamespacedKey(plugin, "stage")
                val match = UUID.randomUUID().toString()
                var removed = 0
                var foreignRemoved = 0
                fun entity(owner: String, id: String, stage: Int, owned: Boolean): Entity {
                    val pdc = mockk<PersistentDataContainer> {
                        every { get(ownerKey, PersistentDataType.STRING) } returns owner
                        every { get(matchKey, PersistentDataType.STRING) } returns id
                        every { get(stageKey, PersistentDataType.INTEGER) } returns stage
                    }
                    return mockk {
                        every { uniqueId } returns UUID.randomUUID()
                        every { persistentDataContainer } returns pdc
                        every { remove() } answers { if (owned) removed++ else foreignRemoved++; Unit }
                    }
                }
                val candidates = List(300) { entity("arcevents-fishing", match, 1, true) } + listOf(
                    entity("foreign", match, 1, false), entity("arcevents-fishing", UUID.randomUUID().toString(), 1, false),
                    entity("arcevents-fishing", match, 2, false),
                )
                var queries = 0
                val world = mockk<World> {
                    every { isChunkLoaded(any(), any()) } answers { firstArg<Int>() == 0 && secondArg<Int>() == 0 }
                    every { getNearbyEntities(any<BoundingBox>()) } answers {
                        val box = firstArg<BoundingBox>()
                        (box.widthX <= 16.0 && box.widthZ <= 16.0) shouldBe true
                        queries++
                        candidates
                    }
                }
                var steps = 0
                val result = cleanupFishingEntities(world, ownerKey, "arcevents-fishing", matchKey, match,
                    stageKey, 1, emptyList()) { step -> queue.submit({ true }) { steps++; step() } }
                result.isDone shouldBe false
                var ticks = 0
                while (!result.isDone && ticks < 100) {
                    steps = 0
                    paper.performTicks(1)
                    (steps <= 128) shouldBe true
                    ticks++
                }
                result.join()
                (ticks > 1) shouldBe true
                queries shouldBe 1
                removed shouldBe 300
                foreignRemoved shouldBe 0
            } finally { queue.close(); scope.close() }
        }
    }

    test("cancelled stale sweep never queries or removes a successor match") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("FishingCancelledCleanup")
            val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
            val queue = BudgetedTaskQueue(scope)
            try {
                val cancelled = AtomicBoolean()
                val world = mockk<World>() // Any native call after cancellation fails the test.
                val key = NamespacedKey(plugin, "owner")
                val result = cleanupFishingEntities(world, key, "arcevents-fishing", key, null, key, null,
                    emptyList(), cancelled::get) { step -> queue.submit({ true }, step) }
                cancelled.set(true)
                paper.performTicks(1)
                result.isCompletedExceptionally shouldBe true
            } finally { queue.close(); scope.close() }
        }
    }
})
