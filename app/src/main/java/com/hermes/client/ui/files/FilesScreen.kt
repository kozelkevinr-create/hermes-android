package com.hermes.client.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Upload
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar

/** Text files we open in the in-app editor; everything else is listed but not editable. */
private val EDITABLE = setOf(
    "md", "txt", "yaml", "yml", "json", "toml", "ini", "conf", "sh", "py", "kt", "js", "ts", "csv", "xml", "env.example",
)

fun isEditable(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in EDITABLE

/**
 * Browse the gateway's managed files. The server is locked to a root (HERMES_DASHBOARD_FILES_ROOT),
 * so "up" simply stops being offered at the top — the client doesn't enforce the boundary itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    startPath: String? = null,
    /** Browse-only: hides upload and opens files in a non-editable viewer. */
    readOnly: Boolean = false,
    /**
     * Highest directory reachable from here. The gateway's own locked root still applies; this is
     * a narrower UI boundary so a shortcut (the Obsidian vault) doesn't let you wander up into the
     * rest of the filesystem. Null means the gateway's root is the only limit.
     */
    boundaryPath: String? = null,
    vm: FilesViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // At the boundary there is nowhere further up, so back leaves the screen instead.
    val atBoundary = boundaryPath != null &&
        state.path.trimEnd('/') == boundaryPath.trimEnd('/')
    val goUp: () -> Unit = {
        val parent = state.parent
        if (!atBoundary && parent != null) vm.load(parent) else onBack()
    }
    // Keep the system back gesture consistent with the toolbar arrow: both walk up the tree and
    // both stop at the boundary. Without this, swiping back from a subfolder would exit outright.
    androidx.activity.compose.BackHandler(enabled = true, onBack = goUp)
    LaunchedEffect(startPath) { vm.load(startPath) }
    LaunchedEffect(state.uploadMessage) {
        state.uploadMessage?.let { snackbar.showSnackbar(it); vm.clearUploadMessage() }
    }
    // SAF picker: OpenDocument gives a persistable URI and lets the provider report name/size.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.upload(it, state.path) }
    }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = state.path.substringAfterLast('/').ifBlank { "Files" },
                navigationIcon = {
                    IconButton(onClick = goUp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!readOnly) {
                FloatingActionButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    if (state.uploading) {
                        CircularProgressIndicator(Modifier.padding(4.dp))
                    } else {
                        Icon(Icons.Rounded.Upload, contentDescription = "Upload a file")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                boundaryPath?.let { b ->
                    state.path.removePrefix(b.trimEnd('/')).trimStart('/').ifBlank { b.substringAfterLast('/') }
                } ?: state.path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (readOnly) {
                Text(
                    "Read-only — edit these in Obsidian",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            HorizontalDivider()

            when {
                state.loading && state.entries.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                state.error != null -> Column(Modifier.padding(16.dp)) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.load(state.path.ifBlank { null }) }) { Text("Retry") }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name) },
                            supportingContent = entry.size
                                ?.takeIf { !entry.isDirectory }
                                ?.let { { Text(humanSize(it)) } },
                            leadingContent = {
                                Icon(
                                    if (entry.isDirectory) Icons.Rounded.Folder
                                    else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (entry.isDirectory) vm.load(entry.path) else onOpenFile(entry.path)
                            },
                        )
                        HorizontalDivider()
                    }
                    if (state.hiddenCount > 0) {
                        item {
                            TextButton(onClick = { vm.toggleHidden() }, modifier = Modifier.padding(8.dp)) {
                                Text(
                                    if (state.showHidden) "Hide ${state.hiddenCount} hidden items"
                                    else "Show ${state.hiddenCount} hidden items",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
