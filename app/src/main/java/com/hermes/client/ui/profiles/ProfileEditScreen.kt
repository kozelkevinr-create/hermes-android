package com.hermes.client.ui.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.network.McpCatalogEntryDto
import com.hermes.client.data.network.McpServerDto
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.network.ToolsetDto
import com.hermes.client.data.network.SkillDto
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileEditState(
    val loading: Boolean = true,
    val description: String = "",
    val soul: String = "",
    val savedDescription: String = "",
    val savedSoul: String = "",
    val saving: Boolean = false,
    val message: String? = null,
    val deleted: Boolean = false,
    val skills: List<SkillDto> = emptyList(),
    val skillQuery: String = "",
    val providers: List<ModelProviderDto> = emptyList(),
    val tools: List<ToolsetDto> = emptyList(),
    val mcp: List<McpServerDto> = emptyList(),
    val mcpError: String? = null,
    val catalog: List<McpCatalogEntryDto>? = null,
    /** Error from the last add, delete or install, shown inside its dialog. */
    val mcpActionError: String? = null,
    val toolsError: String? = null,
) {
    val shownSkills: List<SkillDto>
        get() = if (skillQuery.isBlank()) skills
        else skills.filter { it.name.contains(skillQuery, true) || it.description?.contains(skillQuery, true) == true }

    val dirty: Boolean get() = description != savedDescription || soul != savedSoul
}

