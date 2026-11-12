package com.hermes.client.ui.plugins.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Models for the hermes-calendar dashboard plugin.
 *
 * The server contract is `docs/api/dashboard-api.md` in the plugin repository. Response DTOs are
 * deliberately lenient: [Json] is configured with `ignoreUnknownKeys`, every optional field has a
 * default, and the phone's older server may omit `updated_at`/`server_time` entirely.
 */

// --- Time helpers -----------------------------------------------------------------------------

/** Local wall-clock format the plugin accepts for timed events (no offset; zone is explicit). */
internal val CALENDAR_LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

/** Compact RFC 5545 UTC date-time, used inside RRULE UNTIL values. */
internal val CALENDAR_UTC_DATE_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

/** All-day dates are ISO dates, and RRULE UNTIL uses the compact `yyyyMMdd` form. */
internal val CALENDAR_BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

internal fun LocalDate.toLocalDateTimeString(): String = atStartOfDay().format(CALENDAR_LOCAL_DATE_TIME)

/** Parse a server instant (RFC 3339) tolerantly; the field is optional on older servers. */
internal fun parseInstantOrNull(value: String?): Instant? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
}

// --- Calendar DTOs ----------------------------------------------------------------------------

@Serializable
internal data class CalendarDto(
    val id: String = "",
    @SerialName("source_id") val sourceId: String? = null,
    val scope: String? = null,
    @SerialName("owner_profile_id") val ownerProfileId: String? = null,
    @SerialName("remote_id") val remoteId: String? = null,
    val name: String = "",
    val description: String? = null,
    val color: String? = null,
    @SerialName("default_tz") val defaultTz: String? = null,
    @SerialName("read_only") val readOnly: Boolean = false,
    @SerialName("created_utc") val createdUtc: Long? = null,
    @SerialName("updated_utc") val updatedUtc: Long? = null,
)

@Serializable
internal data class CalendarsResponseDto(
    val ok: Boolean = true,
    val scope: String? = null,
    val count: Int? = null,
    val calendars: List<CalendarDto> = emptyList(),
)

@Serializable
internal data class CalendarResponseDto(
    val ok: Boolean = true,
    val scope: String? = null,
    val calendar: CalendarDto? = null,
)

// --- Event DTOs -------------------------------------------------------------------------------

@Serializable
internal data class EventTimeDto(
    val date: String? = null,
    val local: String? = null,
    val tz: String? = null,
    val utc: String? = null,
)

@Serializable
internal data class EventDto(
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("calendar_id") val calendarId: String = "",
    val categories: List<String> = emptyList(),
    val classification: String? = null,
    @SerialName("created_utc") val createdUtc: Long? = null,
    @SerialName("deleted_utc") val deletedUtc: Long? = null,
    val description: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
    val end: EventTimeDto? = null,
    @SerialName("end_utc") val endUtc: Long? = null,
    val exdate: List<String>? = null,
    val floating: Boolean = false,
    val id: String = "",
    @SerialName("is_override") val isOverride: Boolean = false,
    val location: String? = null,
    @SerialName("master_id") val masterId: String? = null,
    @SerialName("master_rev") val masterRev: Long? = null,
    @SerialName("master_rrule") val masterRrule: String? = null,
    @SerialName("occurrence_end") val occurrenceEnd: EventTimeDto? = null,
    @SerialName("occurrence_end_utc") val occurrenceEndUtc: Long? = null,
    @SerialName("occurrence_start") val occurrenceStart: EventTimeDto? = null,
    @SerialName("occurrence_start_utc") val occurrenceStartUtc: Long? = null,
    @SerialName("override_event_id") val overrideEventId: String? = null,
    @SerialName("owner_profile_id") val ownerProfileId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    val rdate: List<String>? = null,
    @SerialName("recurrence_id_raw") val recurrenceIdRaw: String? = null,
    @SerialName("recurrence_key") val recurrenceKey: String? = null,
    @SerialName("recurrence_range") val recurrenceRange: String? = null,
    val rev: Long = 1,
    val rrule: String? = null,
    @SerialName("rrule_unsupported") val rruleUnsupported: String? = null,
    val scope: String? = null,
    val sequence: Long = 0,
    val start: EventTimeDto? = null,
    @SerialName("start_utc") val startUtc: Long? = null,
    val status: String? = null,
    val summary: String = "",
    val transparency: String? = null,
    val uid: String? = null,
    @SerialName("updated_utc") val updatedUtc: Long? = null,
    val url: String? = null,
)

