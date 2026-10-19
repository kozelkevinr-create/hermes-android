package com.hermes.client.data.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogTest {
    @Before fun setUp() {
        DebugLog.setTokenToRedact(null)
        DebugLog.setEnabled(true)
        DebugLog.clear()
    }

    @After fun tearDown() {
        DebugLog.setEnabled(false)
        DebugLog.setTokenToRedact(null)
        DebugLog.clear()
    }

    @Test fun disabled_log_is_a_noop() {
        DebugLog.clear()
        DebugLog.setEnabled(false)
        DebugLog.log("ws", "should not be recorded")
        assertTrue("disabled logging must record nothing", DebugLog.entries.value.isEmpty())
    }

    @Test fun enabled_log_is_recorded_with_category_and_message() {
        DebugLog.log("session", "open(s1)")
        val e = DebugLog.entries.value
        assertEquals(1, e.size)
        assertEquals("session", e.first().category)
        assertEquals("open(s1)", e.first().message)
    }

    @Test fun ring_buffer_caps_at_max() {
        repeat(DebugLog.MAX_ENTRIES + 50) { DebugLog.log("ws", "line $it") }
        val e = DebugLog.entries.value
        assertEquals(DebugLog.MAX_ENTRIES, e.size)
        // Oldest entries are evicted; the newest must be retained.
        assertEquals("line ${DebugLog.MAX_ENTRIES + 49}", e.last().message)
    }

    @Test fun registered_token_is_redacted() {
        DebugLog.setTokenToRedact("SECRET-TOKEN-123")
        DebugLog.log("rest", "GET /api/sessions  token=SECRET-TOKEN-123 end")
        val msg = DebugLog.entries.value.single().message
        assertFalse("raw token must not appear", msg.contains("SECRET-TOKEN-123"))
        assertTrue("token must be masked", msg.contains("***"))
    }

    @Test fun blank_token_does_not_redact_everything() {
        DebugLog.setTokenToRedact("")
        DebugLog.log("rest", "GET /api/status")
        assertEquals("GET /api/status", DebugLog.entries.value.single().message)
    }

    @Test fun clear_empties_the_buffer() {
        DebugLog.log("ws", "a")
        DebugLog.log("ws", "b")
        DebugLog.clear()
        assertTrue(DebugLog.entries.value.isEmpty())
    }

    @Test fun export_contains_category_and_messages_in_order() {
        DebugLog.log("session", "open(s1)")
        DebugLog.log("error", "message not found")
        val text = DebugLog.export()
        assertTrue(text.contains("session"))
        assertTrue(text.contains("open(s1)"))
        assertTrue(text.contains("message not found"))
        // newest-last ordering
        assertTrue(text.indexOf("open(s1)") < text.indexOf("message not found"))
    }

    @Test fun matrix_credentials_are_redacted() {
        val out = DebugLog.redact(
            "Authorization: Bearer syt_abc.def-123 body={\"password\":\"hunter2\"} ?access_token=xyz&a=b",
        )
        assertFalse(out.contains("syt_abc"))
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("xyz"))
        assertTrue(out.contains("a=b"))
    }

    @Test fun backslash_escaped_json_keys_are_redacted() {
        val out = DebugLog.redact("""{"body":"{\"user\":\"kev\",\"password\":\"hunter2\"}"}""")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("kev"))
    }

    @Test fun quoted_values_with_spaces_and_commas_are_fully_masked() {
        val out = DebugLog.redact("""{"password":"correct horse, battery staple","x":1} access_token="a b,c" tail""")
        assertFalse(out.contains("horse"))
        assertFalse(out.contains("staple"))
        assertFalse(out.contains("b,c"))
        assertTrue(out.contains(""""x":1"""))
        assertTrue(out.contains("tail"))
    }

    @Test fun ordinary_bearer_prose_is_not_masked() {
        val text = "the bearer of bad news and a bearer bond"
        assertEquals(text, DebugLog.redact(text))
    }

    @Test fun schemeless_authorization_credential_is_masked() {
        val out = DebugLog.redact("Authorization: abcdefghijkl")
        assertFalse(out.contains("abcdefghijkl"))
    }

    @Test fun authorization_header_value_is_masked() {
        val out = DebugLog.redact("Authorization: Bearer abcdef0123456789")
        assertFalse(out.contains("abcdef"))
        assertTrue(out.startsWith("Authorization: Bearer "))
    }

    @Test fun apostrophe_inside_double_quoted_value_is_fully_masked() {
        val out = DebugLog.redact("""{"password":"it's a pw","x":1}""")
        assertFalse(out.contains("it's"))
        assertFalse(out.contains("pw"))
        assertTrue(out.contains(""""x":1"""))
    }

    @Test fun escaped_quote_inside_value_does_not_end_the_mask() {
        val out = DebugLog.redact("""{"password":"a\"b c","x":1}""")
        assertFalse(out.contains("b c"))
        assertTrue(out.contains(""""x":1"""))
    }

    @Test fun multi_line_value_is_fully_masked() {
        val out = DebugLog.redact("password: \"first\nsecond\" tail")
        assertFalse(out.contains("first"))
        assertFalse(out.contains("second"))
        assertTrue(out.contains("tail"))
    }

    @Test fun basic_auth_header_credential_is_masked() {
        val out = DebugLog.redact("Authorization: Basic dXNlcjpwYXNzd29yZA==")
        assertFalse(out.contains("dXNlcjpw"))
        assertTrue(out.startsWith("Authorization: Basic "))
    }

    @Test fun escaped_json_authorization_key_is_masked() {
        val out = DebugLog.redact("""{\"authorization\":\"Basic dXNlcjpwYXNz\"}""")
        assertFalse(out.contains("dXNlcjpw"))
        assertTrue(out.contains("authorization"))
    }

    @Test fun new_secret_keys_are_masked_in_json() {
        listOf("login_token", "loginToken", "id_token", "client_secret", "recovery_key").forEach { key ->
            val out = DebugLog.redact("""{"$key":"s3cr3t-value","y":2}""")
            assertFalse("$key must be masked", out.contains("s3cr3t-value"))
            assertTrue(out.contains(""""y":2"""))
        }
    }

    @Test fun new_secret_keys_are_masked_in_key_value_form() {
        listOf("login_token", "loginToken", "id_token", "client_secret", "recovery_key").forEach { key ->
            val out = DebugLog.redact("GET /x?$key=s3cr3t-value&a=b")
            assertFalse("$key must be masked", out.contains("s3cr3t-value"))
            assertTrue(out.contains("a=b"))
        }
    }
}
