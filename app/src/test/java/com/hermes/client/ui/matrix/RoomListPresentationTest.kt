package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

private fun room(
    id: String,
    name: String,
    ts: Long? = null,
    preview: String? = null,
    unread: Long = 0,
    encrypted: Boolean = false,
) = MatrixRoomSummary(
    id = id,
    name = name,
    isEncrypted = encrypted,
    unread = unread,
    isDirect = true,
    lastMessagePreview = preview,
    lastMessageSender = preview?.let { "bot" },
    lastActivityTs = ts,
)

class RoomListPresentationTest {
    // 2026-10-19 12:00 UTC
    private val now = 1_792_411_200_000L
    private val hour = 3_600_000L

    @Test fun sort_is_newest_activity_first_with_no_activity_last_and_ties_kept_in_order() {
        val rooms = listOf(
            room("a", "A", ts = now - 5 * hour),
            room("b", "B", ts = null),
            room("c", "C", ts = now - hour),
            room("d", "D", ts = null),
            room("e", "E", ts = now - 5 * hour),
        )
        assertEquals(listOf("c", "a", "e", "b", "d"), sortByRecency(rooms).map { it.id })
    }

    @Test fun sort_does_not_float_unread_rooms() {
        val rooms = listOf(room("a", "A", ts = now - hour), room("b", "B", ts = now - 2 * hour, unread = 9))
        assertEquals(listOf("a", "b"), sortByRecency(rooms).map { it.id })
    }

    @Test fun filter_matches_name_and_preview_case_insensitively() {
        val rooms = listOf(
            room("a", "Hermes Bot", preview = "status report"),
            room("b", "Kitchen", preview = "Pizza tonight?"),
            room("c", "Garden", preview = null),
        )
        assertEquals(listOf("a"), filterRooms(rooms, "HERMES").map { it.id })
        assertEquals(listOf("b"), filterRooms(rooms, "pizza").map { it.id })
        assertEquals(listOf("a"), filterRooms(rooms, "  report ").map { it.id })
        assertTrue(filterRooms(rooms, "nothing").isEmpty())
    }

    @Test fun blank_query_keeps_every_room() {
        val rooms = listOf(room("a", "A"), room("b", "B"))
        assertEquals(rooms, filterRooms(rooms, "   "))
    }

    @Test fun activity_label_is_time_today_yesterday_or_date() {
        val zone = ZoneOffset.UTC
        val todayAt0905 = now - 3 * hour + 5 * 60_000 // 2026-10-19 09:05 UTC
        assertEquals("09:05", formatRoomActivity(todayAt0905, now, zone, Locale.US))
        val yesterdayLate = now - 12 * hour - 30 * 60_000 // 2026-10-18 23:30 UTC
        assertEquals("Yesterday", formatRoomActivity(yesterdayLate, now, zone, Locale.US))
        val oct3 = now - 16 * 24 * hour
        assertEquals("Oct 3", formatRoomActivity(oct3, now, zone, Locale.US))
    }

    @Test fun activity_label_is_empty_without_activity() {
        assertEquals("", formatRoomActivity(null, now, ZoneOffset.UTC, Locale.US))
    }

    @Test fun ui_state_follows_the_account_state() {
        assertEquals(RoomListUiState.SignedOut, roomListUiState(MatrixState.LoggedOut, emptyList(), ""))
        assertEquals(RoomListUiState.Loading, roomListUiState(MatrixState.Restoring, emptyList(), ""))
        assertEquals(RoomListUiState.Loading, roomListUiState(MatrixState.LoggingIn, emptyList(), ""))
        assertEquals(
            RoomListUiState.RestoreFailed("boom", retryable = true),
            roomListUiState(MatrixState.RestoreFailed("boom", retryable = true), emptyList(), ""),
        )
        assertEquals(RoomListUiState.Failed("bad password"), roomListUiState(MatrixState.Error("bad password"), emptyList(), ""))
    }

    @Test fun ready_state_sorts_filters_and_reports_whether_any_rooms_exist() {
        val ready = MatrixState.Ready("@me:x", "DEV", "https://x", "@bot:x")
        val rooms = listOf(room("old", "Old", ts = now - 9 * hour), room("new", "New", ts = now - hour))
        val state = roomListUiState(ready, rooms, " new ") as RoomListUiState.Ready
        assertEquals(listOf("new"), state.rooms.map { it.id })
        assertEquals("new", state.query)
        assertTrue(state.hasAnyRooms)

        val empty = roomListUiState(ready, emptyList(), "") as RoomListUiState.Ready
        assertTrue(empty.rooms.isEmpty())
        assertEquals(false, empty.hasAnyRooms)
    }
}
