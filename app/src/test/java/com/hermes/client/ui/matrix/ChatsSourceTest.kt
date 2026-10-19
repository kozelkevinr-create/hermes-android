package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatsSourceTest {
    @Test fun with_no_saved_choice_the_default_follows_the_matrix_session() {
        assertEquals(ChatsSource.MATRIX, resolveChatsSource(null, matrixHasSession = true))
        assertEquals(ChatsSource.GATEWAY, resolveChatsSource(null, matrixHasSession = false))
    }

    @Test fun a_saved_choice_always_wins_over_the_default() {
        assertEquals(ChatsSource.GATEWAY, resolveChatsSource(ChatsSource.GATEWAY, matrixHasSession = true))
        assertEquals(ChatsSource.MATRIX, resolveChatsSource(ChatsSource.MATRIX, matrixHasSession = false))
    }

    @Test fun matrix_session_exists_in_restoring_ready_and_restore_failed_only() {
        assertTrue(matrixHasSavedSession(MatrixState.Ready("@a:x", "D", "https://x", "@b:x")))
        assertTrue(matrixHasSavedSession(MatrixState.Restoring))
        assertTrue(matrixHasSavedSession(MatrixState.RestoreFailed("net", retryable = true)))
        assertFalse(matrixHasSavedSession(MatrixState.LoggedOut))
        assertFalse(matrixHasSavedSession(MatrixState.LoggingIn))
        assertFalse(matrixHasSavedSession(MatrixState.Error("wrong password")))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsSourceViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun source_defaults_to_matrix_when_a_session_exists_and_gateway_otherwise() = runTest {
        val saved = MutableStateFlow<ChatsSource?>(null)
        val matrix = MutableStateFlow<MatrixState>(MatrixState.Ready("@a:x", "D", "https://x", "@b:x"))
        val vm = ChatsSourceViewModel(saved, matrix) { saved.value = it }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.source.collect {} }
        assertEquals(ChatsSource.MATRIX, vm.source.value)

        matrix.value = MatrixState.LoggedOut
        assertEquals(ChatsSource.GATEWAY, vm.source.value)
    }

    @Test fun selecting_persists_and_the_saved_choice_then_wins() = runTest {
        val saved = MutableStateFlow<ChatsSource?>(null)
        val matrix = MutableStateFlow<MatrixState>(MatrixState.LoggedOut)
        val persisted = mutableListOf<ChatsSource>()
        val vm = ChatsSourceViewModel(saved, matrix) {
            persisted += it
            saved.value = it
        }
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.source.collect {} }
        assertEquals(ChatsSource.GATEWAY, vm.source.value)

        vm.select(ChatsSource.MATRIX)
        assertEquals(listOf(ChatsSource.MATRIX), persisted)
        assertEquals(ChatsSource.MATRIX, vm.source.value)
        collector.cancel()
    }
}
