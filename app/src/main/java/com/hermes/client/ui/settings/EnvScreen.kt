package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.EnvVarDto
import com.hermes.client.data.repository.EnvRepository
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private val SENSITIVE_MARKERS = listOf("api", "token", "secret", "key", "pass", "cred")

/** Keys with api, token, secret, key, pass or cred in the name stay redacted until the user taps Reveal. */
fun isSensitiveEnvKey(key: String) = SENSITIVE_MARKERS.any { key.contains(it, ignoreCase = true) }

data class EnvUiState(
    val vars: List<Pair<String, EnvVarDto>> = emptyList(),
    /** Real values of keys that are not sensitive, fetched on load. */
    val plain: Map<String, String> = emptyMap(),
    val query: String = "",
    /** Profile whose variables are shown; null follows the active profile. */
    val profile: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
) {
    val filtered: List<Pair<String, EnvVarDto>>
        get() = if (query.isBlank()) vars
        else vars.filter { it.first.contains(query, true) || (it.second.description?.contains(query, true) == true) }
}

@HiltViewModel
class EnvViewModel @Inject constructor(
    private val env: EnvRepository,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(EnvUiState())
    val state: StateFlow<EnvUiState> = _state.asStateFlow()
    private val profile: String? get() = _state.value.profile ?: profileManager.active.value
    val profiles get() = profileManager.list
    val activeProfile get() = profileManager.active

    /** Show another profile's variables. Cached plain values belong to the old profile, so drop them. */
    fun selectProfile(name: String) {
        if (name == (_state.value.profile ?: profileManager.active.value)) return
        _state.value = _state.value.copy(profile = name, plain = emptyMap(), vars = emptyList(), query = "")
        load()
    }

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, error = null)
        runCatching { env.vars(profile) }
            .onSuccess { m ->
                // Set vars first, then alphabetical.
                val sorted = m.toList().sortedWith(compareByDescending<Pair<String, EnvVarDto>> { it.second.isSet }.thenBy { it.first })
                _state.value = _state.value.copy(vars = sorted, loading = false)
            }
            .onFailure { _state.value = _state.value.copy(loading = false, error = it.message ?: "Failed to load") }
    }

    fun onQuery(q: String) { _state.value = _state.value.copy(query = q) }

    fun set(key: String, value: String) = viewModelScope.launch {
        runCatching { env.set(key, value, profile) }
            .onSuccess {
                _state.value = _state.value.copy(message = "$key saved", plain = _state.value.plain - key)
                load()
            }
            .onFailure { _state.value = _state.value.copy(message = "Save failed: ${it.message}") }
    }

    /** Fetch the stored value of [key]; only called when the user taps Reveal. */
    fun reveal(key: String, sensitive: Boolean, onValue: (String) -> Unit) = viewModelScope.launch {
        runCatching { env.reveal(key, profile) }
            .onSuccess { v ->
                // Non-sensitive values are kept so the list can show them; sensitive ones never are.
                if (!sensitive) _state.value = _state.value.copy(plain = _state.value.plain + (key to v))
                onValue(v)
            }
            .onFailure {
                val limited = (it as? com.hermes.client.data.network.HermesApiException)?.code == 429
                _state.value = _state.value.copy(
                    message = if (limited) "Too many reveal requests, wait a bit and try again" else "Reveal failed: ${it.message}",
                )
            }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvScreen(
    onBack: () -> Unit,
    vm: EnvViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<String?>(null) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val activeProfile by vm.activeProfile.collectAsStateWithLifecycle()
    val shownProfile = state.profile ?: activeProfile
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = if (shownProfile.isNullOrBlank()) "API keys & env" else "API keys & env · $shownProfile",
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> com.hermes.client.ui.components.LoadingState()
                state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (profiles.size > 1) {
                        item {
                            androidx.compose.foundation.lazy.LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                            ) {
                                items(profiles, key = { it.name }) { p ->
                                    androidx.compose.material3.FilterChip(
                                        selected = p.name == shownProfile,
                                        onClick = { vm.selectProfile(p.name) },
                                        label = { Text(p.label) },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = vm::onQuery,
                            placeholder = { Text("Search keys…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                    items(state.filtered, key = { it.first }) { (key, dto) ->
                        ListItem(
                            headlineContent = { Text(key) },
                            supportingContent = {
                                Column {
                                    Text(dto.description ?: dto.category ?: "")
                                    val shown = state.plain[key] ?: dto.redactedValue
                                    if (dto.isSet && !shown.isNullOrBlank()) {
                                        Text(
                                            shown,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                Text(
                                    if (dto.isSet) "● set" else "○ not set",
                                    color = if (dto.isSet) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable { editing = key },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { key ->
        val current = state.vars.firstOrNull { it.first == key }?.second
        val sensitive = isSensitiveEnvKey(key) || current?.isPassword == true
        var value by remember(key) { mutableStateOf(if (sensitive) "" else state.plain[key].orEmpty()) }
        var revealed by remember(key) { mutableStateOf(!sensitive && state.plain.containsKey(key)) }
        LaunchedEffect(key) {
            if (!sensitive && current?.isSet == true && !state.plain.containsKey(key)) {
                vm.reveal(key, false) { value = it; revealed = true }
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(key) },
            text = {
                Column {
                    if (current?.isSet == true) {
                        Text(
                            "Current: ${state.plain[key] ?: current.redactedValue ?: "set"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        // Offer Reveal whenever the real value isn't already on screen.
                        if (sensitive || !state.plain.containsKey(key)) TextButton(onClick = {
                            if (revealed) revealed = false
                            else vm.reveal(key, sensitive) { value = it; revealed = true }
                        }) { Text(if (revealed) "Hide" else "Reveal current value") }
                    } else {
                        Text("Not set", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Value") },
                        singleLine = true,
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { editing = null; if (value.isNotBlank()) vm.set(key, value) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
