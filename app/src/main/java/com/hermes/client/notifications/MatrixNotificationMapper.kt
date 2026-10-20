package com.hermes.client.notifications

import com.hermes.client.data.matrix.MatrixRoomSummary
import java.net.URLEncoder

/** What to post and which previously posted room notifications to clear for one room-list change. */
data class MatrixNotificationDiff(
    val posts: List<NotificationSpec>,
    val cancelIds: List<Int>,
)

/** Stable per-room notification id, kept clear of the service (1001/1002) and progress (1003) ids. */
fun matrixNotificationId(roomId: String): Int {
    val id = ("matrix$roomId").hashCode()
    return if (id in 1001..1003) 1004 else id
}

/**
 * Pure diff of two consecutive room-list snapshots into notification actions. A room notifies only
 * when it was already in [previous] and its unread count grew (or its latest activity advanced while
 * unread), the latest event is not our own, and the app is backgrounded; rooms that first appear
 * (initial sync, login) never notify, so a cold start cannot flood the shade with history. A room
 * whose unread count drops to zero (read on this or another device), or that leaves the list
 * (left room, logout), has its notification cleared. Nothing here logs or throws.
 */
fun diffMatrixNotifications(
    previous: List<MatrixRoomSummary>,
    next: List<MatrixRoomSummary>,
    prefs: NotificationPrefs,
    appInForeground: Boolean,
): MatrixNotificationDiff {
    val before = previous.associateBy { it.id }
    val posts = mutableListOf<NotificationSpec>()
    val cancels = mutableListOf<Int>()
    val remaining = next.mapTo(HashSet()) { it.id }
    previous.filter { it.id !in remaining }.mapTo(cancels) { matrixNotificationId(it.id) }
    for (room in next) {
        val old = before[room.id] ?: continue
        val id = matrixNotificationId(room.id)
        if (room.unread == 0L && old.unread > 0L) {
            cancels += id
            continue
        }
        if (!prefs.enabled || !prefs.matrixMessages || appInForeground) continue
        // Unread and the latest-event preview arrive as separate SDK updates, so the unread bump can
        // land while the preview still shows our own last message. Counting a later activity-ts
        // advance (with unread still pending) catches that second update.
        val grew = room.unread > old.unread
        val advanced = room.unread > 0L && (room.lastActivityTs ?: 0L) > (old.lastActivityTs ?: 0L)
        if (!grew && !advanced) continue
        if (room.lastMessageSender == OWN_SENDER) continue
        posts += NotificationSpec(
            id = id,
            channelId = Notif.CHANNEL_MATRIX,
            title = room.name.ifBlank { "New message" },
            body = bodyFor(room),
            route = "matrix_room/" + URLEncoder.encode(room.id, "UTF-8").replace("+", "%20"),
            actions = emptyList(),
            groupKey = "matrix",
        )
    }
    return MatrixNotificationDiff(posts, cancels)
}

private const val OWN_SENDER = "You"

private fun bodyFor(room: MatrixRoomSummary): String {
    val preview = room.lastMessagePreview?.takeIf { it.isNotBlank() } ?: return "New message"
    val sender = room.lastMessageSender?.takeIf { it.isNotBlank() && !room.isDirect }
    return if (sender != null) "$sender: $preview" else preview
}
