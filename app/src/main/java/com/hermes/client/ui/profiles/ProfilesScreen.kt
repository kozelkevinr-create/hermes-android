package com.hermes.client.ui.profiles
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
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
    onEdit: (String) -> Unit,
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
    var creating by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val createError by vm.createError.collectAsStateWithLifecycle()
    val accentOverrides = com.hermes.client.ui.theme.LocalProfileAccentOverrides.current

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Profiles",
                navigationIcon = { IconButton(onClick = onMenu) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New profile") },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(profiles, key = { it.name }) { p ->
                ListItem(
                    leadingContent = {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(20.dp)
                                .background(
                                    com.hermes.client.ui.theme.rememberProfileAccent(
                                        p.name, androidx.compose.foundation.isSystemInDarkTheme(),
                                    ).accent,
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                        )
                    },
                    headlineContent = { Text(p.label) },
                    supportingContent = {
                        Text(
                            when {
                                p.name == active -> "Active · default for new sessions"
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
                androidx.compose.foundation.layout.Row(Modifier.padding(start = 8.dp)) {
                androidx.compose.material3.TextButton(onClick = { onEdit(p.name) }) { Text("Edit") }
                androidx.compose.material3.TextButton(onClick = { pickingAccentFor = p.name }) { Text("Color") }
                androidx.compose.material3.TextButton(
                    // Rename targets the canonical id; the dialog shows the current label.
                    onClick = { renaming = p.name; renamingLabel = p.label },
                ) { Text("Rename") }
                }
            }
        }
    }

    if (creating) {
        NewProfileDialog(
            existing = profiles.map { it.name },
            loadSkills = vm::availableSkills,
            loadModels = vm::modelProviders,
            error = createError,
            onCreate = { name, from, desc, skills, model -> vm.createProfile(name, from, desc, skills, model?.first, model?.second) { creating = false; onEdit(it) } },
            onDismiss = { creating = false; vm.clearCreateError() },
        )
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

@Composable
private fun NewProfileDialog(
    existing: List<String>,
    error: String?,
    loadSkills: suspend () -> List<String>,
    loadModels: suspend () -> List<com.hermes.client.data.network.ModelProviderDto>,
    onCreate: (name: String, cloneFrom: String?, description: String, keepSkills: List<String>, model: Pair<String, String>?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var description by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var cloneFrom by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // null = keep every skill (the default); otherwise only the ticked ones start enabled.
    var chosen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Set<String>?>(null) }
    var allSkills by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }
    var pickSkills by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pickSkills) { if (pickSkills && allSkills.isEmpty()) allSkills = loadSkills() }
    var model by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null) }
    var providers by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.hermes.client.data.network.ModelProviderDto>>(emptyList()) }
    var pickingModel by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pickingModel) { if (pickingModel && providers.isEmpty()) providers = loadModels() }
    // Profile ids become directory names and CLI aliases.
    val valid = Regex("[a-z0-9][a-z0-9_-]{0,63}").matches(name)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New profile") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase() },
                    label = { Text("Name") },
                    supportingText = { Text("Lowercase letters, digits, - and _") },
                    isError = name.isNotEmpty() && !valid,
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                )
                Text("Start from", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                (listOf<String?>(null) + existing).forEach { src ->
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.clickable { cloneFrom = src },
                    ) {
                        androidx.compose.material3.RadioButton(selected = cloneFrom == src, onClick = { cloneFrom = src })
                        Text(src?.let { "Copy of $it" } ?: "Blank")
                    }
                }
                androidx.compose.material3.OutlinedButton(onClick = { pickingModel = true }) {
                    Text(model?.let { "${it.first} / ${it.second}" } ?: "Choose default model (optional)")
                }
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        pickSkills = !pickSkills
                        chosen = if (pickSkills) (chosen ?: emptySet()) else null
                    },
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = pickSkills,
                        onCheckedChange = { pickSkills = it; chosen = if (it) (chosen ?: emptySet()) else null },
                    )
                    Text("Choose which skills to start with")
                }
                if (pickSkills) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.height(180.dp)) {
                        items(allSkills, key = { it }) { sk ->
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    val c = chosen ?: emptySet()
                                    chosen = if (sk in c) c - sk else c + sk
                                },
                            ) {
                                androidx.compose.material3.Checkbox(checked = sk in (chosen ?: emptySet()), onCheckedChange = null)
                                Text(sk, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Text(
                        "${chosen?.size ?: 0} of ${allSkills.size} selected",
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onCreate(name, cloneFrom, description.trim(), chosen?.toList().orEmpty(), model) },
                // The gateway reads an empty keep-list as "keep everything", so require a pick.
                enabled = valid && (!pickSkills || !chosen.isNullOrEmpty()),
            ) { Text("Create") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickingModel) {
        ModelPickerDialog(
            providers = providers,
            onPick = { provider, m -> model = provider to m; pickingModel = false },
            onDismiss = { pickingModel = false },
        )
    }
}
