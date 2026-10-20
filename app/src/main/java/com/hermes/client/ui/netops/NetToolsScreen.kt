package com.hermes.client.ui.netops

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.shizuku.ShizukuManager
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.theme.PanelShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetToolsScreen(onBack: () -> Unit, vm: NetToolsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dnsHost by remember { mutableStateOf("") }
    var httpUrl by remember { mutableStateOf("") }
    // Re-read Shizuku status on each recomposition trigger (cheap binder ping).
    var shizukuTick by remember { mutableStateOf(0) }
    val shizukuStatus = remember(shizukuTick) { ShizukuManager.status() }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Net Tools",
                subtitle = "Diagnostics from this phone",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SectionLabel("PROBES")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.myNetwork() }) { Text("My network") }
                FilledTonalButton(onClick = { vm.subnetSweep(80) }) { Text("Sweep :80") }
                FilledTonalButton(onClick = { vm.subnetSweep(443) }) { Text("Sweep :443") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = dnsHost, onValueChange = { dnsHost = it },
                    label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { if (dnsHost.isNotBlank()) vm.dnsLookup(dnsHost) }) { Text("DNS") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = httpUrl, onValueChange = { httpUrl = it },
                    label = { Text("URL") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { if (httpUrl.isNotBlank()) vm.httpCheck(httpUrl) }) { Text("HTTP/TLS") }
            }

            if (state.running || state.output.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = PanelShape, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        if (state.running) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.height(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("running…", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Text(
                            state.output.ifBlank { " " },
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (state.output.isNotBlank() && !state.running) {
                            androidx.compose.material3.TextButton(onClick = { vm.clear() }) { Text("Clear") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            SectionLabel("WIRELESS DEBUGGING (via Shizuku)")
            when (shizukuStatus) {
                ShizukuManager.Status.UNAVAILABLE -> Text(
                    "Shizuku isn't running. Start the Shizuku app (it needs restarting after each " +
                        "reboot on a non-rooted phone), then come back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ShizukuManager.Status.NEEDS_PERMISSION -> Button(onClick = {
                    ShizukuManager.requestPermission(); shizukuTick++
                }) { Text("Grant Shizuku access") }
                ShizukuManager.Status.READY -> {
                    val on = remember(shizukuTick) { ShizukuManager.isWirelessDebuggingOn() }
                    Text(
                        "Wireless debugging: ${if (on == true) "ON" else if (on == false) "off" else "unknown"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ShizukuManager.setWirelessDebugging(true); shizukuTick++ }) { Text("Enable") }
                        OutlinedButton(onClick = { ShizukuManager.setWirelessDebugging(false); shizukuTick++ }) { Text("Disable") }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            SectionLabel("SPECIALIST TOOLS")
            Text(
                "Capture or scan in these, then Share → Hermes Local to send the result to the agent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EXTERNAL_TOOLS.forEach { tool ->
                val installed = remember(tool.pkg) { isInstalled(context, tool.pkg) }
                ListItem(
                    headlineContent = { Text(tool.label) },
                    supportingContent = { Text(tool.blurb, style = MaterialTheme.typography.bodySmall) },
                    trailingContent = {
                        AssistChip(
                            onClick = { launchOrStore(context, tool) },
                            label = { Text(if (installed) "Open" else "Install") },
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}