@HiltViewModel
class ProfileEditViewModel @Inject constructor(
    private val rest: HermesRestApi,
    private val profileManager: ProfileManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val name: String = savedStateHandle.get<String>("name").orEmpty()
    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val soul = runCatching { rest.profileSoul(name) }
        val skills = runCatching { rest.skills(name) }.getOrDefault(emptyList()).sortedBy { it.name }
        val toolsResult = runCatching { rest.toolsets(name) }
        val tools = toolsResult.getOrDefault(emptyList()).sortedBy { it.label ?: it.name }
        val mcpResult = runCatching { rest.mcpServers(name) }
        val mcp = mcpResult.getOrDefault(emptyList())
        val description = profileManager.list.value.firstOrNull { it.name == name }?.description.orEmpty()
        _state.value = ProfileEditState(
            loading = false,
            description = description, savedDescription = description,
            soul = soul.getOrDefault(""), savedSoul = soul.getOrDefault(""),
            skills = skills, tools = tools, mcp = mcp,
            toolsError = toolsResult.exceptionOrNull()?.message ?: "Could not load tools".takeIf { toolsResult.isFailure },
            mcpError = mcpResult.exceptionOrNull()?.let { "Could not load MCP servers: ${it.message}" },
            message = soul.exceptionOrNull()?.let { "Could not load SOUL.md: ${it.message}" },
        )
    }

    fun onSkillQuery(q: String) { _state.value = _state.value.copy(skillQuery = q) }

    /** Flip a skill for this profile right away; put it back if the gateway refuses. */
    fun toggleSkill(skill: SkillDto, enabled: Boolean) = viewModelScope.launch {
        fun set(on: Boolean) {
            _state.value = _state.value.copy(skills = _state.value.skills.map { if (it.name == skill.name) it.copy(enabled = on) else it })
        }
        set(enabled)
        runCatching { rest.toggleSkill(skill.name, enabled, name) }.onFailure {
            set(!enabled)
            _state.value = _state.value.copy(message = "Could not change ${skill.name}: ${it.message}")
        }
    }

    fun toggleTool(tool: ToolsetDto, enabled: Boolean) = viewModelScope.launch {
        fun set(on: Boolean) {
            _state.value = _state.value.copy(tools = _state.value.tools.map { if (it.name == tool.name) it.copy(enabled = on) else it })
        }
        set(enabled)
        runCatching { rest.toggleToolset(tool.name, enabled, name) }.onFailure {
            set(!enabled)
            _state.value = _state.value.copy(message = "Could not change ${tool.label ?: tool.name}: ${it.message}")
        }
    }

    fun toggleMcp(server: McpServerDto, enabled: Boolean) = viewModelScope.launch {
        fun set(on: Boolean) {
            _state.value = _state.value.copy(mcp = _state.value.mcp.map { if (it.name == server.name) it.copy(enabled = on) else it })
        }
        set(enabled)
        runCatching { rest.toggleMcpServer(server.name, enabled, name) }.onFailure {
            set(!enabled)
            _state.value = _state.value.copy(message = "Could not change ${server.name}: ${it.message}")
        }
    }

    private suspend fun reloadMcp() {
        runCatching { rest.mcpServers(name) }.onSuccess { _state.value = _state.value.copy(mcp = it, mcpError = null) }
    }

    /** Add a server; [onDone] runs only when the gateway accepted it. */
    fun addMcp(
        serverName: String,
        url: String?,
        command: String?,
        args: List<String>,
        env: Map<String, String>,
        onDone: () -> Unit,
    ) = viewModelScope.launch {
        _state.value = _state.value.copy(mcpActionError = null)
        runCatching { rest.addMcpServer(name, serverName, url, command, args, env) }
            .onSuccess { reloadMcp(); _state.value = _state.value.copy(message = "$serverName added"); onDone() }
            .onFailure { _state.value = _state.value.copy(mcpActionError = it.message ?: "Could not add server") }
    }

    fun deleteMcp(server: McpServerDto) = viewModelScope.launch {
        runCatching { rest.deleteMcpServer(server.name, name) }
            .onSuccess { reloadMcp(); _state.value = _state.value.copy(message = "${server.name} removed") }
            .onFailure { _state.value = _state.value.copy(message = "Could not remove ${server.name}: ${it.message}") }
    }

    fun loadCatalog() = viewModelScope.launch {
        _state.value = _state.value.copy(mcpActionError = null)
        runCatching { rest.mcpCatalog(name) }
            .onSuccess { _state.value = _state.value.copy(catalog = it) }
            .onFailure { _state.value = _state.value.copy(catalog = emptyList(), mcpActionError = "Could not load catalog: ${it.message}") }
    }

    fun installCatalog(entry: McpCatalogEntryDto, env: Map<String, String>, onDone: () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(mcpActionError = null)
        runCatching { rest.installMcpCatalogEntry(name, entry.name, env) }
            .onSuccess {
                reloadMcp(); loadCatalog()
                _state.value = _state.value.copy(message = "${entry.name} installed"); onDone()
            }
            .onFailure { _state.value = _state.value.copy(mcpActionError = it.message ?: "Install failed") }
    }

    fun clearMcpActionError() { _state.value = _state.value.copy(mcpActionError = null) }

    /** Load the provider and model catalog for the picker. */
    fun loadModels() = viewModelScope.launch {
        if (_state.value.providers.isEmpty()) {
            runCatching { rest.modelProviders() }.onSuccess { _state.value = _state.value.copy(providers = it) }
        }
    }

    fun setModel(provider: String, model: String) = viewModelScope.launch {
        runCatching { rest.setProfileModel(name, provider, model) }
            .onSuccess { profileManager.refresh(); _state.value = _state.value.copy(message = "Default model set to $model") }
            .onFailure { _state.value = _state.value.copy(message = "Could not set model: ${it.message}") }
    }

    /** Make this the sticky default profile (what new sessions and the CLI use). */
    fun makeDefault() = viewModelScope.launch { profileManager.switchTo(name) }

    val activeProfile get() = profileManager.active
    val profileList get() = profileManager.list

    fun onDescription(v: String) { _state.value = _state.value.copy(description = v) }
    fun onSoul(v: String) { _state.value = _state.value.copy(soul = v) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        _state.value = s.copy(saving = true)
        val result = runCatching {
            if (s.description != s.savedDescription) rest.setProfileDescription(name, s.description.trim())
            if (s.soul != s.savedSoul) rest.setProfileSoul(name, s.soul)
        }
        result.onSuccess { profileManager.refresh() }
        _state.value = _state.value.let {
            if (result.isSuccess) it.copy(saving = false, savedDescription = s.description, savedSoul = s.soul, message = "Saved")
            else it.copy(saving = false, message = "Save failed: ${result.exceptionOrNull()?.message}")
        }
    }

    fun delete() = viewModelScope.launch {
        runCatching { rest.deleteProfile(name) }
            .onSuccess { profileManager.refresh(); _state.value = _state.value.copy(deleted = true) }
            .onFailure { _state.value = _state.value.copy(message = "Delete failed: ${it.message}") }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}

