package com.hermes.client.data.matrix

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        q.send(listOf(1, 2))
        q.send(listOf(3))

        assertEquals(listOf(1, 2), q.batches.first())
    }
}
