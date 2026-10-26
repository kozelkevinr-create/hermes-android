package com.hermes.client.data.ntfy

import com.hermes.client.ui.chat.ApprovalChoice

/**
 * An approval published by the gateway's ntfy-approvals transport. It is answered by a GET to the
 * relay: `<relay>/<allow|session|always|deny>/<requestId>/<nonce>`. The nonce is per request and
 * single-use, so [allowed] only lists what the host permits for this request.
 */
data class NtfyApproval(
    val messageId: String,
    val requestId: String,
    val nonce: String,
    val relayBase: String,
    val allowed: Set<ApprovalChoice>,
    val title: String,
    val description: String,
    val command: String,
) {
    /** Reply link for [choice], or null when the host did not allow it for this request. */
    fun replyUrl(choice: ApprovalChoice): String? {
        if (choice !in allowed) return null
        return "$relayBase/${choice.relayVerb()}/$requestId/$nonce"
    }
}

fun ApprovalChoice.relayVerb(): String = when (this) {
    ApprovalChoice.ONCE -> "allow"
    ApprovalChoice.SESSION -> "session"
    ApprovalChoice.ALWAYS -> "always"
    ApprovalChoice.DENY -> "deny"
}

private val REPLY_URL = Regex("""\A(.*)/(allow|session|always|deny)/([0-9a-f]{32})/([A-Za-z0-9_-]{16,64})\z""")
private const val SCOPES_PREFIX = "scopes-"

/**
 * Recognises an approval prompt among the messages on an approvals topic; null for anything else
 * (a foreign message, or a link that doesn't look like a relay reply). The allowed choices come from
 * the `scopes-…` tag, falling back to the verbs on the buttons for an older plugin that sends none.
 */
fun parseNtfyApproval(m: NtfyMessage): NtfyApproval? {
    val parsed = m.actions.mapNotNull { REPLY_URL.matchEntire(it.url) }
    val first = parsed.firstOrNull() ?: return null
    val relayBase = first.groupValues[1]
    val rid = first.groupValues[3]
    val nonce = first.groupValues[4]
    // Every button must belong to one request, or the message is not one we should act on.
    if (parsed.any { it.groupValues[1] != relayBase || it.groupValues[3] != rid || it.groupValues[4] != nonce }) return null

    val fromTag = m.tags.firstOrNull { it.startsWith(SCOPES_PREFIX) }
        ?.removePrefix(SCOPES_PREFIX)?.split('-')?.mapNotNull(::choiceForWire)?.toSet()
    val fromButtons = parsed.mapNotNull { choiceForVerb(it.groupValues[2]) }.toSet()
    // Deny is always possible; a tag that lists nothing usable falls back to the buttons.
    val allowed = (if (fromTag.isNullOrEmpty()) fromButtons else fromTag) + ApprovalChoice.DENY

    val (description, command) = splitBody(m.message)
    return NtfyApproval(m.id, rid, nonce, relayBase, allowed, m.title, description, command)
}

private fun choiceForWire(w: String): ApprovalChoice? = ApprovalChoice.entries.firstOrNull { it.wire == w }

private fun choiceForVerb(v: String): ApprovalChoice? = when (v) {
    "allow" -> ApprovalChoice.ONCE
    "session" -> ApprovalChoice.SESSION
    "always" -> ApprovalChoice.ALWAYS
    "deny" -> ApprovalChoice.DENY
    else -> null
}

/** The plugin writes `<description>\n\n$ <command>\n\n<footer>`; tolerate any other shape. */
private fun splitBody(body: String): Pair<String, String> {
    val parts = body.split("\n\n")
    val cmdIdx = parts.indexOfFirst { it.startsWith("$ ") }
    if (cmdIdx < 0) return body.trim() to ""
    val command = parts[cmdIdx].removePrefix("$ ")
    val description = parts.take(cmdIdx).joinToString("\n\n").trim()
    return description to command
}

/** What a ntfy event means for the app. */
sealed interface NtfyDelivery {
    data class Approval(val message: NtfyMessage, val approval: NtfyApproval) : NtfyDelivery
    data class Alert(val message: NtfyMessage) : NtfyDelivery
    data class Withdraw(val messageId: String) : NtfyDelivery
}

/**
 * Decides what to do with [event]. [kindOf] gives the configured kind for a topic (null = not one of
 * ours, ignored). A message on an approvals topic that isn't a recognisable approval is shown as a
 * plain alert rather than dropped.
 */
fun classifyNtfy(event: NtfyEvent, kindOf: (String) -> NtfyKind?): NtfyDelivery? = when (event) {
    is NtfyEvent.Withdrawn -> kindOf(event.topic)?.let { NtfyDelivery.Withdraw(event.id) }
    is NtfyEvent.Message -> when (kindOf(event.message.topic)) {
        null -> null
        NtfyKind.GENERAL -> NtfyDelivery.Alert(event.message)
        NtfyKind.APPROVALS ->
            parseNtfyApproval(event.message)?.let { NtfyDelivery.Approval(event.message, it) }
                ?: NtfyDelivery.Alert(event.message)
    }
}
