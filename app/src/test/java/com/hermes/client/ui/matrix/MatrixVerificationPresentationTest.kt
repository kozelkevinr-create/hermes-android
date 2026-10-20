package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixSasEmoji
import com.hermes.client.data.matrix.MatrixVerificationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure verification state machine. */
class MatrixVerificationPresentationTest {

    private val emoji = MatrixSasEmoji("\uD83D\uDE48", "see-no-evil monkey")

    @Test
    fun an_incoming_request_shows_the_device_name() {
        val next = verificationUiOnEvent(VerificationUi.Idle, MatrixVerificationEvent.Incoming("Curve"))
        assertEquals(VerificationUi.Incoming("Curve"), next)
        assertEquals(VerificationUi.Incoming(""), verificationUiOnEvent(VerificationUi.Idle, MatrixVerificationEvent.Incoming("")))
    }

    @Test
    fun an_accept_moves_to_waiting_regardless_of_direction() {
        assertEquals(VerificationUi.WaitingAccept, verificationUiOnEvent(VerificationUi.Idle, MatrixVerificationEvent.Accepted))
        assertEquals(VerificationUi.WaitingAccept, verificationUiOnEvent(VerificationUi.Incoming("X"), MatrixVerificationEvent.Accepted))
    }

    @Test
    fun sas_start_and_emojis_follow_in_order() {
        var s: VerificationUi = verificationUiOnEvent(VerificationUi.WaitingAccept, MatrixVerificationEvent.SasStarted)
        assertEquals(VerificationUi.WaitingEmojis, s)
        s = verificationUiOnEvent(s, MatrixVerificationEvent.Emojis(listOf(emoji, emoji, emoji, emoji, emoji, emoji, emoji)))
        assertEquals(7, (s as VerificationUi.Emojis).emojis.size)
    }

    @Test
    fun an_incoming_request_does_not_override_a_running_flow() {
        assertEquals(VerificationUi.WaitingEmojis, verificationUiOnEvent(VerificationUi.WaitingEmojis, MatrixVerificationEvent.Incoming("X")))
        assertEquals(VerificationUi.WaitingAccept, verificationUiOnEvent(VerificationUi.WaitingAccept, MatrixVerificationEvent.Incoming("X")))
    }

    @Test
    fun settle_outcomes() {
        assertEquals(VerificationUi.Verified, verificationUiOnEvent(VerificationUi.Emojis(listOf(emoji)), MatrixVerificationEvent.Finished))
        assertEquals(
            VerificationUi.Cancelled(byUs = true, cancelCode = "m.user"),
            verificationUiOnEvent(VerificationUi.WaitingAccept, MatrixVerificationEvent.Cancelled(true, "m.user")),
        )
        assertEquals(VerificationUi.Failed, verificationUiOnEvent(VerificationUi.WaitingAccept, MatrixVerificationEvent.Failed))
    }

    @Test
    fun a_settled_state_ignores_late_events() {
        val done = VerificationUi.Verified
        var s = verificationUiOnEvent(done, MatrixVerificationEvent.Emojis(listOf(emoji)))
        assertEquals(done, s)
        s = verificationUiOnEvent(VerificationUi.Cancelled(true, null), MatrixVerificationEvent.Accepted)
        assertEquals(VerificationUi.Cancelled(true, null), s)
        s = verificationUiOnEvent(VerificationUi.Failed, MatrixVerificationEvent.Finished)
        assertEquals(VerificationUi.Failed, s)
    }

    @Test
    fun headlines_are_stable_and_content_free() {
        assertEquals("Verify a device", verificationHeadline(VerificationUi.Idle))
        assertEquals("Verification request", verificationHeadline(VerificationUi.Incoming("X")))
        assertEquals("Waiting for the other side…", verificationHeadline(VerificationUi.WaitingAccept))
        assertEquals("Comparing codes…", verificationHeadline(VerificationUi.WaitingEmojis))
        assertEquals("Do these match on the other device?", verificationHeadline(VerificationUi.Emojis(listOf(emoji))))
        assertEquals("Verified", verificationHeadline(VerificationUi.Verified))
        assertEquals("You cancelled", verificationHeadline(VerificationUi.Cancelled(true, null)))
        assertEquals("They cancelled", verificationHeadline(VerificationUi.Cancelled(false, null)))
        assertEquals("Verification failed", verificationHeadline(VerificationUi.Failed))
        assertTrue(verificationHeadline(VerificationUi.Cancelled(false, null)).contains("cancelled"))
    }
}
