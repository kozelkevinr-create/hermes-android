package com.hermes.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.ntfy.NtfyConfig
import com.hermes.client.data.ntfy.NtfyKind
import com.hermes.client.data.ntfy.NtfyListener
import com.hermes.client.data.ntfy.NtfyStatus
import com.hermes.client.data.ntfy.NtfyStore
import com.hermes.client.data.ntfy.NtfyTopic
import com.hermes.client.data.ntfy.normalizeServerUrl
import com.hermes.client.ui.components.HermesTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NtfySettingsViewModel @Inject constructor(
    private val store: NtfyStore,
    private val listener: NtfyListener,
) : ViewModel() {
    val config: StateFlow<NtfyConfig> =
        store.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NtfyConfig())
    val status: StateFlow<NtfyStatus> = listener.status

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun initialPassword(): String = store.password()

    fun setEnabled(on: Boolean) = viewModelScope.launch { store.update { it.copy(enabled = on) } }

    fun saveServer(url: String, user: String, password: String) = viewModelScope.launch {
        _saveError.value = if (store.setPassword(password)) null else "Couldn't store the password securely on this device"
        store.update { it.copy(serverUrl = normalizeServerUrl(url), username = user.trim()) }
    }

    fun test(url: String, user: String, password: String) = viewModelScope.launch {
        _testResult.value = "Checking…"
        _testResult.value = listener.check(url, user, password) ?: "Connected"
    }

    fun addTopic(name: String, topic: String, kind: NtfyKind) = viewModelScope.launch {
        val t = topic.trim().trim('/')
        if (t.isEmpty()) return@launch
        store.update { c ->
            if (c.topics.any { it.topic == t }) c
            else c.copy(topics = c.topics + NtfyTopic(name.trim().ifEmpty { t }, t, kind))
        }
    }

    fun setTopicEnabled(topic: String, on: Boolean) = viewModelScope.launch {
        store.update { c -> c.copy(topics = c.topics.map { if (it.topic == topic) it.copy(enabled = on) else it }) }
    }

    fun removeTopic(topic: String) = viewModelScope.launch {
        store.update { c -> c.copy(topics = c.topics.filterNot { it.topic == topic }) }
    }
}

/** Where ntfy-delivered notifications come from: the server, who you are on it, and which topics to follow. */
@Composable
fun NtfySettingsScreen(onBack: () -> Unit, vm: NtfySettingsViewModel = hiltViewModel()) {
    val config by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()
    val saveError by vm.saveError.collectAsStateWithLifecycle()

    var url by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var user by remember(config.username) { mutableStateOf(config.username) }
    var password by remember { mutableStateOf(vm.initialPassword()) }
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Notification sources",
                subtitle = "ntfy subscriptions",
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            ListItem(
                headlineContent = { Text("Listen to ntfy") },
                supportingContent = {
                    Text(
                        when (val s = status) {
                            NtfyStatus.Off -> if (config.enabled) "Add a server and at least one topic" else "Off"
                            NtfyStatus.Connecting -> "Connecting…"
                            is NtfyStatus.Connected -> "Connected · ${s.topics} topic${if (s.topics == 1) "" else "s"}"
                            is NtfyStatus.Failed -> s.message
                        },
                    )
                },
                trailingContent = { Switch(checked = config.enabled, onCheckedChange = { vm.setEnabled(it) }) },
            )
            Text(
                "Runs inside the notification service, so notifications must be turned on too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Server", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = url, onValueChange = { url = it }, singleLine = true,
                label = { Text("Address") }, placeholder = { Text("http://192.168.0.127:8080") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = user, onValueChange = { user = it }, singleLine = true,
                label = { Text("User (optional)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveServer(url, user, password) }) { Text("Save") }
                OutlinedButton(onClick = { vm.test(url, user, password) }) { Text("Test connection") }
            }
            (saveError ?: testResult)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    "Topics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                TextButton(onClick = { adding = true }) { Text("Add topic") }
            }
            if (config.topics.isEmpty()) {
                Text(
                    "No topics yet. Add your approvals topic as type Approvals to answer Hermes approvals from here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            config.topics.forEach { t ->
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text("${t.topic} · ${if (t.kind == NtfyKind.APPROVALS) "Approvals" else "Alerts"}") },
                    trailingContent = {
                        Row {
                            Switch(checked = t.enabled, onCheckedChange = { vm.setTopicEnabled(t.topic, it) })
                            IconButton(onClick = { vm.removeTopic(t.topic) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Remove ${t.name}")
                            }
                        }
                    },
                )
            }
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var topic by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf(NtfyKind.GENERAL) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add topic") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") })
                    OutlinedTextField(value = topic, onValueChange = { topic = it }, singleLine = true, label = { Text("Topic") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = kind == NtfyKind.GENERAL, onClick = { kind = NtfyKind.GENERAL }, label = { Text("Alerts") })
                        FilterChip(selected = kind == NtfyKind.APPROVALS, onClick = { kind = NtfyKind.APPROVALS }, label = { Text("Approvals") })
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = topic.isNotBlank(), onClick = { vm.addTopic(name, topic, kind); adding = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
