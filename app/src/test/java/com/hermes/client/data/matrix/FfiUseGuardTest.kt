package com.hermes.client.data.matrix

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfiUseGuardTest {

    @Test
    fun use_after_close_returns_fallback_without_running_block() = runBlocking {
        val guard = FfiUseGuard {}
        guard.closeAndAwait()
        var ran = false
        assertEquals("closed", guard.use("closed") { ran = true; "open" })
        assertFalse(ran)
    }

    @Test
    fun destroy_waits_for_an_in_flight_use() = runBlocking {
        val destroys = AtomicInteger()
        val guard = FfiUseGuard { destroys.incrementAndGet() }
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val use = async(kotlinx.coroutines.Dispatchers.Default) {
            guard.use(0) { started.complete(Unit); release.await(); 1 }
        }
        started.await()
        val closing = async(kotlinx.coroutines.Dispatchers.Default) { guard.closeAndAwait() }
        delay(100)
        assertEquals(0, destroys.get())
        // New uses are already refused while the old one is still running.
        assertEquals(-1, guard.use(-1) { 2 })
        release.complete(Unit)
        assertEquals(1, use.await())
        closing.await()
        assertEquals(1, destroys.get())
    }

    @Test
    fun registry_awaits_every_child_and_refuses_late_registration() = runBlocking {
        class Child : AsyncCloseable {
            val done = CompletableDeferred<Unit>()
            var closed = false
            override fun close() { closed = true }
            override suspend fun awaitClosed() = done.await()
        }
        val registry = ClosableRegistry<Child>()
        val a = Child()
        val b = Child()
        assertTrue(registry.register(a))
        assertTrue(registry.register(b))
        registry.unregister(b)
        var finished = false
        val closing = async(kotlinx.coroutines.Dispatchers.Default) { registry.closeAll(); finished = true }
        delay(100)
        assertTrue(a.closed)
        assertFalse(b.closed)
        assertFalse("closeAll must wait for the child's teardown", finished)
        assertFalse(registry.register(Child()))
        a.done.complete(Unit)
        closing.await()
        assertTrue(finished)
    }

    /** Many threads race "use" calls against several closers; destroy must run once, after all uses. */
    @Test
    fun stress_destroy_once_after_all_uses_and_none_after() {
        val pool = Executors.newFixedThreadPool(16)
        try {
            repeat(300) { round ->
                val inUse = AtomicInteger()
                val destroys = AtomicInteger()
                val destroyed = AtomicBoolean(false)
                val violations = AtomicInteger()
                val admitted = AtomicInteger()
                val guard = FfiUseGuard {
                    if (inUse.get() != 0) violations.incrementAndGet()
                    destroyed.set(true)
                    destroys.incrementAndGet()
                }
                val start = CountDownLatch(1)
                val done = CountDownLatch(14)
                repeat(12) {
                    pool.execute {
                        start.await()
                        runBlocking {
                            repeat(50) {
                                guard.use(Unit) {
                                    admitted.incrementAndGet()
                                    inUse.incrementAndGet()
                                    if (destroyed.get()) violations.incrementAndGet()
                                    yield()
                                    if (destroyed.get()) violations.incrementAndGet()
                                    inUse.decrementAndGet()
                                }
                            }
                        }
                        done.countDown()
                    }
                }
                repeat(2) {
                    pool.execute {
                        start.await()
                        runBlocking {
                            if (round % 2 == 0) yield()
                            guard.closeAndAwait()
                            // Every closer returns only after the destroy has completed.
                            if (!destroyed.get()) violations.incrementAndGet()
                        }
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue("round $round timed out", done.await(30, TimeUnit.SECONDS))
                assertEquals("round $round violations", 0, violations.get())
                assertEquals("round $round destroys", 1, destroys.get())
                assertEquals(0, inUse.get())
                // No use is admitted after destroy.
                assertEquals(-1, runBlocking { guard.use(-1) { 1 } })
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
