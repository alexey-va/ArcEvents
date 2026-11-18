package ru.ruscrafting.events.paper.apocalypse

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.events.paper.EventWork
import java.io.IOException
import java.util.concurrent.CompletableFuture

class ApocalypseCheckpointWritesTest : StringSpec({
    "one snapshot survives uncertain writes and releases native effects once after confirmation" {
        val work = CheckpointTestWork()
        val retries = ArrayDeque<() -> Unit>()
        val health = mutableListOf<Throwable?>()
        val writer = ApocalypseCheckpointWrites(work, retries::addLast, health::add)
        val disk = IOException("Uncertain readback")
        var attempts = 0
        var applied = 0
        val complete = writer.commit { if (++attempts < 3) throw disk }
        complete.thenRun { applied++ }
        work.io.removeFirst()()
        writer.failed shouldBe true
        applied shouldBe 0
        retries.removeFirst()()
        work.io.removeFirst()()
        complete.isDone shouldBe false
        health shouldBe listOf(disk)
        retries.removeFirst()()
        work.io.removeFirst()()
        complete.join() shouldBe Unit
        writer.failed shouldBe false
        applied shouldBe 1
        health shouldBe listOf(disk, null)
    }

    "shutdown fences a scheduled retry and drains a write already submitted to storage" {
        for (betweenRetries in listOf(false, true)) {
            val work = CheckpointTestWork()
            val retries = ArrayDeque<() -> Unit>()
            val writer = ApocalypseCheckpointWrites(work, retries::addLast) {}
            val complete = writer.commit { if (betweenRetries) throw IOException("offline") }
            if (betweenRetries) work.io.removeFirst()()
            val drain = writer.stop()
            complete.isCompletedExceptionally shouldBe true
            drain.isDone shouldBe betweenRetries
            if (betweenRetries) retries.removeFirst()() else work.io.removeFirst()()
            drain.isDone shouldBe true
            work.io.isEmpty() shouldBe true
            retries.isEmpty() shouldBe true
        }
    }
})

private class CheckpointTestWork : EventWork {
    val io = ArrayDeque<() -> Unit>()

    override fun <T> compute(action: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { result ->
        io.addLast { runCatching(action).fold(result::complete, result::completeExceptionally) }
    }
    override fun <T> onMain(action: () -> T): CompletableFuture<T> =
        runCatching(action).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit> = error("No native chunks")
    override fun isCurrent() = true
}
