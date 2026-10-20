package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixNewRoom
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.repository.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the Matrix room list: account state and rooms from the manager, plus the search query. */
@HiltViewModel
class MatrixRoomListViewModel internal constructor(
    private val matrix: StateFlow<MatrixState>,
    rooms: StateFlow<List<MatrixRoomSummary>>,
    private val onRetry: () -> Unit,
    /** The manager's create-room call, lambda-injected for tests. */
    private val onCreateRoom: suspend (MatrixNewRoom) -> String?,
    /** The "encrypted by default" setting, read when the sheet opens. */
    private val defaultEncrypted: kotlinx.coroutines.flow.Flow<Boolean>,
) : ViewModel() {
    @Inject constructor(
        manager: MatrixClientManager,
        settings: SettingsStore,
    ) : this(
        matrix = manager.state,
        rooms = manager.rooms,
        onRetry = { manager.restoreOnStart() },
        onCreateRoom = { room -> manager.createRoom(room) },
        defaultEncrypted = settings.matrixRoomsEncrypted,
    )

    private val query = MutableStateFlow("")

    val uiState: StateFlow<RoomListUiState> = combine(matrix, rooms, query) { m, r, q ->
        roomListUiState(m, r, q)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        roomListUiState(matrix.value, rooms.value, ""),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Re-runs the saved-session restore (the banner's Retry). */
    fun retry() = onRetry()

    // ---- new-room sheet ----

    /** The sheet's live state; closed means the fields are not shown. */
    data class NewRoomSheetUi(
        val open: Boolean = false,
        val creating: Boolean = false,
        val draft: NewRoomDraft = NewRoomDraft(),
        /** Set when the last create attempt failed (the network/gateway reason is unknown). */
        val error: String? = null,
    )

    private val _sheet = MutableStateFlow(NewRoomSheetUi())
    val newRoomSheet: StateFlow<NewRoomSheetUi> = _sheet.asStateFlow()

    /** The id of a successfully created room, once per creation; the screen opens it. */
    val createdRoomId = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Opens the sheet prefilled from current state: the bot's MXID as the invite (editable) and
     * the "encrypted by default" setting as the toggle position. No-op without a live session —
     * the sheet button only shows when signed in.
     */
    fun openNewRoomSheet() {
        if (matrix.value !is MatrixState.Ready) return
        viewModelScope.launch {
            _sheet.value = NewRoomSheetUi(
                open = true,
                draft = NewRoomDraft(
                    invite = (matrix.value as MatrixState.Ready).botMxid,
                    encrypted = defaultEncrypted.first(),
                ),
            )
        }
    }

    fun dismissNewRoomSheet() {
        _sheet.value = NewRoomSheetUi()
    }

    /**
     * Replaces the draft. A mode switch drops the room name (a DM needs none) but keeps the
     * invite and the encrypted toggle; edits clear any create error.
     */
    fun updateNewRoomDraft(draft: NewRoomDraft) {
        val current = _sheet.value
        if (current.creating) return
        val next = if (draft.mode != current.draft.mode) {
            draft.copy(name = "")
        } else {
            draft
        }
        _sheet.value = current.copy(draft = next, error = null)
    }

    /**
     * Creates the room from the current draft. Invalid drafts surface [newRoomError]; a failed
     * create keeps the sheet open with a generic error (the seam never throws). Success closes
     * the sheet, re-emits the id on [createdRoomId], and the room list picks the room up live.
     */
    fun createNewRoom() {
        val current = _sheet.value
        if (current.creating) return
        val request = newRoomRequest(current.draft)
        if (request == null) {
            _sheet.value = current.copy(error = (newRoomError(current.draft) ?: "").ifBlank { "Check the form." })
            return
        }
        _sheet.value = current.copy(creating = true, error = null)
        viewModelScope.launch {
            val id = onCreateRoom(request)
            if (id == null) {
                _sheet.value = _sheet.value.copy(creating = false, error = "Couldn't create the room.")
            } else {
                _sheet.value = NewRoomSheetUi()
                createdRoomId.emit(id)
            }
        }
    }
}
