package com.hermes.client.notifications

import com.hermes.client.data.matrix.MatrixRoomSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixNotificationMapperTest {
    private val on = NotificationPrefs(enabled = true, matrixMessages = true)

    private fun room(
        id: String = "!a:hs",
        unread: Long = 0,
        sender: String? = "hermes",
        preview: String? = "hello",
        name: String = "Hermes",
        direct: Boolean = true,
        ts: Long? = null,
    ) = MatrixRoomSummary(
        id = id, name = name, isEncrypted = true, unread = unread, isDirect = direct,
        lastMessagePreview = preview, lastMessageSender = sender, lastActivityTs = ts,
    )

    private fun diff(prev: List<MatrixRoomSummary>, next: List<MatrixRoomSummary>, prefs: NotificationPrefs = on, fg: Boolean = false) =
        diffMatrixNotifications(prev, next, prefs, fg)

    @Test fun unread_growth_in_known_room_posts_with_route_and_channel() {
        val d = diff(listOf(room(unread = 0)), listOf(room(unread = 1)))
        val spec = d.posts.single()
        assertEquals(Notif.CHANNEL_MATRIX, spec.channelId)
        assertEquals("Hermes", spec.title)
        assertEquals("hello", spec.body)
        assertEquals("matrix_room/%21a%3Ahs", spec.route)
        assertEquals(matrixNotificationId("!a:hs"), spec.id)
        assertTrue(d.cancelIds.isEmpty())
    }

    @Test fun stale_own_preview_then_activity_advance_posts_on_second_update() {
        val prev = listOf(room(ts = 1))
        val first = listOf(room(unread = 1, sender = "You", ts = 1))
        assertTrue(diff(prev, first).posts.isEmpty())
        val second = listOf(room(unread = 1, sender = "hermes", preview = "joke", ts = 2))
        assertEquals("joke", diff(first, second).posts.single().body)
    }

    @Test fun own_message_with_old_unread_pending_does_not_post() {
        val prev = listOf(room(unread = 1, ts = 1))
        assertTrue(diff(prev, listOf(room(unread = 1, sender = "You", ts = 2))).posts.isEmpty())
    }

    @Test fun activity_advance_without_unread_does_not_post() {
        assertTrue(diff(listOf(room(ts = 1)), listOf(room(unread = 0, ts = 2))).posts.isEmpty())
    }

    @Test fun rooms_leaving_the_list_have_their_notification_cleared() {
        val d = diff(listOf(room("!a:hs", unread = 1), room("!b:hs")), listOf(room("!b:hs")))
        assertEquals(listOf(matrixNotificationId("!a:hs")), d.cancelIds)
        assertEquals(listOf(matrixNotificationId("!a:hs")), diff(listOf(room("!a:hs")), emptyList()).cancelIds)
    }

    @Test fun group_room_body_includes_sender() {
        val d = diff(listOf(room(direct = false)), listOf(room(direct = false, unread = 2, sender = "alice")))
        assertEquals("alice: hello", d.posts.single().body)
    }

    @Test fun missing_preview_falls_back_to_generic_body() {
        val d = diff(listOf(room()), listOf(room(unread = 1, preview = null)))
        assertEquals("New message", d.posts.single().body)
    }

    @Test fun newly_appearing_rooms_never_notify() {
        assertTrue(diff(emptyList(), listOf(room(unread = 5))).posts.isEmpty())
    }

    @Test fun own_messages_never_notify() {
        assertTrue(diff(listOf(room()), listOf(room(unread = 1, sender = "You"))).posts.isEmpty())
    }

    @Test fun foreground_suppresses_posts_but_still_clears() {
        assertTrue(diff(listOf(room()), listOf(room(unread = 1)), fg = true).posts.isEmpty())
        val d = diff(listOf(room(unread = 2)), listOf(room(unread = 0)), fg = true)
        assertEquals(listOf(matrixNotificationId("!a:hs")), d.cancelIds)
    }

    @Test fun disabled_prefs_suppress_posts_but_still_clear() {
        val off = NotificationPrefs(enabled = true, matrixMessages = false)
        assertTrue(diff(listOf(room()), listOf(room(unread = 1)), off).posts.isEmpty())
        assertTrue(diff(listOf(room()), listOf(room(unread = 1)), NotificationPrefs(enabled = false)).posts.isEmpty())
        assertEquals(1, diff(listOf(room(unread = 1)), listOf(room(unread = 0)), off).cancelIds.size)
    }

    @Test fun unchanged_or_falling_unread_does_not_repost() {
        assertTrue(diff(listOf(room(unread = 2)), listOf(room(unread = 2))).posts.isEmpty())
        assertTrue(diff(listOf(room(unread = 3)), listOf(room(unread = 1))).posts.isEmpty())
    }

    @Test fun only_changed_rooms_post() {
        val prev = listOf(room("!a:hs"), room("!b:hs", name = "B"))
        val next = listOf(room("!a:hs"), room("!b:hs", name = "B", unread = 1))
        assertEquals(listOf("B"), diff(prev, next).posts.map { it.title })
    }

    @Test fun blank_room_name_uses_fallback_title() {
        assertEquals("New message", diff(listOf(room()), listOf(room(unread = 1, name = " "))).posts.single().title)
    }

    @Test fun ids_avoid_reserved_notification_ids_and_prefs_default_on() {
        assertTrue(matrixNotificationId("!x:hs") !in 1001..1003)
        assertTrue(NotificationPrefs().matrixMessages)
    }
}
