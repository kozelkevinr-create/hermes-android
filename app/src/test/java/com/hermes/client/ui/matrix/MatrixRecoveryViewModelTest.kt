package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixBackupPhase
import com.hermes.client.data.matrix.MatrixRecoveryEnableResult
import com.hermes.client.data.matrix.MatrixRecoveryHandle
import com.hermes.client.data.matrix.MatrixRecoveryPhase
import com.hermes.client.data.matrix.MatrixRecoveryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The recovery VM against a recording fake handle; runs on the unconfined dispatcher so the
 * launched coroutines settle synchronously after [advanceUntilIdle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatrixRecoveryViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private class FakeRecoveryHandle : MatrixRecoveryHandle {
        var statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.DISABLED, MatrixBackupPhase.UNKNOWN)
        var enableResult: MatrixRecoveryEnableResult = MatrixRecoveryEnableResult.KeyCreated("keys-1234 page-like paste")
        var resetResult: String? = "keys-9999 brand new"
        var recoverResult = true
        var disableResult = true
        val calls = mutableListOf<String>()
        var closed = false

        override fun status(): MatrixRecoveryStatus {
            calls += "status"
            return statusSnapshot
        }

        override suspend fun enable(): MatrixRecoveryEnableResult {
            calls += "enable"
            return enableResult
        }

        override suspend fun recover(key: String): Boolean {
            calls += "recover:$key"
            return recoverResult
        }

        override suspend fun reset(): String? {
            calls += "reset"
            return resetResult
        }

        override suspend fun disable(): Boolean {
            calls += "disable"
            return disableResult
        }

        override fun close() {
            closed = true
        }
    }

    private val fake = FakeRecoveryHandle()
    private var handleCalls = 0

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        handleCalls = 0
        fake.statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.DISABLED, MatrixBackupPhase.UNKNOWN)
        fake.enableResult = MatrixRecoveryEnableResult.KeyCreated("keys-1234 page-like paste")
        fake.resetResult = "keys-9999 brand new"
        fake.recoverResult = true
        fake.disableResult = true
        fake.calls.clear()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(returns: MatrixRecoveryHandle? = fake) = MatrixRecoveryViewModel({ handleCalls++; returns })

    @Test
    fun open_shows_the_phase_snapshot_and_caches_the_handle() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()
        assertEquals(RecoveryUi.Status(MatrixRecoveryPhase.DISABLED, MatrixBackupPhase.UNKNOWN), v.ui.value)
        assertTrue(v.sheetOpen.value)
        assertEquals(1, handleCalls)

        v.dismissSheet()
        v.openSheet()
        advanceUntilIdle()
        assertEquals(1, handleCalls) // same handle reused; only the snapshot is re-read
        assertTrue(fake.calls.count { it == "status" } == 2)
    }

    @Test
    fun a_missing_handle_reads_unavailable() = runTest {
        val v = vm(returns = null)
        v.openSheet()
        advanceUntilIdle()
        assertEquals(RecoveryUi.Unavailable, v.ui.value)
    }

    @Test
    fun enable_shows_the_key_exactly_once_then_the_refreshed_status() = runTest {
        fake.statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.ENABLED, MatrixBackupPhase.ENABLED)
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.enable()
        advanceUntilIdle()
        assertEquals(RecoveryUi.EnabledKey("keys-1234 page-like paste"), v.ui.value)
        assertEquals(listOf("status", "enable"), fake.calls)

        v.confirmKeyShown()
        advanceUntilIdle()
        assertEquals(RecoveryUi.Status(MatrixRecoveryPhase.ENABLED, MatrixBackupPhase.ENABLED), v.ui.value)
        assertEquals(listOf("status", "enable", "status"), fake.calls)
    }

    @Test
    fun an_enable_failure_leaves_the_key_unshown() = runTest {
        fake.enableResult = MatrixRecoveryEnableResult.Failed
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.enable()
        advanceUntilIdle()
        assertEquals(RecoveryUi.EnablingFailed, v.ui.value)
        assertFalse(v.ui.value is RecoveryUi.EnabledKey)
        assertEquals(listOf("status", "enable"), fake.calls)
    }

    @Test
    fun a_stale_server_backup_offers_recreation_and_gets_one() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        fake.enableResult = MatrixRecoveryEnableResult.StaleBackupExists
        v.enable()
        advanceUntilIdle()
        assertEquals(RecoveryUi.StaleBackup, v.ui.value)
        assertEquals(listOf("status", "enable"), fake.calls)

        // Recreate: delete the backup, then enable fresh; the fresh key appears exactly once.
        fake.enableResult = MatrixRecoveryEnableResult.KeyCreated("keys-recreated brand new")
        v.recreate()
        advanceUntilIdle()
        assertEquals(RecoveryUi.EnabledKey("keys-recreated brand new"), v.ui.value)
        assertEquals(listOf("status", "enable", "disable", "enable"), fake.calls)

        v.confirmKeyShown()
        advanceUntilIdle()
        assertTrue(v.ui.value is RecoveryUi.Status)
    }

    @Test
    fun recreation_surfaces_each_step_failure() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        fake.enableResult = MatrixRecoveryEnableResult.StaleBackupExists
        v.enable()
        advanceUntilIdle()

        // The deletion step failing keeps everything in place.
        fake.disableResult = false
        v.recreate()
        advanceUntilIdle()
        assertEquals(RecoveryUi.RecreatingFailed, v.ui.value)
        assertEquals(listOf("status", "enable", "disable"), fake.calls)

        // The fresh enable step failing after a successful deletion does the same.
        fake.disableResult = true
        fake.enableResult = MatrixRecoveryEnableResult.Failed
        v.recreate()
        advanceUntilIdle()
        assertEquals(RecoveryUi.RecreatingFailed, v.ui.value)
        assertEquals(listOf("status", "enable", "disable", "disable", "enable"), fake.calls)
    }

    @Test
    fun restore_gates_short_paste_fails_cleanly_and_succeeds_with_a_real_key() = runTest {
        fake.statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN)
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.recover("short")
        advanceUntilIdle()
        assertEquals(RecoveryUi.Status(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN), v.ui.value)
        assertTrue(fake.calls.none { it.startsWith("recover") })

        fake.recoverResult = false
        v.recover("  keys-1234 page-like paste \n")
        advanceUntilIdle()
        assertEquals(RecoveryUi.RecoverFailed, v.ui.value)
        assertEquals("recover:keys-1234 page-like paste", fake.calls.last())

        fake.recoverResult = true
        v.tryAgain()
        advanceUntilIdle()
        assertEquals(RecoveryUi.Status(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN), v.ui.value)

        v.recover("EsTq 9ErC 1tCR vrJB W3yJ 9UZF 8PZM")
        advanceUntilIdle()
        assertEquals(RecoveryUi.Recovered, v.ui.value)
    }

    @Test
    fun disable_returns_to_the_refreshed_off_status() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        fake.statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.DISABLED, MatrixBackupPhase.UNKNOWN)
        v.disable()
        advanceUntilIdle()
        assertEquals(RecoveryUi.Status(MatrixRecoveryPhase.DISABLED, MatrixBackupPhase.UNKNOWN), v.ui.value)
        assertEquals(listOf("status", "disable", "status"), fake.calls)

        fake.disableResult = false
        fake.statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.ENABLED, MatrixBackupPhase.CREATING)
        v.disable()
        advanceUntilIdle()
        assertEquals(RecoveryUi.DisableFailed, v.ui.value)
    }

    @Test
    fun reset_replaces_the_setup_and_hands_out_a_fresh_key_once() = runTest {
        fake.statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN)
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.reset()
        advanceUntilIdle()
        assertEquals(RecoveryUi.EnabledKey("keys-9999 brand new"), v.ui.value)
        assertEquals(listOf("status", "reset"), fake.calls)

        v.confirmKeyShown()
        advanceUntilIdle()
        assertEquals(RecoveryUi.Status(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN), v.ui.value)
        assertEquals(listOf("status", "reset", "status"), fake.calls)
    }

    @Test
    fun a_failed_reset_leaves_the_setup_untouched() = runTest {
        fake.statusSnapshot = MatrixRecoveryStatus(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN)
        fake.resetResult = null
        val v = vm()
        v.openSheet()
        advanceUntilIdle()

        v.reset()
        advanceUntilIdle()
        assertEquals(RecoveryUi.ResetFailed, v.ui.value)
        assertFalse(v.ui.value is RecoveryUi.EnabledKey)

        v.tryAgain()
        advanceUntilIdle()
        assertEquals(RecoveryUi.Status(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN), v.ui.value)
    }

    @Test
    fun try_again_never_drops_the_one_time_key() = runTest {
        val v = vm()
        v.openSheet()
        advanceUntilIdle()
        v.enable()
        advanceUntilIdle()

        v.tryAgain()
        advanceUntilIdle()
        assertEquals(RecoveryUi.EnabledKey("keys-1234 page-like paste"), v.ui.value)
        assertEquals(listOf("status", "enable"), fake.calls)
    }
}
