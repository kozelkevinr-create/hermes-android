package com.hermes.client.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun encodes_request_with_id_method_params() {
        val req = RpcRequest(7, "prompt.submit", buildJsonObject {
            put("text", "hi"); put("session_id", "s1")
        })
        val line = req.encode(json)
        val back = json.parseToJsonElement(line)
        assertTrue(line.contains("\"jsonrpc\":\"2.0\""))
        assertTrue(line.contains("\"method\":\"prompt.submit\""))
        assertTrue(line.contains("\"id\":7"))
        assertTrue(back.toString().contains("\"text\":\"hi\""))
    }

    @Test fun parses_result_reply() {
        val line = """{"jsonrpc":"2.0","id":7,"result":{"ok":true}}"""
        val msg = parseInbound(json, line)
        assertTrue(msg is RpcResult && msg.id == 7L)
    }

    @Test fun parses_error_reply() {
        val line = """{"jsonrpc":"2.0","id":7,"error":{"code":-32601,"message":"nope"}}"""
        val msg = parseInbound(json, line)
        assertTrue(msg is RpcErrorReply && (msg as RpcErrorReply).error.code == -32601)
    }

    @Test fun parses_event_with_type_and_session() {
        val line = """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","payload":{"session_id":"s1","delta":"hello"}}}"""
        val msg = parseInbound(json, line)
        assertTrue(msg is RpcEvent)
        val ev = (msg as RpcEvent).event
        assertEquals("message.delta", ev.type)
        assertEquals("s1", ev.sessionId)
        assertEquals("hello", ev.payload["delta"]!!.toString().trim('"'))
    }

    @Test fun parses_server_request_with_string_id() {
        val line = """{"jsonrpc":"2.0","id":"srq-1a2b3c","method":"approval","params":{"session_id":"s1","request_id":"r9","command":"rm -rf x","choices":["once","deny"]}}"""
        val msg = parseInbound(json, line)
        assertTrue(msg is RpcServerRequest)
        val req = msg as RpcServerRequest
        assertEquals("srq-1a2b3c", req.id)
        val ev = serverRequestToEvent(req)!!
        assertEquals("approval.request", ev.type)
        assertEquals("s1", ev.sessionId)
        assertEquals("rm -rf x", ev.payload["command"]!!.toString().trim('"'))
    }

    @Test fun clarify_request_maps_first_question_and_rpc_id() {
        val line = """{"jsonrpc":"2.0","id":"srq-77","method":"clarify","params":{"session_id":"s1","questions":[{"qid":"q1","question":"Which one?"},{"qid":"q2","question":"Also?"}]}}"""
        val ev = serverRequestToEvent(parseInbound(json, line) as RpcServerRequest)!!
        assertEquals("clarify.request", ev.type)
        assertEquals("srq-77", ev.payload["request_id"]!!.toString().trim('"'))
        assertEquals("q1", ev.payload["qid"]!!.toString().trim('"'))
        assertEquals("Which one?", ev.payload["question"]!!.toString().trim('"'))
    }

    @Test fun unsupported_server_request_has_no_event() {
        val line = """{"jsonrpc":"2.0","id":"srq-5","method":"sudo","params":{"session_id":"s1"}}"""
        assertEquals(null, serverRequestToEvent(parseInbound(json, line) as RpcServerRequest))
    }

    @Test fun server_replies_echo_the_string_id() {
        val ok = encodeServerResult(json, "srq-9", kotlinx.serialization.json.buildJsonObject {
            kotlinx.serialization.json.JsonPrimitive("x")
        })
        assertTrue(ok.contains("\"id\":\"srq-9\""))
        val err = encodeServerError(json, "srq-9", RPC_METHOD_NOT_FOUND, "no")
        assertTrue(err.contains("\"id\":\"srq-9\"") && err.contains("-32601"))
    }
}
