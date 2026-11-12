package com.hermes.client.ui.plugins.workbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkbookUiState(
    val scopeFilter: String? = null,
    val loadingList: Boolean = true,
    val listError: String? = null,
    val workbooks: List<WorkbookSummary> = emptyList(),
    val selected: WorkbookSummary? = null,
    val sheets: List<String> = emptyList(),
    val activeSheet: String? = null,
    val data: WorkbookData? = null,
    val loadingData: Boolean = false,
    val dataError: String? = null,
    /** False when the gateway's plugin is read-only (no revision tokens, or POST routes missing). */
    val canWrite: Boolean = true,
    val saving: Boolean = false,
    /** One-shot message for the snackbar; call [WorkbookViewModel.messageShown] after showing. */
    val message: String? = null,
) {
    /** csv files can only be appended to; xlsx cells can also be edited in place. */
    val canEditCells: Boolean get() = canWrite && selected?.isCsv == false
}

@HiltViewModel
class WorkbookViewModel @Inject constructor(
    private val api: WorkbookApi,
    private val profileManager: ProfileManager,
) : ViewModel() {
    private val _state = MutableStateFlow(WorkbookUiState())
    val state: StateFlow<WorkbookUiState> = _state.asStateFlow()

    private var revision: String? = null
    private var dataJob: Job? = null

    private val profile: String? get() = profileManager.active.value

    init {
        viewModelScope.launch {
            profileManager.active.collect {
                closeWorkbook()
                loadList()
            }
        }
    }

    fun setFilter(scope: String?) {
        _state.update { it.copy(scopeFilter = scope) }
        loadList()
    }

    fun loadList() {
        viewModelScope.launch {
            _state.update { it.copy(loadingList = true, listError = null) }
            try {
                val items = api.workbooks(_state.value.scopeFilter, profile)
                _state.update { it.copy(loadingList = false, workbooks = items) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingList = false, workbooks = emptyList(), listError = describeFailure(e)) }
            }
        }
    }

    fun open(workbook: WorkbookSummary) {
        revision = null
        _state.update {
            it.copy(
                selected = workbook, sheets = emptyList(), activeSheet = null, data = null,
                dataError = null, loadingData = true, canWrite = true,
            )
        }
        dataJob?.cancel()
        dataJob = viewModelScope.launch {
            try {
                val sheets = api.sheets(workbook.name, workbook.scope, profile)
                val first = sheets.sheets.firstOrNull()
                _state.update { it.copy(sheets = sheets.sheets, activeSheet = first) }
                revision = sheets.revision
                if (sheets.revision == null) markReadOnly()
                loadData(workbook, first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingData = false, dataError = describeFailure(e)) }
            }
        }
    }

    fun selectSheet(name: String) {
        val workbook = _state.value.selected ?: return
        _state.update { it.copy(activeSheet = name, data = null, dataError = null, loadingData = true) }
        dataJob?.cancel()
        dataJob = viewModelScope.launch { guardedLoadData(workbook, name) }
    }

    fun refreshData() {
        val workbook = _state.value.selected ?: return
        _state.update { it.copy(loadingData = true, dataError = null) }
        dataJob?.cancel()
        dataJob = viewModelScope.launch { guardedLoadData(workbook, _state.value.activeSheet) }
    }

    fun closeWorkbook() {
        dataJob?.cancel()
        revision = null
        _state.update {
            it.copy(
                selected = null, sheets = emptyList(), activeSheet = null, data = null,
                dataError = null, loadingData = false, canWrite = true,
            )
        }
    }

    /** Edit one cell of an xlsx sheet. [row] and [col] are zero-based. */
    fun writeCell(row: Int, col: Int, text: String) = mutate { workbook, sheet ->
        api.write(
            name = workbook.name, scope = workbook.scope, sheet = sheet,
            start = cellAddress(row, col), values = jsonRows(listOf(listOf(text))),
            ifRevision = revision, profile = profile,
        )
    }

    fun appendRow(values: List<String>) = mutate { workbook, sheet ->
        api.append(
            name = workbook.name, scope = workbook.scope, sheet = sheet,
            rows = jsonRows(listOf(values)), ifRevision = revision, profile = profile,
        )
    }

    fun createWorkbook(name: String, extension: String, scope: String) {
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                api.create(name.trim(), name.trim() + extension, scope, profile)
                _state.update { it.copy(saving = false, message = "Created ${name.trim()}") }
                loadList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleWriteFailure(e)
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun mutate(block: suspend (WorkbookSummary, String?) -> String?) {
        val workbook = _state.value.selected ?: return
        val sheet = _state.value.activeSheet
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            try {
                val newRevision = block(workbook, sheet)
                _state.update { it.copy(saving = false, message = "Saved") }
                loadData(workbook, sheet, hint = newRevision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleWriteFailure(e)
                if (e is WorkbookApiException && e.isConflict) {
                    revision = e.revision ?: revision
                    guardedLoadData(workbook, sheet)
                }
            }
        }
    }

    private fun handleWriteFailure(e: Exception) {
        if (e is WorkbookApiException && e.isRouteMissing) markReadOnly()
        val text = when {
            e is WorkbookApiException && e.isConflict ->
                "This workbook changed elsewhere. Latest version loaded; try your edit again."
            e is WorkbookApiException && e.isRouteMissing ->
                "Editing is not available: the gateway's Workbook plugin is read-only."
            else -> describeFailure(e)
        }
        _state.update { it.copy(saving = false, message = text) }
    }

    private fun markReadOnly() = _state.update { it.copy(canWrite = false) }

    private suspend fun guardedLoadData(workbook: WorkbookSummary, sheet: String?) {
        try {
            loadData(workbook, sheet)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loadingData = false, dataError = describeFailure(e)) }
        }
    }

    private suspend fun loadData(workbook: WorkbookSummary, sheet: String?, hint: String? = null) {
        val data = api.data(workbook.name, workbook.scope, sheet, profile)
        revision = data.revision ?: hint
        if (data.revision == null) markReadOnly()
        _state.update { it.copy(data = data, loadingData = false, dataError = null) }
    }
}
