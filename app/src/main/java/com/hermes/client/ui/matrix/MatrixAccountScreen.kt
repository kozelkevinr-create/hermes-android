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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.matrix.MatrixSasEmoji
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.matrix.MatrixBackupPhase
import com.hermes.client.data.matrix.MatrixRecoveryPhase
import com.hermes.client.ui.components.HermesTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixAccountScreen(
    onBack: () -> Unit,
    vm: MatrixAccountViewModel = hiltViewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val verificationVm: MatrixVerificationViewModel = hiltViewModel()
    val sheetOpen by verificationVm.sheetOpen.collectAsStateWithLifecycle()
    val verificationUi by verificationVm.ui.collectAsStateWithLifecycle()
    val recoveryVm: MatrixRecoveryViewModel = hiltViewModel()
    val recoveryOpen by recoveryVm.sheetOpen.collectAsStateWithLifecycle()
    val recoveryUi by recoveryVm.ui.collectAsStateWithLifecycle()
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
                SignedIn(
                    s,
                    ui.roomCount,
                    onLogout = vm::logout,
                    onVerify = { verificationVm.openSheet() },
                    onRecovery = { recoveryVm.openSheet() },
                )
            } else {
                LoginForm(ui, vm, onPickCa = { picker.launch(arrayOf("*/*")) })
            }
        }
    }
    if (sheetOpen) {
        VerificationSheet(
            ui = verificationUi,
            botMxid = (ui.account as? MatrixState.Ready)?.botMxid.orEmpty(),
            onSelfSession = verificationVm::requestDeviceVerification,
            onBot = { verificationVm.requestUserVerification((ui.account as? MatrixState.Ready)?.botMxid.orEmpty()) },
            onAccept = verificationVm::acceptIncoming,
            onDeclineIncoming = verificationVm::declineIncoming,
            onApprove = verificationVm::approve,
            onDeclineCodes = verificationVm::decline,
            onCancel = verificationVm::cancelFlow,
            onReset = verificationVm::reset,
            onDismiss = verificationVm::dismissSheet,
        )
    }
    if (recoveryOpen) {
        RecoverySheet(
            ui = recoveryUi,
            onEnable = recoveryVm::enable,
            onConfirmKey = recoveryVm::confirmKeyShown,
            onCopyKey = { context.copyText((recoveryUi as? RecoveryUi.EnabledKey)?.key.orEmpty()) },
            onRecover = recoveryVm::recover,
            onReset = recoveryVm::reset,
            onRecreate = recoveryVm::recreate,
            onDisable = recoveryVm::disable,
            onTryAgain = recoveryVm::tryAgain,
            onDismiss = recoveryVm::dismissSheet,
        )
    }
}

/** Copies plain text to the system clipboard; used for the one-time recovery key. */
private fun android.content.Context.copyText(text: String) {
    @Suppress("DEPRECATION")
    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Recovery key", text))
}

