package com.hermes.client.ui.oneshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.theme.PanelShape

/**
 * "Oneshot" tab — hand the agent a task and walk away. The run is held on the server by the
 * Dispatch Bridge and reports back over ntfy, so results arrive as notifications rather than
 * requiring this screen to stay open.
 */
@Composable
fun OneshotScreen(
    onOpenPalette: () -> Unit = {},
    vm: OneshotViewModel = hiltViewModel(),
    promptStore: com.hermes.client.data.repository.PromptStore =
        com.hermes.client.data.repository.PromptStore(androidx.compose.ui.platform.LocalContext.current),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showConfig by remember { mutableStateOf(false) }
    val savedPrompts by promptStore.prompts.collectAsStateWithLifecycle(initialValue = emptyList())
    // Mic gate: ask once, start recording as soon as it's granted.
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun toggleMic() {
        when {
            state.recording -> vm.stopAndTranscribe()
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED -> vm.startRecording()
            else -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Oneshot",
                subtitle = if (state.configured) "Replies arrive via ntfy" else "Bridge not configured",
                actions = {
                    IconButton(onClick = onOpenPalette) {
                        Icon(Icons.Rounded.Search, contentDescription = "Command palette")
                    }
                    IconButton(onClick = { showConfig = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Bridge settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            state.todayTokens?.let { tokens ->
                Text(
                    "TODAY  ${formatTokens(tokens)} tok" +
                        (state.todayCost?.let { "  ·  $" + "%.2f".format(it) } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (!state.configured) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = PanelShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Bridge not configured", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Set the Dispatch Bridge URL and token to send tasks.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { showConfig = true }) { Text("Configure") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (savedPrompts.isNotEmpty()) {
                Text("QUICK", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(4.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    savedPrompts.take(8).forEach { p ->
                        androidx.compose.material3.AssistChip(
                            onClick = { vm.onPromptChanged(p.body) },
                            label = { Text(p.title, maxLines = 1) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = state.prompt,
                onValueChange = vm::onPromptChanged,
                label = { Text("What should the agent do?") },
                placeholder = { Text("Audit the nginx configs and report anything stale") },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                enabled = state.configured && !state.sending,
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.FilledTonalIconButton(
                    onClick = { toggleMic() },
                    enabled = !state.transcribing,
                ) {
                    when {
                        state.transcribing -> CircularProgressIndicator(Modifier.height(18.dp))
                        state.recording -> Icon(Icons.Rounded.Stop, contentDescription = "Stop and transcribe")
                        else -> Icon(Icons.Rounded.Mic, contentDescription = "Dictate")
                    }
                }
                Spacer(Modifier.height(0.dp))
                Text(
                    if (state.recording) "  Listening… tap to stop  " else "  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { vm.send() },
                    enabled = state.configured && state.prompt.isNotBlank() && !state.sending,
                ) {
                    if (state.sending) {
                        CircularProgressIndicator(Modifier.height(18.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        Spacer(Modifier.height(0.dp))
                        Text("  Dispatch")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            state.lastTaskId?.let {
                val scheduled = it.startsWith("scheduled")
                Text(
                    if (scheduled) "Recognised as recurring — $it. Manage it under Brain → Scheduled jobs."
                    else "Dispatched — task $it. Progress and the result arrive as ntfy notifications.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showConfig) {
        BridgeConfigDialog(
            initialUrl = state.bridgeUrl,
            initialToken = state.bridgeToken,
            onSave = { url, token -> vm.saveConfig(url, token); showConfig = false },
            onDismiss = { showConfig = false },
        )
    }
}

@Composable
private fun BridgeConfigDialog(
    initialUrl: String,
    initialToken: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl.ifBlank { "http://192.168.0.127:8099" }) }
    var token by remember { mutableStateOf(initialToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dispatch Bridge") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Bridge URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bridge token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, token) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
