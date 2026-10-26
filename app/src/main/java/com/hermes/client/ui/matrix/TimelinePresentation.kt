package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.ENCRYPTED_PREVIEW
import com.hermes.client.data.matrix.EventOrTxnId
import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.ReactionSummary
import com.hermes.client.data.matrix.MessageSendState
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
    data class DayDivider(val label: String, val ts: Long) : TimelineRow

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
        /** Delivery state for own messages; [MessageSendState.SENT] for everyone else's. */
        val sendState: MessageSendState,
        /** True for own text messages the user may edit; edit affordances show only on these. */
        val isEditable: Boolean = false,
        /** SDK identity used to retry, abort or edit this event, when known. */
        val itemId: EventOrTxnId? = null,
        val reactions: List<ReactionSummary> = emptyList(),
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
                rows += TimelineRow.DayDivider(formatDayLabel(e.ts, nowMs, zone, locale), e.ts)
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
                    sendState = e.sendState,
                    isEditable = e.isEditable,
                    itemId = e.itemId,
                    reactions = e.reactions,
                    grouped = isSameGroup(e.sender, e.ts, lastSender, lastTs),
                    zone = zone,
                    locale = locale,
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
                    sendState = MessageSendState.SENT,
                    grouped = isSameGroup(e.sender, e.ts, lastSender, lastTs),
                    zone = zone,
                    locale = locale,
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
    sendState: MessageSendState,
    isEditable: Boolean = false,
    itemId: EventOrTxnId? = null,
    reactions: List<ReactionSummary> = emptyList(),
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
    sendState = sendState,
    isEditable = isEditable,
    itemId = itemId,
    reactions = reactions,
)

private fun isSameGroup(sender: String, ts: Long, lastSender: String?, lastTs: Long?): Boolean =
    lastSender == sender && lastTs != null && ts >= lastTs && ts - lastTs <= GROUP_WINDOW_MS

/**
 * The event id a read receipt should point at for the current viewport: the newest message row the
 * user has fully rendered, provided it has a remote event id (local echoes and undecryptable events
 * have none, so nothing is sent while one of those is the newest visible). Rows are chronological;
 * visibleKeys is the set of LazyColumn keys (see [timelineRowKey]) of the rows laid out on screen.
 * Pure.
 */
fun latestVisibleEventId(rows: List<TimelineRow>, visibleKeys: Set<String>): String? {
    for (row in rows.asReversed()) {
        if (row !is TimelineRow.Message) continue
        if (row.id !in visibleKeys) continue
        return (row.itemId as? EventOrTxnId.Event)?.eventId
    }
    return null
}

/** Stable LazyColumn key for a row. Day dividers key by timestamp so two dividers with the same
 *  label ("Oct 3" in two different years) stay distinct; events key by their SDK id. */
fun timelineRowKey(row: TimelineRow): String = when (row) {
    is TimelineRow.DayDivider -> "day:${row.ts}"
    is TimelineRow.State -> "state:${row.id}"
    is TimelineRow.Message -> row.id
}

/**
 * Decides when the timeline screen should request more history. Two triggers:
 *  - the user actually scrolled toward the top (a real gesture, latched once seen), or
 *  - an initial fill that pages at most [maxInitialPages] times so a short room still fills the
 *    screen, never looping unbounded when the list stays non-scrollable (e.g. every item Unsupported).
 * Never returns true once [atStart] is set.
 */
class TimelinePagination(private val maxInitialPages: Int = DEFAULT_MAX_INITIAL_PAGES) {
    private var initialPagesRemaining = maxInitialPages
    private var userScrolled = false

    fun shouldLoadMore(
        total: Int,
        lastVisibleIndex: Int?,
        isScrollInProgress: Boolean,
        atStart: Boolean,
    ): Boolean {
        if (atStart || total <= 0) return false
        if (isScrollInProgress) userScrolled = true
        val nearTop = lastVisibleIndex != null && lastVisibleIndex >= total - DEFAULT_THRESHOLD
        if (!nearTop) return false
        return if (userScrolled) true else consumeInitialPage()
    }

    private fun consumeInitialPage(): Boolean {
        if (initialPagesRemaining <= 0) return false
        initialPagesRemaining--
        return true
    }

    companion object {
        const val DEFAULT_THRESHOLD = 5
        const val DEFAULT_MAX_INITIAL_PAGES = 3
    }
}

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
