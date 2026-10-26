package com.hermes.client.notifications

import com.hermes.client.data.ntfy.NtfyApproval
import com.hermes.client.data.ntfy.NtfyMessage
import com.hermes.client.ui.chat.ApprovalChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NtfyNotificationMapperTest {
    private val on = NotificationPrefs(enabled = true)
    private fun message(id: String = "m1") = NtfyMessage(id, "t", 0, "Title", "Body", 3, emptyList(), emptyList())
    private fun approval(allowed: Set<ApprovalChoice>) =
        NtfyApproval("m1", "r".repeat(32), "n".repeat(20), "http://r", allowed, "T", "delete things", "rm -rf x")

    @Test fun approval_buttons_follow_what_the_host_allows() {
        val all = ntfyNotificationSpec(message(), approval(ApprovalChoice.entries.toSet()), on)!!
        assertEquals(listOf("Allow once", "Session", "Deny"), all.actions.map { it.label })
        val once = ntfyNotificationSpec(message(), approval(setOf(ApprovalChoice.ONCE, ApprovalChoice.DENY)), on)!!
        assertEquals(listOf("Allow once", "Deny"), once.actions.map { it.label })
    }

    @Test fun approval_actions_name_the_ntfy_message() {
        val spec = ntfyNotificationSpec(message("abc"), approval(ApprovalChoice.entries.toSet()), on)!!
        assertEquals(setOf(Notif.NTFY_SESSION), spec.actions.map { it.sessionId }.toSet())
        assertEquals(setOf<String?>("abc"), spec.actions.map { it.requestId }.toSet())
        assertEquals(Notif.CHANNEL_APPROVALS, spec.channelId)
    }

    @Test fun plain_message_is_a_buttonless_alert() {
        val spec = ntfyNotificationSpec(message(), null, on)!!
        assertEquals(Notif.CHANNEL_NTFY, spec.channelId)
        assertEquals(emptyList<NotifAction>(), spec.actions)
        assertEquals("Body", spec.body)
    }

    @Test fun respects_the_master_and_approval_toggles() {
        assertNull(ntfyNotificationSpec(message(), null, NotificationPrefs(enabled = false)))
        assertNull(ntfyNotificationSpec(message(), approval(setOf(ApprovalChoice.DENY)), on.copy(approvals = false)))
    }

    @Test fun each_message_gets_its_own_notification_id() {
        assertNotEquals(ntfyNotificationId("a"), ntfyNotificationId("b"))
        assertEquals(ntfyNotificationSpec(message("a"), null, on)!!.id, ntfyNotificationId("a"))
    }
}