@Composable
private fun SignedIn(
    s: MatrixState.Ready,
    roomCount: Int,
    onLogout: () -> Unit,
    onVerify: () -> Unit,
    onRecovery: () -> Unit,
) {
    Text("Signed in", style = MaterialTheme.typography.titleMedium)
    Text("User ID: ${s.userId}")
    Text("Device ID: ${s.deviceId}")
    Text("Homeserver: ${s.homeserverUrl}")
    Text("Bot: ${s.botMxid.ifBlank { "(none)" }}")
    Text("Rooms: $roomCount")
    OutlinedButton(onClick = onVerify) { Text("Verify device…") }
    OutlinedButton(onClick = onRecovery) { Text("Recovery…") }
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

/**
 * The SAS verification sheet: pick a partner (own other session, or the bot's devices), answer
 * an incoming request, compare the emoji short code, or see how the flow settled.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun VerificationSheet(
    ui: VerificationUi,
    botMxid: String,
    onSelfSession: () -> Unit,
    onBot: () -> Unit,
    onAccept: () -> Unit,
    onDeclineIncoming: () -> Unit,
    onApprove: () -> Unit,
    onDeclineCodes: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(verificationHeadline(ui), style = MaterialTheme.typography.titleMedium)
            when (ui) {
                VerificationUi.Idle -> {
                    Text(
                        "Compare a short code with the other device to prove the connection. Do " +
                            "this with one of your own sessions, or with the declared bot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onSelfSession, modifier = Modifier.fillMaxWidth()) {
                        Text("Verify with another session")
                    }
                    if (botMxid.isNotBlank()) {
                        Button(onClick = onBot, modifier = Modifier.fillMaxWidth()) {
                            Text("Verify with $botMxid")
                        }
                    }
                }
                is VerificationUi.Incoming -> {
                    Text(
                        "\u201C${ui.deviceName.ifBlank { "(unnamed device)" }}\u201D wants to verify " +
                            "with this device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAccept) { Text("Accept") }
                        OutlinedButton(onClick = onDeclineIncoming) { Text("Decline") }
                    }
                }
                VerificationUi.WaitingAccept -> {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text(
                        "The request is out. Once the other side accepts, the short code appears.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                VerificationUi.WaitingEmojis -> {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                }
                is VerificationUi.Emojis -> {
                    EmojiGrid(ui.emojis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onApprove) { Text("They match") }
                        OutlinedButton(onClick = onDeclineCodes) { Text("They don't match") }
                    }
                }
                VerificationUi.Verified -> {
                    Text(
                        "The other side is trusted now.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismiss) { Text("Done") }
                }
                is VerificationUi.Cancelled -> {
                    ui.cancelCode?.let {
                        Text(
                            "($it)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = onDismiss) { Text("Done") }
                    OutlinedButton(onClick = onReset) { Text("Try again") }
                }
                VerificationUi.Failed -> {
                    Text(
                        "Something went wrong. You can start over.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onReset) { Text("Try again") }
                    OutlinedButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

/** The SAS short code: up to 7 emojis, symbol first, name beneath, in rows of up to 4. */
@Composable
private fun EmojiGrid(emojis: List<MatrixSasEmoji>) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        emojis.chunked(4).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                row.forEach { emoji ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(emoji.symbol, style = MaterialTheme.typography.headlineLarge)
                        Text(
                            emoji.description,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The recovery sheet: the account's key-backup status, setup with a one-time key reveal,
 * restore from a pasted key, and teardown.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun RecoverySheet(
    ui: RecoveryUi,
    onEnable: () -> Unit,
    onConfirmKey: () -> Unit,
    onCopyKey: () -> Unit,
    onRecover: (String) -> Unit,
    onReset: () -> Unit,
    onRecreate: () -> Unit,
    onDisable: () -> Unit,
    onTryAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(recoveryHeadline(ui), style = MaterialTheme.typography.titleMedium)
            when (ui) {
                is RecoveryUi.Status -> {
                    Text(
                        "Key backup: ${recoveryPhaseLabel(ui.recovery)} · engine: ${backupPhaseLabel(ui.backup)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    when {
                        ui.recovery == MatrixRecoveryPhase.INCOMPLETE -> {
                            var pasted by remember { mutableStateOf("") }
                            var confirming by remember { mutableStateOf(false) }
                            Text(
                                "Recovery is set up, but this device is missing keys. Paste the " +
                                    "recovery key from when recovery was created, or finish the " +
                                    "backup here if this device already holds the secret (for " +
                                    "example right after a reset).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = { pasted = it },
                                label = { Text("Recovery key") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { onRecover(pasted) },
                                enabled = recoveryKeyIsPlausible(pasted),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Restore keys") }
                            OutlinedButton(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                                Text("Enable backup on this device")
                            }
                            if (!confirming) {
                                OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("I don't have my recovery key")
                                }
                            } else {
                                Text(
                                    "Resetting replaces the account's recovery: sessions holding " +
                                        "the old key lose their backup access, and you get a fresh " +
                                        "key. Other devices' verified-trust view is reset too.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Button(
                                    onClick = { onReset() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Yes, reset recovery") }
                                OutlinedButton(onClick = { confirming = false }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Keep as is")
                                }
                            }
                        }
                        ui.recovery == MatrixRecoveryPhase.ENABLED -> {
                            Text(
                                "The account's keys are backed up; a fresh session restores them " +
                                    "with its recovery key.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = onDisable, modifier = Modifier.fillMaxWidth()) {
                                Text("Turn off recovery")
                            }
                        }
                        else -> {
                            Text(
                                "Back this device's keys up to the server, protected by a single " +
                                    "recovery key. Store that key securely — this is the only time " +
                                    "it is shown.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                                Text("Enable recovery")
                            }
                        }
                    }
                }
                RecoveryUi.Enabling -> {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text(
                        "Creating the backup and uploading this device's keys; this can take a " +
                            "while, keep the app open.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is RecoveryUi.EnabledKey -> {
                    Text(
                        "This is the only time the key is shown. Store it outside the device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        ui.key,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onCopyKey) { Text("Copy") }
                        Button(onClick = { onCopyKey(); onConfirmKey() }) { Text("Done") }
                    }
                    Text(
                        "Done also copies the key, so a slip here does not lose it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecoveryUi.StaleBackup -> {
                    Text(
                        "The server still holds a key backup created with a recovery key this " +
                            "device does not have. Deleting that backup discards its keys — " +
                            "they cannot be read without the lost key — and then a fresh backup " +
                            "is set up with the key this device holds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onRecreate, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete the old backup and set up a new one")
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
                RecoveryUi.Recreating -> {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text(
                        "Deleting the old backup and building a new one; this can take a while.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecoveryUi.Recovering -> {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text(
                        "Downloading and importing the backed-up keys.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecoveryUi.Resetting -> {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text(
                        "Replacing the account's recovery setup; the new key appears once it " +
                            "finishes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecoveryUi.Recovered -> {
                    Text(
                        "The backed-up keys are restored; this session decrypts the old history " +
                            "again.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismiss) { Text("Done") }
                }
                RecoveryUi.Disabling -> {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    Text(
                        "Removing the backup from the server.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RecoveryUi.EnablingFailed, RecoveryUi.RecoverFailed, RecoveryUi.ResetFailed,
                RecoveryUi.RecreatingFailed, RecoveryUi.DisableFailed,
                -> {
                    Text(
                        "That did not work. Check the connection and try again; nothing was " +
                            "changed half-way.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onTryAgain, modifier = Modifier.fillMaxWidth()) {
                        Text("Try again")
                    }
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                        Text("Close")
                    }
                }
                RecoveryUi.Unavailable -> {
                    Text(
                        "There is no live session; sign in first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
