package com.hermes.client.data.matrix

import android.content.Context
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomList
import org.matrix.rustcomponents.sdk.RoomListDynamicEntriesController
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TimelineItemContent
import uniffi.matrix_sdk_base.EncryptionState

/** [MatrixSdk] over the matrix-rust-sdk UniFFI bindings. */
class RustMatrixSdk(private val context: Context) : MatrixSdk {
    override suspend fun createClient(config: MatrixClientConfig): MatrixClientHandle {
        MatrixPlatform.initOnce(context)
        config.dataDir.mkdirs()
        config.cacheDir.mkdirs()
        // Last session the SDK asked us to save; used if it asks for it back before we persisted one.
        val latest = java.util.concurrent.atomic.AtomicReference<MatrixSessionData?>()
        val delegate = object : ClientSessionDelegate {
            override fun retrieveSessionFromKeychain(userId: String): Session =
                (config.currentSession(userId) ?: latest.get()?.takeIf { it.userId == userId })?.toSdk()
                    ?: throw IllegalStateException("no saved Matrix session for the requested user")

            override fun saveSessionInKeychain(session: Session) {
                val data = session.toData()
                latest.set(data)
                // Token refresh. Only metadata is logged, never the tokens.
                DebugLog.log("matrix", "session tokens refreshed for ${data.userId}")
                config.onSessionRefreshed(data)
            }
        }
        var builder = ClientBuilder()
            .homeserverUrl(config.homeserverUrl)
            .disableWellKnownLookup(true)
            .userAgent("HermesAndroid")
            // Synapse serves native simplified sliding sync (MSC4186); without a version the room
            // list fails with "Sliding sync version is missing". Needed on restore too.
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .setSessionDelegate(delegate)
            .sqliteStore(
                SqliteStoreBuilder(config.dataDir.absolutePath, config.cacheDir.absolutePath)
                    .passphrase(config.storePassphrase),
            )
        if (config.caCerts.isNotEmpty()) {
            // addRootCertificates alone fails on Android ("builder error": reqwest cannot merge extra
            // roots into rustls-platform-verifier there); disabling the built-in roots switches to a
            // plain verifier that trusts only the imported CA(s).
            builder = builder.addRootCertificates(config.caCerts).disableBuiltInRootCertificates()
        }
        return RustClientHandle(builder.build())
    }
}

private class RustClientHandle(private val client: Client) : MatrixClientHandle {
    private val rawUpdates = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)

    // Mapping needs suspend roomInfo(), so it runs in the collector, sequentially and in order.
    override val roomDiffs: Flow<List<ListDiff<MatrixRoomSummary>>> =
        rawUpdates.receiveAsFlow().map { updates -> updates.map { it.toSummaryDiff() } }

    private var syncService: SyncService? = null
    private var roomListService: RoomListService? = null
    private var roomList: RoomList? = null
    private var listResult: RoomListEntriesWithDynamicAdaptersResult? = null
    private var controller: RoomListDynamicEntriesController? = null
    private var entriesHandle: TaskHandle? = null
    @Volatile private var closed = false

    // Every entry point refuses a closed handle instead of touching destroyed native objects.
    private fun requireOpen() = check(!closed) { "Matrix client handle is closed" }

    override suspend fun login(username: String, password: String, deviceName: String): MatrixSessionData {
        requireOpen()
        client.login(username, password, deviceName, null)
        return client.session().toData()
    }

    override suspend fun restore(session: MatrixSessionData) {
        requireOpen()
        client.restoreSession(session.toSdk())
    }

    override suspend fun startSync() {
        requireOpen()
        val sync = client.syncService().finish()
        syncService = sync
        val svc = sync.roomListService()
        roomListService = svc
        val list = svc.allRooms()
        roomList = list
        val result = list.entriesWithDynamicAdapters(
            50u,
            object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    rawUpdates.trySend(roomEntriesUpdate)
                }
            },
        )
        listResult = result
        entriesHandle = result.entriesStream()
        val ctl = result.controller()
        controller = ctl
        ctl.setFilter(RoomListEntriesDynamicFilterKind.NonLeft)
        sync.start()
    }

    override suspend fun stopSync() {
        if (closed) return
        syncService?.stop()
    }

    override suspend fun logout() {
        requireOpen()
        client.logout()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        // Order: cancel subscriptions, stop sync, then destroy children before the client.
        runCatching { entriesHandle?.cancel() }
        try {
            syncService?.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("matrix-error", "sync stop failed: ${e.javaClass.simpleName}")
        }
        rawUpdates.close()
        runCatching { entriesHandle?.destroy() }
        runCatching { controller?.destroy() }
        runCatching { listResult?.destroy() }
        runCatching { roomList?.destroy() }
        runCatching { roomListService?.destroy() }
        runCatching { syncService?.destroy() }
        runCatching { client.destroy() }
        entriesHandle = null
        controller = null
        listResult = null
        roomList = null
        roomListService = null
        syncService = null
    }
}

