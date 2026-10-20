package com.hermes.client.ui.matrix

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.EventOrTxnId
import com.hermes.client.data.matrix.ImageStager
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.matrix.MatrixTimelineHandle
import com.hermes.client.data.matrix.PaginationState
import com.hermes.client.data.matrix.ReadReceiptSignal
import com.hermes.client.data.matrix.ReadReceiptTracker
import com.hermes.client.data.matrix.StagedImage
import com.hermes.client.data.network.ModelProviderDto
import com.hermes.client.data.repository.ChatRepository
import com.hermes.client.data.repository.ModelRepository
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

/** State of the model picker sheet (quick actions → "Choose model…"). */
data class ModelPickerUi(
    val open: Boolean = false,
    val loading: Boolean = false,
    val providers: List<ModelProviderDto> = emptyList(),
    val failed: Boolean = false,
)

/** Drives one room timeline: opens it, folds its items to rows, and pages backwards on demand. */
@HiltViewModel
class MatrixRoomViewModel internal constructor(
    private val roomId: String,
    matrix: StateFlow<MatrixState>,
    rooms: StateFlow<List<MatrixRoomSummary>>,
    private val openTimeline: suspend (String) -> MatrixTimelineHandle?,
    private val stageImage: suspend (String) -> StagedImage?,
    private val removeStaged: suspend (StagedImage) -> Unit,
    private val loadCommands: suspend () -> List<Pair<String, String>> = { emptyList() },
    private val loadProviders: suspend () -> List<ModelProviderDto> = { emptyList() },
) : ViewModel() {

    @Inject constructor(
        manager: MatrixClientManager,
        savedStateHandle: SavedStateHandle,
        imageStager: ImageStager,
        chatRepository: ChatRepository,
        modelRepository: ModelRepository,
    ) : this(
        roomId = savedStateHandle.get<String>("roomId") ?: "",
        matrix = manager.state,
        rooms = manager.rooms,
        openTimeline = { manager.openTimeline(it) },
        stageImage = { imageStager.stage(it) },
        removeStaged = { imageStager.delete(it) },
        loadCommands = {
            // Cold-start parity with the share path: no screen has opened the gateway socket yet,
            // so the catalog RPC would block forever on its ready-gate. connect() is idempotent.
            // Failure still surfaces as an empty palette (raw commands always send as text).
            chatRepository.connect()
            chatRepository.commandsCatalog()
        },
        loadProviders = { modelRepository.providers() },
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

    private val receipts = ReadReceiptTracker()

    private val _draft = MutableStateFlow("")

    /**
     * The composer text. It lives here (not in the composable) so a failed send can keep it and an
     * ignored send never clears it.
     */
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _commands = MutableStateFlow<List<CommandEntry>>(emptyList())

    /**
     * The Hermes slash-command catalog for the composer palette, fetched from the gateway's
     * commands.catalog RPC (see [ChatRepository.commandsCatalog]) on each room open, best-effort:
     * a gateway that is offline or errors leaves the list empty, which just means no palette —
     * raw slash commands always go out as text messages for the bot to parse.
     */
    val commands: StateFlow<List<CommandEntry>> = _commands.asStateFlow()

    private val _modelPicker = MutableStateFlow(ModelPickerUi())

    /** The quick actions' model picker: providers fetched from the gateway's REST API on open. */
    val modelPicker: StateFlow<ModelPickerUi> = _modelPicker.asStateFlow()

    private val _editing = MutableStateFlow<EventOrTxnId?>(null)

    /** The item being edited; null while the composer is in send mode. */
    val editing: StateFlow<EventOrTxnId?> = _editing.asStateFlow()

    private val _editFailed = MutableStateFlow(false)

    /** True when the last edit attempt failed; cleared when a new one starts or editing ends. */
    val editFailed: StateFlow<Boolean> = _editFailed.asStateFlow()

    /** The draft to restore when the user cancels the edit. */
    private var draftBeforeEdit: String? = null

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
        loadCommandCatalog()
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
     * The layout told us which rows are on screen (their [timelineRowKey]s) and whether a scroll
     * gesture is in progress. Fires at most one read receipt per remote event the user settles on.
     */
    fun onVisibleKeysChanged(keys: Set<String>, isScrollInProgress: Boolean) {
        val s = _state.value
        if (s !is RoomTimelineState.Ready) return
        val eventId = latestVisibleEventId(s.rows, keys)
        val signal = receipts.observe(eventId, isScrollInProgress)
        if (signal !is ReadReceiptSignal.Read) return
        viewModelScope.launch {
            val handle = timeline ?: return@launch
            try {
                handle.sendReadReceipt(signal.eventId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Receipts are best effort; the tracker only redoes a skipped one on the next event.
            }
        }
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
        launchSend(body) {
            // Keep anything typed after submit, but clear exactly the text we sent.
            _draft.compareAndSet(raw, "")
        }
    }

    /**
     * Sends a native quick action's slash command ("/new", "/stop", "/model … --session") as a
     * text message for the bot to parse — identical to typing the command, which is the point:
     * the bot treats both the same. The draft is never touched. Shares the send gate (a command
     * is dropped while another send is in flight) and surfaces failures through [sendFailed].
     */
    fun sendCommand(command: String) {
        val body = command.trim()
        if (!body.startsWith("/")) return
        if (!_sending.compareAndSet(false, true)) return
        launchSend(body) {}
    }

    /** Body sender that assumes the send gate is already held; [onAccepted] on timeline receipt. */
    private fun launchSend(body: String, onAccepted: () -> Unit) {
        _sendFailed.value = false
        viewModelScope.launch {
            try {
                val handle = timeline
                val sent = handle != null && handle.sendText(body)
                if (sent) onAccepted() else _sendFailed.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _sendFailed.value = true
            } finally {
                _sending.value = false
            }
        }
    }

    /**
     * Sends [uri] (a content URI from the photo picker) as an image message: a copy is staged into
     * a cache file with a safe filename, uploaded through the timeline, and the copy is deleted.
     * Image sends share the text send gate, so they never run while a text send/edit is in flight.
     * Failures set [sendFailed]; the draft is untouched.
     */
    fun sendImage(uri: String) {
        if (!_sending.compareAndSet(false, true)) return
        _sendFailed.value = false
        viewModelScope.launch {
            try {
                val staged = stageImage(uri)
                if (staged == null) {
                    _sendFailed.value = true
                    return@launch
                }
                try {
                    val handle = timeline
                    val sent = handle != null && handle.sendImage(staged.path, staged.filename, staged.mimetype)
                    if (!sent) _sendFailed.value = true
                } finally {
                    // Whatever happened, the staged copy is disposable.
                    removeStaged(staged)
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

    /**
     * Enters edit mode for [itemId] on an own text message: the composer prefills its body and the
     * send button becomes "save edit". Ignored while a send is in flight or already editing, both
     * leave the composer unchanged.
     */
    fun beginEdit(itemId: EventOrTxnId, body: String) {
        if (_sending.value) return
        if (_editing.value != null) return
        draftBeforeEdit = _draft.value
        _editFailed.value = false
        _editing.value = itemId
        _draft.value = body
    }

    /**
     * Applies the composer text (trimmed) to the item being edited. Blank input is ignored. The
     * composer returns to send mode only when the edit is accepted — either way nothing typed after
     * the submit is lost.
     */
    fun submitEdit() {
        val itemId = _editing.value ?: return
        val raw = _draft.value
        val body = raw.trim()
        if (body.isEmpty()) return
        if (!_sending.compareAndSet(false, true)) return
        _editFailed.value = false
        viewModelScope.launch {
            try {
                val handle = timeline
                val ok = handle != null && handle.edit(itemId, body)
                if (ok) {
                    // Keep anything typed after submit, but clear exactly the text we sent.
                    _draft.compareAndSet(raw, "")
                    _editing.compareAndSet(itemId, null)
                } else {
                    _editFailed.value = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _editFailed.value = true
            } finally {
                _sending.value = false
            }
        }
    }

    /** Leaves edit mode and restores the draft from before it started. */
    fun cancelEdit() {
        val editing = _editing.value ?: return
        if (_editing.compareAndSet(editing, null)) {
            _editFailed.value = false
            _draft.value = draftBeforeEdit ?: ""
        }
        draftBeforeEdit = null
    }

    /** Re-opens the timeline after a failure. */
    fun retry() {
        if (_state.value is RoomTimelineState.Failed) open()
    }

    /** Refreshes the command catalog; a failure (gateway offline) keeps the list empty. */
    private fun loadCommandCatalog() {
        viewModelScope.launch {
            _commands.value = try {
                loadCommands()
                    .filter { (name, _) -> name.isNotBlank() }
                    .map { (name, desc) -> CommandEntry(name, desc) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Opens the model picker, fetching the provider catalogue from the gateway REST API the first
     * time (best-effort: a failure leaves the sheet showing retry-close-retry). Later opens reuse
     * the loaded providers.
     */
    fun openModelPicker() {
        val current = _modelPicker.value
        if (current.open) return
        if (current.providers.isNotEmpty()) {
            _modelPicker.value = ModelPickerUi(open = true, providers = current.providers)
            return
        }
        _modelPicker.value = ModelPickerUi(open = true, loading = true)
        viewModelScope.launch {
            _modelPicker.value = try {
                ModelPickerUi(open = true, providers = loadProviders())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ModelPickerUi(open = true, failed = true)
            }
        }
    }

    /** Closes the model picker sheet, keeping the already-loaded providers for the next open. */
    fun dismissModelPicker() {
        _modelPicker.value = _modelPicker.value.copy(open = false)
    }

    /** Applies a model pick: closes the sheet and sends the SESSION-scope /model command. */
    fun selectModel(provider: String, model: String) {
        _modelPicker.value = _modelPicker.value.copy(open = false)
        sendCommand(modelCommand(provider, model))
    }

    private fun closeTimeline() {
        collectJob?.cancel()
        collectJob = null
        timeline?.close()
        timeline = null
        // The previous handle cannot confirm or retry anything; editing must not spill across one.
        _editing.value = null
        draftBeforeEdit = null
        _editFailed.value = false
        _sendFailed.value = false
        receipts.reset()
    }

    override fun onCleared() {
        closeTimeline()
        super.onCleared()
    }

    companion object {
        private const val PAGE_SIZE = 50
    }
}
