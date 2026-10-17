package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class ArcEventsPluginGenerationTest : FunSpec({
    test("startup is direct and completed cleanup allows immediate re-enable") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ControlledArcEventsPlugin::class.java)
            plugin.starts shouldBe 1
            paper.server.pluginManager.disablePlugin(plugin)
            plugin.onDisable()
            plugin.closes shouldBe 1
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 2
        }
    }

    test("pending native cleanup rejects re-enable without joining or scheduling a replacement") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ControlledArcEventsPlugin::class.java)
            val nativeTail = CompletableFuture<Unit>()
            plugin.nextDrain = nativeTail
            paper.server.pluginManager.disablePlugin(plugin)
            nativeTail.isDone shouldBe false
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 1
            plugin.closes shouldBe 1
            nativeTail.complete(Unit)
            paper.performTicks(2)
            plugin.starts shouldBe 1
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 2
            plugin.nextDrain = CompletableFuture.completedFuture(Unit)
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
