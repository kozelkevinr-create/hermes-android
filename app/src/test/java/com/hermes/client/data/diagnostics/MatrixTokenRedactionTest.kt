package com.hermes.client.data.diagnostics

import com.hermes.client.data.matrix.redactedCauseChain
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixTokenRedactionTest {
    // Shaped like a real Synapse opaque token: syt_<base64 localpart>_<random>_<checksum>.
    private val token = "syt_YWxpY2U_AbCdEfGhIjKlMnOpQrSt_1aBcDe"

    @Test fun bare_token_in_an_error_message_is_masked() {
        val out = DebugLog.redact("M_UNKNOWN_TOKEN: Invalid access token $token passed")
        assertFalse(out, out.contains(token))
        assertTrue(out, out.contains("M_UNKNOWN_TOKEN"))
    }

    @Test fun token_in_json_body_is_masked() {
        val body = """{"user_id":"@alice:example.test","access_token":"$token","device_id":"DEV123"}"""
        val out = DebugLog.redact(body)
        assertFalse(out, out.contains(token))
        assertTrue(out, out.contains("DEV123"))
    }

    @Test fun token_in_authorization_header_is_masked() {
        val out = DebugLog.redact("GET /sync Authorization: Bearer $token")
        assertFalse(out, out.contains(token))
    }

    @Test fun token_without_a_key_inside_json_is_masked() {
        val out = DebugLog.redact("""{"error":"bad token $token"}""")
        assertFalse(out, out.contains(token))
    }

    @Test fun nested_cause_chain_is_redacted() {
        val e = RuntimeException("login failed", IllegalStateException("""body {"access_token":"$token"}"""))
        val out = redactedCauseChain(e, DebugLog::redact)
        assertFalse(out, out.contains(token))
        assertTrue(out, out.contains("login failed"))
    }

    @Test fun ordinary_words_are_left_alone() {
        val out = DebugLog.redact("syt_ is just a prefix, mat_ too")
        assertTrue(out, out.contains("syt_ is just"))
    }
}
