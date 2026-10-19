package com.hermes.client.ui.matrix

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.matrix.MatrixState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixAccountScreen(
    onBack: () -> Unit,
    vm: MatrixAccountViewModel = hiltViewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                vm.onCaPicked(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } catch (e: Exception) {
                vm.onCaFailed(e.message ?: "Could not read the file")
            }
        }
    }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Matrix account",
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = ui.account
            if (s is MatrixState.Ready) {
                SignedIn(s, ui.roomCount, onLogout = vm::logout)
            } else {
                LoginForm(ui, vm, onPickCa = { picker.launch(arrayOf("*/*")) })
            }
        }
    }
}

@Composable
private fun SignedIn(s: MatrixState.Ready, roomCount: Int, onLogout: () -> Unit) {
    Text("Signed in", style = MaterialTheme.typography.titleMedium)
    Text("User ID: ${s.userId}")
    Text("Device ID: ${s.deviceId}")
    Text("Homeserver: ${s.homeserverUrl}")
    Text("Bot: ${s.botMxid.ifBlank { "(none)" }}")
    Text("Rooms: $roomCount")
    OutlinedButton(onClick = onLogout) { Text("Log out") }
}

@Composable
private fun LoginForm(ui: MatrixAccountUiState, vm: MatrixAccountViewModel, onPickCa: () -> Unit) {
    val f = ui.form
    (ui.account as? MatrixState.RestoreFailed)?.let { failed ->
        Text("Could not restore the saved session. It is kept, so you can retry.", style = MaterialTheme.typography.titleSmall)
        Text(failed.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (failed.retryable) Button(onClick = vm::retryRestore) { Text("Retry") }
            OutlinedButton(onClick = vm::logout) { Text("Discard saved session") }
        }
    }
    val busy = ui.account is MatrixState.LoggingIn || ui.account is MatrixState.Restoring
    Text(
        "Log in to your Matrix homeserver to use Hermes over Matrix. Your password is sent to the " +
            "server once and never stored; only the session is kept (encrypted).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = f.homeserver,
        onValueChange = vm::onHomeserver,
        label = { Text("Homeserver URL (https://…)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = f.username,
        onValueChange = vm::onUsername,
        label = { Text("Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = f.password,
        onValueChange = vm::onPassword,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = f.botMxid,
        onValueChange = vm::onBotMxid,
        label = { Text("Bot MXID (@bot:example.org)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPickCa) { Text("Import CA certificate") }
        if (f.caBytes != null) OutlinedButton(onClick = vm::clearCa) { Text("Remove") }
    }
    Text(
        f.caSummary ?: "No CA imported: the system trust store is used.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    f.caError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = vm::login,
        enabled = !busy && f.homeserver.isNotBlank() && f.username.isNotBlank() && f.password.isNotEmpty(),
    ) { Text("Log in") }
    val status = when (ui.account) {
        MatrixState.LoggingIn -> "Logging in…"
        MatrixState.Restoring -> "Restoring session…"
        else -> null
    }
    status?.let { Text(it) }
    ui.loginError?.takeIf { !busy }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
