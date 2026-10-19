package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MatrixRoomListViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun room(id: String, ts: Long?) = MatrixRoomSummary(id, id, false, 0, true, lastActivityTs = ts)

    @Test fun maps_account_state_to_the_screen_state_and_follows_changes() = runTest {
        val matrix = MutableStateFlow<MatrixState>(MatrixState.LoggedOut)
        val rooms = MutableStateFlow<List<MatrixRoomSummary>>(emptyList())
        val vm = MatrixRoomListViewModel(matrix, rooms) {}
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(RoomListUiState.SignedOut, vm.uiState.value)

        matrix.value = MatrixState.Restoring
        assertEquals(RoomListUiState.Loading, vm.uiState.value)

        matrix.value = MatrixState.Ready("@a:x", "D", "https://x", "@b:x")
        rooms.value = listOf(room("old", 1), room("new", 2))
        val ready = vm.uiState.value as RoomListUiState.Ready
        assertEquals(listOf("new", "old"), ready.rooms.map { it.id })
        assertTrue(ready.hasAnyRooms)
        collector.cancel()
    }

    @Test fun search_query_filters_the_ready_rooms() = runTest {
        val matrix = MutableStateFlow<MatrixState>(MatrixState.Ready("@a:x", "D", "https://x", "@b:x"))
        val rooms = MutableStateFlow(listOf(room("alpha", 2), room("beta", 1)))
        val vm = MatrixRoomListViewModel(matrix, rooms) {}
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.onQueryChange("BET")
        val ready = vm.uiState.value as RoomListUiState.Ready
        assertEquals(listOf("beta"), ready.rooms.map { it.id })
        assertEquals("BET", ready.query)
        collector.cancel()
    }

    @Test fun restore_failure_banner_retry_invokes_the_restore() = runTest {
        val matrix = MutableStateFlow<MatrixState>(MatrixState.RestoreFailed("offline", retryable = true))
        val rooms = MutableStateFlow<List<MatrixRoomSummary>>(emptyList())
        var retries = 0
        val vm = MatrixRoomListViewModel(matrix, rooms) { retries++ }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        assertEquals(RoomListUiState.RestoreFailed("offline", retryable = true), vm.uiState.value)
        vm.retry()
        assertEquals(1, retries)
        collector.cancel()
    }
}
