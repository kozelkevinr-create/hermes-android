package com.hermes.client.domain

import com.hermes.client.data.network.LaneDto
import com.hermes.client.data.network.MessageDto
import com.hermes.client.data.network.ProjectNodeDto
import com.hermes.client.data.network.ProjectTreeDto
import com.hermes.client.data.network.RepoDto
import com.hermes.client.data.network.SessionDto

fun SessionDto.toDomain() = Session(
    id = sessionId,
    title = title?.ifBlank { "Untitled" } ?: "Untitled",
    model = model,
    provider = provider,
    messageCount = messageCount,
    // Normalize the default profile to a stable "default" label: the gateway may report it as
    // null or blank with is_default_profile=true, but grouping/pin-tokens/switching need one key.
    profile = profile?.ifBlank { null } ?: if (isDefaultProfile) "default" else null,
    workspace = cwd?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { null } ?: "No workspace",
    archived = archived,
    source = source,
    lastActive = com.hermes.client.ui.util.secondsToEpochMs(lastActive),
    cwd = cwd?.ifBlank { null },
    gitBranch = gitBranch?.ifBlank { null },
    gitRepoRoot = gitRepoRoot?.ifBlank { null },
)

fun MessageDto.toDomain() = ChatMessage(
    id = id?.toString() ?: "m-${hashCode()}",
    role = when (role.lowercase()) {
        "user" -> Role.USER
        "assistant" -> Role.ASSISTANT
        else -> Role.SYSTEM
    },
    text = content.orEmpty(),
)

fun ProjectTreeDto.toDomain() = ProjectTree(
    projects = projects.map { it.toDomain() },
    activeId = activeId,
)

fun ProjectNodeDto.toDomain() = Project(
    id = id,
    label = label,
    path = path,
    color = color?.ifBlank { null },
    isAuto = isAuto,
    sessionCount = sessionCount,
    lastActive = com.hermes.client.ui.util.secondsToEpochMs(lastActive),
    repos = repos.map { it.toDomain() },
    previewSessions = previewSessions.map { it.toDomain() },
)

fun RepoDto.toDomain() = ProjectRepo(
    id = id,
    label = label,
    path = path,
    sessionCount = sessionCount,
    lanes = groups.map { it.toDomain() },
)

fun LaneDto.toDomain() = ProjectLane(
    id = id,
    label = label,
    path = path,
    isMain = isMain,
    sessions = sessions.map { it.toDomain() },
)

/**
 * Rebuilds a stored transcript the way it looked live: an assistant row's `tool_calls` become tool cards
 * and each `tool` row fills in its card's output instead of showing as a message of its own. Rows the
 * gateway marks hidden are dropped. [idPrefix] keeps ids unique (the gateway reuses the model name as one).
 */
fun List<MessageDto>.toHistory(idPrefix: String = "h"): List<ChatMessage> {
    val out = mutableListOf<ChatMessage>()
    for (dto in this) {
        if (dto.displayKind == "hidden") continue
        if (dto.role.equals("tool", ignoreCase = true)) {
            val output = storedToolOutput(dto.content)
            val owner = dto.toolCallId?.let { id -> out.indexOfLast { m -> m.tools.any { it.id == id } } } ?: -1
            if (owner >= 0) {
                out[owner] = out[owner].let { m ->
                    m.copy(tools = m.tools.map { if (it.id == dto.toolCallId) it.copy(output = output) else it })
                }
            } else {
                // No call row to attach to (trimmed page): still show it as a finished tool card.
                out += ChatMessage(
                    id = "$idPrefix-${out.size}-tool", role = Role.ASSISTANT, text = "",
                    tools = listOf(ToolCall(dto.toolCallId ?: "t-${out.size}", dto.toolName ?: "tool", ToolStatus.DONE, output)),
                )
            }
            continue
        }
        val m = dto.toDomain()
        out += m.copy(id = "$idPrefix-${out.size}-${m.id}", tools = dto.storedToolCalls())
    }
    return out
}

private val historyJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** The calls of a stored assistant row; `tool_calls` arrives as a JSON array or as that array in a string. */
private fun MessageDto.storedToolCalls(): List<ToolCall> {
    val el = toolCalls?.let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }
            ?.let { p -> runCatching { historyJson.parseToJsonElement(p.content) }.getOrNull() } ?: it
    }
    val calls = (el as? kotlinx.serialization.json.JsonArray) ?: return emptyList()
    return calls.mapIndexedNotNull { i, c ->
        val o = c as? kotlinx.serialization.json.JsonObject ?: return@mapIndexedNotNull null
        fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.content
        val fn = o["function"] as? kotlinx.serialization.json.JsonObject
        ToolCall(
            id = str(o["id"]) ?: str(o["call_id"]) ?: "t-$i",
            name = str(fn?.get("name")) ?: str(o["name"]) ?: "tool",
            status = ToolStatus.DONE,
        )
    }
}

/** A stored tool result is usually `{"output": "...", ...}`; show the output, else the raw text. */
private fun storedToolOutput(content: String?): String {
    val raw = content.orEmpty()
    val o = runCatching { historyJson.parseToJsonElement(raw) }.getOrNull() as? kotlinx.serialization.json.JsonObject
    return (o?.get("output") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: raw
}
