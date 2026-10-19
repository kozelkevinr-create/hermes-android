package com.hermes.client.data.matrix

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Lets any number of callers use a native object concurrently while guaranteeing it is destroyed
 * exactly once, and only after every in-flight use has finished.
 *
 * Every call that touches the object runs inside [use]. [use] counts the call in under [lock] unless
 * the guard is already closing, in which case it returns `whenClosed` without running the block. The
 * teardown calls [closeAndAwait]: it flips `closing` under the same lock (so no use can be admitted
 * afterwards), waits until the in-flight count reaches zero, and only then runs [onDestroy]. Because
 * admission and the closing flag share one lock, a use is either counted before closing (and awaited)
 * or refused — there is no window between a "still open" check and the native call.
 * The lock is never held across the block or [onDestroy].
 */
internal class FfiUseGuard(private val onDestroy: () -> Unit) {
    private val lock = Any()
    private var inFlight = 0
    private var closing = false
    private val drained = CompletableDeferred<Unit>()
    private val destroyed = CompletableDeferred<Unit>()
    private val destroyStarted = AtomicBoolean(false)

    private fun enter(): Boolean = synchronized(lock) {
        if (closing) false else { inFlight++; true }
    }

    private fun exit() {
        val idle = synchronized(lock) { --inFlight == 0 && closing }
        if (idle) drained.complete(Unit)
    }

    /** Runs [block] holding a use permit, or returns [whenClosed] untouched once closing began. */
    suspend fun <T> use(whenClosed: T, block: suspend () -> T): T {
        if (!enter()) return whenClosed
        try {
            // Run the block under a context marker so closeAndAwait() can prove it is not being
            // called from inside this very block (which would deadlock). The marker travels with
            // the coroutine across suspension and thread hops.
            return withContext(InUse) { block() }
        } finally {
            exit()
        }
    }

    /** Refuses new uses right away (non-blocking); [closeAndAwait] finishes the job. */
    fun beginClose() {
        val idle = synchronized(lock) { closing = true; inFlight == 0 }
        if (idle) drained.complete(Unit)
    }

    /**
     * Refuses new uses, waits for in-flight ones, then runs [onDestroy] exactly once. Concurrent and
     * repeated calls all return only after the destroy has completed. Not cancellable, so a cancelled
     * caller can never leave the object half torn down.
     *
     * Must never be called from inside a [use] block: it waits for the in-flight count to reach zero,
     * which only happens when that same block returns — a deadlock. The [InUse] context-marker check
     * fails fast with an [IllegalStateException] in that case, before anything is touched.
     */
    suspend fun closeAndAwait() = withContext(NonCancellable) {
        check(currentCoroutineContext()[InUse] == null) {
            "closeAndAwait() must not be called from inside a use {} block"
        }
        beginClose()
        if (destroyStarted.compareAndSet(false, true)) {
            try {
                drained.await()
                onDestroy()
            } finally {
                destroyed.complete(Unit)
            }
        } else {
            destroyed.await()
        }
    }
}

/** Context marker attached while a [FfiUseGuard.use] block runs, so [FfiUseGuard.closeAndAwait] can detect reentrancy. */
private object InUse : CoroutineContext.Element, CoroutineContext.Key<InUse> {
    override val key: CoroutineContext.Key<*> get() = this
}

/** Something that tears itself down in the background and can report when that has finished. */
internal interface AsyncCloseable {
    /** Starts teardown without blocking. Idempotent. */
    fun close()

    /** Suspends until teardown started by [close] has completely finished. */
    suspend fun awaitClosed()
}

/**
 * Tracks children that must finish their teardown before their owner is destroyed (timeline handles
 * before the client). [closeAll] seals the registry under the lock, so a child registered afterwards
 * is refused and every accepted child is awaited.
 */
internal class ClosableRegistry<T : AsyncCloseable> {
    private val lock = Any()
    private val members = LinkedHashSet<T>()
    private var sealed = false

    /** Returns false (and tracks nothing) once [closeAll] has run; the caller must close [child] itself. */
    fun register(child: T): Boolean = synchronized(lock) {
        if (sealed) false else { members += child; true }
    }

    fun unregister(child: T) {
        synchronized(lock) { members -= child }
    }

    /** Closes every tracked child and suspends until all of their teardowns have finished. */
    suspend fun closeAll() {
        val snapshot = synchronized(lock) { sealed = true; members.toList() }
        snapshot.forEach { it.close() }
        snapshot.forEach { it.awaitClosed() }
    }
}
