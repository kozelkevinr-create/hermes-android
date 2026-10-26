package com.hermes.client.data.repository

import com.hermes.client.data.network.HermesGatewayClient
import com.hermes.client.ui.chat.ApprovalChoice
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RespondApprovalTest {
    private val client = mockk<HermesGatewayClient>(relaxed = true)
    private val repo = ChatRepository(client) // match the real ctor; add other relaxed deps if needed

    @Test fun once_sends_only_the_params_the_gateway_accepts() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.ONCE)
        assertEquals("s1", params.captured["session_id"]!!.jsonPrimitive.content)
        assertEquals("once", params.captured["choice"]!!.jsonPrimitive.content)
        assertEquals(setOf("session_id", "choice"), params.captured.keys)
    }

    @Test fun deny_sends_choice_deny_with_request_id() = runTest {
        val params = slot<JsonObject>()
        coEvery { client.call("approval.respond", capture(params)) } returns JsonPrimitive("ok")
        repo.respondApproval("s1", ApprovalChoice.DENY, "req-9")
        assertEquals("deny", params.captured["choice"]!!.jsonPrimitive.content)
        assertEquals("req-9", params.captured["request_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("session_id", "choice", "request_id"), params.captured.keys)
    }
}
