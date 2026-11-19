package com.hermes.client.ui.plugins.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarTimeTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun all_day_exclusive_end_covers_only_the_start_day() {
        val event = allDayEvent("2026-03-20", "2026-03-21")
        assertEquals(listOf(LocalDate.of(2026, 3, 20)), event.coveredDates())
        assertTrue(event.occursOn(LocalDate.of(2026, 3, 20)))
        assertFalse(event.occursOn(LocalDate.of(2026, 3, 21)))
        assertEquals(LocalDate.of(2026, 3, 20), event.allDayEndInclusive())
    }

    @Test
    fun all_day_multi_day_exclusive_end_covers_inclusive_days() {
        val event = allDayEvent("2026-03-20", "2026-03-23")
        assertEquals(
            listOf(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21), LocalDate.of(2026, 3, 22)),
            event.coveredDates(),
        )
        assertEquals(LocalDate.of(2026, 3, 22), event.allDayEndInclusive())
        assertFalse(event.occursOn(LocalDate.of(2026, 3, 23)))
    }

    @Test
    fun timed_event_crossing_midnight_covers_both_days() {
        val event = timedEvent("2026-03-02T23:00:00", "2026-03-03T01:00:00")
        assertEquals(
            listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 3)),
            event.coveredDates(newYork),
        )
    }

    @Test
    fun timed_event_ending_exactly_at_midnight_does_not_cover_the_next_day() {
        val event = timedEvent("2026-03-02T22:00:00", "2026-03-03T00:00:00")
        assertEquals(listOf(LocalDate.of(2026, 3, 2)), event.coveredDates(newYork))
    }

    @Test
    fun timed_event_across_spring_forward_covers_both_dates() {
        // DST begins 2027-03-14 at 02:00 in America/New_York.
        val event = timedEvent("2027-03-13T22:00:00", "2027-03-14T03:00:00")
        assertEquals(
            listOf(LocalDate.of(2027, 3, 13), LocalDate.of(2027, 3, 14)),
            event.coveredDates(newYork),
        )
    }

    @Test
    fun all_day_event_with_only_dates_ignores_the_zone() {
        val event = allDayEvent("2026-03-20", "2026-03-21")
        assertEquals(event.coveredDates(newYork), event.coveredDates(ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun event_time_falls_back_from_missing_local_to_utc() {
        val time = EventTime(tz = "America/New_York", utc = "2026-03-02T14:00:00Z")
        assertEquals(LocalDate.of(2026, 3, 2), time.displayDate(newYork))
        assertEquals(LocalDate.of(2026, 3, 2), time.displayDate(ZoneId.of("UTC")))
    }

    private fun allDayEvent(start: String, endExclusive: String) = CalendarEvent(
        id = "e",
        calendarId = "c",
        scope = CalendarScope.PROFILE,
        summary = "All day",
        description = null,
        location = null,
        url = null,
        allDay = true,
        start = EventTime(date = start),
        end = EventTime(date = endExclusive),
        startUtc = null,
        endUtc = null,
        rrule = null,
        rev = 1,
        masterId = null,
        masterRev = null,
        masterRrule = null,
        isOverride = false,
        parentId = null,
        occurrenceStart = null,
        occurrenceEnd = null,
        recurrenceKey = null,
        status = "CONFIRMED",
    )

    private fun timedEvent(start: String, end: String) = CalendarEvent(
        id = "e",
        calendarId = "c",
        scope = CalendarScope.PROFILE,
        summary = "Timed",
        description = null,
        location = null,
        url = null,
        allDay = false,
        start = EventTime(local = start, tz = "America/New_York"),
        end = EventTime(local = end, tz = "America/New_York"),
        startUtc = null,
        endUtc = null,
        rrule = null,
        rev = 1,
        masterId = null,
        masterRev = null,
        masterRrule = null,
        isOverride = false,
        parentId = null,
        occurrenceStart = null,
        occurrenceEnd = null,
        recurrenceKey = null,
        status = "CONFIRMED",
    )
}
