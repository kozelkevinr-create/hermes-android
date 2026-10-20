package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixSasEmoji
import com.hermes.client.data.matrix.MatrixVerificationEvent
import com.hermes.client.data.matrix.MatrixVerificationHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The verification VM against a recording fake controller. */
@OptIn(ExperimentalCoroutinesApi::class)
class MatrixVerificationViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val events = MutableSharedFlow<MatrixVerificationEvent>(extraBufferCapacity = 16)

    private class FakeHandle(
        private val sink: MutableSharedFlow<MatrixVerificationEvent>,
    ) : MatrixVerificationHandle {
        val calls = mutableListOf<String>()
        override val events: Flow<MatrixVerificationEvent> = sink
        override suspend fun requestDeviceVerification(): Boolean { calls += "requestDevice"; return true }
        override suspend fun requestUserVerification(mxid: String): Boolean { calls += "requestUser"; return true }
        override suspend fun acceptRequest(): Boolean { calls += "accept"; return true }
        override suspend fun startSas(): Boolean { calls += "startSas"; return true }
        override suspend fun approve(): Boolean { calls += "approve"; return true }
        override suspend fun decline(): Boolean { calls += "decline"; return true }
        override suspend fun cancel(): Boolean { calls += "cancel"; return true }
        var closed = false
        override fun close() { closed = true }
    }

    private val handle = FakeHandle(events)
    private var controllerBuilt = 0

    @Before fun setUp() { Dispatchers.setMain(mainDispatcher); controllerBuilt = 0 }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): MatrixVerificationViewModel = MatrixVerificationViewModel({ controllerBuilt++; handle })

    @Test
    fun opening_builds_one_controller_and_subscribes() = runTest {
        val v = vm()
        assertFalse(v.sheetOpen.value)
        v.openSheet()
        advanceUntilIdle()
        assertTrue(v.sheetOpen.value)
        assertEquals(1, controllerBuilt)
        assertEquals(VerificationUi.Idle, v.ui.value)
        // Events reach the state machine through the collector.
        events.tryEmit(MatrixVerificationEvent.Incoming("Curve"))
        assertEquals(VerificationUi.Incoming("Curve"), v.ui.value)
    }

    @Test
    fun reopening_the_sheet_reuses_the_controller() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()
        v.dismissSheet()
        v.openSheet()
        advanceUntilIdle()
        assertEquals(1, controllerBuilt)
    }

    @Test
    fun a_missing_controller_reports_failed() = runTest {
        val v = MatrixVerificationViewModel({ null })
        v.openSheet()
        advanceUntilIdle()
        assertEquals(VerificationUi.Failed, v.ui.value)
        assertTrue(v.sheetOpen.value)
    }

    @Test
    fun the_buttons_drive_the_seam_calls() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.requestDeviceVerification()
        assertEquals(VerificationUi.WaitingAccept, v.ui.value)
        v.acceptIncoming()
        v.approve()
        v.decline()
        v.cancelFlow()
        v.dismissSheet()
        advanceUntilIdle()
        assertEquals(listOf("requestDevice", "accept", "approve", "decline", "cancel"), handle.calls)
        assertFalse(v.sheetOpen.value)
    }

    @Test
    fun user_verification_needs_an_mxid_and_goes_to_the_seam() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.requestUserVerification("")
        assertEquals(VerificationUi.Failed, v.ui.value)
        assertTrue(handle.calls.isEmpty())

        v.reset()
        v.requestUserVerification("@bot:example.test")
        assertEquals(VerificationUi.WaitingAccept, v.ui.value)
        advanceUntilIdle()
        assertEquals(listOf("requestUser"), handle.calls)
    }

    @Test
    fun an_accepted_outgoing_request_starts_the_sas_automatically() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.requestDeviceVerification()
        handle.calls.clear()

        events.tryEmit(MatrixVerificationEvent.Accepted)
        advanceUntilIdle()
        assertEquals(VerificationUi.WaitingAccept, v.ui.value)
        assertEquals(listOf("startSas"), handle.calls)
    }

    @Test
    fun the_emoji_prompt_and_finish_settle_the_flow() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        events.tryEmit(MatrixVerificationEvent.Accepted)
        events.tryEmit(MatrixVerificationEvent.SasStarted)
        events.tryEmit(
            MatrixVerificationEvent.Emojis(
                (1..7).map { MatrixSasEmoji("e$it", "emoji $it") },
            ),
        )
        val emojis = v.ui.value as VerificationUi.Emojis
        assertEquals(7, emojis.emojis.size)
        assertTrue(emojis.emojis.all { it.symbol.startsWith("e") })

        events.tryEmit(MatrixVerificationEvent.Finished)
        assertEquals(VerificationUi.Verified, v.ui.value)

        // A settled state ignores stale events.
        events.tryEmit(MatrixVerificationEvent.Cancelled(false, null))
        assertEquals(VerificationUi.Verified, v.ui.value)
    }

    @Test
    fun a_cancellation_carries_who_cancelled() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        events.tryEmit(MatrixVerificationEvent.Accepted)
        events.tryEmit(MatrixVerificationEvent.Cancelled(false, "m.user"))
        val s = v.ui.value as VerificationUi.Cancelled
        assertFalse(s.byUs)
        assertEquals("m.user", s.cancelCode)
    }
}
