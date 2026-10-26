package com.hermes.client.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class RpcRequest(val id: Long, val method: String, val params: JsonObject) {
    fun encode(json: Json): String {
        val obj = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}

data class RpcError(val code: Int, val message: String)

sealed interface RpcInbound
data class RpcResult(val id: Long, val result: JsonElement) : RpcInbound
data class RpcErrorReply(val id: Long, val error: RpcError) : RpcInbound
data class RpcEvent(val event: ServerEvent) : RpcInbound

/**
 * A server→client JSON-RPC request (approval, clarify, sudo, …): it carries a string id like
 * `srq-1a2b3c` that the client must answer with a response frame carrying the same id.
 */
data class RpcServerRequest(val id: String, val method: String, val params: JsonObject) : RpcInbound

fun parseInbound(json: Json, line: String): RpcInbound {
    val obj = json.parseToJsonElement(line).jsonObject
    val method = obj["method"]?.jsonPrimitive?.content
    if (method == "event") {
        val params = obj.objOrEmpty("params")
        return RpcEvent(ServerEvent.from(params))
    }
    if (method != null && obj["id"] != null) {
        return RpcServerRequest(
            id = obj["id"]!!.jsonPrimitive.content,
            method = method,
            params = obj.objOrEmpty("params"),
        )
    }
    val id = obj["id"]?.jsonPrimitive?.longOrNull ?: -1L
    obj["error"]?.let {
        val e = it.jsonObject
        return RpcErrorReply(
            id,
            RpcError(
                code = e["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                message = e["message"]?.jsonPrimitive?.content ?: "error",
            ),
        )
    }
    return RpcResult(id, obj["result"] ?: JsonPrimitive("null"))
}

fun encodeServerResult(json: Json, id: String, result: JsonObject): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) },
    )

fun encodeServerError(json: Json, id: String, code: Int, message: String): String =
    json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject { put("code", code); put("message", message) })
        },
    )

/** JSON-RPC "method not found": how a client declines a request it has no UI for. */
const val RPC_METHOD_NOT_FOUND = -32601

/** Id prefix the gateway gives server→client requests (`srq-<12 hex>`). */
const val SERVER_REQUEST_PREFIX = "srq-"

/**
 * Maps the server requests this app can show onto the event shapes the chat reducer and the
 * notification mapper already understand, so neither needs a second code path:
 *  - `approval` → `approval.request` (fields pass through; the approval's own `request_id` stays)
 *  - `clarify`  → `clarify.request` with the FIRST question, `request_id` = the rpc id (what the
 *    response frame must echo) and `qid` for the answer map.
 * Null for any other method — the caller declines those with [RPC_METHOD_NOT_FOUND].
 */
fun serverRequestToEvent(req: RpcServerRequest): ServerEvent? {
    val sid = req.params["session_id"]?.jsonPrimitive?.content
    return when (req.method) {
        "approval" -> ServerEvent("approval.request", sid, req.params)
        "clarify" -> {
            val first = (req.params["questions"] as? JsonArray)?.firstOrNull() as? JsonObject
            ServerEvent(
                "clarify.request",
                sid,
                buildJsonObject {
                    put("request_id", req.id)
                    put("question", first?.get("question")?.jsonPrimitive?.content ?: "The agent has a question.")
                    put("qid", first?.get("qid")?.jsonPrimitive?.content ?: "")
                },
            )
        }
        else -> null
    }
}
