package com.hermes.client.ui.profiles
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.nav.ShellViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    onMenu: () -> Unit,
    vm: ShellViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val renameError by vm.renameError.collectAsStateWithLifecycle()
    // Profile currently being renamed (null = no dialog open).
    var renaming by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    var renamingLabel by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("")
    }
    // Profile whose accent colour is being edited (null = no picker open).
    var pickingAccentFor by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val accentOverrides = com.hermes.client.ui.theme.LocalProfileAccentOverrides.current

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Profiles",
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(profiles, key = { it.name }) { p ->
                ListItem(
                    headlineContent = { Text(p.label) },
                    supportingContent = {
                        Text(
                            when {
                                p.name == active -> "Active"
                                p.isDefault -> "Default"
                                else -> "Tap to switch"
                            },
                        )
                    },
                    trailingContent = if (p.name == active) ({
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Rounded.CheckCircle,
                            contentDescription = "Active",
                            tint = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        )
                    }) else null,
                    modifier = Modifier.clickable { vm.switchProfile(p.name) },
                )
                androidx.compose.material3.TextButton(
                    // Rename targets the canonical id; the dialog shows the current label.
                    onClick = { renaming = p.name; renamingLabel = p.label },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Rename") }
            }
        }
    }

    pickingAccentFor?.let { target ->
        com.hermes.client.ui.nav.AccentColorDialog(
            profile = target,
            selected = accentOverrides[target],
            onPick = { argb -> vm.setAccent(target, argb); pickingAccentFor = null },
            onAuto = { vm.clearAccent(target); pickingAccentFor = null },
            onDismiss = { pickingAccentFor = null },
        )
    }

    renaming?.let { target ->
        RenameProfileDialog(
            current = target,
            currentLabel = renamingLabel,
            error = renameError,
            onConfirm = { newName -> vm.renameProfile(target, newName); renaming = null },
            onDismiss = { renaming = null; vm.clearRenameError() },
        )
    }
}

@Composable
private fun RenameProfileDialog(
    current: String,
    currentLabel: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(currentLabel) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename profile") },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Profile name") },
                    singleLine = true,
                )
                if (current == "default") {
                    Text(
                        "The default profile keeps its internal id; this sets the display name.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        "Renaming moves the profile directory and briefly restarts the gateway.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                error?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank() && text != currentLabel,
            ) { Text("Rename") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}