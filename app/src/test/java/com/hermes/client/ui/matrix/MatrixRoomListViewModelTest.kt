package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixNewRoom
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * The room-list VM's new-room sheet: prefill, validation, create through the seam and the
 * created-room event for the screen's navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MatrixRoomListViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val matrix = MutableStateFlow<MatrixState>(
        MatrixState.Ready("@a:example.test", "D", "https://hs", "@bot:example.test"),
    )
    private val rooms = MutableStateFlow<List<MatrixRoomSummary>>(emptyList())
    private val defaultEncrypted = MutableStateFlow(true)
    private val createLog = mutableListOf<MatrixNewRoom>()
    private var nextCreateResult: String? = "rid-1"

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun vm(): MatrixRoomListViewModel {
        createLog.clear()
        nextCreateResult = "rid-1"
        return MatrixRoomListViewModel(
            matrix,
            rooms,
            onRetry = {},
            onCreateRoom = { room ->
                createLog += room
                nextCreateResult
            },
            defaultEncrypted = defaultEncrypted,
        )
    }

    @Test
    fun the_sheet_opens_prefilled_from_state_and_setting() = runTest {
        defaultEncrypted.value = false
        val v = vm()
        v.openNewRoomSheet()
        advanceUntilIdle()

        val s = v.newRoomSheet.value
        assertTrue(s.open)
        assertFalse(s.draft.encrypted)                       // the setting is the toggle's default
        assertEquals("@bot:example.test", s.draft.invite)    // the bot prefilled
        assertEquals(NewRoomMode.ROOM, s.draft.mode)
        assertTrue(createLog.isEmpty())                      // opening sends nothing
    }

    @Test
    fun a_signed_out_room_list_never_opens_the_sheet() = runTest {
        matrix.value = MatrixState.LoggedOut
        val v = vm()
        v.openNewRoomSheet()
        advanceUntilIdle()
        assertFalse(v.newRoomSheet.value.open)
    }

    @Test
    fun editing_clears_errors_and_a_mode_switch_drops_the_room_name() = runTest {
        val v = vm()
        v.openNewRoomSheet()
        advanceUntilIdle()

        v.updateNewRoomDraft(NewRoomDraft(mode = NewRoomMode.ROOM, name = "", invite = "@bot:example.test"))
        v.createNewRoom()
        assertTrue(v.newRoomSheet.value.error != null)

        v.updateNewRoomDraft(NewRoomDraft(mode = NewRoomMode.DM, invite = "@other:x.test", encrypted = false))

        val s = v.newRoomSheet.value
        assertEquals("", s.draft.name)                          // a DM needs no room name
        assertEquals("@other:x.test", s.draft.invite)           // kept through the switch
        assertEquals(false, s.draft.encrypted)                  // kept through the switch
        assertEquals(null, s.error)                             // cleared by editing
        assertTrue(s.open)
    }

    @Test
    fun an_invalid_draft_reports_the_error_and_sends_nothing() = runTest {
        val v = vm()
        v.openNewRoomSheet()
        advanceUntilIdle()
        v.updateNewRoomDraft(NewRoomDraft(mode = NewRoomMode.ROOM, name = ""))
        v.createNewRoom()
        advanceUntilIdle()

        assertEquals("Give the room a name.", v.newRoomSheet.value.error)
        assertTrue(createLog.isEmpty())
        assertTrue(v.newRoomSheet.value.open)
    }

    @Test
    fun a_created_room_emits_its_id_and_closes_the_sheet() = runTest {
        val v = vm()
        val created = mutableListOf<String>()
        val job = launch { v.createdRoomId.collect { created += it } }
        v.openNewRoomSheet()
        advanceUntilIdle()
        v.updateNewRoomDraft(NewRoomDraft(mode = NewRoomMode.DM, invite = "@bot:example.test"))
        v.createNewRoom()
        advanceUntilIdle()

        assertEquals(
            listOf(MatrixNewRoom(name = null, encrypted = true, isDirect = true, invite = listOf("@bot:example.test"))),
            createLog,
        )
        assertEquals(listOf("rid-1"), created)
        assertFalse(v.newRoomSheet.value.open)
        job.cancel()
    }

    @Test
    fun a_failed_create_keeps_the_sheet_open_with_a_generic_error() = runTest {
        val v = vm()
        v.openNewRoomSheet()
        advanceUntilIdle()
        v.updateNewRoomDraft(NewRoomDraft(mode = NewRoomMode.ROOM, name = "Lab"))
        nextCreateResult = null
        v.createNewRoom()
        advanceUntilIdle()

        assertEquals("Couldn't create the room.", v.newRoomSheet.value.error)
        assertTrue(v.newRoomSheet.value.open)
        assertFalse(v.newRoomSheet.value.creating)
        assertEquals(1, createLog.size)
    }

    @Test
    fun dismiss_closes_the_sheet() = runTest {
        val v = vm()
        v.openNewRoomSheet()
        advanceUntilIdle()
        v.dismissNewRoomSheet()
        assertFalse(v.newRoomSheet.value.open)
    }
}
