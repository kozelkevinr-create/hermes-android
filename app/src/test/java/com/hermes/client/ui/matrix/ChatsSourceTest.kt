package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val ready = MatrixState.Ready("@a:x", "D", "https://x", "@b:x")

    private fun TestScope.collect(vm: ChatsSourceViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.source.collect {} }

    @Test fun source_is_null_until_the_saved_choice_has_loaded() = runTest {
        val saved = Channel<ChatsSource?>(Channel.UNLIMITED)
        val matrix = MutableStateFlow<MatrixState>(ready)
        val vm = ChatsSourceViewModel(saved.receiveAsFlow(), matrix, {}, {})
        collect(vm)
        assertNull(vm.source.value)

        saved.send(ChatsSource.GATEWAY)
        assertEquals(ChatsSource.GATEWAY, vm.source.value)
    }

    @Test fun saved_choice_is_shown_without_consulting_the_default() = runTest {
        val saved = MutableStateFlow<ChatsSource?>(ChatsSource.GATEWAY)
        val vm = ChatsSourceViewModel(saved, MutableStateFlow(ready), {}, {})
        collect(vm)
        assertEquals(ChatsSource.GATEWAY, vm.source.value)
    }

    @Test fun default_is_frozen_when_a_login_moves_from_logging_in_to_ready() = runTest {
        val saved = MutableStateFlow<ChatsSource?>(null)
        val matrix = MutableStateFlow<MatrixState>(MatrixState.LoggingIn)
        val vm = ChatsSourceViewModel(saved, matrix, {}, {})
        collect(vm)
        assertEquals(ChatsSource.GATEWAY, vm.source.value)

        matrix.value = ready
        assertEquals(ChatsSource.GATEWAY, vm.source.value)
    }

    @Test fun default_is_matrix_when_restoring_and_stays_matrix_after_ready_and_logout() = runTest {
        val saved = MutableStateFlow<ChatsSource?>(null)
        val matrix = MutableStateFlow<MatrixState>(MatrixState.Restoring)
        val vm = ChatsSourceViewModel(saved, matrix, {}, {})
        collect(vm)
        assertEquals(ChatsSource.MATRIX, vm.source.value)

        matrix.value = ready
        assertEquals(ChatsSource.MATRIX, vm.source.value)
        matrix.value = MatrixState.LoggedOut
        assertEquals(ChatsSource.MATRIX, vm.source.value)
    }

    @Test fun only_an_explicit_select_changes_the_resolved_default() = runTest {
        val saved = MutableStateFlow<ChatsSource?>(null)
        val matrix = MutableStateFlow<MatrixState>(MatrixState.LoggedOut)
        val persisted = mutableListOf<ChatsSource>()
        val vm = ChatsSourceViewModel(saved, matrix, {
            persisted += it
            saved.value = it
        }, {})
        collect(vm)
        assertEquals(ChatsSource.GATEWAY, vm.source.value)

        matrix.value = ready
        assertEquals(ChatsSource.GATEWAY, vm.source.value)

        vm.select(ChatsSource.MATRIX)
        assertEquals(listOf(ChatsSource.MATRIX), persisted)
        assertEquals(ChatsSource.MATRIX, vm.source.value)
    }

    @Test fun profiles_are_refreshed_once_in_the_background_and_failures_are_ignored() = runBlocking {
        val refreshed = CompletableDeferred<Unit>()
        var calls = 0
        val vm = ChatsSourceViewModel(
            MutableStateFlow<ChatsSource?>(ChatsSource.MATRIX),
            MutableStateFlow<MatrixState>(MatrixState.LoggedOut),
            {},
        ) {
            calls++
            refreshed.complete(Unit)
            error("profile list unavailable")
        }
        withTimeout(5_000) { refreshed.await() }
        assertEquals(1, calls)
        assertNotNull(vm)
    }
}
