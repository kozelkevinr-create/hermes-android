package com.hermes.client.data.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadReceiptsTest {
    // Remote event id shapes; placeholders, not real user data.
    private val evt1 = "\$abc-1"
    private val evt2 = "\$abc-2"

    @Test
    fun settled_on_a_new_remote_event_sends_a_receipt() {
        val tracker = ReadReceiptTracker()
        val signal = tracker.observe(evt1, isScrollInProgress = false)
        assertTrue(signal is ReadReceiptSignal.Read)
        assertEquals(evt1, (signal as ReadReceiptSignal.Read).eventId)
    }

    @Test
    fun scrolling_never_sends() {
        val tracker = ReadReceiptTracker()
        assertEquals(ReadReceiptSignal.Idle, tracker.observe(evt1, isScrollInProgress = true))
        // The skipped event still becomes "last seen" once the user settles on it.
        assertTrue(tracker.observe(evt1, isScrollInProgress = false) is ReadReceiptSignal.Read)
    }

    @Test
    fun settling_on_the_same_event_does_not_repeat() {
        val tracker = ReadReceiptTracker()
        tracker.observe(evt1, isScrollInProgress = false)
        assertEquals(ReadReceiptSignal.Idle, tracker.observe(evt1, isScrollInProgress = false))
    }

    @Test
    fun a_new_event_sends_again() {
        val tracker = ReadReceiptTracker()
        tracker.observe(evt1, isScrollInProgress = false)
        assertTrue(tracker.observe(evt2, isScrollInProgress = false) is ReadReceiptSignal.Read)
    }

    @Test
    fun nothing_visible_and_not_ready_are_idle() {
        val tracker = ReadReceiptTracker()
        assertEquals(ReadReceiptSignal.Idle, tracker.observe(null, isScrollInProgress = false))
    }

    @Test
    fun reset_allows_the_same_event_to_be_re_sent() {
        val tracker = ReadReceiptTracker()
        tracker.observe(evt1, isScrollInProgress = false)
        tracker.reset()
        assertTrue(tracker.observe(evt1, isScrollInProgress = false) is ReadReceiptSignal.Read)
    }
}
