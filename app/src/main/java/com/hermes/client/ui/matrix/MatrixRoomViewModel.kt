package com.hermes.client.ui.matrix

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.EventOrTxnId
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.matrix.MatrixTimelineHandle
import com.hermes.client.data.matrix.PaginationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the room screen shows beneath the top bar. */
sealed interface RoomTimelineState {
    /** No Matrix session; the screen offers Settings -> Matrix account. */
    data object SignedOut : RoomTimelineState

    /** Restoring/logging in, or the timeline is being opened. */
    data object Opening : RoomTimelineState

    data class Failed(val message: String) : RoomTimelineState

    data class Ready(
        val rows: List<TimelineRow>,
        val isAtStart: Boolean,
        val isLoadingMore: Boolean,
    ) : RoomTimelineState
}

/** Drives one room timeline: opens it, folds its items to rows, and pages backwards on demand. */
@HiltViewModel
class MatrixRoomViewModel internal constructor(
    private val roomId: String,
    matrix: StateFlow<MatrixState>,
    rooms: StateFlow<List<MatrixRoomSummary>>,
    private val openTimeline: suspend (String) -> MatrixTimelineHandle?,
) : ViewModel() {

    @Inject constructor(manager: MatrixClientManager, savedStateHandle: SavedStateHandle) : this(
        roomId = savedStateHandle.get<String>("roomId") ?: "",
        matrix = manager.state,
        rooms = manager.rooms,
        openTimeline = { manager.openTimeline(it) },
    )

    /** The room's name and encryption, for the top bar; null while the room list is empty. */
    val room: StateFlow<MatrixRoomSummary?> = rooms
        .map { list -> list.firstOrNull { it.id == roomId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, rooms.value.firstOrNull { it.id == roomId })

    private val _state = MutableStateFlow<RoomTimelineState>(RoomTimelineState.Opening)
    val state: StateFlow<RoomTimelineState> = _state.asStateFlow()

    private val _sending = MutableStateFlow(false)

    /** True while a send is in flight, so the composer can disable its send button. */
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _sendFailed = MutableStateFlow(false)

    /** True when the last send attempt failed; cleared when the next one starts. */
    val sendFailed: StateFlow<Boolean> = _sendFailed.asStateFlow()

    private val _draft = MutableStateFlow("")

    /**
     * The composer text. It lives here (not in the composable) so a failed send can keep it and an
     * ignored send never clears it.
     */
    val draft: StateFlow<String> = _draft.asStateFlow()

    private var timeline: MatrixTimelineHandle? = null
    private var collectJob: Job? = null
    private var loading = false

    init {
        viewModelScope.launch {
            matrix.collect { m ->
                when (m) {
                    is MatrixState.Ready -> open()
                    MatrixState.Restoring, MatrixState.LoggingIn -> {
                        closeTimeline()
                        _state.value = RoomTimelineState.Opening
                    }
                    else -> {
                        closeTimeline()
                        _state.value = RoomTimelineState.SignedOut
                    }
                }
            }
        }
    }

    private fun open() {
        closeTimeline()
        _state.value = RoomTimelineState.Opening
        collectJob = viewModelScope.launch {
            val handle = try {
                openTimeline(roomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = RoomTimelineState.Failed(e.message ?: "Couldn't open the room")
                return@launch
            }
            if (handle == null) {
                _state.value = RoomTimelineState.Failed("Room not found")
                return@launch
            }
            timeline = handle
            combine(handle.items, handle.paginationState) { entries, pagination ->
                RoomTimelineState.Ready(
                    rows = timelineRows(entries),
                    isAtStart = pagination == PaginationState.AtStart,
                    isLoadingMore = pagination == PaginationState.Loading,
                )
            }.collect { _state.value = it }
        }
    }

    /** Called when the user scrolls near the top (oldest messages) to page in more history. */
    fun loadMore() {
        val h = timeline ?: return
        val s = _state.value
        if (s !is RoomTimelineState.Ready) return
        if (s.isAtStart || loading) return
        loading = true
        viewModelScope.launch {
            try {
                h.paginateBackwards(PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // paginationState settles back to Idle; there is nothing extra to surface.
            } finally {
                loading = false
            }
        }
    }

    /** Replaces the composer text. */
    fun updateDraft(text: String) {
        _draft.value = text
    }

    /**
     * Sends the current draft (trimmed) as a text message. Blank input and a send that is already
     * in flight are ignored, leaving the draft untouched. The draft is cleared only after the send
     * is accepted by the timeline; on failure it stays so the user can try again.
     */
    fun send() {
        val raw = _draft.value
        val body = raw.trim()
        if (body.isEmpty()) return
        if (!_sending.compareAndSet(false, true)) return
        _sendFailed.value = false
        viewModelScope.launch {
            try {
                val handle = timeline
                val sent = handle != null && handle.sendText(body)
                if (sent) {
                    // Keep anything typed after submit, but clear exactly the text we sent.
                    _draft.compareAndSet(raw, "")
                } else {
                    _sendFailed.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _sendFailed.value = true
            } finally {
                _sending.value = false
            }
        }
    }

    /** Retries the original failed event in place; never sends a new copy of the body. */
    fun retrySend(itemId: EventOrTxnId) {
        viewModelScope.launch {
            val handle = timeline ?: return@launch
            try {
                handle.retrySend(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The handle reports failure by returning false; nothing else to surface here.
            }
        }
    }

    /** Discards the failed local echo without sending anything. */
    fun abortSend(itemId: EventOrTxnId) {
        viewModelScope.launch {
            val handle = timeline ?: return@launch
            try {
                handle.abortSend(itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing sensible to surface; the echo stays visible if the abort failed.
            }
        }
    }

    /** Re-opens the timeline after a failure. */
    fun retry() {
        if (_state.value is RoomTimelineState.Failed) open()
    }

    private fun closeTimeline() {
        collectJob?.cancel()
        collectJob = null
        timeline?.close()
        timeline = null
    }

    override fun onCleared() {
        closeTimeline()
        super.onCleared()
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}
