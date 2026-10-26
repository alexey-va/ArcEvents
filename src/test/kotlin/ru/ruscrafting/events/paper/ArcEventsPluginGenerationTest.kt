package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.concurrent.CompletableFuture

class ArcEventsPluginGenerationTest : FunSpec({
    test("console preparation keeps status available across two drained generations") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.loadSimplePlugin(ControlledArcEventsPlugin::class.java)
            val label = "${plugin.name.lowercase()}:hotload"
            val console = paper.server.consoleSender
            val first = requireNotNull(paper.server.commandMap.getCommand(label))
            val pending = CompletableFuture<Unit>()
            plugin.nextDrain = pending
            first.execute(console, label, arrayOf("prepare")) shouldBe true
            plugin.isEnabled shouldBe true
            plugin.closes shouldBe 1
            pending.isDone shouldBe false
            first.execute(console, label, arrayOf("prepare")) shouldBe true
            plugin.closes shouldBe 1
            paper.server.commandMap.getCommand(label) shouldBe first
            pending.complete(Unit)
            first.execute(console, label, arrayOf("status")) shouldBe true
            paper.server.pluginManager.disablePlugin(plugin)
            paper.server.commandMap.getCommand(label) shouldBe null
            plugin.closes shouldBe 1
            plugin.nextDrain = CompletableFuture.completedFuture(Unit)
            paper.server.pluginManager.enablePlugin(plugin)
            plugin.starts shouldBe 2
            val second = requireNotNull(paper.server.commandMap.getCommand(label))
            (second === first) shouldBe false
            second.execute(console, label, arrayOf("prepare")) shouldBe true
            plugin.closes shouldBe 2
            paper.server.pluginManager.disablePlugin(plugin)
            plugin.closes shouldBe 2
            paper.server.commandMap.getCommand(label) shouldBe null
        }
    }

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
