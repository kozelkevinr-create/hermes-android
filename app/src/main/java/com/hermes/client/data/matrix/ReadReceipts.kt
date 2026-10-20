package com.hermes.client.data.matrix

/** What one viewport observation decided to do about read receipts. */
sealed interface ReadReceiptSignal {
    /** Nothing to send (or the same event and sending would be redundant). */
    data object Idle : ReadReceiptSignal

    /** Send a read receipt for this remote event id. */
    data class Read(val eventId: String) : ReadReceiptSignal
}

/**
 * Decides when a read receipt should be sent from the viewport's newest fully visible event.
 * Pure state machine (no I/O): a receipt is wanted only when the user is settled (not mid-scroll)
 * on a *different* remote event than the last one sent, so scrolling around never spams receipts
 * and a live update moving the newest message sends exactly one.
 */
class ReadReceiptTracker {
    private var lastSent: String? = null

    fun observe(
        /** Newest remote event id fully visible, or null when none applies / not ready. */
        newestVisibleEventId: String?,
        isScrollInProgress: Boolean,
    ): ReadReceiptSignal {
        if (isScrollInProgress) return ReadReceiptSignal.Idle
        val eventId = newestVisibleEventId ?: return ReadReceiptSignal.Idle
        if (eventId == lastSent) return ReadReceiptSignal.Idle
        lastSent = eventId
        return ReadReceiptSignal.Read(eventId)
    }

    /** Forgets what was sent (a new timeline handle needs a fresh marker). */
    fun reset() {
        lastSent = null
    }
}
