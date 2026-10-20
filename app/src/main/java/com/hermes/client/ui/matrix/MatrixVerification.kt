package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixSasEmoji
import com.hermes.client.data.matrix.MatrixVerificationEvent

/**
 * The verification sheet's pure state. Idle → (send request | see incoming) → accepted →
 * SAS started → emojis → verified. Cancelled and Failed can happen at any point after a
 * request exists; the flow is one-shot per sheet open (restart the app flow from Idle).
 */
sealed interface VerificationUi {
    /** No flow yet: the sheet shows its entry buttons. */
    data object Idle : VerificationUi

    /** An incoming request waits for an answer; [deviceName] may be blank (unnamed device). */
    data class Incoming(val deviceName: String) : VerificationUi

    /** A request is out, or was accepted, and the peer has not confirmed the SAS start yet. */
    data object WaitingAccept : VerificationUi

    /** The SAS handshake is running; the emoji prompt is next. */
    data object WaitingEmojis : VerificationUi

    /** The short codes arrived. Both sides show the same list. */
    data class Emojis(val emojis: List<MatrixSasEmoji>) : VerificationUi

    /** The short codes matched on both sides; the device (or user's devices) is trusted. */
    data object Verified : VerificationUi

    /** The flow ended with a cancellation; [cancelCode] is opaque protocol text. */
    data class Cancelled(val byUs: Boolean, val cancelCode: String?) : VerificationUi

    /** The flow failed (non-semantic network or protocol error). */
    data object Failed : VerificationUi
}

/**
 * Pure reducer: folds one [MatrixVerificationEvent] into [current]. Once the flow has settled
 * (verified, cancelled or failed) later events are ignored — starting over resets the state
 * explicitly, so a stale emoji list can never overwrite an outcome.
 */
fun verificationUiOnEvent(current: VerificationUi, event: MatrixVerificationEvent): VerificationUi {
    if (current is VerificationUi.Verified || current is VerificationUi.Cancelled || current is VerificationUi.Failed) {
        return current
    }
    return when (event) {
        is MatrixVerificationEvent.Incoming ->
            if (current is VerificationUi.WaitingAccept || current is VerificationUi.WaitingEmojis) current
            else VerificationUi.Incoming(event.deviceName)

        is MatrixVerificationEvent.Accepted -> VerificationUi.WaitingAccept
        is MatrixVerificationEvent.SasStarted -> VerificationUi.WaitingEmojis
        is MatrixVerificationEvent.Emojis -> VerificationUi.Emojis(event.emojis)
        is MatrixVerificationEvent.Finished -> VerificationUi.Verified
        is MatrixVerificationEvent.Cancelled -> VerificationUi.Cancelled(event.cancelledByUs, event.cancelCode)
        is MatrixVerificationEvent.Failed -> VerificationUi.Failed
    }
}

/** The sheet's headline for [state]; short, content-free. */
fun verificationHeadline(state: VerificationUi): String = when (state) {
    VerificationUi.Idle -> "Verify a device"
    is VerificationUi.Incoming -> "Verification request"
    VerificationUi.WaitingAccept -> "Waiting for the other side…"
    VerificationUi.WaitingEmojis -> "Comparing codes…"
    is VerificationUi.Emojis -> "Do these match on the other device?"
    VerificationUi.Verified -> "Verified"
    is VerificationUi.Cancelled -> if (state.byUs) "You cancelled" else "They cancelled"
    VerificationUi.Failed -> "Verification failed"
}
