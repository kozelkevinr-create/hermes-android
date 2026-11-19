package com.hermes.client.ui.plugins.calendar

import com.hermes.client.data.repository.ProfileManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarEventRefetchTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<CalendarApi>()
    private val profileManager = mockk<ProfileManager>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { profileManager.active } returns MutableStateFlow<String?>("alice")
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun pluginNotFoundEnvelopeIsNotRouteMissing() {
        val e = parseApiError("""{"ok":false,"code":"not_found","error":"gone"}""", 404, json)
        assertTrue(e is CalendarError.NotFound)
        assertFalse((e as CalendarError.NotFound).routeMissing)
    }

    @Test fun frameworkNotFoundIsRouteMissing() {
        val e = parseApiError("""{"detail":"Not Found"}""", 404, json)
        assertTrue((e as CalendarError.NotFound).routeMissing)
    }

    private fun stubLoad() {
        val cal = Calendar("cal_1", "Personal", null, null, "UTC", false, CalendarScope.PROFILE)
        coEvery { api.version("alice") } returns ApiResult.Failure(CalendarError.NotFound())
        coEvery { api.calendars("alice", CalendarScope.PROFILE) } returns ApiResult.Success(listOf(cal))
        coEvery { api.calendars("alice", CalendarScope.GLOBAL) } returns ApiResult.Success(emptyList())
        coEvery { api.events("alice", any(), any(), any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { api.notifications("alice", any(), any(), any(), any()) } returns
            ApiResult.Success(NotificationPage(emptyList(), 0, null))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.conflictingVm(eventResult: ApiResult<CalendarEvent>): CalendarViewModel {
        stubLoad()
        val original = sampleEvent()
        coEvery { api.updateEvent("alice", any()) } returns
            ApiResult.Failure(CalendarError.Conflict(original.id, 1, "changed"))
        coEvery { api.event("alice", any(), any(), any()) } returns eventResult
        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openEdit(original)
        vm.updateEditor { it.copy(summary = "mine") }
        vm.saveEditor()
        return vm
    }

    @Test fun usesTargetedRereadAndSkipsWindowListing() = runTest(dispatcher) {
        val fresh = sampleEvent(rev = 3, summary = "Fresh")
        val vm = conflictingVm(ApiResult.Success(fresh))
        advanceUntilIdle()
        assertEquals(3L, vm.state.value.editor?.eventRev)
        coVerify(exactly = 1) { api.event("alice", any(), any(), any()) }
        // Only the initial load listed events; the refetch did not.
        coVerify(exactly = 1) { api.events("alice", any(), any(), any(), any(), any()) }
    }

    @Test fun deletedElsewhereDoesNotFallBack() = runTest(dispatcher) {
        val vm = conflictingVm(ApiResult.Failure(CalendarError.NotFound("gone", routeMissing = false)))
        advanceUntilIdle()
        assertTrue(vm.state.value.editor?.conflict.orEmpty().contains("deleted elsewhere"))
        coVerify(exactly = 1) { api.events("alice", any(), any(), any(), any(), any()) }
    }

    @Test fun unauthorizedDoesNotFallBack() = runTest(dispatcher) {
        val vm = conflictingVm(ApiResult.Failure(CalendarError.Unauthorized()))
        advanceUntilIdle()
        assertNotNull(vm.state.value.editor?.conflict)
        coVerify(exactly = 1) { api.events("alice", any(), any(), any(), any(), any()) }
    }

    private fun sampleEvent(rev: Long = 1, summary: String = "Design review") = CalendarEvent(
        id = "master-1", calendarId = "cal_1", scope = CalendarScope.PROFILE, summary = summary,
        description = null, location = null, url = null, allDay = false,
        start = EventTime(local = "2026-03-02T09:00:00", tz = "America/New_York", utc = "2026-03-02T14:00:00Z"),
        end = EventTime(local = "2026-03-02T10:00:00", tz = "America/New_York", utc = "2026-03-02T15:00:00Z"),
        startUtc = 1772460000, endUtc = 1772463600, rrule = null, rev = rev,
        masterId = "master-1", masterRev = rev, masterRrule = null, isOverride = false, parentId = null,
        occurrenceStart = null, occurrenceEnd = null, recurrenceKey = null, status = "CONFIRMED",
    )
}
