package com.hermes.client.ui.plugins.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

enum class CalendarTab(val label: String) {
    MONTH("Month"),
    AGENDA("Agenda"),
    NOTIFICATIONS("Notifications"),
}

enum class SnackbarAction { LOAD, NOTIFICATIONS }

data class SnackbarMessage(val text: String, val action: SnackbarAction? = null)

data class EventEditorState(
    val sourceEvent: CalendarEvent? = null,
    /** Master id for edits; null when creating. */
    val eventId: String? = null,
    val eventRev: Long = 1,
    val occurrenceStart: String? = null,
    val scope: CalendarScope = CalendarScope.PROFILE,
    val calendarId: String = "",
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate = LocalDate.now(),
    val startTime: LocalTime = LocalTime.of(9, 0),
    val endDate: LocalDate = LocalDate.now(),
    val endTime: LocalTime = LocalTime.of(10, 0),
    val timeZone: ZoneId = ZoneId.systemDefault(),
    val recurrencePreset: RecurrencePreset = RecurrencePreset.NONE,
    val recurrenceCount: String = "",
    val recurrenceUntil: LocalDate? = null,
    val seriesScope: SeriesScope = SeriesScope.ALL,
    val saving: Boolean = false,
    val conflict: String? = null,
    val error: String? = null,
) {
    val isNew: Boolean get() = eventId == null
    val isRecurring: Boolean get() = sourceEvent?.isRecurring == true || recurrencePreset != RecurrencePreset.NONE
}

data class CalendarUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val snackbar: SnackbarMessage? = null,
    val tab: CalendarTab = CalendarTab.MONTH,
    val month: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val scope: CalendarScope = CalendarScope.PROFILE,
    val selectedCalendarId: String? = null,
    val calendarsByScope: Map<CalendarScope, List<Calendar>> = emptyMap(),
    val events: List<CalendarEvent> = emptyList(),
    val notifications: List<CalendarNotification> = emptyList(),
    val notificationsLoading: Boolean = false,
    val notificationsError: String? = null,
    val notificationWatermark: Long? = null,
    val apiVersion: Int = 0,
    val timeZone: ZoneId = ZoneId.systemDefault(),
    val firstDayOfWeek: DayOfWeek = firstDayOfWeekFor(),
    val editor: EventEditorState? = null,
    val deleteTarget: CalendarEvent? = null,
    val deleteSeriesScope: SeriesScope = SeriesScope.ALL,
) {
    val calendarsForScope: List<Calendar> get() = calendarsByScope[scope].orEmpty()

    val filteredEvents: List<CalendarEvent>
        get() = selectedCalendarId?.let { id -> events.filter { it.calendarId == id } } ?: events

    val supportsIncrementalNotifications: Boolean get() = apiVersion >= 1
}

