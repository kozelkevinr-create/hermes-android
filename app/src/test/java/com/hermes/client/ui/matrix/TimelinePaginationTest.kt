package com.hermes.client.ui.matrix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelinePaginationTest {

    @Test
    fun initial_fill_pages_at_most_max_initial_pages_without_a_gesture() {
        val p = TimelinePagination(maxInitialPages = 3)
        // A short, non-scrollable list stays "near the top" on every layout frame with no gesture.
        assertTrue(p.shouldLoadMore(total = 1, lastVisibleIndex = 0, isScrollInProgress = false, atStart = false))
        assertTrue(p.shouldLoadMore(total = 1, lastVisibleIndex = 0, isScrollInProgress = false, atStart = false))
        assertTrue(p.shouldLoadMore(total = 1, lastVisibleIndex = 0, isScrollInProgress = false, atStart = false))
        // The fourth layout-driven call is refused: no unbounded loop.
        assertFalse(p.shouldLoadMore(total = 1, lastVisibleIndex = 0, isScrollInProgress = false, atStart = false))
    }

    @Test
    fun no_gesture_and_not_near_top_does_not_page() {
        val p = TimelinePagination()
        assertFalse(p.shouldLoadMore(total = 100, lastVisibleIndex = 10, isScrollInProgress = false, atStart = false))
    }

    @Test
    fun a_user_scroll_near_the_top_pages_and_latches() {
        val p = TimelinePagination(maxInitialPages = 3)
        // Consume the initial fill first.
        p.shouldLoadMore(total = 50, lastVisibleIndex = 49, isScrollInProgress = false, atStart = false)
        // A real gesture near the top pages again.
        assertTrue(p.shouldLoadMore(total = 50, lastVisibleIndex = 49, isScrollInProgress = true, atStart = false))
        // The gesture is latched: further near-top frames keep paging without another gesture.
        assertTrue(p.shouldLoadMore(total = 50, lastVisibleIndex = 49, isScrollInProgress = false, atStart = false))
    }

    @Test
    fun never_pages_once_at_start() {
        val p = TimelinePagination()
        assertFalse(p.shouldLoadMore(total = 50, lastVisibleIndex = 49, isScrollInProgress = true, atStart = true))
    }
}
