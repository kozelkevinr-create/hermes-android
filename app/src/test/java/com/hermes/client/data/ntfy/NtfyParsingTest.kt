package com.hermes.client.data.ntfy

import com.hermes.client.ui.chat.ApprovalChoice
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyParsingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val rid = "a".repeat(32)
    private val nonce = "N".repeat(22)
    private val relay = "http://192.168.0.127:8877"

    private fun line(extra: String = "", tags: String = """["warning","scopes-once-session-always-deny"]""") =
        """{"id":"m1","time":1700000000,"event":"message","topic":"approvals","title":"Approval: respond within 30m",""" +
            """"message":"recursive delete\n\n$ rm -rf /tmp/x\n\nAllow once runs THIS command only. No reply = deny.",""" +
            """"priority":4,"tags":$tags,"actions":[""" +
            """{"action":"view","label":"Allow once","url":"$relay/allow/$rid/$nonce","clear":true},""" +
            """{"action":"view","label":"This session","url":"$relay/session/$rid/$nonce","clear":true},""" +
            """{"action":"view","label":"Deny","url":"$relay/deny/$rid/$nonce","clear":true}]$extra}"""

    private fun message(raw: String = line()) = (parseNtfyLine(json, raw) as NtfyEvent.Message).message

    @Test fun parses_a_message_event() {
        val m = message()
        assertEquals("m1", m.id)
        assertEquals("approvals", m.topic)
        assertEquals(4, m.priority)
        assertEquals(3, m.actions.size)
    }

    @Test fun keepalive_and_open_events_are_ignored() {
        assertNull(parseNtfyLine(json, """{"id":"k","time":1,"event":"keepalive","topic":"approvals"}"""))
        assertNull(parseNtfyLine(json, """{"id":"o","time":1,"event":"open","topic":"approvals"}"""))
        assertNull(parseNtfyLine(json, "not json"))
    }

    @Test fun delete_event_withdraws_the_message() {
        val ev = parseNtfyLine(json, """{"id":"m1","time":2,"event":"message_delete","topic":"approvals"}""")
        assertEquals(NtfyEvent.Withdrawn("m1", "approvals"), ev)
    }

    @Test fun approval_is_extracted_with_scopes_command_and_reply_links() {
        val a = parseNtfyApproval(message())!!
        assertEquals(rid, a.requestId)
        assertEquals(relay, a.relayBase)
        assertEquals("rm -rf /tmp/x", a.command)
        assertEquals("recursive delete", a.description)
        assertEquals(setOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.ALWAYS, ApprovalChoice.DENY), a.allowed)
        assertEquals("$relay/always/$rid/$nonce", a.replyUrl(ApprovalChoice.ALWAYS))
        assertEquals("$relay/allow/$rid/$nonce", a.replyUrl(ApprovalChoice.ONCE))
    }

    @Test fun a_choice_the_host_did_not_allow_has_no_reply_link() {
        val a = parseNtfyApproval(message(line(tags = """["warning","scopes-once-deny"]""")))!!
        assertNull(a.replyUrl(ApprovalChoice.SESSION))
        assertNull(a.replyUrl(ApprovalChoice.ALWAYS))
        assertNotNull(a.replyUrl(ApprovalChoice.DENY))
    }

    @Test fun without_a_scopes_tag_the_buttons_decide() {
        val a = parseNtfyApproval(message(line(tags = "[]")))!!
        assertTrue(ApprovalChoice.SESSION in a.allowed)
        assertTrue(ApprovalChoice.ALWAYS !in a.allowed)
    }

    @Test fun message_with_mixed_requests_or_foreign_links_is_not_an_approval() {
        val mixed = line().replace("$relay/deny/$rid/$nonce", "$relay/deny/${"b".repeat(32)}/$nonce")
        assertNull(parseNtfyApproval(message(mixed)))
        val foreign = line().replace(Regex(""""actions":\[.*]"""), """"actions":[{"action":"view","label":"Open","url":"https://example.com/x"}]""")
        assertNull(parseNtfyApproval(message(foreign)))
    }

    @Test fun server_url_is_normalised() {
        assertEquals("http://192.168.0.127:8080", normalizeServerUrl(" 192.168.0.127:8080/ "))
        assertEquals("https://ntfy.example.com", normalizeServerUrl("https://ntfy.example.com/"))
        assertEquals("", normalizeServerUrl("  "))
    }

    @Test fun config_is_usable_only_with_server_and_an_active_topic() {
        val t = NtfyTopic("Approvals", "approvals", NtfyKind.APPROVALS)
        assertTrue(NtfyConfig(true, "http://x", "u", listOf(t)).isUsable)
        assertTrue(!NtfyConfig(false, "http://x", "u", listOf(t)).isUsable)
        assertTrue(!NtfyConfig(true, "", "u", listOf(t)).isUsable)
        assertTrue(!NtfyConfig(true, "http://x", "u", listOf(t.copy(enabled = false))).isUsable)
    }
}

class NtfyClassifyTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val rid = "c".repeat(32)
    private val nonce = "K".repeat(20)
    private fun msg(topic: String, actions: String = "[]") = parseNtfyLine(
        json,
        """{"id":"x1","time":1,"event":"message","topic":"$topic","title":"T","message":"hello","tags":[],"actions":$actions}""",
    )!!
    private val kindOf = { t: String ->
        when (t) { "approvals" -> NtfyKind.APPROVALS; "alerts" -> NtfyKind.GENERAL; else -> null }
    }

    @Test fun general_topic_message_is_an_alert() {
        assertTrue(classifyNtfy(msg("alerts"), kindOf) is NtfyDelivery.Alert)
    }

    @Test fun unknown_topic_is_ignored() {
        assertNull(classifyNtfy(msg("other"), kindOf))
    }

    @Test fun approvals_topic_with_relay_buttons_is_an_approval() {
        val a = """[{"action":"view","label":"Allow once","url":"http://r:8877/allow/$rid/$nonce"},{"action":"view","label":"Deny","url":"http://r:8877/deny/$rid/$nonce"}]"""
        assertTrue(classifyNtfy(msg("approvals", a), kindOf) is NtfyDelivery.Approval)
    }

    @Test fun approvals_topic_message_without_relay_buttons_falls_back_to_an_alert() {
        assertTrue(classifyNtfy(msg("approvals"), kindOf) is NtfyDelivery.Alert)
    }

    @Test fun withdrawal_on_a_known_topic_clears_by_message_id() {
        assertEquals(NtfyDelivery.Withdraw("x1"), classifyNtfy(NtfyEvent.Withdrawn("x1", "approvals"), kindOf))
        assertNull(classifyNtfy(NtfyEvent.Withdrawn("x1", "other"), kindOf))
    }
}
