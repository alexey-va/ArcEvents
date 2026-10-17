package ru.ruscrafting.events.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.inventory.ItemStack
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.LifecycleTaskScope
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec
import ru.arc.paper.playerstate.PaperPlayerStateCodec
import ru.arc.paper.playerstate.PaperPlayerStateService
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

class PlayerStateEscrowAsyncTest : FunSpec({
    test("capture and restore use bounded Paper steps while journal work and exact batch acknowledgements run on worker") {
        EscrowHarness().use { h ->
            val players = (1..4).map { index -> h.paper.server.addPlayer("Escrow$index").also {
                it.inventory.setItem(0, ItemStack.of(Material.DIAMOND, index))
                it.foodLevel = 10 + index
            } }
            val id = UUID.randomUUID()
            val gate = CompletableFuture<Unit>()
            h.computeGate = gate
            val admitted = h.escrow.commitThenMutateStagesAsync(id, players, players.associate { it.uniqueId to "spawn" }, 1_787_730_000_000) {
                val iterator = players.iterator()
                h.work.capture({ true }) {
                    iterator.next().inventory.clear()
                    !iterator.hasNext()
                }
            }
            repeat(8) { h.tick() }
            admitted.isDone shouldBe false
            players.forEach { player ->
                h.escrow.isLocked(player.uniqueId) shouldBe true
                player.inventory.getItem(0)?.type shouldBe Material.DIAMOND
            }
            val player = players.first()
            val drop = PlayerDropItemEvent(player, player.world.dropItem(player.location, ItemStack.of(Material.DIRT)))
            EventStateProtection { h.escrow.isLocked(it.uniqueId) }.drop(drop)
            drop.isCancelled shouldBe true
            h.computeGate = null
            gate.complete(Unit)
            h.until { admitted.isDone }
            admitted.join().committed.states.size shouldBe 4
            h.escrow.pendingCount(id) shouldBe 4
            players.forEach { it.inventory.isEmpty shouldBe true }
            val restores = players.map(h.escrow::recoverAsync)
            h.until { restores.all { it.isDone } }
            restores.forEach { it.join() shouldBe PlayerRecovery(id, "spawn") }
            players.forEachIndexed { index, p ->
                p.inventory.getItem(0) shouldBe ItemStack.of(Material.DIAMOND, index + 1)
                p.foodLevel shouldBe 11 + index
            }
            h.persisted.get() shouldBe 4
            h.escrow.pendingCount(id) shouldBe 0
            h.maxNativeSteps shouldBe 1
            (h.nativeTicks > 4) shouldBe true
            (h.workerCalls.get() > 4) shouldBe true
        }
    }

    test("superseded admission retains its durable recovery but never applies delayed native mutation") {
        EscrowHarness().use { h ->
            val player = h.paper.server.addPlayer("Superseded")
            player.inventory.setItem(0, ItemStack.of(Material.EMERALD, 5))
            val gate = CompletableFuture<Unit>()
            h.computeGate = gate
            var current = true
            var mutations = 0
            val id = UUID.randomUUID()
            val result = h.escrow.commitThenMutateAsync(id, listOf(player), mapOf(player.uniqueId to "spawn"), 1_787_730_000_000,
                isCurrent = { current }) { mutations++; player.inventory.clear() }
            repeat(4) { h.tick() }
            current = false
            h.computeGate = null
            gate.complete(Unit)
            h.until { result.isDone }
            result.isCompletedExceptionally shouldBe true
            mutations shouldBe 0
            player.inventory.getItem(0) shouldBe ItemStack.of(Material.EMERALD, 5)
            h.escrow.pendingCount(id) shouldBe 1
        }
    }

    test("valid outer envelope with malformed native item data is never acknowledged") {
        EscrowHarness().use { h ->
            val player = h.paper.server.addPlayer("OpaqueItem")
            player.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 2))
            val id = UUID.randomUUID()
            val commit = h.escrow.commitThenMutateAsync(id, listOf(player), mapOf(player.uniqueId to "spawn"), 1_787_730_000_000) { player.inventory.clear() }
            h.until { commit.isDone }
            commit.join()
            h.rejectNativeDecode = true
            val restore = h.escrow.recoverAsync(player)
            h.until { restore.isDone }
            restore.isCompletedExceptionally shouldBe true
            h.escrow.pendingCount(id) shouldBe 1
            h.persisted.get() shouldBe 0
            player.inventory.isEmpty shouldBe true
        }
    }
})

private class EscrowHarness : AutoCloseable {
    val paper = MockBukkitTestRuntime.open()
    private val nativeThread = Thread.currentThread()
    private val plugin = paper.createSimplePlugin("EscrowAsync")
    private val root = Files.createTempDirectory("event-escrow-async")
    private val io = BoundedAsyncExecutor("event-escrow-test")
    private val scope = LifecycleTaskScope(BukkitTaskScheduler(plugin))
    private val token = scope.token()
    private val queue = BudgetedTaskQueue(scope, maximumSteps = 1)
    val workerCalls = AtomicInteger()
    val persisted = AtomicInteger()
    var computeGate: CompletableFuture<Unit>? = null
    var rejectNativeDecode = false
    private var nativeSteps = 0
    var maxNativeSteps = 0
    var nativeTicks = 0
    val work = object : EventWork {
        override fun <T> compute(action: () -> T): CompletableFuture<T> {
            val gate = computeGate
            return io.submit {
                check(Thread.currentThread() !== nativeThread)
                gate?.join()
                workerCalls.incrementAndGet()
                action()
            }
        }
        override fun <T> onMain(action: () -> T) = scope.callSync(token) {
            check(Thread.currentThread() === nativeThread)
            action()
        }
        override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> {
            check(Thread.currentThread() === nativeThread)
            return queue.submit(isCurrent) {
                nativeSteps++
                step()
            }
        }
        override fun isCurrent() = scope.isCurrent(token)
    }
    private val codec = PaperPlayerStateCodec(object : PaperItemStackBinaryCodec by NativePaperItemStackBinaryCodec {
        override fun encodeItems(items: List<ItemStack?>): ByteArray {
            check(Thread.currentThread() === nativeThread)
            return NativePaperItemStackBinaryCodec.encodeItems(items)
        }
        override fun decodeItems(payload: ByteArray): List<ItemStack?> {
            check(Thread.currentThread() === nativeThread)
            check(!rejectNativeDecode) { "Malformed native item blob" }
            return NativePaperItemStackBinaryCodec.decodeItems(payload)
        }
    })
    private val store = RecoveryBatchStore(root, stateCodec = codec).also { io.submit { it.prepare() }.join() }
    val escrow = PlayerStateEscrow(store, PaperPlayerStateService(codec = codec,
        primaryThread = { Thread.currentThread() === nativeThread }, persistPlayerData = { persisted.incrementAndGet() }),
        work = work, stateCodec = codec)

    fun tick() {
        nativeSteps = 0
        paper.performTicks(1)
        maxNativeSteps = maxOf(maxNativeSteps, nativeSteps)
        if (nativeSteps > 0) nativeTicks++
        Thread.sleep(2)
    }

    fun until(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "Escrow did not finish" }
            tick()
        }
    }

    override fun close() {
        computeGate?.complete(Unit)
        escrow.close()
        queue.close()
        scope.close()
        io.closeAsync().join()
        paper.close()
        root.toFile().deleteRecursively()
    }
}
