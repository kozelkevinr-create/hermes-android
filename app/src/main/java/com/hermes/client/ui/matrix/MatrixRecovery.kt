package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixBackupPhase
import com.hermes.client.data.matrix.MatrixRecoveryHandle
import com.hermes.client.data.matrix.MatrixRecoveryPhase
import com.hermes.client.data.matrix.MatrixRecoveryStatus

/**
 * The recovery sheet's pure state. The sheet opens on a [Status] snapshot; the actions
 * (enable / recover / disable) run through [MatrixRecoveryHandle] and either settle back into
 * a refreshed [Status] or into an outcome state (the one-time key view, or a failure).
 */
sealed interface RecoveryUi {
    /** A live snapshot; enables the phase-appropriate entries. */
    data class Status(val recovery: MatrixRecoveryPhase, val backup: MatrixBackupPhase) : RecoveryUi

    /** Waiting for enable() — creating the backup, generating the key, uploading all of it. */
    data object Enabling : RecoveryUi

    /**
     * The fresh recovery key, shown exactly once (never logged). Disappears on "Done" —
     * losing it means losing the only way back to the backed-up keys.
     */
    data class EnabledKey(val key: String) : RecoveryUi

    /** enable() failed (server or key-creation problem); the phase snapshot is unchanged. */
    data object EnablingFailed : RecoveryUi

    /**
     * The server holds a key backup that the fresh key cannot match: the backup has to be
     * deleted (its keys are unreadable without the original key, which the user just said
     * was lost) before a backup can be set up.
     */
    data object StaleBackup : RecoveryUi

    /** Waiting for the stale backup's deletion plus a fresh enable(). */
    data object Recreating : RecoveryUi

    /** The recreate sequence did not finish; the setup is still in the stale state. */
    data object RecreatingFailed : RecoveryUi

    /** Waiting for recover() — downloading and importing the backed-up keys. */
    data object Recovering : RecoveryUi

    /** recover() succeeded; the exported keys decrypt the old history again. */
    data object Recovered : RecoveryUi

    /** recover() failed — a wrong key or a server problem. */
    data object RecoverFailed : RecoveryUi

    /** Waiting for reset() — replacing the account's recovery setup server-side. */
    data object Resetting : RecoveryUi

    /** reset() failed; the (broken) recovery setup is untouched. */
    data object ResetFailed : RecoveryUi

    /** Waiting for disable() — tearing the backup down server-side. */
    data object Disabling : RecoveryUi

    /** disable() failed; the session keeps its current phases. */
    data object DisableFailed : RecoveryUi

    /** There is no live session (so no recovery surface). */
    data object Unavailable : RecoveryUi
}

/** The sheet's headline for [ui]; short and content-free. */
fun recoveryHeadline(ui: RecoveryUi): String = when (ui) {
    is RecoveryUi.Status -> "Recovery"
    RecoveryUi.Enabling -> "Setting up recovery…"
    is RecoveryUi.EnabledKey -> "Save your recovery key"
    RecoveryUi.EnablingFailed -> "Recovery setup failed"
    RecoveryUi.StaleBackup -> "A key backup already exists"
    RecoveryUi.Recreating -> "Rebuilding the backup…"
    RecoveryUi.RecreatingFailed -> "Rebuilding failed"
    RecoveryUi.Recovering -> "Restoring keys…"
    RecoveryUi.Recovered -> "Keys restored"
    RecoveryUi.RecoverFailed -> "Restore failed"
    RecoveryUi.Resetting -> "Resetting recovery…"
    RecoveryUi.ResetFailed -> "Resetting failed"
    RecoveryUi.Disabling -> "Turning recovery off…"
    RecoveryUi.DisableFailed -> "Disabling failed"
    RecoveryUi.Unavailable -> "Recovery unavailable"
}

/** One-line label of a recovery phase. */
fun recoveryPhaseLabel(phase: MatrixRecoveryPhase): String = when (phase) {
    MatrixRecoveryPhase.ENABLED -> "Enabled"
    MatrixRecoveryPhase.DISABLED -> "Off"
    MatrixRecoveryPhase.INCOMPLETE -> "Incomplete"
    MatrixRecoveryPhase.UNKNOWN -> "Unknown"
}

/** One-line label of a backup-engine phase. */
fun backupPhaseLabel(phase: MatrixBackupPhase): String = when (phase) {
    MatrixBackupPhase.ENABLED -> "Backing up"
    MatrixBackupPhase.CREATING -> "Preparing"
    MatrixBackupPhase.ENABLING -> "Starting up"
    MatrixBackupPhase.RESUMING -> "Resuming"
    MatrixBackupPhase.DOWNLOADING -> "Restoring"
    MatrixBackupPhase.DISABLING -> "Shutting down"
    MatrixBackupPhase.UNKNOWN -> "Unknown"
}

/**
 * A pasted recovery key is plausible enough to hand to the SDK when its trimmed content is one
 * non-empty line of sane length; the server decides whether it is actually right.
 */
fun recoveryKeyIsPlausible(pasted: String): Boolean {
    val key = pasted.trim()
    return key.length in 8..512 && '\n' !in key
}

/** Maps a [MatrixRecoveryStatus] snapshot into the sheet's [RecoveryUi.Status]. */
fun recoveryStatusUi(status: MatrixRecoveryStatus): RecoveryUi =
    RecoveryUi.Status(status.recovery, status.backup)
