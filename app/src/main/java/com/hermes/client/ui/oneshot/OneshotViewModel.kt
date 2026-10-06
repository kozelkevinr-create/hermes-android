package com.hermes.client.ui.oneshot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.auth.BridgeConfig
import com.hermes.client.data.auth.BridgeConfigStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

data class OneshotState(
    val prompt: String = "",
    val sending: Boolean = false,
    val lastTaskId: String? = null,
    val error: String? = null,
    val configured: Boolean = false,
    val bridgeUrl: String = "",
    val bridgeToken: String = "",
)

/**
 * Fire-and-forget task dispatch through the Dispatch Bridge.
 *
 * Unlike a chat session — which is a live socket the OS kills when the screen locks — the bridge
 * owns the run server-side and reports progress over ntfy, so the task keeps going with the phone
 * asleep or out of range. The reply never comes back through this screen by design; it arrives as
 * a notification.
 */
@HiltViewModel
class OneshotViewModel @Inject constructor(
    private val store: BridgeConfigStore,
    private val okHttp: OkHttpClient,
) : ViewModel() {
    private val _state = MutableStateFlow(OneshotState())
    val state: StateFlow<OneshotState> = _state.asStateFlow()

    init { refreshConfig() }

    fun refreshConfig() {
        val cfg = store.load()
        _state.value = _state.value.copy(
            configured = cfg.isConfigured,
            bridgeUrl = cfg.baseUrl,
            bridgeToken = cfg.token,
        )
    }

    fun onPromptChanged(text: String) {
        _state.value = _state.value.copy(prompt = text, error = null, lastTaskId = null)
    }

    fun saveConfig(url: String, token: String) {
        store.save(BridgeConfig(url, token))
        refreshConfig()
    }

    fun send() {
        val s = _state.value
        if (s.prompt.isBlank() || s.sending || !s.configured) return
        _state.value = s.copy(sending = true, error = null, lastTaskId = null)
        viewModelScope.launch {
            runCatching { dispatch(s.bridgeUrl, s.bridgeToken, s.prompt) }
                .onSuccess { id ->
                    _state.value = _state.value.copy(sending = false, lastTaskId = id, prompt = "")
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(sending = false, error = e.message ?: "Could not dispatch")
                }
        }
    }

    private suspend fun dispatch(baseUrl: String, token: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { put("prompt", prompt) }
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/tasks")
                .header("Authorization", "Bearer $token")
                .post(payload)
                .build()
            okHttp.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException(
                        if (resp.code == 401) "Bridge rejected the token" else "Bridge error ${resp.code}",
                    )
                }
                // The bridge returns the new task's id; fall back to a generic ack if the shape
                // changes, since the useful signal (it was accepted) is the HTTP status.
                Regex("\"(?:task_)?id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "accepted"
            }
        }
}
