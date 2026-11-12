package com.hermes.client.ui.plugins.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarGridTest {
    @Test
    fun leap_year_february_contains_february_29_once() {
        val grid = buildMonthGrid(YearMonth.of(2028, 2), DayOfWeek.MONDAY)
        val dates = grid.weeks.flatten().map { it.date }

        assertEquals(6, grid.weeks.size)
        assertTrue(grid.weeks.all { it.size == 7 })
        assertEquals(42, dates.size)
        assertEquals(1, dates.count { it == LocalDate.of(2028, 2, 29) })
        assertTrue(grid.weeks.flatten().first { it.date == LocalDate.of(2028, 2, 29) }.inMonth)
    }

    @Test
    fun grid_starts_on_the_requested_first_day_of_week() {
        // 2026-02-01 is a Sunday.
        val mondayFirst = buildMonthGrid(YearMonth.of(2026, 2), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 1, 26), mondayFirst.weeks.first().first().date)

        val sundayFirst = buildMonthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 2, 1), sundayFirst.weeks.first().first().date)

        val wednesdayFirst = buildMonthGrid(YearMonth.of(2026, 2), DayOfWeek.WEDNESDAY)
        assertEquals(LocalDate.of(2026, 1, 28), wednesdayFirst.weeks.first().first().date)
    }

    @Test
    fun grid_is_42_consecutive_dates_without_gaps_or_duplicates() {
        val grid = buildMonthGrid(YearMonth.of(2026, 3), DayOfWeek.MONDAY)
        val dates = grid.weeks.flatten().map { it.date }

        assertEquals(42, dates.distinct().size)
        dates.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
    }

    @Test
    fun dst_change_weekends_stay_consecutive() {
        // US spring-forward 2027-03-14 and fall-back 2026-11-01.
        for ((month, dstDate) in listOf(
            YearMonth.of(2027, 3) to LocalDate.of(2027, 3, 14),
            YearMonth.of(2026, 11) to LocalDate.of(2026, 11, 1),
        )) {
            val dates = buildMonthGrid(month, DayOfWeek.MONDAY).weeks.flatten().map { it.date }
            assertTrue(dstDate in dates)
            dates.zipWithNext().forEach { (a, b) -> assertEquals(a.plusDays(1), b) }
        }
    }

    @Test
    fun cells_outside_the_month_are_marked() {
        val grid = buildMonthGrid(YearMonth.of(2026, 3), DayOfWeek.SUNDAY)
        // March 2026 starts on a Sunday, so the first cell is in-month and the last row spills over.
        assertTrue(grid.weeks.first().first().inMonth)
        assertFalse(grid.weeks.last().last().inMonth)
    }

    @Test
    fun weekday_labels_start_at_the_requested_day() {
        val labels = weekdayLabels(DayOfWeek.MONDAY, java.util.Locale.US)
        assertEquals(7, labels.size)
        assertEquals("Mon", labels.first())
        assertEquals("Sun", labels.last())
    }

    @Test
    fun year_boundary_grid_spans_december_and_january() {
        val grid = buildMonthGrid(YearMonth.of(2026, 1), DayOfWeek.SUNDAY)
        val dates = grid.weeks.flatten().map { it.date }
        // 2026-01-01 is a Thursday, so the Sunday-first grid opens in December 2025.
        assertTrue(dates.any { it == LocalDate.of(2025, 12, 28) })
        assertEquals(1, dates.count { it == LocalDate.of(2026, 1, 1) })
    }
}
