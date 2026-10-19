package com.hermes.client.data.matrix

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClosableDiffQueueTest {

    @Test
    fun send_after_close_discards_the_batch() {
        val discarded = mutableListOf<Int>()
        val q = ClosableDiffQueue<Int> { discarded += it }

        q.close()

        assertFalse(q.send(listOf(1, 2, 3)))
        assertEquals(listOf(1, 2, 3), discarded)
    }

    @Test
    fun close_drains_queued_batches_exactly_once() {
        val discarded = mutableListOf<Int>()
        val q = ClosableDiffQueue<Int> { discarded += it }
        assertTrue(q.send(listOf(1, 2)))
        assertTrue(q.send(listOf(3)))

        q.close()

        assertEquals(listOf(1, 2, 3), discarded)
    }

    @Test
    fun send_before_close_keeps_ownership_with_the_collector() {
        val discarded = mutableListOf<Int>()
        val q = ClosableDiffQueue<Int> { discarded += it }

        assertTrue(q.send(listOf(1)))

        assertEquals(emptyList<Int>(), discarded)
    }

    @Test
    fun close_is_idempotent() {
        val discarded = mutableListOf<Int>()
        val q = ClosableDiffQueue<Int> { discarded += it }
        q.send(listOf(1))

        q.close()
        q.close()

        assertEquals(listOf(1), discarded)
    }

    @Test
    fun batches_emit_queued_items_in_order() = runTest {
        val q = ClosableDiffQueue<Int> {}
        val received = mutableListOf<List<Int>>()
        val job = launch { q.batches.collect { received += it } }
        q.send(listOf(1, 2))
        q.send(listOf(3))

        runCurrent()

        assertEquals(listOf(listOf(1, 2), listOf(3)), received)
        q.close()
        job.cancel()
    }

    @Test
    fun concurrent_sends_racing_close_never_lose_or_duplicate_an_item() {
        repeat(ITERATIONS) {
            val deliveredCount = AtomicInteger()
            val discardedCount = AtomicInteger()
            val delivered = ConcurrentHashMap.newKeySet<Int>()
            val discarded = ConcurrentHashMap.newKeySet<Int>()
            val q = ClosableDiffQueue<Int> { item ->
                discarded.add(item)
                discardedCount.incrementAndGet()
            }

            val start = CountDownLatch(1)
            val consumer = Thread {
                runBlocking {
                    q.batches.collect { batch ->
                        batch.forEach { item ->
                            delivered.add(item)
                            deliveredCount.incrementAndGet()
                        }
                    }
                }
            }
            val senders = (0 until SENDER_THREADS).map { t ->
                Thread {
                    start.await()
                    for (i in 0 until SENDS_PER_THREAD) {
                        q.send(listOf(t * SENDS_PER_THREAD + i))
                    }
                }
            }
            val closer = Thread {
                start.await()
                q.close()
            }

            consumer.start()
            (senders + closer).forEach { it.start() }
            // Give the consumer a moment to subscribe so the delivery path is exercised; the
            // invariant below holds regardless of how this races.
            Thread.sleep(20)
            start.countDown()
            (senders + closer).forEach { it.join() }
            consumer.join()

            val total = SENDER_THREADS * SENDS_PER_THREAD
            assertEquals("counters must agree with the sets", delivered.size.toLong(), deliveredCount.get().toLong())
            assertEquals("counters must agree with the sets", discarded.size.toLong(), discardedCount.get().toLong())
            assertEquals(
                "every item must be delivered or discarded exactly once",
                total,
                deliveredCount.get() + discardedCount.get(),
            )
            // No item is both delivered and discarded, and none falls through the cracks.
            val union = HashSet<Int>(delivered).apply { addAll(discarded) }
            assertEquals("no item may be both nor neither", total, union.size)
        }
    }

    companion object {
        private const val SENDER_THREADS = 8
        private const val SENDS_PER_THREAD = 10_000
        private const val ITERATIONS = 3
    }
}
