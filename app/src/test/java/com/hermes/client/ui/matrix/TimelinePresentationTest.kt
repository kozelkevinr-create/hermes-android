package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.EventOrTxnId
import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.MessageSendState
import com.hermes.client.data.matrix.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimelinePresentationTest {
    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US
    private val nowMs = 1_700_000_000_000L

    private fun msg(
        id: String,
        sender: String,
        ts: Long,
        isOwn: Boolean = false,
        body: String = "hi",
        sendState: MessageSendState = MessageSendState.SENT,
    ) = TimelineEntry.Message(id, sender, senderName = "", isOwn = isOwn, ts = ts, body = body, isEdited = false, kind = MessageKind.TEXT, sendState = sendState)

    private fun messages(entries: List<TimelineEntry>) =
        timelineRows(entries, nowMs, zone, locale).filterIsInstance<TimelineRow.Message>()

    @Test
    fun consecutive_messages_from_one_sender_group_together() {
        val rows = messages(
            listOf(
                msg("1", "alice", nowMs),
                msg("2", "alice", nowMs + 60_000),
                msg("3", "alice", nowMs + 120_000),
            ),
        )
        assertEquals(listOf(true, false, false), rows.map { it.showSender })
    }

    @Test
    fun a_different_sender_breaks_the_group() {
        val rows = messages(
            listOf(
                msg("1", "alice", nowMs),
                msg("2", "bob", nowMs + 60_000),
            ),
        )
        assertEquals(listOf(true, true), rows.map { it.showSender })
    }

    @Test
    fun a_gap_over_five_minutes_breaks_the_group() {
        val rows = messages(
            listOf(
                msg("1", "alice", nowMs),
                msg("2", "alice", nowMs + 5 * 60_000L + 1),
            ),
        )
        assertEquals(listOf(true, true), rows.map { it.showSender })
    }

    @Test
    fun own_messages_are_labelled_you() {
        val rows = messages(listOf(msg("1", "@me:example.test", nowMs, isOwn = true)))
        assertEquals("You", rows.single().sender)
        assertTrue(rows.single().isOwn)
    }

    @Test
    fun own_message_send_state_reaches_the_row() {
        val rows = messages(
            listOf(
                msg("1", "@me:example.test", nowMs, isOwn = true, sendState = MessageSendState.NOT_SENT_YET),
                msg("2", "@me:example.test", nowMs + 1_000, isOwn = true, sendState = MessageSendState.SENDING_FAILED),
                msg("3", "@me:example.test", nowMs + 2_000, isOwn = true, sendState = MessageSendState.SENT),
                msg("4", "alice", nowMs + 3_000, sendState = MessageSendState.SENT),
            ),
        )
        assertEquals(
            listOf(
                MessageSendState.NOT_SENT_YET,
                MessageSendState.SENDING_FAILED,
                MessageSendState.SENT,
                MessageSendState.SENT,
            ),
            rows.map { it.sendState },
        )
    }

    @Test
    fun item_id_reaches_the_row_for_in_place_retry() {
        val itemId = EventOrTxnId.Transaction("txn-1")
        val rows = messages(
            listOf(msg("1", "@me:example.test", nowMs, isOwn = true, sendState = MessageSendState.SENDING_FAILED).copy(itemId = itemId)),
        )
        assertEquals(itemId, rows.single().itemId)
    }

    @Test
    fun editability_is_relayed_to_the_row() {
        val rows = messages(
            listOf(
                msg("1", "@me:example.test", nowMs, isOwn = true).copy(isEditable = true),
                msg("2", "alice", nowMs + 1_000).copy(isEditable = true),
                msg("3", "@me:example.test", nowMs + 2_000, isOwn = true),
            ),
        )
        assertEquals(listOf(true, true, false), rows.map { it.isEditable })
    }

    @Test
    fun state_divider_and_read_marker_break_the_group() {
        val entries = listOf(
            msg("1", "alice", nowMs),
            TimelineEntry.StateEvent("s", nowMs + 1, "joined"),
            msg("2", "alice", nowMs + 60_000),
        )
        assertEquals(listOf(true, true), messages(entries).map { it.showSender })
    }

    @Test
    fun utd_renders_as_a_muted_encrypted_message() {
        val rows = timelineRows(listOf(TimelineEntry.Utd("u", "alice", nowMs)), nowMs, zone, locale)
        val row = rows.single() as TimelineRow.Message
        assertTrue(row.isUtd)
        assertEquals("Encrypted message", row.body)
        assertFalse(row.isOwn)
    }

    @Test
    fun state_event_becomes_a_centred_row() {
        val rows = timelineRows(listOf(TimelineEntry.StateEvent("s", nowMs, "alice joined the room")), nowMs, zone, locale)
        assertEquals("alice joined the room", (rows.single() as TimelineRow.State).text)
    }

    @Test
    fun read_marker_and_unsupported_items_are_dropped() {
        val entries = listOf(
            TimelineEntry.DayDivider(nowMs),
            TimelineEntry.ReadMarker,
            TimelineEntry.Unsupported("x"),
            msg("1", "alice", nowMs),
        )
        val rows = timelineRows(entries, nowMs, zone, locale)
        assertEquals(2, rows.size)
        assertTrue(rows[0] is TimelineRow.DayDivider)
        assertEquals("Today", (rows[0] as TimelineRow.DayDivider).label)
        assertTrue(rows[1] is TimelineRow.Message)
    }

    @Test
    fun day_label_is_today_yesterday_or_a_date() {
        assertEquals("Today", formatDayLabel(nowMs, nowMs, zone, locale))
        assertEquals("Yesterday", formatDayLabel(nowMs - 24 * 3600_000L, nowMs, zone, locale))
        val threeDaysAgo = nowMs - 3 * 24 * 3600_000L
        val expected = DateTimeFormatter.ofPattern("MMM d", locale).format(Instant.ofEpochMilli(threeDaysAgo).atZone(zone))
        assertEquals(expected, formatDayLabel(threeDaysAgo, nowMs, zone, locale))
    }

    @Test
    fun message_time_is_hh_mm() {
        val expected = DateTimeFormatter.ofPattern("HH:mm", locale).format(Instant.ofEpochMilli(nowMs).atZone(zone))
        assertEquals(expected, formatTimelineTime(nowMs, zone, locale))
    }
}
