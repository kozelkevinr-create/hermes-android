package com.hermes.client.data.matrix

/** One row of the room list, independent of the SDK's `Room`/`RoomInfo` types. */
data class MatrixRoomSummary(
    val id: String,
    val name: String,
    val isEncrypted: Boolean,
    val unread: Long,
    val isDirect: Boolean,
    /** Plain-text, single-line, length-capped preview of the latest event; null when there is none. */
    val lastMessagePreview: String? = null,
    /** Human label for the latest event's sender ("You" for own events); null when there is none. */
    val lastMessageSender: String? = null,
    /** Epoch millis of the latest event; null when there is none. */
    val lastActivityTs: Long? = null,
    /** Single uppercase letter for the row's avatar circle. */
    val avatarLetter: String = "#",
)

/**
 * SDK-independent mirror of the SDK `Session`. [slidingSyncVersion] is the SDK enum name
 * (`NONE` / `NATIVE`).
 */
data class MatrixSessionData(
    val accessToken: String,
    val refreshToken: String?,
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val oauthData: String?,
    val slidingSyncVersion: String,
) {
    // Never print tokens.
    override fun toString() = "MatrixSessionData(userId=$userId, deviceId=$deviceId, homeserverUrl=$homeserverUrl)"
}

/** Everything persisted about the signed-in account except the store passphrase. */
data class MatrixAccount(
    val session: MatrixSessionData,
    val botMxid: String,
    /** Imported private CA certificates, one DER blob each. Empty = platform trust store. */
    val caCerts: List<ByteArray> = emptyList(),
    /** Subdirectory of the Matrix store dir holding this session's SDK database. */
    val storeSlot: String = "s0",
) {
    override fun toString() = "MatrixAccount(session=$session, botMxid=$botMxid, caCerts=${caCerts.size}, slot=$storeSlot)"
}

/** Redacted "Class: message <- Class: message" chain for showing/logging SDK errors (they nest). */
fun redactedCauseChain(e: Throwable, redact: (String) -> String): String =
    redact(
        generateSequence(e) { it.cause?.takeIf { c -> c !== it } }
            .take(8)
            .joinToString("\n  caused by: ") { "${it::class.qualifiedName}: ${it.message ?: it}" },
    )
