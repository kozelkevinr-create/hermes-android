package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixRoomDetails
import com.hermes.client.data.matrix.MatrixRoomMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/** The settings VM against a recording fake; the unconfined dispatcher settles launches at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class MatrixRoomSettingsViewModelTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private class FakeAdmin : RoomAdminApi {
        var details: MatrixRoomDetails? = detailsOf()
        var succeed = true
        val calls = mutableListOf<String>()

        override suspend fun details(roomId: String): MatrixRoomDetails? {
            calls += "details:$roomId"
            return details
        }

        override suspend fun rename(roomId: String, name: String): Boolean {
            calls += "rename:$name"
            if (succeed) details = details?.copy(name = name)
            return succeed
        }

        override suspend fun setTopic(roomId: String, topic: String): Boolean {
            calls += "topic:$topic"
            if (succeed) details = details?.copy(topic = topic)
            return succeed
        }

        override suspend fun invite(roomId: String, userId: String): Boolean {
            calls += "invite:$userId"
            return succeed
        }

        override suspend fun remove(roomId: String, userId: String): Boolean {
            calls += "remove:$userId"
            return succeed
        }

        override suspend fun leave(roomId: String): Boolean {
            calls += "leave"
            return succeed
        }

        override suspend fun setFavourite(roomId: String, favourite: Boolean): Boolean {
            calls += "favourite:$favourite"
            if (succeed) details = details?.copy(isFavourite = favourite)
            return succeed
        }

        override suspend fun setMarkedUnread(roomId: String, unread: Boolean): Boolean {
            calls += "unread:$unread"
            if (succeed) details = details?.copy(isMarkedUnread = unread)
            return succeed
        }
    }

    private val fake = FakeAdmin()

    @Before fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        fake.details = detailsOf()
        fake.succeed = true
        fake.calls.clear()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = MatrixRoomSettingsViewModel("!room:hs", fake)

    private fun MatrixRoomSettingsViewModel.ready() = ui.value as RoomSettingsUi.Ready

    @Test
    fun loads_the_details_on_creation() = runTest {
        val v = vm()
        advanceUntilIdle()
        assertEquals("Room", v.ready().details.name)
        assertEquals(listOf("details:!room:hs"), fake.calls)
    }

    @Test
    fun an_unknown_room_reads_unavailable() = runTest {
        fake.details = null
        val v = vm()
        advanceUntilIdle()
        assertEquals(RoomSettingsUi.Unavailable, v.ui.value)
    }

    @Test
    fun rename_trims_saves_and_shows_the_new_name() = runTest {
        val v = vm()
        v.rename("  Plans  ")
        advanceUntilIdle()
        assertTrue("rename:Plans" in fake.calls)
        assertEquals("Plans", v.ready().details.name)
        assertEquals("Name saved.", v.ready().notice)
        assertFalse(v.ready().busy)
    }

    @Test
    fun a_blank_name_is_refused_without_a_call() = runTest {
        val v = vm()
        v.rename("   ")
        advanceUntilIdle()
        assertEquals(listOf("details:!room:hs"), fake.calls)
        assertEquals("Give the room a name.", v.ready().notice)
    }

    @Test
    fun a_failed_rename_keeps_the_old_name_and_says_so() = runTest {
        val v = vm()
        fake.succeed = false
        v.rename("Plans")
        advanceUntilIdle()
        assertEquals("Room", v.ready().details.name)
        assertEquals("Could not rename the room.", v.ready().notice)
    }

    @Test
    fun invite_checks_the_matrix_id_shape_first() = runTest {
        val v = vm()
        v.invite("not an id")
        advanceUntilIdle()
        assertTrue(fake.calls.none { it.startsWith("invite:") })
        assertEquals("Enter a Matrix ID like @name:server.", v.ready().notice)

        v.invite("  @sam:home.lan ")
        advanceUntilIdle()
        assertTrue("invite:@sam:home.lan" in fake.calls)
        assertEquals("Invite sent.", v.ready().notice)
    }

    @Test
    fun favourite_and_unread_flags_round_trip_without_a_notice() = runTest {
        val v = vm()
        v.setFavourite(true)
        advanceUntilIdle()
        v.setMarkedUnread(true)
        advanceUntilIdle()
        assertTrue(v.ready().details.isFavourite)
        assertTrue(v.ready().details.isMarkedUnread)
        assertNull(v.ready().notice)
    }

    @Test
    fun remove_calls_through_and_reloads() = runTest {
        val v = vm()
        v.remove("@bot:home.lan")
        advanceUntilIdle()
        assertTrue("remove:@bot:home.lan" in fake.calls)
        assertEquals("Member removed.", v.ready().notice)
    }

    @Test
    fun leaving_marks_the_screen_done_and_failure_does_not() = runTest {
        val v = vm()
        v.leave()
        advanceUntilIdle()
        assertTrue(v.ready().left)

        val failing = vm()
        fake.succeed = false
        failing.leave()
        advanceUntilIdle()
        assertFalse(failing.ready().left)
        assertEquals("Could not leave the room.", failing.ready().notice)
    }

    @Test
    fun actions_are_ignored_before_the_details_load() = runTest {
        fake.details = null
        val v = vm()
        advanceUntilIdle()
        v.rename("Plans")
        v.setFavourite(true)
        advanceUntilIdle()
        assertTrue(fake.calls.none { it.startsWith("rename:") || it.startsWith("favourite:") })
    }

    @Test
    fun the_leave_warning_differs_for_direct_messages() {
        assertTrue(leaveWarning(detailsOf(isDirect = true)).contains("conversation"))
        assertTrue(leaveWarning(detailsOf(isDirect = false)).contains("invites you"))
    }

    companion object {
        fun detailsOf(isDirect: Boolean = false) = MatrixRoomDetails(
            name = "Room",
            topic = "",
            isDirect = isDirect,
            isFavourite = false,
            isMarkedUnread = false,
            canRename = true,
            canSetTopic = true,
            canInvite = true,
            members = listOf(
                MatrixRoomMember("@me:home.lan", "Me", isMe = true, isInvited = false, canRemove = false),
                MatrixRoomMember("@bot:home.lan", "Bot", isMe = false, isInvited = false, canRemove = true),
            ),
        )
    }
}
