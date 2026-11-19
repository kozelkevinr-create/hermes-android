package com.hermes.client.ui.plugins.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.LoadingState
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    vm: CalendarViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Notifications poll only while the screen is actually visible, at a conservative interval.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.pollNotificationsWhileVisible()
        }
    }

    LaunchedEffect(state.snackbar) {
        val message = state.snackbar ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = message.text,
            actionLabel = if (message.action != null) "Retry" else null,
        )
        if (result == SnackbarResult.ActionPerformed) vm.onSnackbarAction()
        vm.consumeSnackbar()
    }

    BackHandler(enabled = state.editor != null || state.deleteTarget != null || state.calendarEditor != null) {
        when {
            state.calendarEditor != null -> vm.closeCalendarEditor()
            state.editor != null -> vm.closeEditor()
            else -> vm.cancelDelete()
        }
    }
    BackHandler(enabled = state.editor == null && state.deleteTarget == null && state.calendarEditor == null) { onBack() }

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize(), "Loading calendar")
            state.error != null -> ErrorState(
                message = state.error!!,
                onRetry = vm::load,
            )
            else -> Column(Modifier.fillMaxSize()) {
                SegmentedTabs(selected = state.tab, onSelect = vm::selectTab)
                if (state.tab != CalendarTab.NOTIFICATIONS) {
                    ScopeSelector(state = state, vm = vm)
                }
                if (state.refreshing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                when (state.tab) {
                    CalendarTab.MONTH -> MonthTab(state = state, vm = vm)
                    CalendarTab.AGENDA -> AgendaTab(state = state, vm = vm)
                    CalendarTab.NOTIFICATIONS -> NotificationsTab(state = state, vm = vm)
                }
            }
        }

        if (!state.loading && state.error == null && state.tab != CalendarTab.NOTIFICATIONS) {
            FloatingActionButton(
                onClick = { vm.openCreate() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "New event")
            }
        }

        val editor = state.editor
        if (editor != null) {
            EventEditorSheet(
                editor = editor,
                calendars = state.calendarsByScope[editor.scope].orEmpty(),
                onDismiss = vm::closeEditor,
                onSave = vm::saveEditor,
                onDelete = vm::requestDeleteFromEditor,
                onUpdate = vm::updateEditor,
                onScope = vm::updateEditorScope,
            )
        }
        state.calendarEditor?.let { draft ->
            NewCalendarDialog(
                draft = draft,
                onUpdate = vm::updateCalendarEditor,
                onSave = vm::saveCalendar,
                onDismiss = vm::closeCalendarEditor,
            )
        }
        state.deleteTarget?.let { event ->
            DeleteEventDialog(
                event = event,
                seriesScope = state.deleteSeriesScope,
                onScope = vm::setDeleteSeriesScope,
                onConfirm = vm::confirmDelete,
                onDismiss = vm::cancelDelete,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

// --- Top controls -----------------------------------------------------------------------------

@Composable
private fun SegmentedTabs(selected: CalendarTab, onSelect: (CalendarTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        CalendarTab.entries.forEachIndexed { index, tab ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = CalendarTab.entries.size),
                label = { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun ScopeSelector(state: CalendarUiState, vm: CalendarViewModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = state.scope == CalendarScope.PROFILE,
                onClick = { vm.selectScope(CalendarScope.PROFILE) },
                label = { Text(CalendarScope.PROFILE.label) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = state.scope == CalendarScope.GLOBAL,
                onClick = { vm.selectScope(CalendarScope.GLOBAL) },
                label = { Text(CalendarScope.GLOBAL.label) },
            )
        }
        val calendars = state.calendarsForScope
        if (calendars.size > 1) {
            var expanded by remember { mutableStateOf(false) }
            Box(Modifier.padding(top = 4.dp)) {
                OutlinedButton(onClick = { expanded = true }) {
                    val selected = calendars.firstOrNull { it.id == state.selectedCalendarId }
                    Text("Calendar: ${selected?.displayName ?: "All calendars"}")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("All calendars") },
                        onClick = {
                            vm.selectCalendar(null)
                            expanded = false
                        },
                    )
                    calendars.forEach { calendar ->
                        DropdownMenuItem(
                            text = { Text(calendar.displayName) },
                            onClick = {
                                vm.selectCalendar(calendar.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
        } else if (calendars.size == 1) {
            Text(
                calendars.first().displayName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (state.scope == CalendarScope.PROFILE) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "No calendars yet",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = vm::openNewCalendar) { Text("Create calendar") }
            }
        } else {
            Text(
                "No calendars in this store",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.scope == CalendarScope.PROFILE && calendars.isNotEmpty()) {
            TextButton(onClick = vm::openNewCalendar) { Text("New calendar") }
        }
    }
}

@Composable
private fun NewCalendarDialog(
    draft: CalendarEditorState,
    onUpdate: ((CalendarEditorState) -> CalendarEditorState) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!draft.saving) onDismiss() },
        title = { Text("New calendar") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { v -> onUpdate { it.copy(name = v, nameError = null) } },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = draft.nameError != null,
                    supportingText = draft.nameError?.let { e -> { Text(e) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { v -> onUpdate { it.copy(description = v, error = null) } },
                    label = { Text("Description (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = draft.timeZoneId,
                    onValueChange = { v -> onUpdate { it.copy(timeZoneId = v, zoneError = null) } },
                    label = { Text("Default time zone") },
                    singleLine = true,
                    isError = draft.zoneError != null,
                    supportingText = draft.zoneError?.let { e -> { Text(e) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Created in this profile's own store.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                draft.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !draft.saving) {
                Text(if (draft.saving) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !draft.saving) { Text("Cancel") }
        },
    )
}


// --- Month ------------------------------------------------------------------------------------

@Composable
private fun MonthTab(state: CalendarUiState, vm: CalendarViewModel) {
    Column(Modifier.fillMaxSize()) {
        MonthHeader(
            month = state.month,
            onPrevious = vm::previousMonth,
            onNext = vm::nextMonth,
        )
        WeekdayHeader(state.firstDayOfWeek)
        MonthGridSection(
            month = state.month,
            firstDayOfWeek = state.firstDayOfWeek,
            events = state.filteredEvents,
            selectedDate = state.selectedDate,
            zone = state.timeZone,
            onSelect = vm::selectDate,
        )
        HorizontalDivider()
        val dayEvents = remember(state.filteredEvents, state.selectedDate, state.timeZone) {
            state.filteredEvents
                .filter { it.occursOn(state.selectedDate, state.timeZone) }
                .sortedBy { it.effectiveStart.instant?.epochSecond ?: Long.MAX_VALUE }
        }
        if (dayEvents.isEmpty()) {
            EmptyState(
                title = "No events",
                subtitle = "Nothing scheduled on ${state.selectedDate.format(DAY_HEADER)}",
                icon = Icons.Rounded.Event,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            Text(
                state.selectedDate.format(DAY_HEADER),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                items(dayEvents, key = { it.stableKey }) { event ->
                    EventRow(
                        event = event,
                        zone = state.timeZone,
                        readOnly = event.readOnly(state),
                        onClick = { vm.openEdit(event) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: java.time.YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month")
        }
        Text(
            month.atDay(1).format(MONTH_HEADER),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun WeekdayHeader(firstDayOfWeek: java.time.DayOfWeek) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        weekdayLabels(firstDayOfWeek).forEach { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun MonthGridSection(
    month: java.time.YearMonth,
    firstDayOfWeek: java.time.DayOfWeek,
    events: List<CalendarEvent>,
    selectedDate: LocalDate,
    zone: ZoneId,
    onSelect: (LocalDate) -> Unit,
) {
    val grid = remember(month, firstDayOfWeek) { buildMonthGrid(month, firstDayOfWeek) }
    val counts = remember(events, zone) {
        val map = HashMap<LocalDate, Int>()
        events.forEach { event ->
            event.coveredDates(zone).forEach { date -> map[date] = (map[date] ?: 0) + 1 }
        }
        map
    }
    val today = LocalDate.now(zone)
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        grid.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    DayCell(
                        cell = cell,
                        selected = cell.date == selectedDate,
                        today = cell.date == today,
                        eventCount = counts[cell.date] ?: 0,
                        onClick = { onSelect(cell.date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    cell: MonthCell,
    selected: Boolean,
    today: Boolean,
    eventCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        cell.inMonth -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val label = buildString {
        append(cell.date.format(DAY_HEADER))
        if (eventCount > 0) append(", $eventCount event${if (eventCount == 1) "" else "s"}")
    }
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .padding(2.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            cell.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
        )
        if (eventCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(eventCount.coerceAtMost(3)) {
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(if (selected) contentColor else MaterialTheme.colorScheme.primary),
                    )
                }
                if (eventCount > 3) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) contentColor else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        } else if (today) {
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary),
            )
        }
    }
}

// --- Agenda -----------------------------------------------------------------------------------

@Composable
private fun AgendaTab(state: CalendarUiState, vm: CalendarViewModel) {
    val grouped = remember(state.filteredEvents, state.timeZone) {
        state.filteredEvents
            .sortedBy { it.effectiveStart.instant?.epochSecond ?: Long.MAX_VALUE }
            .groupBy { it.displayStartDate(state.timeZone) ?: LocalDate.MAX }
            .toSortedMap()
    }
    if (grouped.isEmpty()) {
        EmptyState(
            title = "No upcoming events",
            subtitle = "The next ${AGENDA_DAYS} days are clear",
            icon = Icons.Rounded.Event,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        grouped.forEach { (date, events) ->
            val key = if (date == LocalDate.MAX) "unknown" else date.toString()
            item(key = "header-$key") {
                Text(
                    if (date == LocalDate.MAX) "Date unknown" else date.format(DAY_HEADER),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            items(events, key = { it.stableKey }) { event ->
                EventRow(
                    event = event,
                    zone = state.timeZone,
                    readOnly = event.readOnly(state),
                    onClick = { vm.openEdit(event) },
                )
            }
            item(key = "divider-$key") { HorizontalDivider() }
        }
    }
}

// --- Event row --------------------------------------------------------------------------------

@Composable
private fun EventRow(
    event: CalendarEvent,
    zone: ZoneId,
    readOnly: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !readOnly, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(event.summary, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text(
                eventTimeLabel(event, zone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!event.location.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Place,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (event.isRecurring) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Repeats",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (readOnly) {
            Icon(
                Icons.Rounded.Lock,
                contentDescription = "Read-only calendar",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

private fun CalendarEvent.readOnly(state: CalendarUiState): Boolean {
    val calendar = state.calendarsByScope[scope].orEmpty().firstOrNull { it.id == calendarId }
    return calendar?.readOnly == true
}

// --- Notifications ----------------------------------------------------------------------------

@Composable
private fun NotificationsTab(state: CalendarUiState, vm: CalendarViewModel) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Notifications",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = vm::refreshNotifications,
                enabled = !state.notificationsLoading,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh notifications")
            }
        }
        if (state.notificationsLoading && state.notifications.isEmpty()) {
            LoadingState(Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        state.notificationsError?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::refreshNotifications) { Text("Retry") }
                }
            }
        }
        if (state.notifications.isEmpty()) {
            EmptyState(
                title = "No notifications",
                subtitle = "Calendar reminders will appear here",
                icon = Icons.Rounded.Notifications,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.notifications, key = { it.id }) { notification ->
                    NotificationRow(
                        notification = notification,
                        timeZone = state.timeZone,
                        onDismiss = { vm.dismissNotification(notification.id) },
                        onSnooze = { minutes -> vm.snoozeNotification(notification.id, minutes) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: CalendarNotification,
    timeZone: ZoneId,
    onDismiss: () -> Unit,
    onSnooze: (Int) -> Unit,
) {
    var snoozeMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Notifications,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(notification.title, style = MaterialTheme.typography.titleSmall)
            Text(
                notificationSummary(notification, timeZone),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            notification.snoozeUntil?.let { until ->
                Text(
                    "Snoozed until ${formatInstant(until, timeZone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Row(Modifier.padding(top = 2.dp)) {
                IconButton(
                    onClick = onDismiss,
                    enabled = notification.canDismiss,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = "Dismiss notification")
                }
                Box {
                    IconButton(
                        onClick = { snoozeMenu = true },
                        enabled = notification.canSnooze,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Rounded.Snooze, contentDescription = "Snooze notification")
                    }
                    DropdownMenu(expanded = snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                        SNOOZE_OPTIONS.forEach { (minutes, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    snoozeMenu = false
                                    onSnooze(minutes)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider()
}

private fun notificationSummary(notification: CalendarNotification, zone: ZoneId): String {
    val state = notification.state.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val fire = notification.fireAt?.let { formatInstant(it, zone) }
    return if (fire != null) "$state · $fire" else state
}

// --- Event editor -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventEditorSheet(
    editor: EventEditorState,
    calendars: List<Calendar>,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: ((EventEditorState) -> EventEditorState) -> Unit,
    onScope: (CalendarScope) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showStartDate by remember { mutableStateOf(false) }
    var showEndDate by remember { mutableStateOf(false) }
    var showStartTime by remember { mutableStateOf(false) }
    var showEndTime by remember { mutableStateOf(false) }
    var showUntilDate by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (editor.isNew) "New event" else "Edit event",
                style = MaterialTheme.typography.titleLarge,
            )
            editor.conflict?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            OutlinedTextField(
                value = editor.summary,
                onValueChange = { text -> onUpdate { it.copy(summary = text) } },
                label = { Text("Title") },
                singleLine = true,
                isError = editor.error == "Title is required",
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            if (editor.isNew) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = editor.scope == CalendarScope.PROFILE,
                        onClick = { onScope(CalendarScope.PROFILE) },
                        label = { Text(CalendarScope.PROFILE.label) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = editor.scope == CalendarScope.GLOBAL,
                        onClick = { onScope(CalendarScope.GLOBAL) },
                        label = { Text(CalendarScope.GLOBAL.label) },
                    )
                }
            }
            if (editor.isNew) {
                val target = calendars.firstOrNull { it.id == editor.calendarId }
                Text(
                    "Saves to: ${editor.scope.label} · ${target?.displayName ?: "no calendar selected"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            CalendarPicker(
                calendars = calendars,
                selectedId = editor.calendarId,
                onSelect = { id -> onUpdate { it.copy(calendarId = id) } },
            )
            OutlinedTextField(
                value = editor.description,
                onValueChange = { text -> onUpdate { it.copy(description = text) } },
                label = { Text("Description") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            OutlinedTextField(
                value = editor.location,
                onValueChange = { text -> onUpdate { it.copy(location = text) } },
                label = { Text("Location") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("All-day", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = editor.allDay,
                    onCheckedChange = { checked -> onUpdate { it.copy(allDay = checked) } },
                    enabled = editor.isNew,
                )
            }
            if (!editor.isNew) {
                Text(
                    "Timed and all-day events cannot be converted after creation.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DateField(
                    label = "Start date",
                    date = editor.startDate,
                    onClick = { showStartDate = true },
                    modifier = Modifier.weight(1f),
                )
                if (!editor.allDay) {
                    TimeField(
                        label = "Start time",
                        time = editor.startTime,
                        onClick = { showStartTime = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DateField(
                    label = if (editor.allDay) "End date (inclusive)" else "End date",
                    date = editor.endDate,
                    onClick = { showEndDate = true },
                    modifier = Modifier.weight(1f),
                )
                if (!editor.allDay) {
                    TimeField(
                        label = "End time",
                        time = editor.endTime,
                        onClick = { showEndTime = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "Time zone: ${editor.timeZone.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            RecurrenceEditor(
                editor = editor,
                onUpdate = onUpdate,
                onPickUntil = { showUntilDate = true },
            )
            if (!editor.isNew && editor.isRecurring) {
                Text(
                    "Apply changes to",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    FilterChip(
                        selected = editor.seriesScope == SeriesScope.THIS,
                        onClick = { onUpdate { it.copy(seriesScope = SeriesScope.THIS) } },
                        label = { Text(SeriesScope.THIS.label) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = editor.seriesScope == SeriesScope.ALL,
                        onClick = { onUpdate { it.copy(seriesScope = SeriesScope.ALL) } },
                        label = { Text(SeriesScope.ALL.label) },
                    )
                }
                if (editor.seriesScope == SeriesScope.THIS) {
                    Text(
                        "Recurrence and all-day settings stay unchanged for a single occurrence.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            editor.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!editor.isNew) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Delete")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !editor.saving) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSave, enabled = !editor.saving) {
                    Text(if (editor.saving) "Saving…" else "Save")
                }
            }
        }
    }

    if (showStartDate) {
        DatePickerDialogCompat(
            initial = editor.startDate,
            onDismiss = { showStartDate = false },
            onPick = { date ->
                showStartDate = false
                onUpdate {
                    it.copy(
                        startDate = date,
                        endDate = if (it.endDate.isBefore(date)) date else it.endDate,
                    )
                }
            },
        )
    }
    if (showEndDate) {
        DatePickerDialogCompat(
            initial = editor.endDate,
            onDismiss = { showEndDate = false },
            onPick = { date ->
                showEndDate = false
                onUpdate { it.copy(endDate = date) }
            },
        )
    }
    if (showStartTime) {
        TimePickerDialogCompat(
            initial = editor.startTime,
            onDismiss = { showStartTime = false },
            onPick = { time ->
                showStartTime = false
                onUpdate { it.copy(startTime = time) }
            },
        )
    }
    if (showEndTime) {
        TimePickerDialogCompat(
            initial = editor.endTime,
            onDismiss = { showEndTime = false },
            onPick = { time ->
                showEndTime = false
                onUpdate { it.copy(endTime = time) }
            },
        )
    }
    if (showUntilDate) {
        DatePickerDialogCompat(
            initial = editor.recurrenceUntil ?: editor.startDate,
            onDismiss = { showUntilDate = false },
            onPick = { date ->
                showUntilDate = false
                onUpdate { it.copy(recurrenceUntil = date) }
            },
        )
    }
}

@Composable
private fun CalendarPicker(
    calendars: List<Calendar>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                calendars.firstOrNull { it.id == selectedId }?.displayName ?: "Choose a calendar",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            calendars.forEach { calendar ->
                DropdownMenuItem(
                    text = { Text(calendar.displayName) },
                    onClick = {
                        onSelect(calendar.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RecurrenceEditor(
    editor: EventEditorState,
    onUpdate: ((EventEditorState) -> EventEditorState) -> Unit,
    onPickUntil: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val locked = !editor.isNew && editor.seriesScope == SeriesScope.THIS
    Text(
        "Repeats",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 16.dp),
    )
    Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !locked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(editor.recurrencePreset.label)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RecurrencePreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.label) },
                    onClick = {
                        onUpdate { it.copy(recurrencePreset = preset) }
                        expanded = false
                    },
                )
            }
        }
    }
    if (editor.recurrencePreset != RecurrencePreset.NONE && !locked) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = editor.recurrenceCount,
                onValueChange = { text ->
                    onUpdate { it.copy(recurrenceCount = text.filter(Char::isDigit)) }
                },
                label = { Text("Count") },
                placeholder = { Text("No limit") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onPickUntil,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(
                    editor.recurrenceUntil?.format(SHORT_DATE) ?: "Until date",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (editor.recurrenceUntil != null) {
                IconButton(
                    onClick = { onUpdate { it.copy(recurrenceUntil = null) } },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Clear until date")
                }
            }
        }
        Text(
            "Use a count or an until date, not both.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DateField(
    label: String,
    date: LocalDate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(date.format(SHORT_DATE), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    time: LocalTime,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(time.format(TIME_ONLY), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerDialogCompat(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    } ?: onDismiss()
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialogCompat(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPick: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(LocalTime.of(pickerState.hour, pickerState.minute))
                },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = pickerState) },
    )
}

@Composable
private fun DeleteEventDialog(
    event: CalendarEvent,
    seriesScope: SeriesScope,
    onScope: (SeriesScope) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete event?") },
        text = {
            Column {
                Text("\"${event.summary}\" will be deleted.")
                if (event.isRecurring) {
                    Text(
                        "This is a repeating event.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(Modifier.padding(top = 8.dp)) {
                        FilterChip(
                            selected = seriesScope == SeriesScope.THIS,
                            onClick = { onScope(SeriesScope.THIS) },
                            label = { Text(SeriesScope.THIS.label) },
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = seriesScope == SeriesScope.ALL,
                            onClick = { onScope(SeriesScope.ALL) },
                            label = { Text(SeriesScope.ALL.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// --- Formatting -------------------------------------------------------------------------------

private val MONTH_HEADER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val DAY_HEADER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private const val AGENDA_DAYS = 30L

private val SNOOZE_OPTIONS: List<Pair<Int, String>> = listOf(
    5 to "5 minutes",
    15 to "15 minutes",
    60 to "1 hour",
    1440 to "1 day",
)

private fun eventTimeLabel(event: CalendarEvent, zone: ZoneId): String {
    if (event.allDay) {
        val start = event.effectiveStart.displayDate(zone)
        val end = event.allDayEndInclusive()
        return when {
            start == null -> "All day"
            end == null || end == start -> "All day · ${start.format(DAY_HEADER)}"
            else -> "All day · ${start.format(SHORT_DATE)} – ${end.format(SHORT_DATE)}"
        }
    }
    val start = event.effectiveStart.localDateTime
        ?: event.effectiveStart.instant?.atZone(zone)?.toLocalDateTime()
    val end = event.effectiveEnd.localDateTime
        ?: event.effectiveEnd.instant?.atZone(zone)?.toLocalDateTime()
    return if (start != null && end != null) {
        "${start.toLocalTime().format(TIME_ONLY)} – ${end.toLocalTime().format(TIME_ONLY)}"
    } else {
        "Time unavailable"
    }
}

private fun formatInstant(instant: Instant, zone: ZoneId): String =
    instant.atZone(zone).format(INSTANT_LABEL)

private val INSTANT_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
