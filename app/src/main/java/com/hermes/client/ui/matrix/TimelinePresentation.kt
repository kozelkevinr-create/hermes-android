package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.ENCRYPTED_PREVIEW
import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.TimelineEntry
import com.hermes.client.data.matrix.senderLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How close in time two consecutive messages from one sender must be to collapse into a group. */
const val GROUP_WINDOW_MS = 5 * 60_000L

/** One rendered row of the timeline, ready for the LazyColumn. */
sealed interface TimelineRow {
    data class DayDivider(val label: String) : TimelineRow

    /** A small centred membership / name / topic change. */
    data class State(val id: String, val text: String) : TimelineRow

    data class Message(
        val id: String,
        /** Human label for the sender ("You" for own messages). */
        val sender: String,
        val isOwn: Boolean,
        val body: String,
        val isEdited: Boolean,
        val kind: MessageKind,
        val time: String,
        /** Show the avatar + name; false for the continuation of a sender group. */
        val showSender: Boolean,
        val isUtd: Boolean,
    ) : TimelineRow
}

/**
 * Maps the chronological timeline entries to display rows, collapsing consecutive messages from the
 * same sender within [GROUP_WINDOW_MS] so the avatar and name show only on the first of a group.
 * Read markers and unsupported items are dropped. Pure: [nowMs], [zone] and [locale] are passed in.
 */
fun timelineRows(
    entries: List<TimelineEntry>,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>(entries.size)
    var lastSender: String? = null
    var lastTs: Long? = null
    for (e in entries) {
        when (e) {
            is TimelineEntry.DayDivider -> {
                rows += TimelineRow.DayDivider(formatDayLabel(e.ts, nowMs, zone, locale))
                lastSender = null
                lastTs = null
            }
            is TimelineEntry.ReadMarker -> {
                lastSender = null
                lastTs = null
            }
            is TimelineEntry.StateEvent -> {
                rows += TimelineRow.State(e.id, e.summary)
                lastSender = null
                lastTs = null
            }
            is TimelineEntry.Unsupported -> {
                lastSender = null
                lastTs = null
            }
            is TimelineEntry.Message -> {
                rows += messageRow(
                    id = e.id,
                    sender = e.sender,
                    senderName = e.senderName,
                    isOwn = e.isOwn,
                    ts = e.ts,
                    body = e.body,
                    isEdited = e.isEdited,
                    kind = e.kind,
                    isUtd = false,
                    grouped = isSameGroup(e.sender, e.ts, lastSender, lastTs),
                    zone,
                    locale,
                )
                lastSender = e.sender
                lastTs = e.ts
            }
            is TimelineEntry.Utd -> {
                rows += messageRow(
                    id = e.id,
                    sender = e.sender,
                    senderName = null,
                    isOwn = false,
                    ts = e.ts,
                    body = ENCRYPTED_PREVIEW,
                    isEdited = false,
                    kind = MessageKind.OTHER,
                    isUtd = true,
                    grouped = isSameGroup(e.sender, e.ts, lastSender, lastTs),
                    zone,
                    locale,
                )
                lastSender = e.sender
                lastTs = e.ts
            }
        }
    }
    return rows
}

private fun messageRow(
    id: String,
    sender: String,
    senderName: String?,
    isOwn: Boolean,
    ts: Long,
    body: String,
    isEdited: Boolean,
    kind: MessageKind,
    isUtd: Boolean,
    grouped: Boolean,
    zone: ZoneId,
    locale: Locale,
): TimelineRow.Message = TimelineRow.Message(
    id = id,
    sender = senderLabel(sender, senderName?.takeIf { it.isNotBlank() }, isOwn),
    isOwn = isOwn,
    body = body,
    isEdited = isEdited,
    kind = kind,
    time = formatTimelineTime(ts, zone, locale),
    showSender = !grouped,
    isUtd = isUtd,
)

private fun isSameGroup(sender: String, ts: Long, lastSender: String?, lastTs: Long?): Boolean =
    lastSender == sender && lastTs != null && ts >= lastTs && ts - lastTs <= GROUP_WINDOW_MS

/** "14:05" for a message timestamp. */
fun formatTimelineTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** "Today", "Yesterday", or "Oct 3" for a date divider. */
fun formatDayLabel(epochMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val local = Instant.ofEpochMilli(epochMs).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val date = local.toLocalDate()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("MMM d", locale).format(local)
    }
}
