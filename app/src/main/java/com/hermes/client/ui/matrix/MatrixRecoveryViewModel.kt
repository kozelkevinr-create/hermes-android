package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixRecoveryEnableResult
import com.hermes.client.data.matrix.MatrixRecoveryHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the recovery sheet: one [MatrixRecoveryHandle] per live session, created once and
 * kept across sheet re-opens; the phases are re-read (polled) on every open and after every
 * action settles. The one-time key from [enable] lives only in this state until it is shown.
 */
@HiltViewModel
class MatrixRecoveryViewModel internal constructor(
    /** The manager's handle factory, lambda-injected for tests. */
    private val handleFor: suspend () -> MatrixRecoveryHandle?,
) : ViewModel() {
    @Inject constructor(manager: MatrixClientManager) : this({ manager.recoveryHandle() })

    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    private val _ui = MutableStateFlow<RecoveryUi>(RecoveryUi.Unavailable)
    val ui: StateFlow<RecoveryUi> = _ui.asStateFlow()

    private var handle: MatrixRecoveryHandle? = null

    /** Opens the sheet and refreshes the phase snapshot (also builds the handle once). */
    fun openSheet() {
        _sheetOpen.value = true
        viewModelScope.launch {
            val h = handle ?: handleFor()?.also { handle = it }
            _ui.value = if (h == null) RecoveryUi.Unavailable else recoveryStatusUi(h.status())
        }
    }

    fun dismissSheet() {
        _sheetOpen.value = false
    }

    /** Starts recovery setup; the recovery key appears once when it all finishes. */
    fun enable() {
        val h = handle ?: run { _ui.value = RecoveryUi.Unavailable; return }
        _ui.value = RecoveryUi.Enabling
        viewModelScope.launch {
            _ui.value = when (val r = h.enable()) {
                is MatrixRecoveryEnableResult.KeyCreated -> RecoveryUi.EnabledKey(r.key.trim())
                is MatrixRecoveryEnableResult.StaleBackupExists -> RecoveryUi.StaleBackup
                is MatrixRecoveryEnableResult.Failed -> RecoveryUi.EnablingFailed
            }
        }
    }

    /**
     * Deletes the server backup that the fresh key cannot match and immediately sets up a new
     * one with this device's key. The deleted backup's keys were unreadable without the
     * original recovery key — that key was just reported lost — so nothing readable is lost.
     */
    fun recreate() {
        val h = handle ?: run { _ui.value = RecoveryUi.Unavailable; return }
        _ui.value = RecoveryUi.Recreating
        viewModelScope.launch {
            if (!h.disable()) {
                _ui.value = RecoveryUi.RecreatingFailed
                return@launch
            }
            _ui.value = when (val r = h.enable()) {
                is MatrixRecoveryEnableResult.KeyCreated -> RecoveryUi.EnabledKey(r.key.trim())
                is MatrixRecoveryEnableResult.StaleBackupExists -> RecoveryUi.StaleBackup
                is MatrixRecoveryEnableResult.Failed -> RecoveryUi.RecreatingFailed
            }
        }
    }

    /** Leaves the one-time key view; the sheet then shows the refreshed phases. */
    fun confirmKeyShown() {
        if (_ui.value is RecoveryUi.EnabledKey) refresh()
    }

    /** Restores the backup into this session from a pasted recovery key. */
    fun recover(pasted: String) {
        val key = pasted.trim()
        if (!recoveryKeyIsPlausible(key)) return
        val h = handle ?: run { _ui.value = RecoveryUi.Unavailable; return }
        _ui.value = RecoveryUi.Recovering
        viewModelScope.launch {
            _ui.value = if (h.recover(key)) RecoveryUi.Recovered else RecoveryUi.RecoverFailed
        }
    }

    /**
     * Replaces the account's recovery setup after a lost recovery key and shows the fresh key
     * once. Other sessions holding the old key lose their access to the backup.
     */
    fun reset() {
        val h = handle ?: run { _ui.value = RecoveryUi.Unavailable; return }
        _ui.value = RecoveryUi.Resetting
        viewModelScope.launch {
            val key = h.reset()
            _ui.value = if (key != null) RecoveryUi.EnabledKey(key.trim()) else RecoveryUi.ResetFailed
        }
    }

    /** Turns recovery and the key backup off (server side too). */
    fun disable() {
        val h = handle ?: run { _ui.value = RecoveryUi.Unavailable; return }
        _ui.value = RecoveryUi.Disabling
        viewModelScope.launch {
            if (h.disable()) {
                refresh()
            } else {
                _ui.value = RecoveryUi.DisableFailed
            }
        }
    }

    /** Leaves an outcome state (failure or Recovered) back at the current phase snapshot. */
    fun tryAgain() {
        if (_ui.value is RecoveryUi.EnabledKey) return
        refresh()
    }

    /** Re-reads the phases; an unusable handle reads as Unavailable. */
    private fun refresh() {
        viewModelScope.launch {
            val h = handle
            _ui.value = if (h == null) RecoveryUi.Unavailable else recoveryStatusUi(h.status())
        }
    }
}
