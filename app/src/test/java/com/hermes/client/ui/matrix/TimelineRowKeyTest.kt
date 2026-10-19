package com.hermes.client.ui.matrix

import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.MessageSendState
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineRowKeyTest {

    private fun message(id: String) = TimelineRow.Message(
        id = id,
        sender = "alice",
        isOwn = false,
        body = "hi",
        isEdited = false,
        kind = MessageKind.TEXT,
        time = "14:05",
        showSender = true,
        isUtd = false,
        sendState = MessageSendState.SENT,
    )

    @Test
    fun day_dividers_with_the_same_label_key_by_timestamp() {
        assertEquals("day:1", timelineRowKey(TimelineRow.DayDivider("Oct 3", ts = 1)))
        assertEquals("day:2", timelineRowKey(TimelineRow.DayDivider("Oct 3", ts = 2)))
    }

    @Test
    fun rows_never_produce_duplicate_keys() {
        val rows = listOf(
            TimelineRow.DayDivider("Oct 3", ts = 1),
            TimelineRow.DayDivider("Oct 3", ts = 2),
            TimelineRow.State("evt-1", "alice joined"),
            message("fallback-1"),
            message("fallback-2"),
            message("fallback-3"),
        )
        val keys = rows.map(::timelineRowKey)
        assertEquals(keys.size, keys.toSet().size)
    }
}
