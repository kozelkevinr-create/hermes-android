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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarCreateTest {
    private val dispatcher = StandardTestDispatcher()
    private val api = mockk<CalendarApi>()
    private val profileManager = mockk<ProfileManager>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { profileManager.active } returns MutableStateFlow<String?>("alice")
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun cal(id: String, name: String, scope: CalendarScope) = Calendar(
        id = id, name = name, description = null, color = null, defaultTz = "UTC",
        readOnly = false, scope = scope,
    )

    private fun stubLoad(profile: List<Calendar>, global: List<Calendar>) {
        coEvery { api.version("alice") } returns ApiResult.Failure(CalendarError.NotFound())
        coEvery { api.calendars("alice", CalendarScope.PROFILE) } returns ApiResult.Success(profile)
        coEvery { api.calendars("alice", CalendarScope.GLOBAL) } returns ApiResult.Success(global)
        coEvery { api.events("alice", any(), any(), any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { api.notifications("alice", any(), any(), any(), any()) } returns
            ApiResult.Success(NotificationPage(emptyList(), 0, null))
    }

    @Test fun validationMirrorsServerLimits() {
        assertEquals("Name is required", CalendarEditorState(name = "  ").validated().nameError)
        assertNotNull(CalendarEditorState(name = "x".repeat(201)).validated().nameError)
        assertNotNull(CalendarEditorState(name = "ok", timeZoneId = "Mars/Base").validated().zoneError)
        assertNotNull(CalendarEditorState(name = "ok", description = "d".repeat(2001)).validated().error)
        assertFalse(CalendarEditorState(name = " ok ", timeZoneId = "Europe/Berlin").validated().hasErrors)
    }

    @Test fun createsProfileCalendarThenSelectsIt() = runTest(dispatcher) {
        stubLoad(emptyList(), listOf(cal("cal_global", "Shared", CalendarScope.GLOBAL)))
        val created = cal("cal_new", "Personal", CalendarScope.PROFILE)
        coEvery { api.createCalendar("alice", CalendarScope.PROFILE, "Personal", null, null, "Europe/Berlin") } returns
            ApiResult.Success(created)

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openNewCalendar()
        vm.updateCalendarEditor { it.copy(name = "  Personal ", timeZoneId = "Europe/Berlin") }
        vm.saveCalendar()
        advanceUntilIdle()

        assertNull(vm.state.value.calendarEditor)
        assertEquals("cal_new", vm.state.value.selectedCalendarId)
        assertEquals(CalendarScope.PROFILE, vm.state.value.scope)
        coVerify(exactly = 1) { api.createCalendar("alice", CalendarScope.PROFILE, "Personal", null, null, "Europe/Berlin") }
    }

    @Test fun serverNameErrorLandsOnTheNameField() = runTest(dispatcher) {
        stubLoad(emptyList(), emptyList())
        coEvery { api.createCalendar(any(), any(), any(), any(), any(), any()) } returns
            ApiResult.Failure(CalendarError.Validation("name must be 1-200 characters"))

        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openNewCalendar()
        vm.updateCalendarEditor { it.copy(name = "x") }
        vm.saveCalendar()
        advanceUntilIdle()

        val editor = vm.state.value.calendarEditor
        assertNotNull(editor)
        assertEquals("name must be 1-200 characters", editor!!.nameError)
        assertFalse(editor.saving)
    }

    @Test fun invalidDraftIsNotSent() = runTest(dispatcher) {
        stubLoad(emptyList(), emptyList())
        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openNewCalendar()
        vm.saveCalendar()
        advanceUntilIdle()
        assertEquals("Name is required", vm.state.value.calendarEditor?.nameError)
        coVerify(exactly = 0) { api.createCalendar(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun newEventFallsBackToGlobalWhenProfileHasNoCalendars() = runTest(dispatcher) {
        stubLoad(emptyList(), listOf(cal("cal_global", "Shared", CalendarScope.GLOBAL)))
        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openCreate()
        val editor = vm.state.value.editor
        assertNotNull(editor)
        assertEquals(CalendarScope.GLOBAL, editor!!.scope)
        assertEquals("cal_global", editor.calendarId)
        assertNull(vm.state.value.calendarEditor)
    }

    @Test fun newEventWithNoCalendarsAnywhereOpensNewCalendarDialog() = runTest(dispatcher) {
        stubLoad(emptyList(), emptyList())
        val vm = CalendarViewModel(api, profileManager)
        advanceUntilIdle()
        vm.openCreate()
        assertNull(vm.state.value.editor)
        assertTrue(vm.state.value.calendarEditor != null)
    }
}
