package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.EventOrTxnId
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.matrix.MatrixTimelineHandle
import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.MessageSendState
import com.hermes.client.data.matrix.PaginationState
import com.hermes.client.data.matrix.StagedImage
import com.hermes.client.data.matrix.TimelineEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeTimeline : MatrixTimelineHandle {
    private val _items = MutableStateFlow<List<TimelineEntry>>(emptyList())
    override val items: StateFlow<List<TimelineEntry>> = _items
    private val _pagination = MutableStateFlow(PaginationState.Idle)
    override val paginationState: StateFlow<PaginationState> = _pagination

    var closed = false
    var pages = 0
    var nextAtStart = false

    val sent = mutableListOf<String>()
    var sendResult = true
    var sendGate: CompletableDeferred<Unit>? = null

    val retried = mutableListOf<EventOrTxnId>()
    var retryResult = true
    val aborted = mutableListOf<EventOrTxnId>()
    var abortResult = true
    val edits = mutableListOf<Pair<EventOrTxnId, String>>()
    var editResult = true
    val receiptsSent = mutableListOf<String>()
    var receiptResult = true
    val imagesSent = mutableListOf<Triple<String, String, String?>>()
    var imageResult = true

    override suspend fun sendImage(path: String, filename: String, mimetype: String?): Boolean {
        imagesSent += Triple(path, filename, mimetype)
        return imageResult
    }

    override suspend fun sendReadReceipt(eventId: String): Boolean {
        receiptsSent += eventId
        return receiptResult
    }

    override suspend fun edit(itemId: EventOrTxnId, newBody: String): Boolean {
        edits += itemId to newBody
        return editResult
    }

    override suspend fun sendText(body: String): Boolean {
        sent += body
        sendGate?.await()
        return sendResult
    }

    override suspend fun retrySend(itemId: EventOrTxnId): Boolean {
        retried += itemId
        return retryResult
    }

    override suspend fun abortSend(itemId: EventOrTxnId): Boolean {
        aborted += itemId
        return abortResult
    }

    override suspend fun paginateBackwards(count: Int): Boolean {
        pages++
        if (nextAtStart) {
            _pagination.value = PaginationState.AtStart
            return true
        }
        _pagination.value = PaginationState.Idle
        return false
    }

    override fun close() {
        closed = true
    }

    fun setItems(entries: List<TimelineEntry>) {
        _items.value = entries
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MatrixRoomViewModelTest {
    private val roomId = "!room:example.test"
    private val matrix = MutableStateFlow<MatrixState>(MatrixState.LoggedOut)
    private val rooms = MutableStateFlow<List<MatrixRoomSummary>>(emptyList())
    private var nextTimeline: MatrixTimelineHandle? = null
    private val opened = mutableListOf<String>()

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(
        loadCommands: suspend () -> List<Pair<String, String>> = { emptyList() },
    ): MatrixRoomViewModel {
        opened.clear()
        return MatrixRoomViewModel(
            roomId,
            matrix,
            rooms,
            openTimeline = { id ->
                thrownOnOpen?.let { throw it }
                opened += id
                nextTimeline
            },
            stageImage = { uri ->
                nextStagedImage.also { stagedUris += uri }
            },
            removeStaged = { staged ->
                stagedPathsRemoved += staged.path
            },
            loadCommands = loadCommands,
        )
    }

    /** Staged fake for [MatrixRoomViewModel.sendImage]; null makes staging fail. */
    private var nextStagedImage: StagedImage? = StagedImage("/cache/stage-1.jpg", "photo.jpg", "image/jpeg")
    private val stagedUris = mutableListOf<String>()
    private val stagedPathsRemoved = mutableListOf<String>()

    /** When set, the next [MatrixRoomViewModel] open call throws it (the outer test controls timing). */
    private var thrownOnOpen: Throwable? = null

    private fun readySummary() = MatrixRoomSummary(roomId, "Room", isEncrypted = true, unread = 0, isDirect = true)

    private fun readyRoom(): FakeTimeline {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        nextTimeline = timeline
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        return timeline
    }

    @Test
    fun signed_out_when_matrix_is_logged_out() = runTest {
        val vm = vm()
        assertEquals(RoomTimelineState.SignedOut, vm.state.value)
        assertNull(vm.room.value)
    }

    @Test
    fun opening_while_restoring() = runTest {
        matrix.value = MatrixState.Restoring
        val vm = vm()
        assertEquals(RoomTimelineState.Opening, vm.state.value)
    }

    @Test
    fun ready_state_folds_items_to_rows_and_reflects_the_room() = runTest {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        nextTimeline = timeline
        timeline.setItems(
            listOf(
                TimelineEntry.Message("1", "@a:example.test", "", isOwn = false, ts = 1, body = "hi", isEdited = false, kind = MessageKind.TEXT, sendState = MessageSendState.SENT),
            ),
        )
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()

        assertEquals(listOf(roomId), opened)
        assertEquals("Room", vm.room.value?.name)
        assertTrue(vm.room.value?.isEncrypted == true)
        val ready = vm.state.value as RoomTimelineState.Ready
        assertEquals(1, ready.rows.size)
        assertFalse(ready.isAtStart)
    }

    @Test
    fun failed_when_the_room_is_unknown() = runTest {
        rooms.value = listOf(readySummary())
        nextTimeline = null
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()
        assertEquals(RoomTimelineState.Failed("Room not found"), vm.state.value)
    }

    @Test
    fun failed_when_open_throws() = runTest {
        rooms.value = listOf(readySummary())
        thrownOnOpen = RuntimeException("boom")
        val vm = vm()
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        assertEquals(RoomTimelineState.Failed("boom"), vm.state.value)
    }

    @Test
    fun load_more_pages_backwards_and_reaches_start() = runTest {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        nextTimeline = timeline
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()
        assertTrue(vm.state.value is RoomTimelineState.Ready)

        vm.loadMore()
        assertEquals(1, timeline.pages)
        assertFalse((vm.state.value as RoomTimelineState.Ready).isAtStart)

        timeline.nextAtStart = true
        vm.loadMore()
        assertEquals(2, timeline.pages)
        assertTrue((vm.state.value as RoomTimelineState.Ready).isAtStart)

        // At the start, no further page is requested.
        vm.loadMore()
        assertEquals(2, timeline.pages)
    }

    @Test
    fun send_trims_the_text_and_reports_success() = runTest {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        nextTimeline = timeline
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()

        vm.updateDraft("  hello there  ")
        vm.send()
        advanceUntilIdle()

        assertEquals(listOf("hello there"), timeline.sent)
        assertFalse(vm.sending.value)
        assertFalse(vm.sendFailed.value)
    }

    @Test
    fun send_failure_is_surfaced() = runTest {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        timeline.sendResult = false
        nextTimeline = timeline
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()

        vm.updateDraft("hello")
        vm.send()
        advanceUntilIdle()

        assertEquals(listOf("hello"), timeline.sent)
        assertFalse(vm.sending.value)
        assertTrue(vm.sendFailed.value)
    }

    @Test
    fun blank_input_is_ignored() = runTest {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        nextTimeline = timeline
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()

        vm.updateDraft("   \n\t ")
        vm.send()
        advanceUntilIdle()

        assertTrue(timeline.sent.isEmpty())
        assertEquals("   \n\t ", vm.draft.value)
        assertFalse(vm.sending.value)
        assertFalse(vm.sendFailed.value)
    }

    @Test
    fun a_second_send_while_one_is_in_flight_is_ignored() = runTest {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        val gate = CompletableDeferred<Unit>()
        timeline.sendGate = gate
        nextTimeline = timeline
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()

        vm.updateDraft("first")
        vm.send()
        runCurrent()
        assertTrue(vm.sending.value)

        vm.send()
        runCurrent()
        assertEquals(listOf("first"), timeline.sent)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first"), timeline.sent)
        assertFalse(vm.sending.value)
    }

    @Test
    fun send_success_clears_the_draft() = runTest {
        readyRoom()
        val vm = vm()

        vm.updateDraft("hello there")
        vm.send()
        advanceUntilIdle()

        assertEquals("", vm.draft.value)
        assertFalse(vm.sendFailed.value)
    }

    @Test
    fun send_failure_keeps_the_draft() = runTest {
        val timeline = readyRoom()
        timeline.sendResult = false
        val vm = vm()

        vm.updateDraft("hello there")
        vm.send()
        advanceUntilIdle()

        assertEquals("hello there", vm.draft.value)
        assertTrue(vm.sendFailed.value)
    }

    @Test
    fun overlapping_send_keeps_the_draft() = runTest {
        val timeline = readyRoom()
        val gate = CompletableDeferred<Unit>()
        timeline.sendGate = gate
        val vm = vm()

        vm.updateDraft("first")
        vm.send()
        runCurrent()
        assertTrue(vm.sending.value)

        vm.send()
        runCurrent()
        assertEquals("first", vm.draft.value)
        assertEquals(listOf("first"), timeline.sent)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("", vm.draft.value)
    }

    @Test
    fun retry_uses_the_same_item_and_never_sends_again() = runTest {
        val timeline = readyRoom()
        val vm = vm()
        val itemId = EventOrTxnId.Transaction("txn-1")

        vm.retrySend(itemId)
        advanceUntilIdle()

        assertEquals(listOf(itemId), timeline.retried)
        assertTrue(timeline.sent.isEmpty())
    }

    @Test
    fun delete_aborts_the_failed_echo() = runTest {
        val timeline = readyRoom()
        val vm = vm()
        val itemId = EventOrTxnId.Transaction("txn-1")

        vm.abortSend(itemId)
        advanceUntilIdle()

        assertEquals(listOf(itemId), timeline.aborted)
        assertTrue(timeline.sent.isEmpty())
    }

    @Test
    fun begin_edit_prefills_the_body_and_sets_editing() = runTest {
        readyRoom()
        val vm = vm()
        val itemId = EventOrTxnId.Event("$1evt")

        vm.beginEdit(itemId, "original")
        advanceUntilIdle()

        assertEquals(itemId, vm.editing.value)
        assertEquals("original", vm.draft.value)
        assertFalse(vm.sending.value)
    }

    @Test
    fun submit_edit_sends_a_new_body_in_place_and_stops_editing() = runTest {
        val timeline = readyRoom()
        val vm = vm()
        val itemId = EventOrTxnId.Event("$1evt")

        vm.beginEdit(itemId, "original")
        vm.updateDraft(" revised ")
        vm.submitEdit()
        advanceUntilIdle()

        assertEquals(listOf(itemId to "revised"), timeline.edits)
        assertNull(vm.editing.value)
        assertEquals("", vm.draft.value)
        assertFalse(vm.editFailed.value)
        assertTrue(timeline.sent.isEmpty())
    }

    @Test
    fun submit_edit_failure_keeps_editing_and_the_draft() = runTest {
        val timeline = readyRoom()
        timeline.editResult = false
        val vm = vm()
        val itemId = EventOrTxnId.Event("$1evt")

        vm.beginEdit(itemId, "original")
        vm.updateDraft("revised")
        vm.submitEdit()
        advanceUntilIdle()

        assertTrue(vm.editFailed.value)
        assertEquals(itemId, vm.editing.value)
        assertEquals("revised", vm.draft.value)
    }

    @Test
    fun a_blank_edit_body_is_ignored() = runTest {
        val timeline = readyRoom()
        val vm = vm()
        val itemId = EventOrTxnId.Event("$1evt")

        vm.beginEdit(itemId, "original")
        vm.updateDraft("   \n\t ")
        vm.submitEdit()
        advanceUntilIdle()

        assertEquals(itemId, vm.editing.value)
        assertEquals("   \n\t ", vm.draft.value)
        assertTrue(timeline.edits.isEmpty())
    }

    @Test
    fun cancel_edit_restores_the_previous_draft() = runTest {
        readyRoom()
        val vm = vm()
        val itemId = EventOrTxnId.Event("$1evt")

        vm.updateDraft("kept")
        vm.beginEdit(itemId, "original")
        assertEquals("original", vm.draft.value)
        vm.cancelEdit()
        advanceUntilIdle()

        assertNull(vm.editing.value)
        assertEquals("kept", vm.draft.value)
        assertFalse(vm.editFailed.value)
    }

    @Test
    fun begin_edit_is_ignored_while_a_send_is_in_flight() = runTest {
        val timeline = readyRoom()
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        timeline.sendGate = gate

        vm.updateDraft("hello")
        vm.send()
        runCurrent()
        assertTrue(vm.sending.value)

        vm.beginEdit(EventOrTxnId.Event("$1evt"), "original")
        assertNull(vm.editing.value)

        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun editing_ends_when_the_timeline_is_closed() = runTest {
        readyRoom()
        val vm = vm()

        vm.beginEdit(EventOrTxnId.Event("$1evt"), "original")
        matrix.value = MatrixState.Restoring

        assertNull(vm.editing.value)
        assertEquals(RoomTimelineState.Opening, vm.state.value)
    }

    @Test
    fun logout_closes_the_timeline_and_shows_signed_out() = runTest {
        rooms.value = listOf(readySummary())
        val timeline = FakeTimeline()
        nextTimeline = timeline
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()
        assertTrue(vm.state.value is RoomTimelineState.Ready)

        matrix.value = MatrixState.LoggedOut
        assertTrue(timeline.closed)
        assertEquals(RoomTimelineState.SignedOut, vm.state.value)
    }

    @Test
    fun reopening_ready_closes_the_previous_handle() = runTest {
        rooms.value = listOf(readySummary())
        val first = FakeTimeline()
        nextTimeline = first
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        val vm = vm()
        assertTrue(vm.state.value is RoomTimelineState.Ready)
        assertFalse(first.closed)

        val second = FakeTimeline()
        nextTimeline = second
        // A distinct Ready re-emits and re-opens the timeline.
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@other:example.test")
        assertTrue(first.closed)
        assertFalse(second.closed)
        assertEquals(listOf(roomId, roomId), opened)
    }

    @Test
    fun settling_the_viewport_on_the_newest_event_sends_one_read_receipt() = runTest {
        val timeline = readyRoom()
        timeline.setItems(
            listOf(
                msgEntry("\$abc-1", ts = 1).copy(itemId = EventOrTxnId.Event("\$abc-1")),
                msgEntry("\$abc-2", ts = 2).copy(itemId = EventOrTxnId.Event("\$abc-2")),
            ),
        )
        val vm = vm()
        val ready = vm.state.value as RoomTimelineState.Ready
        val keys = ready.rows.map { timelineRowKey(it) }.toSet()

        vm.onVisibleKeysChanged(keys, isScrollInProgress = false)
        advanceUntilIdle()
        assertEquals(listOf("\$abc-2"), timeline.receiptsSent)

        vm.onVisibleKeysChanged(keys, isScrollInProgress = false)
        advanceUntilIdle()
        assertEquals(listOf("\$abc-2"), timeline.receiptsSent)
    }

    @Test
    fun scrolling_does_not_send_read_receipts() = runTest {
        val timeline = readyRoom()
        timeline.setItems(listOf(msgEntry("\$abc-1", ts = 1).copy(itemId = EventOrTxnId.Event("\$abc-1"))))
        val vm = vm()
        val ready = vm.state.value as RoomTimelineState.Ready
        val key = timelineRowKey(ready.rows.last())

        vm.onVisibleKeysChanged(setOf(key), isScrollInProgress = true)
        advanceUntilIdle()
        assertTrue(timeline.receiptsSent.isEmpty())
    }

    /** Own-viewpoint incoming message with the given remote event id as its row id. */
    private fun msgEntry(id: String, ts: Long) = TimelineEntry.Message(
        id = id,
        sender = "@bot:example.test",
        senderName = "Bot",
        isOwn = false,
        ts = ts,
        body = "hi",
        isEdited = false,
        kind = MessageKind.TEXT,
        sendState = MessageSendState.SENT,
    )

    @Test
    fun send_image_stages_sends_and_removes_the_copy() = runTest {
        val timeline = readyRoom()
        val vm = vm()

        vm.sendImage("content://picked/one")
        advanceUntilIdle()

        assertEquals(listOf("content://picked/one"), stagedUris)
        assertEquals(listOf(Triple("/cache/stage-1.jpg", "photo.jpg", "image/jpeg")), timeline.imagesSent)
        assertEquals(listOf("/cache/stage-1.jpg"), stagedPathsRemoved)
        assertFalse(vm.sendFailed.value)
        assertFalse(vm.sending.value)
        assertTrue(timeline.sent.isEmpty())
    }

    @Test
    fun staging_failure_is_surfaced_and_sends_nothing() = runTest {
        val timeline = readyRoom()
        val vm = vm()
        nextStagedImage = null

        vm.sendImage("content://broken")
        advanceUntilIdle()

        assertEquals(listOf("content://broken"), stagedUris)
        assertTrue(timeline.imagesSent.isEmpty())
        assertTrue(stagedPathsRemoved.isEmpty())
        assertTrue(vm.sendFailed.value)
        assertFalse(vm.sending.value)
    }

    @Test
    fun an_image_send_failure_is_surfaced() = runTest {
        val timeline = readyRoom()
        timeline.imageResult = false
        val vm = vm()

        vm.sendImage("content://picked/one")
        advanceUntilIdle()

        assertEquals(1, timeline.imagesSent.size)
        assertTrue(vm.sendFailed.value)
        // The staged copy is still disposed of after a failed upload.
        assertEquals(listOf("/cache/stage-1.jpg"), stagedPathsRemoved)
    }

    @Test
    fun an_image_send_while_a_text_send_is_in_flight_is_ignored() = runTest {
        val timeline = readyRoom()
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        timeline.sendGate = gate

        vm.updateDraft("first")
        vm.send()
        runCurrent()
        assertTrue(vm.sending.value)

        vm.sendImage("content://picked/one")
        runCurrent()
        assertTrue(timeline.imagesSent.isEmpty())
        assertTrue(stagedUris.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
    }

    // ---- command catalog (palette source) ----

    @Test
    fun the_command_catalog_loads_when_the_room_opens() = runTest {
        val timeline = readyRoom()
        val vm = vm(loadCommands = { listOf("/new" to "Start a new session", "/model" to "Switch model") })
        advanceUntilIdle()

        assertTrue(vm.state.value is RoomTimelineState.Ready)
        assertEquals(
            listOf(CommandEntry("/new", "Start a new session"), CommandEntry("/model", "Switch model")),
            vm.commands.value,
        )
    }

    @Test
    fun a_failing_catalog_load_is_tolerated_and_leaves_no_palette() = runTest {
        readyRoom()
        val vm = vm(loadCommands = { error("socket closed") })
        advanceUntilIdle()

        // The timeline still works; the palette just stays empty.
        assertTrue(vm.state.value is RoomTimelineState.Ready)
        assertEquals(emptyList<CommandEntry>(), vm.commands.value)
    }

    @Test
    fun blank_command_names_are_dropped() = runTest {
        readyRoom()
        val vm = vm(loadCommands = { listOf("  " to "junk", "/new" to "New") })
        advanceUntilIdle()

        assertEquals(listOf(CommandEntry("/new", "New")), vm.commands.value)
    }
}
