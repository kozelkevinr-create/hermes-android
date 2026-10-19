package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.BuildConfig
import com.hermes.client.data.matrix.CertificateBytes
import com.hermes.client.data.matrix.CertificateSummary
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixDefaults
import com.hermes.client.data.matrix.MatrixState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.Job
import javax.inject.Inject

/** Form fields; the password lives here only until a login attempt starts. */
data class MatrixFormState(
    val homeserver: String = "",
    val username: String = "",
    val password: String = "",
    val botMxid: String = "",
    val caBytes: ByteArray? = null,
    val caSummary: String? = null,
    val caError: String? = null,
)

data class MatrixAccountUiState(
    val form: MatrixFormState = MatrixFormState(),
    val account: MatrixState = MatrixState.LoggedOut,
    val roomCount: Int = 0,
    val loginError: String? = null,
)

@HiltViewModel
class MatrixAccountViewModel @Inject constructor(
    private val manager: MatrixClientManager,
) : ViewModel() {
    private val defaults = MatrixDefaults.of(
        BuildConfig.MATRIX_DEFAULT_HOMESERVER,
        BuildConfig.MATRIX_DEFAULT_USERNAME,
        BuildConfig.MATRIX_DEFAULT_BOT_MXID,
    )
    private val form = MutableStateFlow(
        MatrixFormState(homeserver = defaults.homeserver, username = defaults.username, botMxid = defaults.botMxid),
    )

    val state: StateFlow<MatrixAccountUiState> =
        combine(form, manager.state, manager.rooms, manager.loginError) { f, s, r, e ->
            MatrixAccountUiState(f, s, r.size, e)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MatrixAccountUiState(form.value))

    fun onHomeserver(v: String) = form.update { it.copy(homeserver = v.trim()) }
    fun onUsername(v: String) = form.update { it.copy(username = v.trim()) }
    fun onPassword(v: String) = form.update { it.copy(password = v) }
    fun onBotMxid(v: String) = form.update { it.copy(botMxid = v.trim()) }

    fun onCaPicked(bytes: ByteArray) {
        form.update {
            try {
                val certs = CertificateBytes.parse(bytes)
                it.copy(
                    caBytes = bytes,
                    caSummary = certs.joinToString("\n") { der -> CertificateSummary.describe(der) },
                    caError = null,
                )
            } catch (e: Exception) {
                it.copy(caError = e.message ?: "Not a valid certificate")
            }
        }
    }

    fun onCaFailed(message: String) = form.update { it.copy(caError = message) }

    fun clearCa() = form.update { it.copy(caBytes = null, caSummary = null, caError = null) }

    private var loginJob: Job? = null

    fun login() {
        // Defence in depth with the manager: no second attempt while one runs or a session is live.
        if (loginJob?.isActive == true) return
        when (manager.state.value) {
            MatrixState.LoggingIn, MatrixState.Restoring, is MatrixState.Ready -> return
            else -> Unit
        }
        val f = form.value
        if (f.password.isEmpty()) return
        // Drop the password from UI state immediately; it is only passed through to the SDK.
        form.update { it.copy(password = "") }
        // Runs on the manager's own scope, so leaving the screen doesn't orphan a server-side device.
        loginJob = manager.login(f.homeserver, f.username, f.password, f.botMxid, f.caBytes)
    }

    fun retryRestore() {
        manager.restoreOnStart()
    }

    fun logout() {
        manager.logout()
    }

    private inline fun MutableStateFlow<MatrixFormState>.update(block: (MatrixFormState) -> MatrixFormState) {
        value = block(value)
    }
}
