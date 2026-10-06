package com.hermes.client.ui.netops

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.theme.Neon

/**
 * NetOps board — TCP reachability of services the user owns, checked from the phone. The
 * headline of the "Tools" section: a defensive homelab console, not a scanner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetOpsScreen(onBack: () -> Unit, vm: NetOpsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    // Probe on first open so the board isn't a wall of greys.
    LaunchedEffect(Unit) { vm.checkAll() }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "NetOps",
                subtitle = "Reachability from this phone",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.checkAll() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Re-check all")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Add a target")
            }
        },
    ) { padding ->
        if (state.targets.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text(
                    "No targets yet. Connect a gateway, or add one with +.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.targets, key = { it.label + it.host + it.port }) { t ->
                    ListItem(
                        leadingContent = { StatusLamp(t.status) },
                        headlineContent = { Text(t.label) },
                        supportingContent = {
                            Text(
                                "${t.host}:${t.port}" + (t.latencyMs?.let { "  ·  ${it}ms" } ?: ""),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = if (!t.builtin) ({
                            IconButton(onClick = { vm.removeTarget(t) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Remove")
                            }
                        }) else null,
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAdd) {
        AddTargetDialog(
            onAdd = { label, host, port -> vm.addTarget(label, host, port); showAdd = false },
            onDismiss = { showAdd = false },
        )
    }
}

@Composable
private fun StatusLamp(status: Reach) {
    if (status == Reach.CHECKING) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        return
    }
    val color = when (status) {
        Reach.UP -> Neon.Phosphor
        Reach.DOWN -> Neon.Red
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(14.dp).clip(CircleShape).background(color))
}

@Composable
private fun AddTargetDialog(onAdd: (String, String, Int) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    val portNum = port.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a target") },
        text = {
            Column {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("Label (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("Host or IP") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (host.isNotBlank() && portNum != null) onAdd(label, host, portNum) },
                enabled = host.isNotBlank() && portNum != null && portNum in 1..65535,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
