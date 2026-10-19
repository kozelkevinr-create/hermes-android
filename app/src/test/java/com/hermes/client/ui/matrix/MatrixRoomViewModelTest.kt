package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.EventOrTxnId
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.matrix.MatrixTimelineHandle
import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.MessageSendState
import com.hermes.client.data.matrix.PaginationState
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

    private fun vm(): MatrixRoomViewModel {
        opened.clear()
        return MatrixRoomViewModel(roomId, matrix, rooms) { id ->
            opened += id
            nextTimeline
        }
    }

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
        val vm2 = MatrixRoomViewModel(roomId, matrix, rooms) { throw RuntimeException("boom") }
        matrix.value = MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test")
        assertEquals(RoomTimelineState.Failed("boom"), vm2.state.value)
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
}
