package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixBackupPhase
import com.hermes.client.data.matrix.MatrixRecoveryPhase
import com.hermes.client.data.matrix.MatrixRecoveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure recovery presentation helpers. */
class MatrixRecoveryPresentationTest {

    @Test
    fun the_status_snapshot_maps_into_the_sheet_state() {
        assertEquals(
            RecoveryUi.Status(MatrixRecoveryPhase.ENABLED, MatrixBackupPhase.DOWNLOADING),
            recoveryStatusUi(MatrixRecoveryStatus(MatrixRecoveryPhase.ENABLED, MatrixBackupPhase.DOWNLOADING)),
        )
        assertEquals(
            RecoveryUi.Status(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN),
            recoveryStatusUi(MatrixRecoveryStatus(MatrixRecoveryPhase.INCOMPLETE, MatrixBackupPhase.UNKNOWN)),
        )
    }

    @Test
    fun the_plausibility_gate_trims_and_rejects_garbage() {
        assertTrue(recoveryKeyIsPlausible("  EsTq 9ErC 1tCR vrJB W3yJ 9UZF  8PZM \n"))
        assertFalse(recoveryKeyIsPlausible("   "))
        assertFalse(recoveryKeyIsPlausible("short"))
        assertFalse(recoveryKeyIsPlausible("two\nlines"))
    }

    @Test
    fun headlines_cover_every_state_and_stay_content_free() {
        assertEquals("Recovery", recoveryHeadline(RecoveryUi.Status(MatrixRecoveryPhase.ENABLED, MatrixBackupPhase.ENABLED)))
        assertEquals("Setting up recovery…", recoveryHeadline(RecoveryUi.Enabling))
        assertEquals("Save your recovery key", recoveryHeadline(RecoveryUi.EnabledKey("abcd")))
        assertEquals("Recovery setup failed", recoveryHeadline(RecoveryUi.EnablingFailed))
        assertEquals("A key backup already exists", recoveryHeadline(RecoveryUi.StaleBackup))
        assertEquals("Rebuilding the backup…", recoveryHeadline(RecoveryUi.Recreating))
        assertEquals("Rebuilding failed", recoveryHeadline(RecoveryUi.RecreatingFailed))
        assertEquals("Restoring keys…", recoveryHeadline(RecoveryUi.Recovering))
        assertEquals("Keys restored", recoveryHeadline(RecoveryUi.Recovered))
        assertEquals("Restore failed", recoveryHeadline(RecoveryUi.RecoverFailed))
        assertEquals("Resetting recovery…", recoveryHeadline(RecoveryUi.Resetting))
        assertEquals("Resetting failed", recoveryHeadline(RecoveryUi.ResetFailed))
        assertEquals("Turning recovery off…", recoveryHeadline(RecoveryUi.Disabling))
        assertEquals("Disabling failed", recoveryHeadline(RecoveryUi.DisableFailed))
        assertEquals("Recovery unavailable", recoveryHeadline(RecoveryUi.Unavailable))
    }

    @Test
    fun phase_labels_are_short_and_content_free() {
        assertEquals("Enabled", recoveryPhaseLabel(MatrixRecoveryPhase.ENABLED))
        assertEquals("Off", recoveryPhaseLabel(MatrixRecoveryPhase.DISABLED))
        assertEquals("Incomplete", recoveryPhaseLabel(MatrixRecoveryPhase.INCOMPLETE))
        assertEquals("Unknown", recoveryPhaseLabel(MatrixRecoveryPhase.UNKNOWN))
        assertEquals("Backing up", backupPhaseLabel(MatrixBackupPhase.ENABLED))
        assertEquals("Unknown", backupPhaseLabel(MatrixBackupPhase.UNKNOWN))
    }
}
