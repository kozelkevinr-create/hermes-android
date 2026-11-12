package com.hermes.client.ui.plugins.calendar

import com.hermes.client.data.repository.ProfileManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<CalendarApi>()
    private val profileManager = mockk<ProfileManager>()
    private val active = MutableStateFlow<String?>("alice")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { profileManager.active } returns active
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun version404DegradesToApiZeroAndFullListPolling() = runTest(dispatcher) {
        stubLoad(version = ApiResult.Failure(CalendarError.NotFound("Not Found")))
        val page = NotificationPage(listOf(notification("rmi_1")), 1, null)
        coEvery { api.notifications("alice", any(), any(), any(), any()) } returns ApiResult.Success(page)

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()

        assertEquals(0, vm.state.value.apiVersion)
        assertFalse(vm.state.value.supportsIncrementalNotifications)
        assertEquals(listOf(profileCalendar), vm.state.value.calendarsByScope[CalendarScope.PROFILE])
        assertEquals(listOf(globalCalendar), vm.state.value.calendarsByScope[CalendarScope.GLOBAL])

        backgroundScope.launch { vm.pollNotificationsWhileVisible() }
        advanceTimeBy(1)
        coVerify(exactly = 1) { api.notifications("alice", 50, 0, false, null) }
        advanceTimeBy(30_000)
        // Old server: no `since`, the full list is fetched again.
        coVerify(exactly = 2) { api.notifications("alice", 50, 0, false, null) }
    }

    @Test
    fun newServerPollsIncrementallyWithServerTimeWatermark() = runTest(dispatcher) {
        stubLoad(version = ApiResult.Success(CalendarVersion(apiVersion = 1, pluginVersion = "0.0.1")))
        coEvery { api.notifications("alice", any(), any(), any(), any()) } returns ApiResult.Success(
            NotificationPage(listOf(notification("rmi_1")), 1, serverTimeEpoch = 100),
        )

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        assertTrue(vm.state.value.supportsIncrementalNotifications)

        backgroundScope.launch { vm.pollNotificationsWhileVisible() }
        advanceTimeBy(1)
        coVerify(exactly = 1) { api.notifications("alice", 50, 0, false, since = null) }

        coEvery { api.notifications("alice", any(), any(), any(), any()) } returns ApiResult.Success(
            NotificationPage(listOf(notification("rmi_2")), 2, serverTimeEpoch = 200),
        )
        advanceTimeBy(30_000)
        coVerify(exactly = 1) { api.notifications("alice", 50, 0, false, since = 100L) }
        advanceUntilIdle()
        assertEquals(2, vm.state.value.notifications.size)
        assertEquals(200L, vm.state.value.notificationWatermark)
    }

    @Test
    fun savingAllDayEventSendsExclusiveEndDate() = runTest(dispatcher) {
        stubLoad()
        val draftSlot = slot<EventDraft>()
        coEvery { api.createEvent("alice", capture(draftSlot)) } returns ApiResult.Success(event())

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openCreate(LocalDate.of(2026, 3, 20))
        vm.updateEditor {
            it.copy(
                summary = "Holiday",
                allDay = true,
                startDate = LocalDate.of(2026, 3, 20),
                endDate = LocalDate.of(2026, 3, 20),
            )
        }
        vm.saveEditor()
        advanceUntilIdle()

        assertEquals("Holiday", draftSlot.captured.summary)
        assertTrue(draftSlot.captured.allDay)
        assertEquals("2026-03-20", draftSlot.captured.start)
        // The editor's end date is inclusive; the server expects an exclusive end.
        assertEquals("2026-03-21", draftSlot.captured.end)
        assertNull(draftSlot.captured.timezone)
        assertNull(vm.state.value.editor)
    }

    @Test
    fun revisionConflictRefetchesEventKeepsEditorOpenAndRetriesWithNewRev() = runTest(dispatcher) {
        stubLoad()
        val original = event(rev = 1, summary = "Design review")
        val changed = event(rev = 2, summary = "Changed elsewhere")
        val patchSlot = slot<EventPatch>()
        coEvery { api.updateEvent("alice", capture(patchSlot)) } returns
            ApiResult.Failure(CalendarError.Conflict(original.id, 1, "event changed"))
        coEvery { api.events("alice", any(), any(), any(), any(), any()) } returns
            ApiResult.Success(listOf(changed))

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openEdit(original)
        vm.updateEditor { it.copy(summary = "My edit") }
        vm.saveEditor()
        advanceUntilIdle()

        val afterConflict = vm.state.value.editor
        assertNotNull(afterConflict)
        assertEquals("My edit", afterConflict?.summary)
        assertEquals(2L, afterConflict?.eventRev)
        assertNotNull(afterConflict?.conflict)
        assertEquals(1L, patchSlot.captured.expectedRev)

        coEvery { api.updateEvent("alice", capture(patchSlot)) } returns ApiResult.Success(changed)
        vm.saveEditor()
        advanceUntilIdle()

        assertEquals(2L, patchSlot.captured.expectedRev)
        assertEquals("My edit", patchSlot.captured.summary)
        assertNull(vm.state.value.editor)
        assertEquals("Event updated", vm.state.value.snackbar?.text)
    }

    @Test
    fun recurringSingleOccurrencePatchSendsOccurrenceStartAndThisScope() = runTest(dispatcher) {
        stubLoad()
        val occurrence = event(rev = 1, recurrence = true)
        val patchSlot = slot<EventPatch>()
        coEvery { api.updateEvent("alice", capture(patchSlot)) } returns ApiResult.Success(occurrence)

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openEdit(occurrence)
        vm.updateEditor { it.copy(summary = "One week only", seriesScope = SeriesScope.THIS) }
        vm.saveEditor()
        advanceUntilIdle()

        assertEquals(SeriesScope.THIS, patchSlot.captured.seriesScope)
        assertEquals("2026-03-02T09:00:00", patchSlot.captured.occurrenceStart)
        assertNull(patchSlot.captured.rrule)
    }

    @Test
    fun deleteRecurringOccurrenceSendsMasterIdAndOccurrenceStart() = runTest(dispatcher) {
        stubLoad()
        val occurrence = event(rev = 3, recurrence = true)
        coEvery {
            api.deleteEvent("alice", CalendarScope.PROFILE, occurrence.seriesId, 3L, "2026-03-02T09:00:00", SeriesScope.THIS)
        } returns ApiResult.Success(Unit)

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.requestDelete(occurrence)
        vm.setDeleteSeriesScope(SeriesScope.THIS)
        vm.confirmDelete()
        advanceUntilIdle()

        coVerify {
            api.deleteEvent("alice", CalendarScope.PROFILE, occurrence.seriesId, 3L, "2026-03-02T09:00:00", SeriesScope.THIS)
        }
        assertNull(vm.state.value.deleteTarget)
        assertEquals("Event deleted", vm.state.value.snackbar?.text)
    }

    @Test
    fun dismissRemovesNotificationAndSnoozeOnlyAcceptsAllowlistedMinutes() = runTest(dispatcher) {
        stubLoad()
        val item = notification("rmi_1")
        coEvery { api.notifications("alice", any(), any(), any(), any()) } returns
            ApiResult.Success(NotificationPage(listOf(item), 1, 100))
        coEvery { api.dismissNotification("alice", "rmi_1") } returns ApiResult.Success(item.copy(state = "acknowledged"))

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.refreshNotifications()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.notifications.size)

        vm.snoozeNotification("rmi_1", 7)
        advanceUntilIdle()
        coVerify(exactly = 0) { api.snoozeNotification(any(), any(), any()) }

        vm.dismissNotification("rmi_1")
        advanceUntilIdle()
        assertTrue(vm.state.value.notifications.isEmpty())
        assertEquals("Notification dismissed", vm.state.value.snackbar?.text)
    }

    @Test
    fun switchingScopeReloadsCalendarsAndClearsTheCalendarFilter() = runTest(dispatcher) {
        stubLoad()
        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.selectCalendar("cal_profile")
        assertEquals("cal_profile", vm.state.value.selectedCalendarId)

        vm.selectScope(CalendarScope.GLOBAL)
        advanceUntilIdle()

        assertEquals(CalendarScope.GLOBAL, vm.state.value.scope)
        assertNull(vm.state.value.selectedCalendarId)
        assertEquals(listOf(globalCalendar), vm.state.value.calendarsForScope)
        coVerify { api.events("alice", CalendarScope.GLOBAL, any(), any(), any(), any()) }
    }

    private fun stubLoad(
        version: ApiResult<CalendarVersion> = ApiResult.Success(CalendarVersion(1, "0.0.1")),
    ) {
        coEvery { api.version("alice") } returns version
        coEvery { api.calendars("alice", CalendarScope.PROFILE) } returns ApiResult.Success(listOf(profileCalendar))
        coEvery { api.calendars("alice", CalendarScope.GLOBAL) } returns ApiResult.Success(listOf(globalCalendar))
        coEvery { api.events("alice", any(), any(), any(), any(), any()) } returns ApiResult.Success(emptyList())
    }

    private val profileCalendar = Calendar(
        id = "cal_profile",
        name = "Personal",
        description = null,
        color = null,
        defaultTz = "America/New_York",
        readOnly = false,
        scope = CalendarScope.PROFILE,
    )

    private val globalCalendar = Calendar(
        id = "cal_global",
        name = "Global",
        description = null,
        color = null,
        defaultTz = "America/New_York",
        readOnly = false,
        scope = CalendarScope.GLOBAL,
    )

    private fun event(rev: Long = 1, summary: String = "Design review", recurrence: Boolean = false) = CalendarEvent(
        id = "master-1",
        calendarId = "cal_profile",
        scope = CalendarScope.PROFILE,
        summary = summary,
        description = null,
        location = null,
        url = null,
        allDay = false,
        start = EventTime(local = "2026-03-02T09:00:00", tz = "America/New_York", utc = "2026-03-02T14:00:00Z"),
        end = EventTime(local = "2026-03-02T10:00:00", tz = "America/New_York", utc = "2026-03-02T15:00:00Z"),
        startUtc = 1772460000,
        endUtc = 1772463600,
        rrule = if (recurrence) "FREQ=WEEKLY;COUNT=3" else null,
        rev = rev,
        masterId = "master-1",
        masterRev = rev,
        masterRrule = if (recurrence) "FREQ=WEEKLY;COUNT=3" else null,
        isOverride = false,
        parentId = null,
        occurrenceStart = if (recurrence) {
            EventTime(local = "2026-03-02T09:00:00", tz = "America/New_York", utc = "2026-03-02T14:00:00Z")
        } else {
            null
        },
        occurrenceEnd = null,
        recurrenceKey = if (recurrence) "T:1772460000" else null,
        status = "CONFIRMED",
    )

    private fun notification(id: String) = CalendarNotification(
        id = id,
        title = "Profile 3",
        state = "delivered",
        priority = "normal",
        storeId = "profile:alice",
        eventId = "event-1",
        recurrenceKey = "T:1800000720",
        occurrenceStart = Instant.parse("2027-01-15T08:12:00Z"),
        fireAt = Instant.parse("2027-01-15T08:02:00Z"),
        snoozeUntil = null,
        attempt = 1,
        updatedAt = Instant.parse("2027-01-15T08:10:00Z"),
        updatedAtEpoch = 1800000600,
    )
}
