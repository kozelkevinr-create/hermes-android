package com.hermes.client.ui.plugins.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarRruleTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun none_maps_to_no_rule() {
        assertNull(buildRrule(RruleSpec(RecurrencePreset.NONE), allDay = false, zone = newYork))
    }

    @Test
    fun daily_count_maps_to_supported_subset() {
        assertEquals(
            "FREQ=DAILY;COUNT=5",
            buildRrule(RruleSpec(RecurrencePreset.DAILY, count = 5), allDay = false, zone = newYork),
        )
    }

    @Test
    fun weekly_until_timed_uses_utc_end_of_local_day() {
        assertEquals(
            // 2026-03-16 23:59:59 America/New_York is EDT (UTC-4) after the DST change.
            "FREQ=WEEKLY;UNTIL=20260317T035959Z",
            buildRrule(
                RruleSpec(RecurrencePreset.WEEKLY, until = LocalDate.of(2026, 3, 16)),
                allDay = false,
                zone = newYork,
            ),
        )
    }

    @Test
    fun monthly_until_all_day_uses_date_only() {
        assertEquals(
            "FREQ=MONTHLY;UNTIL=20260401",
            buildRrule(
                RruleSpec(RecurrencePreset.MONTHLY, until = LocalDate.of(2026, 4, 1)),
                allDay = true,
                zone = newYork,
            ),
        )
    }

    @Test
    fun yearly_count_maps_to_supported_subset() {
        assertEquals(
            "FREQ=YEARLY;COUNT=3",
            buildRrule(RruleSpec(RecurrencePreset.YEARLY, count = 3), allDay = true, zone = newYork),
        )
    }

    @Test
    fun count_wins_when_both_are_present() {
        assertEquals(
            "FREQ=DAILY;COUNT=2",
            buildRrule(
                RruleSpec(RecurrencePreset.DAILY, count = 2, until = LocalDate.of(2026, 4, 1)),
                allDay = false,
                zone = newYork,
            ),
        )
    }

    @Test
    fun parse_restores_preset_count_and_until() {
        val countRule = parseRrule("FREQ=WEEKLY;COUNT=3")
        assertEquals(RecurrencePreset.WEEKLY, countRule.preset)
        assertEquals(3, countRule.count)
        assertNull(countRule.until)

        val untilRule = parseRrule("FREQ=MONTHLY;UNTIL=20260401")
        assertEquals(RecurrencePreset.MONTHLY, untilRule.preset)
        assertEquals(LocalDate.of(2026, 4, 1), untilRule.until)

        val utcUntil = parseRrule("FREQ=YEARLY;UNTIL=20260317T035959Z")
        assertEquals(LocalDate.of(2026, 3, 17), utcUntil.until)
    }

    @Test
    fun parse_preserves_other_supported_parts_and_reads_the_frequency() {
        val spec = parseRrule("FREQ=WEEKLY;BYDAY=MO,WE;COUNT=10")
        assertEquals(RecurrencePreset.WEEKLY, spec.preset)
        assertEquals(10, spec.count)
    }

    @Test
    fun parse_unknown_frequency_degrades_to_none() {
        assertEquals(RecurrencePreset.NONE, parseRrule("FREQ=HOURLY;COUNT=2").preset)
        assertEquals(RecurrencePreset.NONE, parseRrule(null).preset)
        assertEquals(RecurrencePreset.NONE, parseRrule("").preset)
    }
}
