package com.hermes.client.ui.plugins

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesRestApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

/** A dashboard plugin tab advertised by `GET /api/dashboard/plugins`. */
data class DashboardPlugin(val name: String, val label: String, val path: String)

/**
 * Lenient parser: the endpoint's exact shape is not pinned down, so accept either a bare array
 * or `{ "plugins": [...] }`, and read the tab path from `tab.path`, `path` or `/<name>`.
 */
internal fun parseDashboardPlugins(body: String, json: Json = Json): List<DashboardPlugin> {
    val root: JsonElement = json.parseToJsonElement(body)
    val arr: JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> root["plugins"] as? JsonArray ?: JsonArray(emptyList())
        else -> JsonArray(emptyList())
    }
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull
        val name = str(o["name"]) ?: return@mapNotNull null
        val tab = o["tab"] as? JsonObject
        val path = str(tab?.get("path")) ?: str(o["path"]) ?: "/$name"
        val label = str(tab?.get("label")) ?: str(o["label"]) ?: str(o["title"]) ?: name
        DashboardPlugin(name, label, if (path.startsWith("/")) path else "/$path")
    }
}

data class PluginsUiState(
    val plugins: List<DashboardPlugin> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class PluginsViewModel @Inject constructor(
    private val rest: HermesRestApi,
    private val json: Json,
) : ViewModel() {
    private val _state = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = PluginsUiState(loading = true)
        _state.value = runCatching { parseDashboardPlugins(rest.dashboardPluginsRaw(), json) }
            .fold(
                { PluginsUiState(plugins = it, loading = false) },
                { PluginsUiState(loading = false, error = it.message ?: "Failed to load plugins") },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    onBack: () -> Unit,
    onOpen: (path: String, title: String) -> Unit,
    vm: PluginsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Plugins",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> com.hermes.client.ui.components.ErrorState(
                    message = state.error!!, onRetry = { vm.load() },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.plugins, key = { it.name }) { p ->
                        ListItem(
                            headlineContent = { Text(p.label) },
                            supportingContent = { Text(p.path) },
                            modifier = Modifier.clickable { onOpen(p.path, p.label) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
