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

/** Recovery phases of the account's key backup, as the recovery sheet shows them. */
enum class MatrixRecoveryPhase { UNKNOWN, ENABLED, DISABLED, INCOMPLETE }

/** Phases of the backup engine itself (uploads and restores), as the recovery sheet shows them. */
enum class MatrixBackupPhase { UNKNOWN, CREATING, ENABLING, RESUMING, ENABLED, DOWNLOADING, DISABLING }

/** Snapshot of the session's recovery state: the account-wide key backup and its engine. */
data class MatrixRecoveryStatus(val recovery: MatrixRecoveryPhase, val backup: MatrixBackupPhase)

/**
 * The outcome of enabling recovery: the one-time recovery key, a stale server-side backup the
 * fresh key cannot match (it must be deleted before a new backup can be set up), or a plain
 * failure.
 */
sealed interface MatrixRecoveryEnableResult {
    /** Recovery is enabled end-to-end; [key] is the recovery key, to be shown exactly once. */
    data class KeyCreated(val key: String) : MatrixRecoveryEnableResult

    /** The server already holds a key backup that this device's (fresh) key cannot match. */
    data object StaleBackupExists : MatrixRecoveryEnableResult

    /** Anything else: server issues, local crypto problems. */
    data object Failed : MatrixRecoveryEnableResult
}

/**
 * The recovery (key-backup) surface of one live session, created once per session on
 * [MatrixClientHandle.getRecovery] and closed with the client. Never throws; failures come
 * back as null/false, and the recovery key is handed out exactly once, by [enable].
 */
interface MatrixRecoveryHandle : AutoCloseable {
    /** The recovery and backup phases right now. */
    fun status(): MatrixRecoveryStatus

    /**
     * Enables recovery and waits for the key uploads to finish; the result carries the recovery
     * key (shown exactly once and never logged), or tells why setup could not finish.
     */
    suspend fun enable(): MatrixRecoveryEnableResult

    /** Restores this session's key backup from [key]; false for a wrong key or server failure. */
    suspend fun recover(key: String): Boolean

    /**
     * Replaces the account's recovery setup and returns a new recovery key (the old one stops
     * working); the "lost my recovery key" path out of the INCOMPLETE state. Null on failure.
     */
    suspend fun reset(): String?

    /** Turns recovery off and deletes the server-side backup. False on failure. */
    suspend fun disable(): Boolean
}

/** Thin seam over the matrix-rust-sdk FFI so the account manager is JVM-testable with fakes. */
interface MatrixSdk {
    suspend fun createClient(config: MatrixClientConfig): MatrixClientHandle
}

/**
 * One create-room call's inputs. [name] is the visible room name (null for an unnamed DM).
 * [isDirect] marks a direct-message room (trusted private preset); [invite] lists the invited
 * MXIDs — for a DM exactly its peer (the Hermes bot).
 */
data class MatrixNewRoom(
    val name: String?,
    val encrypted: Boolean,
    val isDirect: Boolean,
    val invite: List<String> = emptyList(),
)

/** One person in a room, as the room settings screen lists them. */
data class MatrixRoomMember(
    val userId: String,
    /** Display name, or null when the member has none set. */
    val displayName: String?,
    val isMe: Boolean,
    /** Invited but not joined yet. */
    val isInvited: Boolean,
    /** True when the signed-in user may remove this member. */
    val canRemove: Boolean,
)

/** What the room settings screen shows and which controls the signed-in user may use. */
data class MatrixRoomDetails(
    val name: String,
    val topic: String,
    val isDirect: Boolean,
    val isFavourite: Boolean,
    val isMarkedUnread: Boolean,
    val canRename: Boolean,
    val canSetTopic: Boolean,
    val canInvite: Boolean,
    val members: List<MatrixRoomMember>,
)

/** One emoji of the SAS short-authentication-string (7 of these make up a verification prompt). */
data class MatrixSasEmoji(val symbol: String, val description: String)

/**
 * Events of the SAS verification flow, in order: a request is seen (incoming) or accepted
 * (either direction), the SAS handshake starts, the emoji prompt arrives, then finish, cancel
 * or failure. Emojis are the short code both sides compare.
 */