@Serializable
internal data class EventsResponseDto(
    val ok: Boolean = true,
    val scope: String? = null,
    val count: Int? = null,
    @SerialName("recurrence_expanded") val recurrenceExpanded: Boolean = false,
    @SerialName("recurrence_warnings") val recurrenceWarnings: List<String> = emptyList(),
    val events: List<EventDto> = emptyList(),
    val range: EventRangeDto? = null,
)

@Serializable
internal data class EventRangeDto(
    val start: Long? = null,
    val end: Long? = null,
    val timezone: String? = null,
)

@Serializable
internal data class EventResponseDto(
    val ok: Boolean = true,
    val scope: String? = null,
    val event: EventDto? = null,
)

// --- Notification DTOs ------------------------------------------------------------------------

@Serializable
internal data class ServerTimeDto(
    val epoch: Long? = null,
    val iso: String? = null,
)

@Serializable
internal data class NotificationDeliveryDto(
    val channel: String? = null,
    val attempt: Long? = null,
    val outcome: String? = null,
    @SerialName("exit_code") val exitCode: Long? = null,
    @SerialName("provider_id") val providerId: String? = null,
    val error: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    val started: String? = null,
    val finished: String? = null,
)

@Serializable
internal data class NotificationDto(
    val id: String = "",
    val title: String = "",
    val state: String = "scheduled",
    val priority: String? = null,
    @SerialName("store_id") val storeId: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("recurrence_key") val recurrenceKey: String? = null,
    @SerialName("occurrence_start") val occurrenceStart: String? = null,
    @SerialName("fire_at") val fireAt: String? = null,
    @SerialName("snooze_until") val snoozeUntil: String? = null,
    val attempt: Long = 0,
    @SerialName("updated_at") val updatedAt: ServerTimeDto? = null,
    @SerialName("last_delivery") val lastDelivery: NotificationDeliveryDto? = null,
)

@Serializable
internal data class NotificationsResponseDto(
    val ok: Boolean = true,
    val count: Int? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    @SerialName("server_time") val serverTime: ServerTimeDto? = null,
    val notifications: List<NotificationDto> = emptyList(),
)

@Serializable
internal data class NotificationResponseDto(
    val ok: Boolean = true,
    val notification: NotificationDto? = null,
)

// --- Version / status DTOs --------------------------------------------------------------------

@Serializable
internal data class VersionDto(
    val ok: Boolean = true,
    @SerialName("api_version") val apiVersion: JsonElement? = null,
    @SerialName("plugin_version") val pluginVersion: String? = null,
)

@Serializable
internal data class StatusDatabaseDto(
    val exists: Boolean = false,
    val path: String? = null,
)

@Serializable
internal data class StatusDatabasesDto(
    val profile: StatusDatabaseDto? = null,
    val global: StatusDatabaseDto? = null,
)

@Serializable
internal data class SharedStoreDto(
    val ok: Boolean? = null,
    val writable: Boolean? = null,
    val detail: String? = null,
    val shared: String? = null,
    @SerialName("peers_seen") val peersSeen: List<String> = emptyList(),
)

@Serializable
internal data class StatusDto(
    val ok: Boolean = true,
    val plugin: String? = null,
    val version: String? = null,
    val databases: StatusDatabasesDto? = null,
    @SerialName("profile_data_dir") val profileDataDir: String? = null,
    @SerialName("global_data_dir") val globalDataDir: String? = null,
    @SerialName("shared_store") val sharedStore: SharedStoreDto? = null,
)

// --- Error envelope ---------------------------------------------------------------------------

@Serializable
internal data class ApiErrorDto(
    val ok: Boolean = false,
    val error: String? = null,
    val code: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("expected_rev") val expectedRev: Long? = null,
    @SerialName("error_type") val errorType: String? = null,
)

// --- Domain -----------------------------------------------------------------------------------

/** Store scope. `profile` is the signed-in profile's private store; `global` is shared. */
enum class CalendarScope(val wire: String, val label: String) {
    PROFILE("profile", "This profile"),
    GLOBAL("global", "Global (shared)");

    companion object {
        fun fromWire(value: String?): CalendarScope =
            if (value.equals("global", ignoreCase = true)) GLOBAL else PROFILE
    }
}

/** Recurring-series edit/delete scope. */
enum class SeriesScope(val wire: String, val label: String) {
    THIS("this", "This event"),
    ALL("all", "All events");

    companion object {
        fun fromWire(value: String?): SeriesScope =
            if (value.equals("this", ignoreCase = true)) THIS else ALL
    }
}

