package com.hermes.client.data.ntfy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What a subscribed topic carries: gateway approvals (answered through the relay) or plain alerts. */
enum class NtfyKind { APPROVALS, GENERAL }

@Serializable
data class NtfyTopic(
    val name: String,
    val topic: String,
    val kind: NtfyKind = NtfyKind.GENERAL,
    val enabled: Boolean = true,
)

/** User configuration for the ntfy source. The password lives in encrypted storage, never here. */
@Serializable
data class NtfyConfig(
    val enabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val topics: List<NtfyTopic> = emptyList(),
) {
    val activeTopics: List<NtfyTopic> get() = topics.filter { it.enabled && it.topic.isNotBlank() }
    val isUsable: Boolean get() = enabled && serverUrl.isNotBlank() && activeTopics.isNotEmpty()
}

@Serializable
data class NtfyAction(val label: String, val url: String)

/** One ntfy `message` event. [priority] is ntfy's 1..5 (3 = default). */
@Serializable
data class NtfyMessage(
    val id: String,
    val topic: String,
    val timeSeconds: Long,
    val title: String,
    val message: String,
    val priority: Int,
    val tags: List<String>,
    val actions: List<NtfyAction>,
)

sealed interface NtfyEvent {
    data class Message(val message: NtfyMessage) : NtfyEvent
    /** The publisher deleted (or the server expired) message [id]: its card and notification go away. */
    data class Withdrawn(val id: String, val topic: String) : NtfyEvent
}

/** Parses one line of ntfy's `/json` stream. Null for keepalives, `open`, and anything malformed. */
fun parseNtfyLine(json: Json, line: String): NtfyEvent? {
    val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
    val id = obj.str("id") ?: return null
    val topic = obj.str("topic") ?: return null
    return when (obj.str("event")) {
        "message" -> NtfyEvent.Message(
            NtfyMessage(
                id = id,
                topic = topic,
                timeSeconds = (obj["time"] as? JsonPrimitive)?.longOrNull ?: 0L,
                title = obj.str("title").orEmpty(),
                message = obj.str("message").orEmpty(),
                priority = (obj["priority"] as? JsonPrimitive)?.intOrNull ?: 3,
                tags = (obj["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                actions = (obj["actions"] as? JsonArray)?.mapNotNull { el ->
                    val a = el as? JsonObject ?: return@mapNotNull null
                    val url = a.str("url") ?: return@mapNotNull null
                    NtfyAction(a.str("label").orEmpty(), url)
                } ?: emptyList(),
            ),
        )
        // A delete is its own event with a fresh id; the message it withdraws is named by sequence_id
        // (which is the original message id unless the publisher chose another sequence).
        "message_delete", "message_expire" -> NtfyEvent.Withdrawn(obj.str("sequence_id") ?: id, topic)
        else -> null
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** `serverUrl` without a trailing slash, with a scheme (bare hosts get http, ntfy's LAN default). */
fun normalizeServerUrl(raw: String): String {
    val t = raw.trim().trimEnd('/')
    if (t.isEmpty()) return ""
    return if ("://" in t) t else "http://$t"
}
