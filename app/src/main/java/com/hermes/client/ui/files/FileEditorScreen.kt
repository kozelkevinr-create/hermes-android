package com.hermes.client.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.theme.TerminalMono

/** Edit a managed text file. Saving is guarded against the agent having changed it underneath. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
    path: String,
    onBack: () -> Unit,
    /** View-only: no save action and the text field cannot be edited. */
    readOnly: Boolean = false,
    vm: FileEditorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(path) { vm.load(path) }
    LaunchedEffect(state.saved) { if (state.saved) snackbar.showSnackbar("Saved") }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = state.name.ifBlank { if (readOnly) "Viewer" else "Editor" } +
                    if (state.dirty) " •" else "",
                subtitle = if (readOnly) "Read-only" else null,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!readOnly) {
                        IconButton(onClick = { vm.save() }, enabled = state.dirty && !state.saving) {
                            if (state.saving) CircularProgressIndicator(Modifier.padding(4.dp))
                            else Icon(Icons.Rounded.Save, contentDescription = "Save")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                BasicTextField(
                    value = state.text,
                    onValueChange = { if (!readOnly) vm.onTextChanged(it) },
                    readOnly = readOnly,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = TerminalMono,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                )
            }
        }
    }

    if (state.conflict) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Changed on the server") },
            text = {
                Text(
                    "${state.name} was modified since you opened it — most likely by the agent. " +
                        "Saving now would discard that change.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.reloadFromServer() }) { Text("Reload theirs") }
            },
            dismissButton = {
                TextButton(onClick = { vm.save(force = true) }) { Text("Overwrite") }
            },
        )
    }
}
