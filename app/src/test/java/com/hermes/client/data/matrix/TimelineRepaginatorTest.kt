package com.hermes.client.data.matrix

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineRepaginatorTest {

    @Test
    fun reset_before_first_page_is_ignored() = runTest {
        var pages = 0
        val rep = TimelineRepaginator(this, debounceMs = 100) { pages++; false }
        rep.onBatch(true)
        advanceUntilIdle()
        assertEquals(0, pages)
    }

    @Test
    fun reset_after_first_page_schedules_a_single_page_after_the_debounce() = runTest {
        var pages = 0
        val rep = TimelineRepaginator(this, debounceMs = 100) { pages++; false }
        rep.onFirstPageRequested()
        rep.onBatch(true)
        advanceTimeBy(99)
        assertEquals(0, pages)
        advanceUntilIdle()
        assertEquals(1, pages)
    }

    @Test
    fun debounce_coalesces_many_resets_into_one_page() = runTest {
        var pages = 0
        val rep = TimelineRepaginator(this, debounceMs = 100) { pages++; false }
        rep.onFirstPageRequested()
        rep.onBatch(true)
        rep.onBatch(true)
        rep.onBatch(true)
        advanceUntilIdle()
        assertEquals(1, pages)
    }

    @Test
    fun non_reset_batches_do_not_page() = runTest {
        var pages = 0
        val rep = TimelineRepaginator(this, debounceMs = 100) { pages++; false }
        rep.onFirstPageRequested()
        rep.onBatch(false)
        advanceUntilIdle()
        assertEquals(0, pages)
    }

    @Test
    fun stops_paging_for_good_once_at_start() = runTest {
        var pages = 0
        val rep = TimelineRepaginator(this, debounceMs = 100) { pages++; true }
        rep.onFirstPageRequested()
        rep.onBatch(true)
        advanceUntilIdle()
        assertEquals(1, pages)

        // A later reset must not schedule another page: we already hit the start.
        rep.onBatch(true)
        advanceUntilIdle()
        assertEquals(1, pages)
    }
}