private suspend fun Room.toSummary(): MatrixRoomSummary {
    val roomId = id()
    return try {
        val info = roomInfo()
        try {
            val name = info.displayName ?: info.rawName ?: info.id
            val activity = latestActivity()
            MatrixRoomSummary(
                id = info.id,
                name = name,
                isEncrypted = info.encryptionState == EncryptionState.ENCRYPTED,
                unread = info.numUnreadMessages.toLong(),
                isDirect = info.isDirect,
                lastMessagePreview = activity.preview,
                lastMessageSender = activity.sender,
                lastActivityTs = activity.ts,
                avatarLetter = avatarLetterFor(name),
            )
        } finally {
            info.destroy()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MatrixRoomSummary(roomId, roomId, isEncrypted = false, unread = 0, isDirect = false, avatarLetter = avatarLetterFor(roomId))
    }
}

/** The room's latest event as the room list shows it. Any failure reads as "no activity". */
private class LatestActivity(val preview: String?, val sender: String?, val ts: Long?) {
    companion object {
        val NONE = LatestActivity(null, null, null)
    }
}

private suspend fun Room.latestActivity(): LatestActivity = try {
    val latest = latestEvent()
    try {
        latest.toActivity()
    } finally {
        latest.destroy()
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    LatestActivity.NONE
}

private fun LatestEventValue.toActivity(): LatestActivity = when (this) {
    is LatestEventValue.Remote -> eventActivity(sender, timestamp.toLong(), profile, content, isOwn)
    is LatestEventValue.Local -> eventActivity(sender, timestamp.toLong(), profile, content, isOwn = true)
    is LatestEventValue.RemoteInvite -> LatestActivity(null, null, timestamp.toLong())
    else -> LatestActivity.NONE
}

private fun eventActivity(
    sender: String,
    ts: Long,
    profile: ProfileDetails,
    content: TimelineItemContent,
    isOwn: Boolean,
): LatestActivity {
    val displayName = (profile as? ProfileDetails.Ready)?.displayName
    return LatestActivity(content.previewOrNull(), senderLabel(sender, displayName, isOwn), ts)
}

/** Plain-text preview of an event's content, or null for state and membership events. */
private fun TimelineItemContent.previewOrNull(): String? {
    val msgLike = this as? TimelineItemContent.MsgLike ?: return null
    return when (val kind = msgLike.content.kind) {
        is MsgLikeKind.Message -> previewText(kind.content.body)
        is MsgLikeKind.UnableToDecrypt -> ENCRYPTED_PREVIEW
        is MsgLikeKind.Redacted -> "Message deleted"
        is MsgLikeKind.Poll -> previewText(kind.question)
        is MsgLikeKind.Sticker -> previewText(kind.body)
        else -> null
    }
}

/** Maps to summaries and releases the `Room` handles the diff carried. */
private suspend fun RoomListEntriesUpdate.toSummaryDiff(): ListDiff<MatrixRoomSummary> {
    suspend fun one(r: Room) = r.toSummary().also { r.destroy() }
    suspend fun many(rs: List<Room>) = rs.map { one(it) }
    return when (this) {
        is RoomListEntriesUpdate.Append -> ListDiff.Append(many(values))
        is RoomListEntriesUpdate.Clear -> ListDiff.Clear
        is RoomListEntriesUpdate.PushFront -> ListDiff.PushFront(one(value))
        is RoomListEntriesUpdate.PushBack -> ListDiff.PushBack(one(value))
        is RoomListEntriesUpdate.PopFront -> ListDiff.PopFront
        is RoomListEntriesUpdate.PopBack -> ListDiff.PopBack
        is RoomListEntriesUpdate.Insert -> ListDiff.Insert(index.toInt(), one(value))
        is RoomListEntriesUpdate.Set -> ListDiff.Set(index.toInt(), one(value))
        is RoomListEntriesUpdate.Remove -> ListDiff.Remove(index.toInt())
        is RoomListEntriesUpdate.Truncate -> ListDiff.Truncate(length.toInt())
        is RoomListEntriesUpdate.Reset -> ListDiff.Reset(many(values))
    }
}

private fun Session.toData() = MatrixSessionData(
    accessToken = accessToken,
    refreshToken = refreshToken,
    userId = userId,
    deviceId = deviceId,
    homeserverUrl = homeserverUrl,
    oauthData = oauthData,
    slidingSyncVersion = slidingSyncVersion.name,
)

private fun MatrixSessionData.toSdk() = Session(
    accessToken = accessToken,
    refreshToken = refreshToken,
    userId = userId,
    deviceId = deviceId,
    homeserverUrl = homeserverUrl,
    oauthData = oauthData,
    slidingSyncVersion = runCatching { SlidingSyncVersion.valueOf(slidingSyncVersion) }
        .getOrDefault(SlidingSyncVersion.NATIVE),
)
