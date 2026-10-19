package com.hermes.client.data.matrix

import android.content.Context
import com.hermes.client.data.diagnostics.DebugLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Disposable
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.OtherState
import org.matrix.rustcomponents.sdk.PaginationStatusListener
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomList
import org.matrix.rustcomponents.sdk.RoomListDynamicEntriesController
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.SendTarget
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.VirtualTimelineItem
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import uniffi.matrix_sdk.PaginationStatus
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

    /** Timeline handles opened from this client; they must finish tearing down before [client]. */
    private val timelines = ClosableRegistry<RustTimelineHandle>()

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

    override suspend fun openTimeline(roomId: String): MatrixTimelineHandle? {
        requireOpen()
        val room = client.getRoom(roomId) ?: return null
        val timeline = try {
            room.timeline()
        } catch (e: CancellationException) {
            runCatching { room.destroy() }
            throw e
        } catch (e: Exception) {
            runCatching { room.destroy() }
            throw e
        }
        val handle = RustTimelineHandle(room, timeline)
        if (!timelines.register(handle)) {
            // The client is closing: nothing will await this handle, so tear it down here.
            handle.close()
            handle.awaitClosed()
            throw IllegalStateException("Matrix client handle is closed")
        }
        handle.onClosed { timelines.unregister(handle) }
        try {
            handle.start()
        } catch (e: Throwable) {
            runCatching { handle.close() }
            throw e
        }
        return handle
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
        // Children before the client: every open timeline must finish its own teardown (which
        // destroys its Room/Timeline) before client.destroy(). close() is already suspend, and the
        // manager calls it off the main thread.
        timelines.closeAll()
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

/** Number of events the timeline loads on the first page and on every repagination. */
private const val TIMELINE_PAGE_SIZE = 50

/** Monotonic suffix for items whose SDK `uniqueId` is missing, so row keys never collide. */
private val FALLBACK_ID_COUNTER = java.util.concurrent.atomic.AtomicLong(0)

private class RustTimelineHandle(
    private val room: Room,
    private val timeline: Timeline,
) : MatrixTimelineHandle, AsyncCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rawUpdates = ClosableDiffQueue<TimelineDiff> { it.destroyCarriedItems() }

    private val _items = MutableStateFlow<List<TimelineEntry>>(emptyList())
    override val items: StateFlow<List<TimelineEntry>> = _items.asStateFlow()

    private val _paginationState = MutableStateFlow(PaginationState.Idle)
    override val paginationState: StateFlow<PaginationState> = _paginationState.asStateFlow()

    @Volatile private var listenerHandle: TaskHandle? = null
    @Volatile private var paginationHandle: TaskHandle? = null
    private var collectorJob: Job? = null
    private val closeRequested = AtomicBoolean(false)
    private val tornDown = CompletableDeferred<Unit>()

    /**
     * Every FFI call on [timeline] / [room] (start, UI pagination, repaginator pages) runs inside
     * [FfiUseGuard.use]; the teardown's [FfiUseGuard.closeAndAwait] refuses new uses, waits for the
     * in-flight ones and only then runs the destroys. So no call can reach a destroyed object, and a
     * call made after close returns promptly without touching FFI.
     */
    private val ffi = FfiUseGuard {
        // Stable now: start() is a guarded use and has finished. Stop diffs, drain what is buffered
        // (each item destroyed exactly once), then destroy listener, pagination, timeline, room.
        val listener = listenerHandle
        val pagination = paginationHandle
        runCatching { listener?.cancel() }
        runCatching { pagination?.cancel() }
        rawUpdates.close()
        runCatching { listener?.destroy() }
        runCatching { pagination?.destroy() }
        runCatching { timeline.destroy() }
        runCatching { room.destroy() }
    }

    private val repaginator = TimelineRepaginator(scope) { count -> paginateBackwards(count) }

    private val listener = object : TimelineListener {
        override fun onUpdate(diff: List<TimelineDiff>) {
            // Never throw on the SDK thread. If the handle is already closing (or the send fails),
            // send() discards the batch's items so a late diff is never leaked.
            rawUpdates.send(diff)
        }
    }

    private val statusListener = object : PaginationStatusListener {
        override fun onUpdate(status: PaginationStatus) {
            _paginationState.value = when (status) {
                is PaginationStatus.Paginating -> PaginationState.Loading
                is PaginationStatus.Idle ->
                    if (status.hitTimelineStart) PaginationState.AtStart else PaginationState.Idle
            }
        }
    }

    init {
        collectorJob = scope.launch {
            rawUpdates.batches.collect { updates ->
                try {
                    var reset = false
                    val diffs = ArrayList<ListDiff<TimelineEntry>>(updates.size)
                    for (d in updates) {
                        if (d is TimelineDiff.Clear || (d is TimelineDiff.Reset && d.values.isEmpty())) {
                            reset = true
                        }
                        diffs += d.toEntryDiff()
                    }
                    _items.value = applyDiffs(_items.value, diffs)
                    repaginator.onBatch(reset)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A single bad batch must never kill the collector.
                    DebugLog.log("matrix-timeline", "apply batch failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Attaches the listener and pagination subscription, then loads the first page. */
    suspend fun start() {
        ffi.use(Unit) {
            listenerHandle = timeline.addListener(listener)
            paginationHandle = timeline.subscribeToBackPaginationStatus(statusListener)
            val hitStart = timeline.paginateBackwards(TIMELINE_PAGE_SIZE.toUShort())
            // Resets only count once the first page has been requested (a Clear during the initial
            // load must not schedule another page); seed its result so a start-of-room page also stops
            // any later Clear from firing a redundant one.
            repaginator.onFirstPageRequested(hitStart)
        }
    }

    override suspend fun paginateBackwards(count: Int): Boolean = ffi.use(false) {
        _paginationState.value = PaginationState.Loading
        timeline.paginateBackwards(count.toUShort())
    }

    override suspend fun sendText(body: String): Boolean = ffi.use(false) {
        val content = try {
            messageEventContentFromMarkdown(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("matrix-timeline", "send content failed: ${e.javaClass.simpleName}")
            return@use false
        }
        try {
            val handle = timeline.send(content)
            runCatching { handle.destroy() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log the body: it is user content.
            DebugLog.log("matrix-timeline", "send failed: ${e.javaClass.simpleName}")
            false
        } finally {
            runCatching { content.destroy() }
        }
    }

    override suspend fun retrySend(itemId: EventOrTxnId): Boolean = ffi.use(false) {
        try {
            // The id is a plain FFI value type, so there is no native object to destroy.
            timeline.retrySend(itemId.toSdk(), SendTarget.Event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log the event contents: retry only names the SDK failure class.
            DebugLog.log("matrix-timeline", "retry send failed: ${e.javaClass.simpleName}")
            false
        }
    }

    override suspend fun abortSend(itemId: EventOrTxnId): Boolean = ffi.use(false) {
        try {
            timeline.abortSend(itemId.toSdk(), SendTarget.Event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.log("matrix-timeline", "abort send failed: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Runs [action] once teardown has fully finished (immediately if it already has). */
    fun onClosed(action: () -> Unit) {
        tornDown.invokeOnCompletion { action() }
    }

    override suspend fun awaitClosed() = tornDown.join()

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        // Refuse new FFI calls immediately and stop the debounced repagination; this never blocks the
        // caller. The ordered teardown runs on this handle's own background scope (Dispatchers.Default,
        // never cancelled) so it cannot block the main thread:
        //   refuse uses -> cancel SDK TaskHandles -> cancelAndJoin the collector -> join the
        //   repaginator's page -> wait for in-flight guarded calls -> drain queue and destroy.
        ffi.beginClose()
        repaginator.close()
        // Best effort now so diffs stop early; the guarded destroy repeats it from stable state.
        runCatching { listenerHandle?.cancel() }
        runCatching { paginationHandle?.cancel() }
        scope.launch {
            try {
                collectorJob?.cancelAndJoin()
                repaginator.awaitClosed()
                ffi.closeAndAwait()
            } finally {
                tornDown.complete(Unit)
            }
        }
    }
}

/** Destroys every [TimelineItem] carried by a diff the collector never received. */
private fun TimelineDiff.destroyCarriedItems() {
    when (this) {
        is TimelineDiff.Append -> values.forEach { runCatching { it.destroy() } }
        is TimelineDiff.Clear -> Unit
        is TimelineDiff.PushFront -> runCatching { value.destroy() }
        is TimelineDiff.PushBack -> runCatching { value.destroy() }
        is TimelineDiff.PopFront -> Unit
        is TimelineDiff.PopBack -> Unit
        is TimelineDiff.Insert -> runCatching { value.destroy() }
        is TimelineDiff.Set -> runCatching { value.destroy() }
        is TimelineDiff.Remove -> Unit
        is TimelineDiff.Truncate -> Unit
        is TimelineDiff.Reset -> values.forEach { runCatching { it.destroy() } }
    }
}

/** Maps an SDK diff onto the pure [ListDiff], destroying every carried item exactly once. */
private suspend fun TimelineDiff.toEntryDiff(): ListDiff<TimelineEntry> {
    suspend fun one(item: TimelineItem): TimelineEntry = try {
        item.toEntry()
    } finally {
        item.destroy()
    }
    suspend fun many(items: List<TimelineItem>): List<TimelineEntry> {
        val out = ArrayList<TimelineEntry>(items.size)
        var handled = 0
        try {
            for (item in items) {
                try {
                    out += item.toEntry()
                } finally {
                    item.destroy()
                    handled++
                }
            }
        } finally {
            // Items after the last one handled (cancellation or an error) were never destroyed.
            items.drop(handled).forEach { runCatching { it.destroy() } }
        }
        return out
    }
    return when (this) {
        is TimelineDiff.Append -> ListDiff.Append(many(values))
        is TimelineDiff.Clear -> ListDiff.Clear
        is TimelineDiff.PushFront -> ListDiff.PushFront(one(value))
        is TimelineDiff.PushBack -> ListDiff.PushBack(one(value))
        is TimelineDiff.PopFront -> ListDiff.PopFront
        is TimelineDiff.PopBack -> ListDiff.PopBack
        is TimelineDiff.Insert -> ListDiff.Insert(index.toInt(), one(value))
        is TimelineDiff.Set -> ListDiff.Set(index.toInt(), one(value))
        is TimelineDiff.Remove -> ListDiff.Remove(index.toInt())
        is TimelineDiff.Truncate -> ListDiff.Truncate(length.toInt())
        is TimelineDiff.Reset -> ListDiff.Reset(many(values))
    }
}

private fun TimelineItem.toEntry(): TimelineEntry {
    val id = runCatching { uniqueId().id }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "fallback-${FALLBACK_ID_COUNTER.incrementAndGet()}"
    return try {
        val virtual = asVirtual()
        if (virtual != null) {
            when (virtual) {
                is VirtualTimelineItem.DateDivider -> TimelineEntry.DayDivider(virtual.ts.toLong())
                is VirtualTimelineItem.ReadMarker -> TimelineEntry.ReadMarker
                else -> TimelineEntry.Unsupported(id)
            }
        } else {
            val event = asEvent()
            if (event == null) {
                TimelineEntry.Unsupported(id)
            } else {
                try {
                    event.toEventEntry(id, event.eventOrTransactionId.toItemId())
                } finally {
                    event.destroy()
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Never let one bad item kill the collector; log the variant and render nothing.
        DebugLog.log("matrix-timeline", "skip item: ${e.javaClass.simpleName}")
        TimelineEntry.Unsupported(id)
    }
}

private fun EventTimelineItem.toEventEntry(id: String, itemId: EventOrTxnId): TimelineEntry {
    val disposables = ArrayList<Disposable>()
    try {
        val sender = sender
        val isOwn = isOwn
        val ts = timestamp.toLong()
        val senderName = (senderProfile as? ProfileDetails.Ready)?.displayName.orEmpty()
        val sendState = localSendState.toSendState()
        val content = this.content
        disposables += content
        return when (content) {
            is TimelineItemContent.MsgLike -> {
                val msgLike = content.content
                disposables += msgLike
                msgLike.toEntry(disposables, id, itemId, sender, senderName, isOwn, ts, sendState)
            }
            is TimelineItemContent.RoomMembership -> TimelineEntry.StateEvent(id, ts, content.membershipSummary())
            is TimelineItemContent.ProfileChange -> TimelineEntry.StateEvent(id, ts, content.profileSummary())
            is TimelineItemContent.State -> TimelineEntry.StateEvent(id, ts, content.content.stateSummary())
            else -> TimelineEntry.Unsupported(id)
        }
    } finally {
        disposables.asReversed().forEach { runCatching { it.destroy() } }
    }
}

private fun MsgLikeContent.toEntry(
    disposables: MutableList<Disposable>,
    id: String,
    itemId: EventOrTxnId,
    sender: String,
    senderName: String,
    isOwn: Boolean,
    ts: Long,
    sendState: MessageSendState,
): TimelineEntry {
    val kind = this.kind
    disposables += kind
    return when (kind) {
        is MsgLikeKind.Message -> {
            val mc = kind.content
            disposables += mc
            mc.toMessageEntry(disposables, id, itemId, sender, senderName, isOwn, ts, sendState)
        }
        is MsgLikeKind.UnableToDecrypt -> TimelineEntry.Utd(id, sender, ts)
        is MsgLikeKind.Redacted ->
            TimelineEntry.Message(id, sender, senderName, isOwn, ts, "", isEdited = false, MessageKind.OTHER, sendState, itemId)
        is MsgLikeKind.Poll ->
            TimelineEntry.Message(id, sender, senderName, isOwn, ts, kind.question, isEdited = false, MessageKind.OTHER, sendState, itemId)
        is MsgLikeKind.Sticker ->
            TimelineEntry.Message(id, sender, senderName, isOwn, ts, kind.body, isEdited = false, MessageKind.IMAGE, sendState, itemId)
        else ->
            TimelineEntry.Message(id, sender, senderName, isOwn, ts, "", isEdited = false, MessageKind.OTHER, sendState, itemId)
    }
}

private fun MessageContent.toMessageEntry(
    disposables: MutableList<Disposable>,
    id: String,
    itemId: EventOrTxnId,
    sender: String,
    senderName: String,
    isOwn: Boolean,
    ts: Long,
    sendState: MessageSendState,
): TimelineEntry {
    val body = this.body
    val isEdited = this.isEdited
    val msgType = this.msgType
    disposables += msgType
    val (kind, effectiveBody) = when (msgType) {
        is MessageType.Text -> MessageKind.TEXT to body
        is MessageType.Notice -> MessageKind.NOTICE to body
        is MessageType.Emote -> MessageKind.EMOTE to body
        is MessageType.Image -> MessageKind.IMAGE to attachmentBody(msgType.content, body, disposables)
        is MessageType.File -> MessageKind.FILE to attachmentBody(msgType.content, body, disposables)
        is MessageType.Audio -> MessageKind.FILE to attachmentBody(msgType.content, body, disposables)
        is MessageType.Video -> MessageKind.FILE to attachmentBody(msgType.content, body, disposables)
        is MessageType.Gallery -> {
            val c = msgType.content
            disposables += c
            MessageKind.OTHER to c.body
        }
        else -> MessageKind.OTHER to body
    }
    return TimelineEntry.Message(id, sender, senderName, isOwn, ts, effectiveBody, isEdited, kind, sendState, itemId)
}

/** The filename of an attachment, falling back to the message body when the filename is absent. */
private fun <T : Disposable> attachmentBody(content: T, body: String, disposables: MutableList<Disposable>): String {
    disposables += content
    val filename = when (content) {
        is org.matrix.rustcomponents.sdk.ImageMessageContent -> content.filename
        is org.matrix.rustcomponents.sdk.FileMessageContent -> content.filename
        is org.matrix.rustcomponents.sdk.AudioMessageContent -> content.filename
        is org.matrix.rustcomponents.sdk.VideoMessageContent -> content.filename
        else -> null
    }
    return filename?.takeIf { it.isNotBlank() } ?: body
}

private fun EventSendState?.toSendState(): MessageSendState = when (this) {
    is EventSendState.NotSentYet -> MessageSendState.NOT_SENT_YET
    is EventSendState.SendingFailed -> MessageSendState.SENDING_FAILED
    else -> MessageSendState.SENT
}

private fun EventOrTransactionId.toItemId(): EventOrTxnId = when (this) {
    is EventOrTransactionId.EventId -> EventOrTxnId.Event(eventId)
    is EventOrTransactionId.TransactionId -> EventOrTxnId.Transaction(transactionId)
}

private fun EventOrTxnId.toSdk(): EventOrTransactionId = when (this) {
    is EventOrTxnId.Event -> EventOrTransactionId.EventId(eventId)
    is EventOrTxnId.Transaction -> EventOrTransactionId.TransactionId(transactionId)
}

private fun TimelineItemContent.RoomMembership.membershipSummary(): String {
    val who = senderLabel(userId, userDisplayName, isOwn = false)
    return when (change) {
        MembershipChange.JOINED, MembershipChange.INVITATION_ACCEPTED -> "$who joined the room"
        MembershipChange.LEFT, MembershipChange.INVITATION_REJECTED, MembershipChange.INVITATION_REVOKED -> "$who left the room"
        MembershipChange.INVITED -> "$who was invited"
        MembershipChange.KICKED, MembershipChange.KICKED_AND_BANNED -> "$who was kicked"
        MembershipChange.BANNED -> "$who was banned"
        MembershipChange.UNBANNED -> "$who was unbanned"
        else -> "$who updated their membership"
    }
}

private fun TimelineItemContent.ProfileChange.profileSummary(): String {
    val name = displayName
    val prev = prevDisplayName
    return when {
        !name.isNullOrBlank() && !prev.isNullOrBlank() && name != prev -> "changed their name to $name"
        !name.isNullOrBlank() && prev.isNullOrBlank() -> "set their name to $name"
        name.isNullOrBlank() && !prev.isNullOrBlank() -> "removed their name"
        avatarUrl != null -> "changed their avatar"
        else -> "updated their profile"
    }
}

private fun OtherState.stateSummary(): String = when (this) {
    is OtherState.RoomName -> "changed the room name"
    is OtherState.RoomTopic -> "changed the topic"
    is OtherState.RoomAvatar -> "changed the room avatar"
    is OtherState.RoomCreate -> "created the room"
    is OtherState.RoomEncryption -> "turned on encryption"
    else -> "changed a room setting"
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

/**
 * Plain-text preview of an event's content, or null for state and membership events. Uses the
 * message's plain `body` (no HTML); markdown in it is shown as typed.
 */
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

/**
 * Maps to summaries and releases the `Room` handles the diff carried. Every room is destroyed
 * exactly once, even when mapping is cancelled or fails part-way through a batch.
 */
private suspend fun RoomListEntriesUpdate.toSummaryDiff(): ListDiff<MatrixRoomSummary> {
    suspend fun one(r: Room): MatrixRoomSummary = try {
        r.toSummary()
    } finally {
        r.destroy()
    }
    suspend fun many(rs: List<Room>): List<MatrixRoomSummary> {
        val out = ArrayList<MatrixRoomSummary>(rs.size)
        var handled = 0
        try {
            for (r in rs) {
                try {
                    out += r.toSummary()
                } finally {
                    r.destroy()
                    handled++
                }
            }
        } finally {
            // Rooms after the last one handled (cancellation or an error) were never destroyed.
            rs.drop(handled).forEach { runCatching { it.destroy() } }
        }
        return out
    }
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
