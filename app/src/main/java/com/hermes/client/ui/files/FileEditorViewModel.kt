package com.hermes.client.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesRestApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditorState(
    val path: String = "",
    val name: String = "",
    val text: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    val dirty: Boolean = false,
    /** Content as it was when opened — the baseline a conflict is detected against. */
    val baseline: String = "",
    /** Set when the file changed on the server since opening; a save would overwrite that change. */
    val conflict: Boolean = false,
)

/**
 * Loads a managed text file, tracks edits, and saves it back.
 *
 * Neither file API does conflict detection — the gateway docs state stale-checking is the
 * client's job. That matters here because the agent edits these same files autonomously, so a
 * naive save would silently discard its work. [save] re-reads the file first and compares it to
 * the content loaded at open time, surfacing [EditorState.conflict] instead of overwriting.
 */
@HiltViewModel
class FileEditorViewModel @Inject constructor(
    private val rest: HermesRestApi,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    fun load(path: String) {
        _state.value = EditorState(path = path, loading = true)
        viewModelScope.launch {
            runCatching { rest.readFile(path) }
                .onSuccess { dto ->
                    val loaded = decode(dto.dataUrl)
                    _state.value = EditorState(
                        path = dto.path,
                        name = dto.name,
                        text = loaded,
                        loading = false,
                        baseline = loaded,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "Could not open file")
                }
        }
    }

    fun onTextChanged(text: String) {
        _state.value = _state.value.copy(text = text, dirty = true, saved = false)
    }

    /** Save, unless the file changed on the server since we opened it. [force] saves anyway. */
    fun save(force: Boolean = false) {
        val s = _state.value
        if (s.saving) return
        _state.value = s.copy(saving = true, error = null, conflict = false)
        viewModelScope.launch {
            if (!force) {
                // Compare content, not size: an edit that keeps the byte count the same (a typo
                // fix, a flipped flag) would slip past a size check and get silently overwritten.
                val current = runCatching { rest.readFile(s.path) }.getOrNull()
                if (current != null && decode(current.dataUrl) != s.baseline) {
                    _state.value = _state.value.copy(saving = false, conflict = true)
                    return@launch
                }
            }
            runCatching { rest.writeFile(s.path, s.text.toByteArray(), "text/plain") }
                .onSuccess {
                    // The text we just wrote becomes the new baseline; otherwise the next save
                    // would compare against the pre-edit content and false-positive a conflict.
                    _state.value = _state.value.copy(
                        saving = false,
                        saved = true,
                        dirty = false,
                        conflict = false,
                        baseline = s.text,
                    )
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(saving = false, error = e.message ?: "Could not save")
                }
        }
    }

    /** Discard local edits and reload whatever the server now has. */
    fun reloadFromServer() = load(_state.value.path)

    private fun decode(dataUrl: String): String {
        val b64 = dataUrl.substringAfter("base64,", "")
        if (b64.isBlank()) return ""
        return runCatching {
            String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
        }.getOrDefault("")
    }
}
