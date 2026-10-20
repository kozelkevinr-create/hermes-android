package com.hermes.client.data.matrix

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Parameters for building a client. Paths must be unique per session. */
class MatrixClientConfig(
    val homeserverUrl: String,
    /** DER certificates; non-empty means "trust only these" (the platform verifier cannot merge roots). */
    val caCerts: List<ByteArray>,
    val storePassphrase: String,
    val dataDir: File,
    val cacheDir: File,
    /** Called (on an SDK thread) when the SDK refreshes the session tokens. */
    val onSessionRefreshed: (MatrixSessionData) -> Unit,
    /** The persisted session for [userId], if any (SDK keychain lookup). */
    val currentSession: (userId: String) -> MatrixSessionData?,
)

/** Thin seam over the matrix-rust-sdk FFI so the account manager is JVM-testable with fakes. */
interface MatrixSdk {
    suspend fun createClient(config: MatrixClientConfig): MatrixClientHandle
}

interface MatrixClientHandle {
    suspend fun login(username: String, password: String, deviceName: String): MatrixSessionData
    suspend fun restore(session: MatrixSessionData)

    /**
     * Room-list diffs, already mapped to summaries. Single-collector; collect before [startSync].
     * Completes when the handle is closed.
     */
    val roomDiffs: Flow<List<ListDiff<MatrixRoomSummary>>>

    suspend fun startSync()
    suspend fun stopSync()

    /**
     * Opens a read-only live timeline for a room, or null when the room is unknown. The handle owns
     * its own collector and must be [MatrixTimelineHandle.close]d when the screen is done.
     */
    suspend fun openTimeline(roomId: String): MatrixTimelineHandle? = null

    /** Server-side logout (invalidates the access token). */
    suspend fun logout()

    /** Stops sync, then destroys every SDK object this handle holds. Idempotent. */
    suspend fun close()
}

/**
 * A live room timeline. [items] holds immutable snapshots, already applied from the SDK's diffs on
 * a single collector; [paginationState] reflects back-pagination (Loading / AtStart / Idle).
 */
interface MatrixTimelineHandle {
    val items: StateFlow<List<TimelineEntry>>

    /** Pages older events in; returns true when the start of the room has been reached. */
    suspend fun paginateBackwards(count: Int): Boolean

    /** Sends [body] as a markdown text message; false when it could not be sent. Never throws. */
    suspend fun sendText(body: String): Boolean

    /** Retries the same failed local echo in place; false when it could not be retried. Never throws. */
    suspend fun retrySend(itemId: EventOrTxnId): Boolean

    /** Discards the local echo for [itemId]; false when there is nothing left to abort. Never throws. */
    suspend fun abortSend(itemId: EventOrTxnId): Boolean

    /** Edits [itemId]'s text message to the markdown [newBody]; false when it could not be applied. Never throws. */
    suspend fun edit(itemId: EventOrTxnId, newBody: String): Boolean

    /** Sends a public read receipt for the remote event id [eventId]; false when it was rejected. Never throws. */
    suspend fun sendReadReceipt(eventId: String): Boolean

    val paginationState: StateFlow<PaginationState>

    /** Cancels subscriptions and destroys every SDK object this handle holds. Idempotent. */
    fun close()
}
