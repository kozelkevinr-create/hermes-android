package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixVerificationEvent
import com.hermes.client.data.matrix.MatrixVerificationHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the SAS emoji-verification sheet: one [MatrixVerificationHandle] per live session,
 * created once and kept across sheet re-opens so an incoming request is seen even while the
 * sheet is closed. The pure state machine in [MatrixVerification.kt] does the transitions.
 */
@HiltViewModel
class MatrixVerificationViewModel internal constructor(
    /** The manager's controller factory, lambda-injected for tests. */
    private val controller: suspend () -> MatrixVerificationHandle?,
) : ViewModel() {
    @Inject constructor(manager: MatrixClientManager) : this({ manager.verificationController() })

    private val _sheetOpen = MutableStateFlow(false)
    val sheetOpen: StateFlow<Boolean> = _sheetOpen.asStateFlow()

    private val _ui = MutableStateFlow<VerificationUi>(VerificationUi.Idle)
    val ui: StateFlow<VerificationUi> = _ui.asStateFlow()

    private var handle: MatrixVerificationHandle? = null
    private var collector: Job? = null

    /** Opens the sheet and (once) starts the event collector. */
    fun openSheet() {
        _sheetOpen.value = true
        if (collector != null) return
        viewModelScope.launch {
            val h = controller()
            handle = h
            if (h == null) {
                _ui.value = VerificationUi.Failed // no session or no controller: report it
                return@launch
            }
            collector = viewModelScope.launch {
                h.events.collect { onEvent(it) }
            }
        }
    }

    fun dismissSheet() {
        _sheetOpen.value = false
    }

    /** Sends a device-to-device request (same account, another of our sessions). */
    fun requestDeviceVerification() {
        _ui.value = VerificationUi.WaitingAccept
        begin { it.requestDeviceVerification() }
    }

    /** Sends a user-verification request to [mxid] (the Hermes bot, typically). */
    fun requestUserVerification(mxid: String) {
        // A blank MXID means the caller decide-gated wrongly; treat it as out-of-flow failure.
        if (mxid.isBlank()) {
            _ui.value = VerificationUi.Failed
            return
        }
        _ui.value = VerificationUi.WaitingAccept
        begin { it.requestUserVerification(mxid) }
    }

    /** Accepts the shown incoming request. */
    fun acceptIncoming() {
        begin { it.acceptRequest() }
    }

    /** Declines the shown incoming request; the flow settles via the cancel event. */
    fun declineIncoming() {
        begin { it.decline() }
    }

    /** The short codes match. */
    fun approve() {
        begin { it.approve() }
    }

    /** The short codes do not match. */
    fun decline() {
        begin { it.decline() }
    }

    /** Aborts the flow; the cancel event settles the sheet state. */
    fun cancelFlow() {
        begin { it.cancel() }
    }

    /** Starts the flow over (the state resets; the controller is reused). */
    fun reset() {
        _ui.value = VerificationUi.Idle
    }

    /** Runs [call] on the current handle, silently; the flow reports via events. */
    private fun begin(call: suspend (MatrixVerificationHandle) -> Unit) {
        val h = handle ?: return
        viewModelScope.launch { call(h) }
    }

    /**
     * Folds an event into the state, and makes the accepted-outgoing case start the SAS
     * handshake (the initiator must send SAS start after the peer accepts; duplicate starts
     * from both sides are deduplicated by the protocol).
     */
    private fun onEvent(event: MatrixVerificationEvent) {
        _ui.value = verificationUiOnEvent(_ui.value, event)
        if (event is MatrixVerificationEvent.Accepted) begin { it.startSas() }
    }

    override fun onCleared() {
        // The controller outlives the sheet (and this VM): the manager/client closes it.
        collector?.cancel()
        super.onCleared()
    }
}