/**
 * State holder for the native calendar screen. Holds all screen state (tab, month, selection,
 * editor draft, dialogs) so rotation does not lose the user's work.
 */
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val api: CalendarApi,
    private val profileManager: ProfileManager,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    private var profile: String? = null
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            if (profileManager.active.value.isNullOrBlank()) {
                runCatching { profileManager.refresh() }
            }
            profileManager.active.collect { name ->
                if (name.isNullOrBlank()) {
                    _state.update { it.copy(loading = false, error = "No active profile") }
                } else if (name != profile) {
                    profile = name
                    _state.update {
                        it.copy(
                            calendarsByScope = emptyMap(),
                            events = emptyList(),
                            notifications = emptyList(),
                            notificationWatermark = null,
                            selectedCalendarId = null,
                            editor = null,
                            deleteTarget = null,
                        )
                    }
                    load()
                }
            }
        }
    }

    // --- Loading ------------------------------------------------------------------------------

    /** Reloads calendars for both stores and events for the visible range. */
    fun load() {
        val currentProfile = profile ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val initial = _state.value
            _state.update {
                it.copy(
                    loading = initial.calendarsByScope.isEmpty(),
                    refreshing = initial.calendarsByScope.isNotEmpty(),
                    error = null,
                )
            }

            // The phone's server may predate GET /version; a 404 simply means api 0.
            val apiVersion = when (val version = api.version(currentProfile)) {
                is ApiResult.Success -> version.value.apiVersion
                is ApiResult.Failure -> 0
            }
            _state.update { it.copy(apiVersion = apiVersion) }

            val scope = _state.value.scope
            val calendars = _state.value.calendarsByScope.toMutableMap()
            var currentScopeError: CalendarError? = null
            for (target in CalendarScope.entries) {
                when (val result = api.calendars(currentProfile, target)) {
                    is ApiResult.Success -> calendars[target] = result.value
                    is ApiResult.Failure -> if (target == scope) currentScopeError = result.error
                }
            }
            if (currentScopeError != null) {
                _state.update {
                    it.copy(
                        calendarsByScope = calendars,
                        loading = false,
                        refreshing = false,
                        error = currentScopeError.userMessage(),
                    )
                }
                return@launch
            }
            _state.update { it.copy(calendarsByScope = calendars) }

            val (rangeStart, rangeEndExclusive) = visibleRange()
            val zone = _state.value.timeZone
            val eventsResult = api.events(
                profile = currentProfile,
                scope = scope,
                start = rangeStart.toLocalDateTimeString(zone),
                end = rangeEndExclusive.toLocalDateTimeString(zone),
                timezone = zone.id,
            )
            when (eventsResult) {
                is ApiResult.Success -> _state.update {
                    it.copy(events = eventsResult.value, loading = false, refreshing = false, error = null)
                }
                is ApiResult.Failure -> _state.update {
                    val hadEvents = it.events.isNotEmpty()
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = if (hadEvents) null else eventsResult.error.userMessage(),
                        snackbar = if (hadEvents) {
                            SnackbarMessage("Couldn't refresh events", SnackbarAction.LOAD)
                        } else {
                            it.snackbar
                        },
                    )
                }
            }
        }
    }

    private fun visibleRange(): Pair<LocalDate, LocalDate> {
        val snapshot = _state.value
        return if (snapshot.tab == CalendarTab.AGENDA) {
            snapshot.selectedDate to snapshot.selectedDate.plusDays(AGENDA_DAYS)
        } else {
            val grid = buildMonthGrid(snapshot.month, snapshot.firstDayOfWeek)
            grid.weeks.first().first().date to grid.weeks.last().last().date.plusDays(1)
        }
    }

    private fun LocalDate.toLocalDateTimeString(zone: ZoneId): String =
        atStartOfDay(zone).toLocalDateTime().format(CALENDAR_LOCAL_DATE_TIME)

    // --- Navigation / selection ---------------------------------------------------------------

    fun selectTab(tab: CalendarTab) {
        if (tab == _state.value.tab) return
        _state.update { it.copy(tab = tab) }
        when (tab) {
            CalendarTab.NOTIFICATIONS -> if (_state.value.notifications.isEmpty()) refreshNotifications()
            else -> load()
        }
    }

    fun selectDate(date: LocalDate) {
        _state.update { it.copy(selectedDate = date) }
    }

    fun previousMonth() = moveMonth(-1)

    fun nextMonth() = moveMonth(1)

    private fun moveMonth(months: Long) {
        val month = _state.value.month.plusMonths(months)
        _state.update { it.copy(month = month, selectedDate = month.atDay(1)) }
        if (_state.value.tab != CalendarTab.NOTIFICATIONS) load()
    }

    fun selectScope(scope: CalendarScope) {
        if (scope == _state.value.scope) return
        _state.update { it.copy(scope = scope, selectedCalendarId = null, events = emptyList()) }
        load()
    }

    fun selectCalendar(calendarId: String?) {
        _state.update { it.copy(selectedCalendarId = calendarId) }
    }

    // --- Notifications ------------------------------------------------------------------------

    /** Polls only while the composable is visible (driven by repeatOnLifecycle). */
    suspend fun pollNotificationsWhileVisible() {
        refreshNotificationsNow(userInitiated = false, incremental = _state.value.supportsIncrementalNotifications)
        while (true) {
            delay(NOTIFICATION_POLL_MS)
            refreshNotificationsNow(userInitiated = false, incremental = _state.value.supportsIncrementalNotifications)
        }
    }

    fun refreshNotifications() {
        viewModelScope.launch { refreshNotificationsNow(userInitiated = true, incremental = false) }
    }

    private suspend fun refreshNotificationsNow(userInitiated: Boolean, incremental: Boolean) {
        val currentProfile = profile ?: return
        if (_state.value.notificationsLoading) return
        val since = if (incremental && _state.value.supportsIncrementalNotifications) {
            _state.value.notificationWatermark
        } else {
            null
        }
        _state.update { it.copy(notificationsLoading = true) }
        try {
            when (val result = api.notifications(currentProfile, limit = 50, since = since)) {
                is ApiResult.Success -> {
                    val page = result.value
                    _state.update { snapshot ->
                        val merged = if (since == null) {
                            page.notifications.sortedByFireAt()
                        } else {
                            mergeNotifications(snapshot.notifications, page.notifications)
                        }
                        snapshot.copy(
                            notifications = merged,
                            notificationsLoading = false,
                            notificationsError = null,
                            // The server snapshot is the watermark; max item time is not safe when a page is empty.
                            notificationWatermark = page.serverTimeEpoch ?: snapshot.notificationWatermark,
                        )
                    }
                }
                is ApiResult.Failure -> _state.update { snapshot ->
                    snapshot.copy(
                        notificationsLoading = false,
                        notificationsError = if (userInitiated) result.error.userMessage() else snapshot.notificationsError,
                    )
                }
            }
        } finally {
            // A lifecycle cancellation mid-request must not leave the tab stuck on "loading".
            _state.update { if (it.notificationsLoading) it.copy(notificationsLoading = false) else it }
        }
    }

    private fun mergeNotifications(
        existing: List<CalendarNotification>,
        incoming: List<CalendarNotification>,
    ): List<CalendarNotification> {
        if (incoming.isEmpty()) return existing
        val byId = LinkedHashMap<String, CalendarNotification>()
        existing.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        return byId.values.toList().sortedByFireAt()
    }

    private fun List<CalendarNotification>.sortedByFireAt(): List<CalendarNotification> =
        sortedWith(
            compareByDescending<CalendarNotification> { it.fireAt?.epochSecond ?: Long.MIN_VALUE }
                .thenByDescending { it.id },
        )

    fun dismissNotification(notificationId: String) {
        val currentProfile = profile ?: return
        viewModelScope.launch {
            when (val result = api.dismissNotification(currentProfile, notificationId)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        notifications = it.notifications.filterNot { n -> n.id == notificationId },
                        snackbar = SnackbarMessage("Notification dismissed"),
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(snackbar = SnackbarMessage(result.error.userMessage(), SnackbarAction.NOTIFICATIONS))
                }
            }
        }
    }

    fun snoozeNotification(notificationId: String, minutes: Int) {
        if (minutes !in ALLOWED_SNOOZE_MINUTES) return
        val currentProfile = profile ?: return
        viewModelScope.launch {
            when (val result = api.snoozeNotification(currentProfile, notificationId, minutes)) {
                is ApiResult.Success -> {
                    val updated = result.value
                    _state.update { snapshot ->
                        val list = if (updated != null) {
                            snapshot.notifications.map { if (it.id == updated.id) updated else it }
                        } else {
                            snapshot.notifications
                        }
                        snapshot.copy(
                            notifications = list,
                            snackbar = SnackbarMessage("Snoozed for ${snoozeLabel(minutes)}"),
                        )
                    }
                    if (updated == null) refreshNotificationsNow(userInitiated = false, incremental = false)
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(snackbar = SnackbarMessage(result.error.userMessage(), SnackbarAction.NOTIFICATIONS))
                }
            }
        }
    }

    private fun snoozeLabel(minutes: Int): String = when (minutes) {
        60 -> "1 hour"
        1440 -> "1 day"
        else -> "$minutes minutes"
    }

    // --- Editor -------------------------------------------------------------------------------

    fun openCreate(date: LocalDate = _state.value.selectedDate) {
        val snapshot = _state.value
        val calendars = snapshot.calendarsByScope[snapshot.scope].orEmpty()
        val calendar = calendars.firstOrNull { !it.readOnly } ?: calendars.firstOrNull()
        if (calendar == null) {
            _state.update { it.copy(snackbar = SnackbarMessage("No calendar available in this store")) }
            return
        }
        _state.update {
            it.copy(
                editor = EventEditorState(
                    scope = snapshot.scope,
                    calendarId = calendar.id,
                    startDate = date,
                    endDate = date,
                    timeZone = snapshot.timeZone,
                ),
            )
        }
    }

    fun openEdit(event: CalendarEvent) {
        val startZone = event.effectiveStart.zoneId
        val endZone = event.effectiveEnd.zoneId
        val startLocal = event.effectiveStart.localDateTime
            ?: event.effectiveStart.instant?.atZone(startZone)?.toLocalDateTime()
        val endLocal = event.effectiveEnd.localDateTime
            ?: event.effectiveEnd.instant?.atZone(endZone)?.toLocalDateTime()
        val startDate = if (event.allDay) {
            event.effectiveStart.localDate ?: LocalDate.now()
        } else {
            startLocal?.toLocalDate() ?: LocalDate.now()
        }
        val endDate = if (event.allDay) {
            event.allDayEndInclusive() ?: startDate
        } else {
            endLocal?.toLocalDate() ?: startDate
        }
        val rule = parseRrule(event.masterRrule ?: event.rrule)
        _state.update {
            it.copy(
                editor = EventEditorState(
                    sourceEvent = event,
                    eventId = event.seriesId,
                    eventRev = event.seriesRev,
                    occurrenceStart = if (event.isRecurring) event.occurrenceSelector() else null,
                    scope = event.scope,
                    calendarId = event.calendarId,
                    summary = event.summary,
                    description = event.description.orEmpty(),
                    location = event.location.orEmpty(),
                    allDay = event.allDay,
                    startDate = startDate,
                    startTime = startLocal?.toLocalTime() ?: LocalTime.of(9, 0),
                    endDate = endDate,
                    endTime = endLocal?.toLocalTime() ?: LocalTime.of(10, 0),
                    timeZone = startZone,
                    recurrencePreset = rule.preset,
                    recurrenceCount = rule.count?.toString().orEmpty(),
                    recurrenceUntil = rule.until,
                ),
            )
        }
    }

    fun updateEditor(transform: (EventEditorState) -> EventEditorState) {
        _state.update { snapshot ->
            snapshot.copy(editor = snapshot.editor?.let(transform))
        }
    }

    fun updateEditorScope(scope: CalendarScope) {
        updateEditor { editor ->
            val calendars = _state.value.calendarsByScope[scope].orEmpty()
            val calendarId = calendars.firstOrNull { it.id == editor.calendarId }?.id
                ?: calendars.firstOrNull { !it.readOnly }?.id
                ?: calendars.firstOrNull()?.id.orEmpty()
            editor.copy(scope = scope, calendarId = calendarId)
        }
    }

    fun closeEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun saveEditor() {
        val editor = _state.value.editor ?: return
        val currentProfile = profile ?: return
        validateEditor(editor)?.let { problem ->
            updateEditor { it.copy(error = problem) }
            return
        }
        viewModelScope.launch {
            updateEditor { it.copy(saving = true, error = null, conflict = null) }
            val result = if (editor.isNew) {
                api.createEvent(currentProfile, editor.toDraft())
            } else {
                api.updateEvent(currentProfile, editor.toPatch())
            }
            when (result) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(
                            editor = null,
                            snackbar = SnackbarMessage(if (editor.isNew) "Event created" else "Event updated"),
                        )
                    }
                    load()
                }
                is ApiResult.Failure -> handleSaveFailure(editor, result.error)
            }
        }
    }

    private fun validateEditor(editor: EventEditorState): String? {
        if (editor.summary.isBlank()) return "Title is required"
        if (editor.calendarId.isBlank()) return "Choose a calendar"
        if (editor.allDay) {
            if (editor.endDate.isBefore(editor.startDate)) return "End date must be on or after the start date"
        } else {
            val start = LocalDateTime.of(editor.startDate, editor.startTime)
            val end = LocalDateTime.of(editor.endDate, editor.endTime)
            if (!end.isAfter(start)) return "End must be after the start"
        }
        val count = editor.recurrenceCount.trim()
        if (editor.recurrencePreset != RecurrencePreset.NONE && count.isNotEmpty()) {
            val parsed = count.toIntOrNull()
            if (parsed == null || parsed < 1) return "Repeat count must be a positive number"
            if (editor.recurrenceUntil != null) return "Use a repeat count or an until date, not both"
        }
        return null
    }

    private suspend fun handleSaveFailure(editor: EventEditorState, error: CalendarError) {
        when (error) {
            is CalendarError.Conflict -> {
                // Re-read precisely the row the server named, keep the user's draft open, and
                // retry with the fresh master revision on the next save.
                val current = refetchEvent(editor.eventId ?: error.eventId, editor.scope, editor.startDate)
                _state.update { snapshot ->
                    val open = snapshot.editor ?: editor
                    snapshot.copy(
                        editor = open.copy(
                            saving = false,
                            eventRev = current?.seriesRev ?: open.eventRev,
                            conflict = if (current != null) {
                                "This event changed elsewhere (now \"${current.summary}\"). " +
                                    "Your changes are still here — review them and save again."
                            } else {
                                "This event changed elsewhere. Check the agenda, then try again."
                            },
                        ),
                    )
                }
            }
            else -> updateEditor { it.copy(saving = false, error = error.userMessage()) }
        }
    }

    private suspend fun refetchEvent(
        eventId: String?,
        scope: CalendarScope,
        around: LocalDate,
    ): CalendarEvent? {
        val currentProfile = profile ?: return null
        val zone = _state.value.timeZone
        val result = api.events(
            profile = currentProfile,
            scope = scope,
            start = around.minusDays(2).toLocalDateTimeString(zone),
            end = around.plusDays(3).toLocalDateTimeString(zone),
            timezone = zone.id,
        )
        return when (result) {
            is ApiResult.Success -> result.value.firstOrNull {
                eventId == null || it.id == eventId || it.seriesId == eventId
            }
            is ApiResult.Failure -> null
        }
    }

    private fun EventEditorState.toDraft(): EventDraft {
        val start = if (allDay) {
            startDate.toString()
        } else {
            LocalDateTime.of(startDate, startTime).format(CALENDAR_LOCAL_DATE_TIME)
        }
        val end = if (allDay) {
            // The UI's end date is inclusive; the server's all-day end is exclusive.
            endDate.plusDays(1).toString()
        } else {
            LocalDateTime.of(endDate, endTime).format(CALENDAR_LOCAL_DATE_TIME)
        }
        return EventDraft(
            calendarId = calendarId,
            summary = summary.trim(),
            start = start,
            end = end,
            timezone = if (allDay) null else timeZone.id,
            description = description.trim().ifBlank { null },
            location = location.trim().ifBlank { null },
            url = null,
            rrule = buildRrule(
                RruleSpec(recurrencePreset, recurrenceCount.trim().toIntOrNull(), recurrenceUntil),
                allDay,
                timeZone,
            ),
            allDay = allDay,
            scope = scope,
        )
    }

    private fun EventEditorState.toPatch(): EventPatch {
        val id = eventId ?: error("edit without an event id")
        val start = if (allDay) {
            startDate.toString()
        } else {
            LocalDateTime.of(startDate, startTime).format(CALENDAR_LOCAL_DATE_TIME)
        }
        val end = if (allDay) {
            endDate.plusDays(1).toString()
        } else {
            LocalDateTime.of(endDate, endTime).format(CALENDAR_LOCAL_DATE_TIME)
        }
        val thisOnly = seriesScope == SeriesScope.THIS
        return EventPatch(
            eventId = id,
            expectedRev = eventRev,
            scope = scope,
            summary = summary.trim(),
            start = start,
            end = end,
            timezone = if (allDay) null else timeZone.id,
            description = description.trim().ifBlank { null },
            location = location.trim().ifBlank { null },
            url = sourceEvent?.url,
            rrule = if (thisOnly) null else buildRrule(
                RruleSpec(recurrencePreset, recurrenceCount.trim().toIntOrNull(), recurrenceUntil),
                allDay,
                timeZone,
            ),
            occurrenceStart = if (thisOnly) occurrenceStart else null,
            seriesScope = seriesScope,
        )
    }

    // --- Delete -------------------------------------------------------------------------------

    fun requestDelete(event: CalendarEvent) {
        _state.update {
            it.copy(deleteTarget = event, deleteSeriesScope = SeriesScope.ALL, editor = null)
        }
    }

    fun requestDeleteFromEditor() {
        val event = _state.value.editor?.sourceEvent ?: return
        requestDelete(event)
    }

    fun setDeleteSeriesScope(scope: SeriesScope) {
        _state.update { it.copy(deleteSeriesScope = scope) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val event = _state.value.deleteTarget ?: return
        val currentProfile = profile ?: return
        val seriesScope = _state.value.deleteSeriesScope
        viewModelScope.launch {
            val occurrence = if (seriesScope == SeriesScope.THIS) event.occurrenceSelector() else null
            when (
                val result = api.deleteEvent(
                    profile = currentProfile,
                    scope = event.scope,
                    eventId = event.seriesId,
                    expectedRev = event.seriesRev,
                    occurrenceStart = occurrence,
                    seriesScope = seriesScope,
                )
            ) {
                is ApiResult.Success -> {
                    _state.update {
                        it.copy(deleteTarget = null, snackbar = SnackbarMessage("Event deleted"))
                    }
                    load()
                }
                is ApiResult.Failure -> {
                    _state.update {
                        it.copy(
                            deleteTarget = null,
                            snackbar = SnackbarMessage(result.error.userMessage(), SnackbarAction.LOAD),
                        )
                    }
                }
            }
        }
    }

    // --- Snackbar -----------------------------------------------------------------------------

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    fun onSnackbarAction() {
        when (_state.value.snackbar?.action) {
            SnackbarAction.LOAD -> {
                consumeSnackbar()
                load()
            }
            SnackbarAction.NOTIFICATIONS -> {
                consumeSnackbar()
                refreshNotifications()
            }
            null -> Unit
        }
    }

    private companion object {
        const val AGENDA_DAYS = 30L
        const val NOTIFICATION_POLL_MS = 30_000L
        val ALLOWED_SNOOZE_MINUTES = setOf(5, 15, 60, 1440)
    }
}
