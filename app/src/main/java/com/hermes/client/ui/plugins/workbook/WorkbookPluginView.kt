package com.hermes.client.ui.plugins.workbook

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.plugins.NativePluginView

/** Native screen for the `hermes-workbook` dashboard plugin. */
class WorkbookPluginView : NativePluginView {
    override val pluginName: String = "hermes-workbook"
    override val title: String = "Workbooks"

    @Composable
    override fun Content(onBack: () -> Unit) {
        WorkbookScreen(onBack = onBack)
    }
}

private val CELL_WIDTH = 120.dp
private val ROW_NUMBER_WIDTH = 44.dp
private val CELL_HEIGHT = 40.dp
private const val MAX_APPEND_FIELDS = 12

@Composable
internal fun WorkbookScreen(onBack: () -> Unit, vm: WorkbookViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var showAppend by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CellRef?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    BackHandler(enabled = state.selected != null) { vm.closeWorkbook() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.canWrite) {
                if (state.selected == null) {
                    FloatingActionButton(onClick = { showCreate = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "New workbook")
                    }
                } else if (state.data != null) {
                    FloatingActionButton(onClick = { showAppend = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add row")
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.selected == null) {
                WorkbookList(state, vm)
            } else {
                WorkbookViewer(state, vm, onEdit = { editing = it })
            }
            if (state.saving) {
                CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(8.dp))
            }
        }
    }

    if (showCreate) {
        CreateDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, ext, scope ->
                showCreate = false
                vm.createWorkbook(name, ext, scope)
            },
        )
    }
    if (showAppend) {
        AppendDialog(
            columns = (state.data?.width ?: 1).coerceIn(1, MAX_APPEND_FIELDS),
            onDismiss = { showAppend = false },
            onAppend = { values ->
                showAppend = false
                vm.appendRow(values)
            },
        )
    }
    editing?.let { ref ->
        EditCellDialog(
            address = cellAddress(ref.row, ref.col),
            initial = ref.value,
            onDismiss = { editing = null },
            onSave = { text ->
                editing = null
                vm.writeCell(ref.row, ref.col, text)
            },
        )
    }
}

private data class CellRef(val row: Int, val col: Int, val value: String)

@Composable
private fun WorkbookList(state: WorkbookUiState, vm: WorkbookViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf<Pair<String?, String>>(null to "All", "profile" to "Profile", "global" to "Shared").forEach { (v, label) ->
                FilterChip(
                    selected = state.scopeFilter == v,
                    onClick = { vm.setFilter(v) },
                    label = { Text(label) },
                )
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = { vm.loadList() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
            }
        }
        when {
            state.loadingList -> Centered { CircularProgressIndicator() }
            state.listError != null -> Centered {
                Text(state.listError, color = MaterialTheme.colorScheme.error)
            }
            state.workbooks.isEmpty() -> Centered {
                Text(
                    "No workbooks yet. Tap + to create one, or ask an agent to.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.workbooks, key = { it.scope + ":" + it.name }) { wb ->
                    ListItem(
                        headlineContent = { Text(wb.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text(wb.file + if (wb.exists) "" else " (file missing)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = {
                            AssistChip(onClick = {}, label = { Text(if (wb.scope == "global") "shared" else "profile") })
                        },
                        modifier = Modifier.clickable(enabled = wb.exists) { vm.open(wb) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun WorkbookViewer(state: WorkbookUiState, vm: WorkbookViewModel, onEdit: (CellRef) -> Unit) {
    val workbook = state.selected ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.closeWorkbook() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to workbooks")
            }
            Text(
                workbook.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.refreshData() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Reload")
            }
        }
        if (!state.canWrite) {
            Text(
                "Read-only: the gateway's Workbook plugin does not support editing.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (state.sheets.size > 1) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sheets.forEach { name ->
                    FilterChip(
                        selected = state.activeSheet == name,
                        onClick = { vm.selectSheet(name) },
                        label = { Text(name) },
                    )
                }
            }
        }
        val data = state.data
        when {
            state.loadingData -> Centered { CircularProgressIndicator() }
            state.dataError != null -> Centered { Text(state.dataError, color = MaterialTheme.colorScheme.error) }
            data == null || data.rows.isEmpty() -> Centered { Text("This sheet is empty.") }
            else -> {
                if (data.truncated) {
                    Text(
                        "Showing the first rows only.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Grid(data, editable = state.canEditCells, onEdit = onEdit)
            }
        }
    }
}

@Composable
private fun Grid(data: WorkbookData, editable: Boolean, onEdit: (CellRef) -> Unit) {
    val width = data.width
    val totalWidth = ROW_NUMBER_WIDTH + CELL_WIDTH * width
    val border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
        LazyColumn(Modifier.width(totalWidth)) {
            item {
                Row {
                    GridCell("", ROW_NUMBER_WIDTH, header = true, borderStroke = border)
                    for (c in 0 until width) GridCell(columnLabel(c), CELL_WIDTH, header = true, borderStroke = border)
                }
            }
            itemsIndexed(data.rows) { r, row ->
                Row {
                    GridCell((r + 1).toString(), ROW_NUMBER_WIDTH, header = true, borderStroke = border)
                    for (c in 0 until width) {
                        val value = row.getOrElse(c) { "" }
                        GridCell(
                            value, CELL_WIDTH, header = false, borderStroke = border,
                            onClick = if (editable) ({ onEdit(CellRef(r, c, value)) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(
    text: String,
    cellWidth: androidx.compose.ui.unit.Dp,
    header: Boolean,
    borderStroke: BorderStroke,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .width(cellWidth)
            .height(CELL_HEIGHT)
            .border(borderStroke)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
            color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun EditCellDialog(address: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $address") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Value") },
                supportingText = { Text("Formulas (starting with =) are not supported.") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AppendDialog(columns: Int, onDismiss: () -> Unit, onAppend: (List<String>) -> Unit) {
    val values = remember { mutableStateOf(List(columns) { "" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add row") },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth()) {
                values.value.forEachIndexed { i, v ->
                    OutlinedTextField(
                        value = v,
                        onValueChange = { nv -> values.value = values.value.toMutableList().also { it[i] = nv } },
                        label = { Text("Column ${columnLabel(i)}") },
                        singleLine = true,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAppend(values.value) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CreateDialog(onDismiss: () -> Unit, onCreate: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var extension by remember { mutableStateOf(".xlsx") }
    var scope by remember { mutableStateOf("profile") }
    val valid = name.isNotBlank() && name.all { it.isLetterOrDigit() || it in "-_." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New workbook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    supportingText = { Text("Letters, digits, - _ . only") },
                    singleLine = true,
                    isError = name.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions.Default,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(".xlsx", ".csv").forEach {
                        FilterChip(selected = extension == it, onClick = { extension = it }, label = { Text(it) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("profile" to "Profile", "global" to "Shared").forEach { (v, label) ->
                        FilterChip(selected = scope == v, onClick = { scope = v }, label = { Text(label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onCreate(name, extension, scope) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