data class Calendar(
    val id: String,
    val name: String,
    val description: String?,
    val color: String?,
    val defaultTz: String?,
    val readOnly: Boolean,
    val scope: CalendarScope,
) {
    val displayName: String get() = name.ifBlank { "Calendar" }
}

/**
 * One time from the server: all-day events carry [date] (exclusive on `end`), timed events carry
 * [local] + [tz], and both carry [utc]. Any component may be missing on older servers.
 */
data class EventTime(
    val date: String? = null,
    val local: String? = null,
    val tz: String? = null,
    val utc: String? = null,
) {
    val zoneId: ZoneId
        get() = tz?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()

    val localDateTime: LocalDateTime?
        get() = local?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    val localDate: LocalDate?
        get() = date?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val instant: Instant?
        get() = utc?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: localDateTime?.let { runCatching { it.atZone(zoneId).toInstant() }.getOrNull() }

    fun displayDate(fallbackZone: ZoneId): LocalDate? =
        localDate ?: localDateTime?.toLocalDate() ?: instant?.atZone(fallbackZone)?.toLocalDate()
}

data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val scope: CalendarScope,
    val summary: String,
    val description: String?,
    val location: String?,
    val url: String?,
    val allDay: Boolean,
    val start: EventTime,
    val end: EventTime,
    val startUtc: Long?,
    val endUtc: Long?,
    val rrule: String?,
    val rev: Long,
    val masterId: String?,
    val masterRev: Long?,
    val masterRrule: String?,
    val isOverride: Boolean,
    val parentId: String?,
    val occurrenceStart: EventTime?,
    val occurrenceEnd: EventTime?,
    val recurrenceKey: String?,
    val status: String?,
) {
    val isRecurring: Boolean get() = !masterRrule.isNullOrBlank() || !rrule.isNullOrBlank()

    /** Current master revision; PATCH/DELETE `expected_rev` always refers to it. */
    val seriesRev: Long get() = masterRev ?: rev

    /** Id to PATCH/DELETE; expanded occurrences use the series master. */
    val seriesId: String get() = masterId?.takeIf { it.isNotBlank() } ?: id

    val effectiveStart: EventTime get() = occurrenceStart ?: start
    val effectiveEnd: EventTime get() = occurrenceEnd ?: end

    /** Original occurrence start for `series_scope=this`: local time or all-day date. */
    fun occurrenceSelector(): String? {
        val time = occurrenceStart ?: return null
        return if (allDay) time.date else time.local
    }

    val stableKey: String
        get() = "$id|${recurrenceKey.orEmpty()}|${effectiveStart.utc.orEmpty()}|${effectiveStart.local.orEmpty()}|${effectiveStart.date.orEmpty()}"

    fun displayStart(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
        effectiveStart.localDateTime ?: effectiveStart.instant?.atZone(zone)?.toLocalDateTime()

    fun displayStartDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        effectiveStart.displayDate(zone)

    fun displayEndDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        effectiveEnd.displayDate(zone)

    /** Inclusive end date for an all-day event (the server's end date is exclusive). */
    fun allDayEndInclusive(): LocalDate? =
        effectiveEnd.localDate?.minusDays(1) ?: effectiveEnd.localDate

    /** Calendar days this event covers, used for month dots and day filtering. */
    fun coveredDates(zone: ZoneId = ZoneId.systemDefault()): List<LocalDate> {
        if (allDay) {
            val first = effectiveStart.localDate ?: return emptyList()
            val endExclusive = effectiveEnd.localDate ?: first.plusDays(1)
            val last = if (endExclusive.isAfter(first)) endExclusive.minusDays(1) else first
            return datesBetween(first, last)
        }
        val first = effectiveStart.displayDate(zone) ?: return emptyList()
        val end = effectiveEnd
        val endInstant = end.instant
        val endLocalDateTime = end.localDateTime
        val endLocalDate = end.localDate
        val last = when {
            endInstant != null -> {
                val atZone = endInstant.atZone(zone)
                if (atZone.toLocalTime() == LocalTime.MIDNIGHT) atZone.toLocalDate().minusDays(1) else atZone.toLocalDate()
            }
            endLocalDateTime != null -> endLocalDateTime.toLocalDate()
            endLocalDate != null -> endLocalDate
            else -> first
        }
        return datesBetween(first, if (last.isBefore(first)) first else last)
    }

    fun occursOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        date in coveredDates(zone)
}

private fun datesBetween(first: LocalDate, last: LocalDate): List<LocalDate> {
    if (last.isBefore(first)) return emptyList()
    val out = ArrayList<LocalDate>()
    var day = first
    while (!day.isAfter(last) && out.size < 62) {
        out += day
        day = day.plusDays(1)
    }
    return out
}

data class CalendarNotification(
    val id: String,
    val title: String,
    val state: String,
    val priority: String?,
    val storeId: String?,
    val eventId: String?,
    val recurrenceKey: String?,
    val occurrenceStart: Instant?,
    val fireAt: Instant?,
    val snoozeUntil: Instant?,
    val attempt: Long,
    val updatedAt: Instant?,
    val updatedAtEpoch: Long?,
) {
    val canDismiss: Boolean get() = state != "acknowledged"
    val canSnooze: Boolean get() = state != "acknowledged"
}

data class NotificationPage(
    val notifications: List<CalendarNotification>,
    val total: Int?,
    val serverTimeEpoch: Long?,
)

data class CalendarVersion(
    val apiVersion: Int,
    val pluginVersion: String?,
) {
    /** `since` polling and `server_time` exist from api_version 1. */
    val supportsIncrementalNotifications: Boolean get() = apiVersion >= 1
}

data class CalendarPluginStatus(
    val plugin: String?,
    val version: String?,
    val profileDataDir: String?,
    val globalDataDir: String?,
    val profileDbExists: Boolean,
    val globalDbExists: Boolean,
    val sharedStoreOk: Boolean?,
)

// --- Request models ---------------------------------------------------------------------------

/** A new event. Times are the server's accepted wall-clock strings (or dates for all-day). */
data class EventDraft(
    val calendarId: String,
    val summary: String,
    val start: String,
    val end: String,
    val timezone: String?,
    val description: String?,
    val location: String?,
    val url: String?,
    val rrule: String?,
    val allDay: Boolean,
    val scope: CalendarScope,
)

/** An edit. `all_day` is intentionally absent: the contract does not allow patching it. */
data class EventPatch(
    val eventId: String,
    val expectedRev: Long,
    val scope: CalendarScope,
    val summary: String,
    val start: String,
    val end: String,
    val timezone: String?,
    val description: String?,
    val location: String?,
    val url: String?,
    val rrule: String?,
    val occurrenceStart: String?,
    val seriesScope: SeriesScope,
)

// --- DTO mappers ------------------------------------------------------------------------------

internal fun CalendarDto.toDomain(): Calendar = Calendar(
    id = id,
    name = name,
    description = description,
    color = color,
    defaultTz = defaultTz,
    readOnly = readOnly,
    scope = CalendarScope.fromWire(scope),
)

internal fun EventTimeDto?.toDomain(): EventTime =
    if (this == null) EventTime() else EventTime(date = date, local = local, tz = tz, utc = utc)

internal fun EventDto.toDomain(): CalendarEvent = CalendarEvent(
    id = id,
    calendarId = calendarId,
    scope = CalendarScope.fromWire(scope),
    summary = summary,
    description = description,
    location = location,
    url = url,
    allDay = allDay,
    start = start.toDomain(),
    end = end.toDomain(),
    startUtc = startUtc,
    endUtc = endUtc,
    rrule = rrule,
    rev = rev,
    masterId = masterId,
    masterRev = masterRev,
    masterRrule = masterRrule,
    isOverride = isOverride,
    parentId = parentId,
    occurrenceStart = occurrenceStart?.toDomain(),
    occurrenceEnd = occurrenceEnd?.toDomain(),
    recurrenceKey = recurrenceKey,
    status = status,
)

internal fun NotificationDto.toDomain(): CalendarNotification = CalendarNotification(
    id = id,
    title = title,
    state = state,
    priority = priority,
    storeId = storeId,
    eventId = eventId,
    recurrenceKey = recurrenceKey,
    occurrenceStart = parseInstantOrNull(occurrenceStart),
    fireAt = parseInstantOrNull(fireAt),
    snoozeUntil = parseInstantOrNull(snoozeUntil),
    attempt = attempt,
    updatedAt = updatedAt?.let { parseInstantOrNull(it.iso) ?: it.epoch?.let(Instant::ofEpochSecond) },
    updatedAtEpoch = updatedAt?.epoch,
)

internal fun VersionDto.toDomain(): CalendarVersion {
    val api = runCatching { apiVersion?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull() ?: 0
    return CalendarVersion(apiVersion = api, pluginVersion = pluginVersion)
}

internal fun StatusDto.toDomain(): CalendarPluginStatus = CalendarPluginStatus(
    plugin = plugin,
    version = version,
    profileDataDir = profileDataDir,
    globalDataDir = globalDataDir,
    profileDbExists = databases?.profile?.exists == true,
    globalDbExists = databases?.global?.exists == true,
    sharedStoreOk = sharedStore?.ok,
)

// --- Recurrence presets -----------------------------------------------------------------------

enum class RecurrencePreset(val label: String, val frequency: String?) {
    NONE("None", null),
    DAILY("Daily", "DAILY"),
    WEEKLY("Weekly", "WEEKLY"),
    MONTHLY("Monthly", "MONTHLY"),
    YEARLY("Yearly", "YEARLY");

    companion object {
        fun fromFrequency(frequency: String?): RecurrencePreset =
            entries.firstOrNull { it.frequency != null && it.frequency.equals(frequency, ignoreCase = true) } ?: NONE
    }
}

data class RruleSpec(
    val preset: RecurrencePreset = RecurrencePreset.NONE,
    val count: Int? = null,
    val until: LocalDate? = null,
)

/**
 * Map the editor's preset to the contract's RRULE subset. The plugin supports FREQ, INTERVAL,
 * COUNT, UNTIL, BYDAY, BYMONTHDAY, BYMONTH, BYSETPOS and WKST; this editor only emits FREQ plus an
 * optional COUNT or UNTIL. UNTIL is a UTC date-time for timed events and a date for all-day events.
 */
fun buildRrule(spec: RruleSpec, allDay: Boolean, zone: ZoneId): String? {
    val frequency = spec.preset.frequency ?: return null
    val builder = StringBuilder("FREQ=").append(frequency)
    val count = spec.count?.takeIf { it > 0 }
    when {
        count != null -> builder.append(";COUNT=").append(count)
        spec.until != null && allDay -> builder.append(";UNTIL=").append(spec.until.format(CALENDAR_BASIC_DATE))
        spec.until != null -> builder.append(";UNTIL=").append(
            spec.until.atTime(23, 59, 59).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(CALENDAR_UTC_DATE_TIME),
        )
    }
    return builder.toString()
}

/** Parse the subset this editor emits so an existing series opens with the right preset. */
fun parseRrule(rrule: String?): RruleSpec {
    val text = rrule?.trim()?.removePrefix("RRULE:")?.takeIf { it.isNotEmpty() } ?: return RruleSpec()
    val parts = text.split(';').mapNotNull { part ->
        val split = part.split('=', limit = 2)
        if (split.size == 2) split[0].trim().uppercase() to split[1].trim() else null
    }.toMap()
    val preset = RecurrencePreset.fromFrequency(parts["FREQ"])
    val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
    val until = parts["UNTIL"]?.let(::parseCompactUntil)
    return RruleSpec(preset = preset, count = count, until = until)
}

private fun parseCompactUntil(value: String): LocalDate? {
    val digits = value.take(8)
    if (digits.length != 8) return null
    return runCatching { LocalDate.parse(digits, CALENDAR_BASIC_DATE) }.getOrNull()
}

// --- Grid helpers -----------------------------------------------------------------------------

data class MonthCell(val date: LocalDate, val inMonth: Boolean)

data class MonthGrid(val month: java.time.YearMonth, val weeks: List<List<MonthCell>>)

/**
 * A stable six-week grid starting on the locale's first day of week. Pure date arithmetic, so it
 * is correct across leap years and DST transitions (dates, not instants).
 */
fun buildMonthGrid(month: java.time.YearMonth, firstDayOfWeek: DayOfWeek): MonthGrid {
    val first = month.atDay(1)
    val offset = Math.floorMod(first.dayOfWeek.value - firstDayOfWeek.value, 7)
    val gridStart = first.minusDays(offset.toLong())
    val weeks = (0 until 6).map { week ->
        (0 until 7).map { day ->
            val date = gridStart.plusDays((week * 7 + day).toLong())
            MonthCell(date = date, inMonth = date.month == month.month && date.year == month.year)
        }
    }
    return MonthGrid(month, weeks)
}

fun weekdayLabels(firstDayOfWeek: DayOfWeek, locale: Locale = Locale.getDefault()): List<String> =
    (0 until 7).map { index ->
        DayOfWeek.of((firstDayOfWeek.value - 1 + index) % 7 + 1).getDisplayName(TextStyle.SHORT, locale)
    }

internal fun firstDayOfWeekFor(locale: Locale = Locale.getDefault()): DayOfWeek =
    java.time.temporal.WeekFields.of(locale).firstDayOfWeek
