package com.hermes.client.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.FileEntryDto
import com.hermes.client.data.network.HermesRestApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backup files Hermes leaves behind (handbook/docker.md.bak-20261004-192223) — noise on a phone. */
private val BACKUP_RE = Regex("""\.bak-\d{8}-\d{6}$""")

/**
 * Clutter hidden by default: dotfiles/dirs (.git, .obsidian, .cache) and Hermes' timestamped
 * backups. Both are things you scroll past rather than open on a phone — especially in the
 * Obsidian vault, where .git and .obsidian outnumber the actual notes.
 */
private fun isClutter(name: String): Boolean =
    name.startsWith(".") || BACKUP_RE.containsMatchIn(name)

data class FilesState(
    val path: String = "",
    val parent: String? = null,
    val entries: List<FileEntryDto> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val hiddenCount: Int = 0,
    val showHidden: Boolean = false,
    val uploading: Boolean = false,
    val uploadMessage: String? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val rest: HermesRestApi,
    private val uploader: com.hermes.client.data.files.FileUploader,
) : ViewModel() {
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    fun load(path: String? = null) {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { rest.listFiles(path) }
                .onSuccess { dto ->
                    val all = dto.entries
                    _state.value = FilesState(
                        path = dto.path,
                        parent = dto.parent,
                        entries = visible(all, _state.value.showHidden),
                        loading = false,
                        hiddenCount = all.count { isClutter(it.name) },
                        showHidden = _state.value.showHidden,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "Could not list files")
                }
        }
    }

    fun toggleHidden() {
        _state.value = _state.value.copy(showHidden = !_state.value.showHidden)
        load(_state.value.path.ifBlank { null })
    }

    private fun visible(all: List<FileEntryDto>, showHidden: Boolean) =
        if (showHidden) all else all.filterNot { isClutter(it.name) }

    /** Upload the document behind [uri] into [destDir] (default: the uploads folder). */
    fun upload(uri: android.net.Uri, destDir: String) {
        _state.value = _state.value.copy(uploading = true, uploadMessage = null)
        viewModelScope.launch {
            val result = uploader.upload(uri, destDir)
            _state.value = _state.value.copy(
                uploading = false,
                uploadMessage = if (result.ok) "Uploaded ${result.name}" else (result.error ?: "Upload failed"),
            )
            if (result.ok) load(destDir)
        }
    }

        fun clearUploadMessage() {
        _state.value = _state.value.copy(uploadMessage = null)
    }
}
