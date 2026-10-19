package com.hermes.client.data.matrix

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import uniffi.matrix_sdk_base.EncryptionState
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

/** One row of the room list: name, encryption and unread count. */
data class SpikeRoomRow(val id: String, val name: String, val encrypted: Boolean, val unread: Long)

/**
 * Throwaway holder for the spike screen. Not a ViewModel on purpose: it lives in the activity and
 * everything is torn down with it. Credentials are only passed through to the SDK, never retained.
 */
class MatrixSpike(context: Context) {
    private val factory = MatrixClientFactory(context.applicationContext)

    var status by mutableStateOf("Not logged in")
    val rooms = mutableStateListOf<SpikeRoomRow>()
    // Replaced wholesale (immutable snapshots) because the SDK calls back on its own threads.
    var timelineLines by mutableStateOf<List<String>>(emptyList())
    var timelineCallbacks by mutableStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var client: Client? = null
    private var syncService: SyncService? = null
    private var listResult: RoomListEntriesWithDynamicAdaptersResult? = null
    private var entriesHandle: TaskHandle? = null
    private var timeline: Timeline? = null
    private var timelineHandle: TaskHandle? = null

    // Rust calls the listeners from its own threads, so the backing lists are guarded.
    private val lock = Any()
    private var roomEntries: List<Room> = emptyList()
    private var timelineItems: List<TimelineItem> = emptyList()

