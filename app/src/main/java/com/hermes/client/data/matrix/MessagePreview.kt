package com.hermes.client.data.matrix

/** Shown instead of the body of an event the client could not decrypt. */
const val ENCRYPTED_PREVIEW = "Encrypted message"

/** Longest preview kept, in characters (the row shows one line; the rest is cut). */
const val MAX_PREVIEW_LENGTH = 120

/**
 * Turns a message body into a single plain-text line: runs of whitespace (including newlines)
 * collapse to one space, the ends are trimmed, and anything past [max] characters is cut with an
 * ellipsis. Pure, so the room list never shows a multi-line or unbounded body.
 */
fun previewText(raw: String, max: Int = MAX_PREVIEW_LENGTH): String {
    val flat = raw.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
}

/**
 * Label for a message sender: their display name when they have one, otherwise the localpart of
 * their MXID (`@bot:server` -> `bot`). Our own events read "You".
 */
fun senderLabel(userId: String, displayName: String?, isOwn: Boolean): String = when {
    isOwn -> "You"
    !displayName.isNullOrBlank() -> displayName.trim()
    else -> userId.removePrefix("@").substringBefore(':').ifBlank { userId }
}

/** The avatar letter for a room: the first character of its name, uppercased; "#" when blank. */
fun avatarLetterFor(name: String): String =
    name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#"
