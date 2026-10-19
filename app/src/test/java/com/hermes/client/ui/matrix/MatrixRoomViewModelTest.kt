package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.matrix.MatrixTimelineHandle
import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.MessageSendState
import com.hermes.client.data.matrix.PaginationState
import com.hermes.client.data.matrix.TimelineEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
}
