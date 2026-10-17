package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation
import java.util.concurrent.CompletableFuture

class ArcEventsPluginGenerationTest : FunSpec({
    test("re-enable waits for the exact previous durable drain and creates a fresh drain") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadSimplePlugin(ControlledArcEventsPlugin::class.java)
                paper.performTicks(2)
                plugin.starts shouldBe 1
                val first = CompletableFuture<Unit>()
                plugin.nextDrain = first
                paper.server.pluginManager.disablePlugin(plugin)
                // Idempotent duplicate disable must not replace the pending predecessor.
                plugin.onDisable()
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(2)
                plugin.starts shouldBe 1
                first.complete(Unit)
                paper.performTicks(2)
                plugin.starts shouldBe 2
                val second = CompletableFuture<Unit>()
                plugin.nextDrain = second
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(2)
                plugin.starts shouldBe 2
                second.complete(Unit)
                paper.performTicks(2)
                plugin.starts shouldBe 3
                plugin.nextDrain = CompletableFuture.completedFuture(Unit)
            }
        }
    }

    test("a superseded waiting generation cannot activate after its predecessor drains") {
        failOnUnsupportedMockBukkitOperation {
            MockBukkitTestRuntime.open().use { paper ->
                val plugin = paper.loadSimplePlugin(ControlledArcEventsPlugin::class.java)
                paper.performTicks(2)
                val old = CompletableFuture<Unit>()
                plugin.nextDrain = old
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                old.complete(Unit)
                paper.performTicks(3)
                plugin.starts shouldBe 2
                plugin.closes shouldBe 1
                plugin.nextDrain = CompletableFuture.completedFuture(Unit)
            }
        }
    }
})

open class ControlledArcEventsPlugin : ArcEventsPlugin() {
    var starts = 0
    var closes = 0
    var nextDrain = CompletableFuture.completedFuture(Unit)
    override fun startGeneration() { starts++ }
    override fun closeGeneration(): CompletableFuture<Unit> { closes++; return nextDrain }
}
