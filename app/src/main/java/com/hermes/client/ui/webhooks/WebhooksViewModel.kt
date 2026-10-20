package com.hermes.client.ui.webhooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.WebhookDto
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WebhooksState(
    val webhooks: List<WebhookDto> = emptyList(),
    val globallyEnabled: Boolean = false,
    val baseUrl: String = "",
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class WebhooksViewModel @Inject constructor(
    private val rest: HermesRestApi,
) : ViewModel() {
    private val _state = MutableStateFlow(WebhooksState())
    val state: StateFlow<WebhooksState> = _state.asStateFlow()

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { rest.webhooks() }
                .onSuccess {
                    _state.value = WebhooksState(
                        webhooks = it.subscriptions,
                        globallyEnabled = it.enabled,
                        baseUrl = it.baseUrl,
                        loading = false,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "Could not load webhooks")
                }
        }
    }

    fun toggle(name: String, enabled: Boolean) {
        // Optimistic: flip locally, then reconcile with the server's view on completion.
        _state.value = _state.value.copy(
            webhooks = _state.value.webhooks.map { if (it.name == name) it.copy(enabled = enabled) else it },
        )
        viewModelScope.launch {
            runCatching { rest.setWebhookEnabled(name, enabled) }
                .onSuccess { load() }
                .onFailure { e ->
                    _state.value = _state.value.copy(error = e.message ?: "Could not toggle $name")
                    load()
                }
        }
    }
}
