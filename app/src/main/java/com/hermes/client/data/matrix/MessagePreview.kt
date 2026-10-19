package com.hermes.client.data.matrix

/** Shown instead of the body of an event the client could not decrypt. */
const val ENCRYPTED_PREVIEW = "Encrypted message"

/** Longest preview kept, in code points (the row shows one line; the rest is cut). */
const val MAX_PREVIEW_LENGTH = 120

private val WHITESPACE = Regex("[\\s\\p{Z}\\p{Cc}]+")

/**
 * Turns a message body into a single plain-text line: runs of whitespace, separators and control
 * characters collapse to one space, the ends are trimmed, and anything past [max] code points is
 * cut with an ellipsis. Cuts land on code-point boundaries, so an emoji (a surrogate pair) is never
 * split. The input is the event's plain `body`, which carries no HTML; markdown is left as typed.
 */
fun previewText(raw: String, max: Int = MAX_PREVIEW_LENGTH): String {
    val flat = raw.replace(WHITESPACE, " ").trim()
    if (flat.codePointCount(0, flat.length) <= max) return flat
    val end = flat.offsetByCodePoints(0, max - 1)
    return flat.substring(0, end).trimEnd() + "…"
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