@Composable
fun ProfileEditScreen(onBack: () -> Unit, vm: ProfileEditViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val active by vm.activeProfile.collectAsStateWithLifecycle()
    val profiles by vm.profileList.collectAsStateWithLifecycle()
    val info = profiles.firstOrNull { it.name == vm.name }
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingModel by remember { mutableStateOf(false) }
    // The lists are long, so they start collapsed; open state survives rotation.
    var skillsOpen by rememberSaveable { mutableStateOf(false) }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var mcpOpen by rememberSaveable { mutableStateOf(false) }
    var addingMcp by remember { mutableStateOf(false) }
    var browsingCatalog by remember { mutableStateOf(false) }
    var removingMcp by remember { mutableStateOf<McpServerDto?>(null) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = vm.name,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            com.hermes.client.ui.components.LoadingState()
        } else {
            LazyColumn(
                Modifier.padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = state.description,
                        onValueChange = vm::onDescription,
                        label = { Text("Description") },
                        supportingText = { Text("What this profile is for") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
                item {
                    OutlinedTextField(
                        value = state.soul,
                        onValueChange = vm::onSoul,
                        label = { Text("SOUL.md (personality and standing instructions)") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(
                        onClick = vm::save,
                        enabled = state.dirty && !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.saving) "Saving…" else "Save description and SOUL") }
                }
                item {
                    HorizontalDivider()
                    Text("Default model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        info?.model?.takeIf { it.isNotBlank() }?.let { m -> info.provider?.let { "$it / $m" } ?: m } ?: "Not set",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    OutlinedButton(onClick = { vm.loadModels(); pickingModel = true }) { Text("Change model") }
                }
                item {
                    HorizontalDivider()
                    Text("Default profile", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    if (active == vm.name) {
                        Text("This is the default profile for new sessions.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        OutlinedButton(onClick = vm::makeDefault) { Text("Make this the default profile") }
                    }
                }
                item {
                    HorizontalDivider()
                    SectionHeader(
                        title = "Skills",
                        summary = "${state.skills.count { it.enabled }} of ${state.skills.size} enabled",
                        expanded = skillsOpen,
                        onToggle = { skillsOpen = !skillsOpen },
                    )
                }
                if (skillsOpen) {
                    item {
                        OutlinedTextField(
                            value = state.skillQuery,
                            onValueChange = vm::onSkillQuery,
                            placeholder = { Text("Search skills…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(state.shownSkills, key = { "skill:${it.name}" }) { sk ->
                        ListItem(
                            headlineContent = { Text(sk.name) },
                            supportingContent = sk.description?.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2) } },
                            trailingContent = { Switch(checked = sk.enabled, onCheckedChange = { vm.toggleSkill(sk, it) }) },
                        )
                    }
                }
                item {
                    HorizontalDivider()
                    SectionHeader(
                        title = "Tools",
                        summary = "${state.tools.count { it.enabled }} of ${state.tools.size} enabled",
                        expanded = toolsOpen,
                        onToggle = { toolsOpen = !toolsOpen },
                    )
                }
                if (toolsOpen) {
                    items(state.tools, key = { "tool:${it.name}" }) { t ->
                        ListItem(
                            headlineContent = { Text(t.label ?: t.name) },
                            supportingContent = t.description?.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2) } },
                            trailingContent = { Switch(checked = t.enabled, onCheckedChange = { vm.toggleTool(t, it) }) },
                        )
                    }
                }
                item {
                    HorizontalDivider()
                    SectionHeader(
                        title = "MCP servers",
                        summary = if (state.mcpError != null) "failed to load"
                        else if (state.mcp.isEmpty()) "none configured" else "${state.mcp.count { it.enabled }} of ${state.mcp.size} enabled",
                        expanded = mcpOpen,
                        onToggle = { mcpOpen = !mcpOpen },
                    )
                }
                if (mcpOpen) {
                    item {
                        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { vm.clearMcpActionError(); addingMcp = true }) { Text("Add server") }
                            OutlinedButton(onClick = { vm.loadCatalog(); browsingCatalog = true }) { Text("Browse catalog") }
                        }
                    }
                    state.mcpError?.let { err ->
                        item { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                    items(state.mcp, key = { "mcp:${it.name}" }) { m ->
                        val fromPlugin = m.source == "plugin"
                        ListItem(
                            headlineContent = { Text(m.name) },
                            supportingContent = {
                                Text(if (fromPlugin) "Provided by plugin ${m.plugin ?: ""}".trim() else (m.transport ?: ""))
                            },
                            trailingContent = {
                                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    if (!fromPlugin) {
                                        IconButton(onClick = { removingMcp = m }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "Remove ${m.name}")
                                        }
                                    }
                                    Switch(checked = m.enabled, enabled = !fromPlugin, onCheckedChange = { vm.toggleMcp(m, it) })
                                }
                            },
                        )
                    }
                }
                if (vm.name != "default") {
                    item {
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) { Text("Delete profile", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    if (addingMcp) {
        AddMcpDialog(
            error = state.mcpActionError,
            onAdd = { n, url, cmd, args, env -> vm.addMcp(n, url, cmd, args, env) { addingMcp = false } },
            onDismiss = { addingMcp = false },
        )
    }
    if (browsingCatalog) {
        McpCatalogDialog(
            entries = state.catalog,
            error = state.mcpActionError,
            onInstall = { entry, env -> vm.installCatalog(entry, env) { } },
            onDismiss = { browsingCatalog = false },
        )
    }
    removingMcp?.let { m ->
        AlertDialog(
            onDismissRequest = { removingMcp = null },
            title = { Text("Remove ${m.name}?") },
            text = { Text("This deletes the server from ${vm.name}'s config. Other profiles are not affected.") },
            confirmButton = { TextButton(onClick = { removingMcp = null; vm.deleteMcp(m) }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removingMcp = null }) { Text("Cancel") } },
        )
    }

    if (pickingModel) {
        ModelPickerDialog(
            providers = state.providers,
            onPick = { provider, model -> pickingModel = false; vm.setModel(provider, model) },
            onDismiss = { pickingModel = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${vm.name}?") },
            text = { Text("This removes the profile's directory, including its sessions, config and keys. It cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(title: String, summary: String, expanded: Boolean, onToggle: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
        )
    }
}

@Composable
private fun ModelPickerDialog(
    providers: List<ModelProviderDto>,
    onPick: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val rows = remember(providers, query) {
        providers.flatMap { p -> p.models.map { p.slug to it } }
            .filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default model") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search models…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (providers.isEmpty()) Text("Loading…", modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(rows, key = { "${it.first}/${it.second}" }) { (provider, model) ->
                        ListItem(
                            headlineContent = { Text(model, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text(provider, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.clickable { onPick(provider, model) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddMcpDialog(
    error: String?,
    onAdd: (name: String, url: String?, command: String?, args: List<String>, env: Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    val envMap = env.lines().mapNotNull { line ->
        line.substringBefore('=', "").trim().takeIf { it.isNotEmpty() && line.contains('=') }
            ?.let { it to line.substringAfter('=').trim() }
    }.toMap()
    val valid = Regex("[A-Za-z0-9][A-Za-z0-9_-]*").matches(name) && (if (remote) url.isNotBlank() else command.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add MCP server") },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = !remote, onClick = { remote = false }, label = { Text("Local command") })
                    androidx.compose.material3.FilterChip(selected = remote, onClick = { remote = true }, label = { Text("Remote URL") })
                }
                if (remote) {
                    OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(command, { command = it }, label = { Text("Command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        args, { args = it }, label = { Text("Arguments") },
                        supportingText = { Text("Separated by spaces") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        env, { env = it }, label = { Text("Environment (optional)") },
                        supportingText = { Text("One KEY=value per line") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    if (remote) onAdd(name, url.trim(), null, emptyList(), emptyMap())
                    else onAdd(name, null, command.trim(), args.split(' ').filter { it.isNotBlank() }, envMap)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun McpCatalogDialog(
    entries: List<McpCatalogEntryDto>?,
    error: String?,
    onInstall: (McpCatalogEntryDto, Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<McpCatalogEntryDto?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(selected?.name ?: "MCP catalog") },
        text = {
            val sel = selected
            if (sel == null) {
                Column {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    when {
                        entries == null -> Text("Loading…")
                        entries.isEmpty() && error == null -> Text("The catalog is empty.")
                        else -> LazyColumn(Modifier.height(360.dp)) {
                            items(entries, key = { it.name }) { e ->
                                ListItem(
                                    headlineContent = { Text(e.name) },
                                    supportingContent = { Text(e.description.orEmpty(), maxLines = 2) },
                                    trailingContent = { if (e.installed) Text("installed", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.clickable(enabled = !e.installed) { values.clear(); selected = e },
                                )
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sel.description.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    if (sel.needsInstall) {
                        Text(
                            "This entry downloads and builds code on the gateway host.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    sel.requiredEnv.forEach { spec ->
                        OutlinedTextField(
                            value = values[spec.name].orEmpty(),
                            onValueChange = { values[spec.name] = it },
                            label = { Text(spec.name + if (spec.required) " *" else "") },
                            supportingText = spec.prompt?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            val sel = selected
            if (sel != null) {
                TextButton(
                    enabled = sel.requiredEnv.none { it.required && values[it.name].isNullOrBlank() },
                    onClick = { onInstall(sel, values.filterValues { it.isNotBlank() }.toMap()); selected = null },
                ) { Text("Install") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (selected != null) selected = null else onDismiss() }) {
                Text(if (selected != null) "Back" else "Close")
            }
        },
    )
}
