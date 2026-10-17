package ru.ruscrafting.events.paper

import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import java.util.concurrent.CompletableFuture

/** One event runtime owns both queues; feature owners submit work through this narrow port. */
interface EventWork {
    fun <T> compute(action: () -> T): CompletableFuture<T>
    fun <T> onMain(action: () -> T): CompletableFuture<T>
    fun <T> callNative(action: () -> T): CompletableFuture<T> = onMain(action)
    fun capture(isCurrent: () -> Boolean, step: () -> Boolean): CompletableFuture<Unit>
    fun isCurrent(): Boolean
    fun cleanupNative(step: () -> Boolean): CompletableFuture<Unit> = CompletableFuture.failedFuture(UnsupportedOperationException("Native cleanup owner is unavailable"))
    fun cleanupDrain(): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
}

internal class RuntimeEventWork(
    private val io: BoundedAsyncExecutor,
    private val scope: LifecycleTaskScope,
    private val native: BudgetedTaskQueue,
    private val cleanup: ru.arc.paper.api.ArcNativeCleanupService,
    private val owner: String,
) : EventWork {
    private val token = scope.token()
    private val cleanups = java.util.concurrent.ConcurrentHashMap.newKeySet<CompletableFuture<Unit>>()
    private val cleanupFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
    private var cleanupSealed = false
    override fun <T> compute(action: () -> T) = io.submit(action)
    override fun <T> onMain(action: () -> T) = scope.callSync(token, action)
    override fun <T> callNative(action: () -> T): CompletableFuture<T> =
        scope.callSync(token) { native.call({ scope.isCurrent(token) }, action) }.thenCompose { it }
    override fun capture(isCurrent: () -> Boolean, step: () -> Boolean) =
        native.submit({ scope.isCurrent(token) && isCurrent() }, step)
    override fun isCurrent() = scope.isCurrent(token)
    override fun cleanupNative(step: () -> Boolean): CompletableFuture<Unit> {
        check(!cleanupSealed) { "Native cleanup generation is sealed" }
        val result = runCatching { cleanup.submit(owner, java.util.function.BooleanSupplier(step)).toCompletableFuture().thenApply { Unit } }
            .getOrElse { CompletableFuture.failedFuture(it) }
        cleanups += result
        result.whenComplete { _, failure ->
            if (failure != null) cleanupFailure.compareAndSet(null, failure)
            cleanups.remove(result)
        }
        return result
    }
    override fun cleanupDrain(): CompletableFuture<Unit> {
        cleanupSealed = true
        return CompletableFuture.allOf(*cleanups.toTypedArray()).thenApply {
            cleanupFailure.get()?.let { throw it }
            Unit
        }
    }
}
