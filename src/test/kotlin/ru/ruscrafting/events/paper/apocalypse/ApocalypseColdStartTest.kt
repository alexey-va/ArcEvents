package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.InMemoryRedis
import ru.ruscrafting.events.config.ArcEventsConfig
import ru.ruscrafting.events.config.ArcEventsLocale
import ru.ruscrafting.events.config.NetworkSettings
import ru.ruscrafting.events.config.NodeMode
import ru.ruscrafting.events.domain.apocalypse.ApocalypseLayout
import ru.ruscrafting.events.domain.apocalypse.ApocalypseWorldLayout
import ru.ruscrafting.events.paper.EventWork
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture

class ApocalypseColdStartTest : FunSpec({
    test("a cold host protects only expedition inventories and permits an exact origin return before the source exists") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ColdExpedition")
            paper.addSimpleWorld("world")
            PaperArcRuntime.installScheduling(plugin)
            val work = ColdStartWork()
            try {
                val ordinary = paper.addPlayer("ArcadePlayer")
                val survivor = paper.addPlayer("Survivor")
                val orphan = paper.addPlayer("Orphan")
                val root = plugin.dataFolder.toPath()
                val journal = ApocalypseStorage(root)
                val marker = NamespacedKey(plugin, "apocalypse_session")
                val session = UUID.randomUUID().toString()
                survivor.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 3))
                val origin = PaperPlayerStateService().captureEnvelope(survivor, System.currentTimeMillis())
                journal.save(ApocalypsePlayerRecord(survivor.uniqueId.toString(), "parkour", ApocalypsePlayerPhase.ACTIVE,
                    origin, null, nativeSession = session))
                survivor.inventory.setItem(0, ItemStack.of(Material.IRON_AXE))
                survivor.persistentDataContainer.set(marker, PersistentDataType.STRING, session)
                orphan.persistentDataContainer.set(marker, PersistentDataType.STRING, UUID.randomUUID().toString())
                val source = CompletableFuture<ApocalypseLayout>()
                val settings = coldSettings(NodeMode.HOST)
                val redis = InMemoryRedis()
                ApocalypseRuntime(plugin, { settings }, coldLocale(), mockk(), work, redis, mockk(),
                    occupied = { false }, queued = { error("Cold entry reached the queue") },
                    returnToOrigin = { _, _ -> error("Unexpected transfer") }, sourceLayout = source, chunkTickets = mockk()).use { runtime ->
                    runtime.start()
                    runtime.isBusy(survivor.uniqueId) shouldBe true
                    runtime.isBusy(orphan.uniqueId) shouldBe true
                    runtime.isBusy(ordinary.uniqueId) shouldBe false
                    paper.advanceUntil("host join reconciliation") {
                        runtime.allowsRecoveryCommand(survivor, "/arcevents apocalypse recover")
                    }
                    runtime.ready shouldBe false
                    runtime.command(ordinary, listOf("fishing")) shouldBe false
                    runtime.enter(ordinary)
                    redis.loadMapEntries("arc:events:apocalypse:host:v1", "parkour").join().single() shouldBe "0"
                    runtime.isPlaying(survivor.uniqueId) shouldBe false
                    runtime.isBusy(survivor.uniqueId) shouldBe true
                    survivor.inventory.getItem(0)?.type shouldBe Material.IRON_AXE
                    runtime.command(survivor, listOf("apocalypse", "recover")) shouldBe true
                    paper.advanceUntil("acknowledged origin restoration") { !runtime.isBusy(survivor.uniqueId) }
                    survivor.inventory.getItem(0) shouldBe ItemStack.of(Material.DIAMOND, 3)
                    val parked = requireNotNull(journal.load(survivor.uniqueId))
                    parked.phase shouldBe ApocalypsePlayerPhase.PARKED
                    parked.origin shouldBe origin
                    PaperPlayerStateCodec().decode(requireNotNull(parked.expedition)).storage[0]?.type shouldBe Material.IRON_AXE
                    runtime.isBusy(survivor.uniqueId) shouldBe false
                    // MockBukkit leaves Server.getWorldContainer unimplemented.
                    mockkObject(paper.server) {
                        every { paper.server.worldContainer } returns root.toFile()
                        source.complete(ApocalypseWorldLayout(68200133L))
                        paper.performTicks(2)
                    }
                    runtime.ready shouldBe false
                    runtime.isBusy(orphan.uniqueId) shouldBe true
                    paper.server.getWorld("arcevents_apocalypse_v2") shouldBe null
                    Files.exists(root.resolve("arcevents_apocalypse_v2")) shouldBe false
                }
                Files.exists(root.resolve("data/apocalypse/world-arcevents_apocalypse_v2/world.json")) shouldBe false
            } finally { work.close(); Tasks.reset() }
        }
    }

    test("a relay acknowledges a returned player and retains an orphaned native inventory without a local expedition") {
        MockBukkitTestRuntime.open().use { paper ->
            val plugin = paper.createSimplePlugin("ColdRelay")
            paper.addSimpleWorld("world")
            PaperArcRuntime.installScheduling(plugin)
            val work = ColdStartWork()
            try {
                val ordinary = paper.addPlayer("Returned")
                val orphan = paper.addPlayer("Orphan")
                orphan.persistentDataContainer.set(NamespacedKey(plugin, "apocalypse_session"), PersistentDataType.STRING, UUID.randomUUID().toString())
                val redis = InMemoryRedis()
                val hostSettings = coldSettings(NodeMode.HOST)
                val host = ApocalypseNetwork(redis, { hostSettings }, mockk(), work)
                val nonce = host.prepareReturn(ordinary.uniqueId, "spawn").join()
                val settings = coldSettings(NodeMode.RELAY)
                ApocalypseRuntime(plugin, { settings }, coldLocale(), mockk(), work, redis, mockk(),
                    occupied = { false }, queued = mockk(), returnToOrigin = { _, _ -> false }, chunkTickets = mockk()).use { runtime ->
                    runtime.start()
                    paper.advanceUntil("the origin acknowledges the returned player") {
                        !host.returnPending(ordinary.uniqueId, "spawn", nonce).join()
                    }
                    runtime.ready shouldBe true
                    runtime.isBusy(ordinary.uniqueId) shouldBe false
                    host.returnPending(ordinary.uniqueId, "spawn", nonce).join() shouldBe false
                    runtime.command(orphan, listOf("apocalypse", "recover")) shouldBe true
                    runtime.isBusy(orphan.uniqueId) shouldBe true
                }
                Files.exists(plugin.dataFolder.toPath().resolve("data/apocalypse")) shouldBe false
            } finally { work.close(); Tasks.reset() }
        }
    }
})

private fun coldSettings(mode: NodeMode) = mockk<ArcEventsConfig> {
    every { nodeMode } returns mode
    every { serverId } returns if (mode == NodeMode.HOST) "parkour" else "spawn"
    every { hostServer } returns "parkour"
    every { network } returns NetworkSettings(true, setOf("parkour", "spawn"), 300, 45, 5, 20, true, true)
}

private fun coldLocale() = mockk<ArcEventsLocale> {
    every { render(any(), any(), any()) } answers { Component.text(firstArg<String>()) }
}

private class ColdStartWork : EventWork, AutoCloseable {
    private val main = LifecycleTaskScope()
    override fun <T> onMain(action: () -> T) = main.callSync(action)
    override fun <T> compute(action: () -> T): CompletableFuture<T> =
        runCatching(action).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> = error("Unexpected native capture")
    override fun isCurrent() = true
    override fun close() = main.close()
}

private fun MockBukkitTestRuntime.advanceUntil(operation: String, done: () -> Boolean) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!done()) {
        check(System.nanoTime() < deadline) { "Timed out waiting for $operation" }
        performTicks(1)
        Thread.sleep(1)
    }
}
