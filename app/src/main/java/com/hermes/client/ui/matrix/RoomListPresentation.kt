package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.ui.util.isSameDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Matrix room list shows, derived from the account state and the room list. */
sealed interface RoomListUiState {
    data object SignedOut : RoomListUiState
    data object Loading : RoomListUiState
    data class RestoreFailed(val message: String, val retryable: Boolean) : RoomListUiState
    data class Failed(val message: String) : RoomListUiState

    /** [rooms] is already sorted and filtered; [hasAnyRooms] tells "no rooms" apart from "no matches". */
    data class Ready(val rooms: List<MatrixRoomSummary>, val query: String, val hasAnyRooms: Boolean) : RoomListUiState
}

/** Case-insensitive match on the room name or its last message preview. A blank query keeps everything. */
fun filterRooms(rooms: List<MatrixRoomSummary>, query: String): List<MatrixRoomSummary> {
    val q = query.trim()
    if (q.isEmpty()) return rooms
    return rooms.filter {
        it.name.contains(q, ignoreCase = true) ||
            (it.lastMessagePreview?.contains(q, ignoreCase = true) == true)
    }
}

/**
 * Activity label for a room row: "14:05" today, "Yesterday", otherwise "Oct 3". Empty when there is
 * no activity. Pure: [nowMs], [zone] and [locale] are passed in so it is deterministic in tests.
 */
fun formatRoomActivity(
    epochMs: Long?,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    if (epochMs == null) return ""
    val local = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return when {
        isSameDay(epochMs, nowMs, zone) -> DateTimeFormatter.ofPattern("HH:mm", locale).format(local)
        local.toLocalDate() == today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("MMM d", locale).format(local)
    }
}

/** Maps the account state and the room list to what the screen shows. */
fun roomListUiState(matrix: MatrixState, rooms: List<MatrixRoomSummary>, query: String): RoomListUiState =
    when (matrix) {
        MatrixState.LoggedOut -> RoomListUiState.SignedOut
        MatrixState.Restoring, MatrixState.LoggingIn -> RoomListUiState.Loading
        is MatrixState.RestoreFailed -> RoomListUiState.RestoreFailed(matrix.message, matrix.retryable)
        is MatrixState.Error -> RoomListUiState.Failed(matrix.message)
        is MatrixState.Ready -> RoomListUiState.Ready(
            // The SDK's list order is the recency order (sliding sync), so it is used as-is. The latest
            // event timestamp can disagree with it (state, UTD, invite and local echo events), so we
            // never re-sort by it.
            rooms = filterRooms(rooms, query),
            query = query.trim(),
            hasAnyRooms = rooms.isNotEmpty(),
        )
    }