sealed interface MatrixVerificationEvent {
    /** The peer sent a verification request; [deviceName] is the peer device's display name. */
    data class Incoming(val deviceName: String) : MatrixVerificationEvent
    /** A request was accepted (ours by the peer, or the peer's by us). SAS follows. */
    data object Accepted : MatrixVerificationEvent
    /** The SAS handshake started; the emoji prompt is next. */
    data object SasStarted : MatrixVerificationEvent
    /** The emoji short code arrived; both sides show the same list. */
    data class Emojis(val emojis: List<MatrixSasEmoji>) : MatrixVerificationEvent
    /** Both sides accepted the short code; keys, messages and devices are trusted now. */
    data object Finished : MatrixVerificationEvent
    /** The flow was cancelled. [cancelledByUs] distinguishes the two sides. */
    data class Cancelled(val cancelledByUs: Boolean, val cancelCode: String?) : MatrixVerificationEvent
    /** The flow failed non-semantically (network, protocol error). */
    data object Failed : MatrixVerificationEvent
}

/**
 * The verification-flow controller for one live session. A session ever has one; created on
 * [MatrixClientHandle.getVerificationController] and closed with the client. One collector of
 * [events] at a time (the screen's VM). All actions are never-throw seam calls: false means
 * "not possible now", the flow itself reports via [events].
 */
interface MatrixVerificationHandle : AutoCloseable {
    val events: Flow<MatrixVerificationEvent>
    /** Starts device-to-device verification (the same account, a new/other session). */
    suspend fun requestDeviceVerification(): Boolean
    /** Starts identity verification with another user ([mxid]) — their devices, then trust. */
    suspend fun requestUserVerification(mxid: String): Boolean
    /** Accepts the pending incoming request. */
    suspend fun acceptRequest(): Boolean
    /** Sends the SAS start (each side may; the protocol dedups). */
    suspend fun startSas(): Boolean
    /** The short codes match. */
    suspend fun approve(): Boolean
    /** The short codes do not match. */
    suspend fun decline(): Boolean
    /** Aborts the flow. */
    suspend fun cancel(): Boolean
}

interface MatrixClientHandle {
    suspend fun login(username: String, password: String, deviceName: String): MatrixSessionData
    suspend fun restore(session: MatrixSessionData)

    /**
     * Creates the room and returns its new id, or null when creation failed. Never throws; a
     * closed handle also reports null.
     */
    suspend fun createRoom(room: MatrixNewRoom): String? = null

    /** The room's settings view, or null when the room is unknown or the call failed. Never throws. */
    suspend fun roomDetails(roomId: String): MatrixRoomDetails? = null

    /** Room-management calls below: true on success, false on any failure. Never throw. */
    suspend fun renameRoom(roomId: String, name: String): Boolean = false
    suspend fun setRoomTopic(roomId: String, topic: String): Boolean = false
    suspend fun inviteToRoom(roomId: String, userId: String): Boolean = false
    suspend fun removeFromRoom(roomId: String, userId: String): Boolean = false
    suspend fun leaveRoom(roomId: String): Boolean = false
    suspend fun setRoomFavourite(roomId: String, favourite: Boolean): Boolean = false
    suspend fun setRoomMarkedUnread(roomId: String, unread: Boolean): Boolean = false

    /**
     * The session's SAS verification controller, created once per session and closed with the
     * client; null without a live session or on failure. Never throws.
     */
    suspend fun getVerificationController(): MatrixVerificationHandle? = null

    /**
     * The session's recovery (key-backup) handle, created once per session and closed with the
     * client; null without a live session or on failure. Never throws.
     */
    suspend fun getRecovery(): MatrixRecoveryHandle? = null

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

    /** Adds the current user's [key] reaction to [itemId], or removes it if already there; false on failure. Never throws. */
    suspend fun toggleReaction(itemId: EventOrTxnId, key: String): Boolean = false

    /** Sends a public read receipt for the remote event id [eventId]; false when it was rejected. Never throws. */
    suspend fun sendReadReceipt(eventId: String): Boolean

    /** Uploads [path] as an image message named [filename]; false when it could not be sent. Never throws. */
    suspend fun sendImage(path: String, filename: String, mimetype: String?): Boolean

    val paginationState: StateFlow<PaginationState>

    /** Cancels subscriptions and destroys every SDK object this handle holds. Idempotent. */
    fun close()
}