    suspend fun loginAndListRooms(homeserver: String, user: String, password: String, ca: ByteArray?) {
        stop()
        status = "Building client..."
        val c = factory.build(homeserver, ca)
        status = "Logging in..."
        c.login(user.trim(), password, "Hermes spike", null)
        client = c
        DebugLog.log("matrix", "logged in as ${c.userId()} device ${c.deviceId()}")
        status = "Logged in as ${c.userId()}; starting sync..."

        val sync = c.syncService().finish()
        syncService = sync
        val result = sync.roomListService().allRooms().entriesWithDynamicAdapters(
            50u,
            object : RoomListEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                    synchronized(lock) { roomEntries = applyDiffs(roomEntries, roomEntriesUpdate.map { it.toListDiff() }) }
                    refreshRows()
                }
            },
        )
        listResult = result
        entriesHandle = result.entriesStream()
        result.controller().setFilter(RoomListEntriesDynamicFilterKind.NonLeft)
        sync.start()
        status = "Syncing as ${c.userId()}"
    }

    private fun refreshRows() {
        val snapshot = synchronized(lock) { roomEntries }
        // roomInfo() is a suspend call, so it can't run inside the (non-suspend) listener callback.
        scope.launch {
            val rows = snapshot.map { r ->
                val info = r.roomInfo()
                SpikeRoomRow(
                    id = info.id,
                    name = info.displayName ?: info.rawName ?: info.id,
                    encrypted = info.encryptionState == EncryptionState.ENCRYPTED,
                    unread = info.numUnreadMessages.toLong(),
                )
            }
            rooms.clear()
            rooms.addAll(rows)
        }
    }

    suspend fun createDmAndPing(bot: String, encrypted: Boolean) {
        val c = client ?: error("Log in first")
        status = "Creating ${if (encrypted) "encrypted" else "unencrypted"} DM..."
        val roomId = c.createRoom(
            CreateRoomParameters(
                name = null,
                isEncrypted = encrypted,
                isDirect = true,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
                invite = listOf(bot.trim()),
            ),
        )
        status = "Created room (encrypted=$encrypted)"
        val room = c.getRoom(roomId) ?: error("Room $roomId not available after creation")
        timelineHandle?.cancel()
        timeline?.destroy()
        // Drop the destroyed references so a throw from room.timeline() can't leave them in use.
        timelineHandle = null
        timeline = null
        synchronized(lock) { timelineItems = emptyList() }
        val tl = room.timeline()
        timeline = tl
        timelineLines = emptyList()
        timelineCallbacks = 0
        timelineHandle = tl.addListener(
            object : TimelineListener {
                // An exception thrown from a UniFFI callback is swallowed, so report it ourselves.
                override fun onUpdate(diff: List<TimelineDiff>) {
                    try {
                        val ops = diff.map { it.toListDiff() }
                        val items = synchronized(lock) {
                            timelineItems = applyDiffs(timelineItems, ops)
                            timelineItems
                        }
                        val lines = items.mapIndexed { i, item -> describe(i, item) }
                        timelineCallbacks++
                        timelineLines = lines
                        // Variant + size only, never content.
                        val summary = "timeline update: ${ops.joinToString(",") { it.label() }} -> ${items.size} item(s)"
                        DebugLog.log("matrix", summary)
                        android.util.Log.i("MatrixSpike", summary)
                    } catch (e: Throwable) {
                        val msg = DebugLog.redact("timeline listener failed: $e")
                        DebugLog.log("matrix-error", msg)
                        android.util.Log.e("MatrixSpike", msg)
                        timelineLines = listOf(msg)
                    }
                }
            },
        )
        status = "Timeline listener registered; sending ping..."
        tl.send(messageEventContentFromMarkdown("ping from Hermes Android spike"))
        status = "Ping queued (encrypted=$encrypted)"
    }

    private fun TimelineDiff.toListDiff(): ListDiff<TimelineItem> = when (this) {
        is TimelineDiff.Append -> ListDiff.Append(values)
        is TimelineDiff.Clear -> ListDiff.Clear
        is TimelineDiff.PushFront -> ListDiff.PushFront(value)
        is TimelineDiff.PushBack -> ListDiff.PushBack(value)
        is TimelineDiff.PopFront -> ListDiff.PopFront
        is TimelineDiff.PopBack -> ListDiff.PopBack
        is TimelineDiff.Insert -> ListDiff.Insert(index.toInt(), value)
        is TimelineDiff.Set -> ListDiff.Set(index.toInt(), value)
        is TimelineDiff.Remove -> ListDiff.Remove(index.toInt())
        is TimelineDiff.Truncate -> ListDiff.Truncate(length.toInt())
        is TimelineDiff.Reset -> ListDiff.Reset(values)
    }

    private fun RoomListEntriesUpdate.toListDiff(): ListDiff<Room> = when (this) {
        is RoomListEntriesUpdate.Append -> ListDiff.Append(values)
        is RoomListEntriesUpdate.Clear -> ListDiff.Clear
        is RoomListEntriesUpdate.PushFront -> ListDiff.PushFront(value)
        is RoomListEntriesUpdate.PushBack -> ListDiff.PushBack(value)
        is RoomListEntriesUpdate.PopFront -> ListDiff.PopFront
        is RoomListEntriesUpdate.PopBack -> ListDiff.PopBack
        is RoomListEntriesUpdate.Insert -> ListDiff.Insert(index.toInt(), value)
        is RoomListEntriesUpdate.Set -> ListDiff.Set(index.toInt(), value)
        is RoomListEntriesUpdate.Remove -> ListDiff.Remove(index.toInt())
        is RoomListEntriesUpdate.Truncate -> ListDiff.Truncate(length.toInt())
        is RoomListEntriesUpdate.Reset -> ListDiff.Reset(values)
    }

    // Every item gets a line, including virtual ones (day dividers, read marker), so nothing is hidden.
    private fun describe(index: Int, item: TimelineItem): String {
        val ev = item.asEvent() ?: return "#$index [virtual: ${item.asVirtual()?.let { it::class.simpleName }}]"
        val text = when (val c = ev.content) {
            is TimelineItemContent.MsgLike -> when (val k = c.content.kind) {
                is MsgLikeKind.Message -> k.content.body
                is MsgLikeKind.UnableToDecrypt -> "[unable to decrypt]"
                else -> "[${k::class.simpleName}]"
            }
            else -> "[${c::class.simpleName}]"
        }
        return DebugLog.redact("#$index ${ev.sender}: $text")
    }

    fun stop() {
        timelineHandle?.cancel()
        entriesHandle?.cancel()
        timeline?.destroy()
        listResult?.destroy()
        syncService?.destroy()
        client?.destroy()
        timelineHandle = null
        entriesHandle = null
        timeline = null
        listResult = null
        syncService = null
        client = null
    }
}
