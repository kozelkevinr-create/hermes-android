package com.hermes.client.ui.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewRoomFormTest {

    // ---- parseInvites ----

    @Test
    fun invites_parse_from_spaces_and_commas() {
        assertEquals(listOf("@a:one.test", "@b:two.test", "@c:three.test"), parseInvites(" @a:one.test ,@b:two.test  @c:three.test"))
        assertEquals(emptyList<String>(), parseInvites("  ,  "))
    }

    // ---- mxidIsValid ----

    @Test
    fun mxid_shapes() {
        assertTrue(mxidIsValid("@kevin:matrix.home.lan"))
        assertTrue(mxidIsValid("@1:2"))
        assertFalse(mxidIsValid("kevin:matrix.home.lan"))
        assertFalse(mxidIsValid("@kevin"))
        assertFalse(mxidIsValid("@:home.lan"))
        assertFalse(mxidIsValid("@kevin:"))
        assertFalse(mxidIsValid("@kevin:matrix.home.lan:9"))
        assertFalse(mxidIsValid("@ kevin:home.lan"))
    }

    // ---- newRoomError ----

    @Test
    fun a_named_room_without_invites_is_valid() {
        assertNull(newRoomError(NewRoomDraft(mode = NewRoomMode.ROOM, name = "Home")))
    }

    @Test
    fun a_room_needs_a_name() {
        assertEquals("Give the room a name.", newRoomError(NewRoomDraft(mode = NewRoomMode.ROOM, name = "   ")))
    }

    @Test
    fun a_room_with_valid_invites_is_fine_but_not_a_bad_mxid() {
        assertNull(newRoomError(NewRoomDraft(mode = NewRoomMode.ROOM, name = "Home", invite = "@bot:x.test")))
        assertEquals(
            "One of the invites is not an \"@user:server\".",
            newRoomError(NewRoomDraft(mode = NewRoomMode.ROOM, name = "Home", invite = "@bot:x.test, junk")),
        )
    }

    @Test
    fun a_dm_needs_at_least_one_invite() {
        assertEquals(
            "Add at least one \"@user:server\" to invite.",
            newRoomError(NewRoomDraft(mode = NewRoomMode.DM, invite = "  ")),
        )
    }

    // ---- newRoomRequest ----

    @Test
    fun a_room_request_trims_the_name_and_keeps_invites() {
        assertEquals(
            com.hermes.client.data.matrix.MatrixNewRoom(
                name = "Home",
                encrypted = true,
                isDirect = false,
                invite = listOf("@bot:x.test"),
            ),
            newRoomRequest(NewRoomDraft(mode = NewRoomMode.ROOM, name = "  Home  ", invite = "@bot:x.test")),
        )
    }

    @Test
    fun a_dm_request_is_nameless_and_direct() {
        assertEquals(
            com.hermes.client.data.matrix.MatrixNewRoom(
                name = null,
                encrypted = false,
                isDirect = true,
                invite = listOf("@bot:x.test", "@a:y.test"),
            ),
            newRoomRequest(NewRoomDraft(mode = NewRoomMode.DM, name = "ignored", encrypted = false, invite = "@bot:x.test @a:y.test")),
        )
    }

    @Test
    fun invalid_drafts_build_no_request() {
        assertTrue(newRoomRequest(NewRoomDraft(mode = NewRoomMode.ROOM, name = " ")) == null)
        assertTrue(newRoomRequest(NewRoomDraft(mode = NewRoomMode.DM, invite = "junk")) == null)
    }
}
