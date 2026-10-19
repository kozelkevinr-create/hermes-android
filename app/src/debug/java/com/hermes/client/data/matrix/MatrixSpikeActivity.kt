package com.hermes.client.data.matrix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.hermes.client.data.diagnostics.DebugLog
import com.hermes.client.ui.theme.HermesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Debug-only screen for exercising matrix-rust-sdk on a real device (see the Matrix spike design doc). */
class MatrixSpikeActivity : ComponentActivity() {
    private lateinit var spike: MatrixSpike

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.setEnabled(true)
        spike = MatrixSpike(this)
        setContent { HermesTheme { Surface { SpikeScreen(spike) } } }
    }

    override fun onDestroy() {
        spike.stop()
        super.onDestroy()
    }
}

/** Class name + message of the throwable and every cause, one per line (SDK errors nest). */
internal fun errorChain(e: Throwable): String =
    generateSequence(e) { it.cause?.takeIf { c -> c !== it } }
        .take(8)
        .joinToString("\n  caused by: ") { "${it::class.qualifiedName}: ${it.message ?: it}" }

@Composable
private fun SpikeScreen(spike: MatrixSpike) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var homeserver by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var bot by remember { mutableStateOf("") }
    var encrypted by remember { mutableStateOf(true) }
    var caBytes by remember { mutableStateOf<ByteArray?>(null) }
    var caName by remember { mutableStateOf("none (system roots only)") }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                CertificateBytes.parse(bytes) // validate early
                caBytes = bytes
                caName = "${uri.lastPathSegment} (${bytes.size} bytes)"
            }.onFailure { error = it.message }
        }
    }

    fun run(block: suspend () -> Unit) {
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val chain = DebugLog.redact(errorChain(e))
                DebugLog.log("matrix-error", chain)
                android.util.Log.e("MatrixSpike", chain)
                error = chain
            }
        }
    }

    Column(
        Modifier.statusBarsPadding().navigationBarsPadding().imePadding().padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Matrix spike (debug)", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            homeserver, { homeserver = it }, Modifier.fillMaxWidth(),
            label = { Text("Homeserver URL (http:// or https://)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
        OutlinedTextField(
            password, { password = it }, Modifier.fillMaxWidth(),
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text("Pick CA certificate") }
            Text(caName, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            bot, { bot = it }, Modifier.fillMaxWidth(),
            label = { Text("Bot MXID") }, singleLine = true,
        )
        Button({
            val pw = password
            password = ""
            run { spike.loginAndListRooms(homeserver, user, pw, caBytes) }
        }) { Text("Login + list rooms") }

        Text(spike.status, style = MaterialTheme.typography.bodyMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // Right under the status so it is visible without scrolling to the bottom.
        Text(
            "Timeline (${spike.timelineLines.size} items, ${spike.timelineCallbacks} updates)",
            style = MaterialTheme.typography.titleMedium,
        )
        spike.timelineLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }

        spike.rooms.forEach { r ->
            Text(
                "${r.name}  |  ${if (r.encrypted) "encrypted" else "plain"}  |  unread ${r.unread}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Encrypted")
            Switch(encrypted, { encrypted = it })
        }
        Button({ run { spike.createDmAndPing(bot, encrypted) } }) { Text("Create DM with bot") }
    }
}
