package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixNewRoom

/** What the new-room sheet creates: a named room, or a nameless direct message. */
enum class NewRoomMode { ROOM, DM }

/**
 * The new-room sheet's editable state. [name] matters in [NewRoomMode.ROOM] (DMs stay unnamed so
 * the room list shows the peer); [invite] takes one or more MXIDs, comma or whitespace separated
 * (the Hermes bot is prefilled); [encrypted] is the room's encryption toggle.
 */
data class NewRoomDraft(
    val mode: NewRoomMode = NewRoomMode.ROOM,
    val name: String = "",
    val invite: String = "",
    val encrypted: Boolean = true,
)

/**
 * MXIDs from the invite field: comma/whitespace separated entries. No validation here — an
 * unparseable entry is surfaced by [newRoomError] instead of silently dropped.
 */
fun parseInvites(text: String): List<String> =
    text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }

/** An MXID shape check: "@"…local…":"…domain…"" with non-empty parts and no spaces. */
fun mxidIsValid(mxid: String): Boolean {
    if (!mxid.startsWith("@") || mxid.length < 3) return false
    val body = mxid.drop(1)
    val colon = body.indexOf(':')
    if (colon <= 0 || colon == body.length - 1) return false
    val domain = body.substring(colon + 1)
    return !body.contains(' ') && !domain.contains(':') && !domain.startsWith('.')
}

/** Why the draft cannot be created yet, or null when it can. Never logs or surfaces input. */
fun newRoomError(draft: NewRoomDraft): String? = when (draft.mode) {
    NewRoomMode.ROOM -> when {
        draft.name.isBlank() -> "Give the room a name."
        else -> inviteFieldError(draft.invite, required = false)
    }
    NewRoomMode.DM -> inviteFieldError(draft.invite, required = true)
}

private fun inviteFieldError(text: String, required: Boolean): String? {
    val invites = parseInvites(text)
    return when {
        invites.isEmpty() && required -> "Add at least one \"@user:server\" to invite."
        invites.any { !mxidIsValid(it) } -> "One of the invites is not an \"@user:server\"."
        else -> null
    }
}

/**
 * The create call for [draft], or null while [newRoomError] says the draft is incomplete. A room
 * keeps its trimmed name and a private preset; a DM drops the name (the room list shows the
 * peer) and marks the pairing direct so the bot's session lands in it alone.
 */
fun newRoomRequest(draft: NewRoomDraft): MatrixNewRoom? {
    if (newRoomError(draft) != null) return null
    return when (draft.mode) {
        NewRoomMode.ROOM -> MatrixNewRoom(
            name = draft.name.trim(),
            encrypted = draft.encrypted,
            isDirect = false,
            invite = parseInvites(draft.invite),
        )
        NewRoomMode.DM -> MatrixNewRoom(
            name = null,
            encrypted = draft.encrypted,
            isDirect = true,
            invite = parseInvites(draft.invite),
        )
    }
}
