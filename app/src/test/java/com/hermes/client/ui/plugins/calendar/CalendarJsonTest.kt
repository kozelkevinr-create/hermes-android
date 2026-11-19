package com.hermes.client.ui.plugins.calendar

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalendarJsonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun events_timed_example_from_docs() {
        val dto = json.decodeFromString(EventsResponseDto.serializer(), TIMED_EVENT_LIST).toDomainEvents()

        assertEquals(1, dto.size)
        val event = dto.single()
        assertEquals("01a1d0d8-90b1-7b48-98a6-c044f3589baa", event.id)
        assertEquals("Design review", event.summary)
        assertFalse(event.allDay)
        assertEquals("2026-03-02T09:00:00", event.start.local)
        assertEquals("America/New_York", event.start.tz)
        assertEquals("2026-03-02T15:00:00Z", event.end.utc)
        assertEquals(3600L, event.endUtc!! - event.startUtc!!)
        assertEquals(1L, event.rev)
        assertEquals(1L, event.seriesRev)
        assertFalse(event.isRecurring)
        assertEquals(LocalDate.of(2026, 3, 2), event.displayStartDate(java.time.ZoneId.of("America/New_York")))
    }

    @Test
    fun events_all_day_example_has_exclusive_end() {
        val response = json.decodeFromString(EventResponseDto.serializer(), ALL_DAY_EVENT)
        val event = response.event!!.toDomain()

        assertTrue(event.allDay)
        assertEquals("2026-03-20", event.start.date)
        assertEquals("2026-03-21", event.end.date)
        assertNull(event.start.local)
        assertEquals(LocalDate.of(2026, 3, 20), event.effectiveStart.localDate)
        assertEquals(LocalDate.of(2026, 3, 20), event.allDayEndInclusive())
        assertEquals(listOf(LocalDate.of(2026, 3, 20)), event.coveredDates())
        assertTrue(event.occursOn(LocalDate.of(2026, 3, 20)))
        assertFalse(event.occursOn(LocalDate.of(2026, 3, 21)))
    }

    @Test
    fun events_recurring_occurrence_example_from_docs() {
        val events = json.decodeFromString(EventsResponseDto.serializer(), RECURRING_OCCURRENCE_LIST).toDomainEvents()
        val occurrence = events.single()

        assertTrue(occurrence.isRecurring)
        assertEquals("01a1d0d8-90d6-77c8-b842-7a2556537ab1", occurrence.seriesId)
        assertEquals(1L, occurrence.masterRev)
        assertEquals(1L, occurrence.seriesRev)
        assertEquals("T:1772460000", occurrence.recurrenceKey)
        assertEquals("2026-03-02T09:00:00", occurrence.occurrenceSelector())
        assertEquals("2026-03-02T09:00:00", occurrence.effectiveStart.local)
        assertEquals("FREQ=WEEKLY;BYDAY=MO;COUNT=3", occurrence.rrule)
    }

    @Test
    fun calendars_example_from_docs() {
        val calendars = json.decodeFromString(CalendarsResponseDto.serializer(), CALENDARS).calendars.map { it.toDomain() }

        assertEquals(1, calendars.size)
        val calendar = calendars.single()
        assertEquals("Work", calendar.displayName)
        assertEquals("#2F6FEB", calendar.color)
        assertEquals("America/New_York", calendar.defaultTz)
        assertFalse(calendar.readOnly)
        assertEquals(CalendarScope.PROFILE, calendar.scope)
    }

    @Test
    fun notifications_new_server_with_updated_at_and_server_time() {
        val page = json.decodeFromString(NotificationsResponseDto.serializer(), NOTIFICATIONS).toNotificationPage()

        assertEquals(2, page.notifications.size)
        assertEquals(4, page.total)
        assertEquals(1794505216L, page.serverTimeEpoch)
        val first = page.notifications.first()
        assertEquals("rmi_01a1d0d8-9137-73c3-aa40-b0b1c3cf6d0f", first.id)
        assertEquals("Profile 3", first.title)
        assertEquals("delivered", first.state)
        assertEquals(1800000600L, first.updatedAtEpoch)
        assertEquals(1800000600L, first.updatedAt?.epochSecond)
        assertEquals(1800000720L, first.occurrenceStart?.epochSecond)
    }

    @Test
    fun notifications_old_server_without_updated_at_or_server_time() {
        val page = json.decodeFromString(NotificationsResponseDto.serializer(), OLD_SERVER_NOTIFICATIONS).toNotificationPage()

        assertEquals(1, page.notifications.size)
        assertNull(page.serverTimeEpoch)
        val item = page.notifications.single()
        assertNull(item.updatedAt)
        assertNull(item.updatedAtEpoch)
        assertEquals(1800000120L, item.fireAt?.epochSecond)
    }

    @Test
    fun version_example_from_docs() {
        val version = json.decodeFromString(VersionDto.serializer(), VERSION).toDomain()
        assertEquals(1, version.apiVersion)
        assertEquals("0.0.1", version.pluginVersion)
        assertTrue(version.supportsIncrementalNotifications)
    }

    @Test
    fun version_tolerates_numeric_api_version_and_missing_fields() {
        val version = json.decodeFromString(VersionDto.serializer(), """{"ok":true,"api_version":2}""").toDomain()
        assertEquals(2, version.apiVersion)
        assertNull(version.pluginVersion)
    }

    @Test
    fun status_example_from_docs() {
        val status = json.decodeFromString(StatusDto.serializer(), STATUS).toDomain()
        assertEquals("hermes-calendar", status.plugin)
        assertEquals("0.0.1", status.version)
        assertFalse(status.profileDbExists)
        assertFalse(status.globalDbExists)
        assertEquals(true, status.sharedStoreOk == true)
    }

    @Test
    fun unknown_fields_are_ignored() {
        val parsed = json.decodeFromString(
            EventsResponseDto.serializer(),
            TIMED_EVENT_LIST.replaceFirst("\"events\": [", "\"future_top_level\": 7, \"events\": ["),
        )
        assertEquals(1, parsed.events.size)
    }

    @Test
    fun error_revision_conflict_maps_to_conflict_with_event_id() {
        val error = parseApiError(REVISION_CONFLICT, 409, json)
        assertTrue(error is CalendarError.Conflict)
        val conflict = error as CalendarError.Conflict
        assertEquals("01a1d0d8-90b1-7b48-98a6-c044f3589baa", conflict.eventId)
        assertEquals(1L, conflict.expectedRev)
    }

    @Test
    fun error_400_invalid_argument_maps_to_validation_with_server_message() {
        val error = parseApiError(INVALID_ARGUMENT, 400, json)
        assertTrue(error is CalendarError.Validation)
        assertEquals("summary is required", error.message)
    }

    @Test
    fun error_401_maps_to_unauthorized_even_without_envelope() {
        val error = parseApiError("""{"detail":"Invalid credentials"}""", 401, json)
        assertTrue(error is CalendarError.Unauthorized)
    }

    @Test
    fun error_404_maps_to_not_found() {
        val error = parseApiError(NOT_FOUND, 404, json)
        assertTrue(error is CalendarError.NotFound)
    }

    private fun EventsResponseDto.toDomainEvents(): List<CalendarEvent> = events.map { it.toDomain() }

    private fun NotificationsResponseDto.toNotificationPage(): NotificationPage = NotificationPage(
        notifications = notifications.map { it.toDomain() },
        total = total,
        serverTimeEpoch = serverTime?.epoch,
    )

    private companion object {
        const val TIMED_EVENT_LIST = """
        {
          "count": 1,
          "events": [
            {
              "all_day": false,
              "calendar_id": "cal_01a1d0d8-90a4-714d-872d-f49906a620e2",
              "categories": [],
              "classification": "PUBLIC",
              "created_utc": 1794505216,
              "deleted_utc": null,
              "description": "Bring the schema draft.",
              "duration_seconds": 3600,
              "end": {
                "date": null,
                "local": "2026-03-02T10:00:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T15:00:00Z"
              },
              "end_utc": 1772463600,
              "exdate": null,
              "floating": false,
              "id": "01a1d0d8-90b1-7b48-98a6-c044f3589baa",
              "is_override": false,
              "location": "Room 4B",
              "master_id": "01a1d0d8-90b1-7b48-98a6-c044f3589baa",
              "master_rev": 1,
              "master_rrule": null,
              "occurrence_end": {
                "date": null,
                "local": "2026-03-02T10:00:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T15:00:00Z"
              },
              "occurrence_end_utc": 1772463600,
              "occurrence_start": {
                "date": null,
                "local": "2026-03-02T09:00:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T14:00:00Z"
              },
              "occurrence_start_utc": 1772460000,
              "override_event_id": null,
              "owner_profile_id": "alice",
              "parent_id": null,
              "rdate": null,
              "recurrence_id_raw": null,
              "recurrence_key": null,
              "recurrence_range": null,
              "rev": 1,
              "rrule": null,
              "rrule_unsupported": null,
              "scope": "profile",
              "sequence": 0,
              "start": {
                "date": null,
                "local": "2026-03-02T09:00:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T14:00:00Z"
              },
              "start_utc": 1772460000,
              "status": "CONFIRMED",
              "summary": "Design review",
              "transparency": "OPAQUE",
              "uid": "01a1d0d8-90b1-77ec-9168-eebd6acbb178@hermes.local",
              "updated_utc": 1794505216,
              "url": null
            }
          ],
          "ok": true,
          "range": {
            "end": 1775016000,
            "start": 1772341200,
            "timezone": "America/New_York"
          },
          "recurrence_expanded": false,
          "scope": "profile"
        }
        """

        const val ALL_DAY_EVENT = """
        {
          "event": {
            "all_day": true,
            "calendar_id": "cal_01a1d0d8-90a4-714d-872d-f49906a620e2",
            "categories": [],
            "classification": "PUBLIC",
            "created_utc": 1794505216,
            "deleted_utc": null,
            "description": null,
            "duration_seconds": null,
            "end": {
              "date": "2026-03-21",
              "local": null,
              "tz": null,
              "utc": "2026-03-21T00:00:00Z"
            },
            "end_utc": 1774051200,
            "exdate": null,
            "floating": false,
            "id": "01a1d0d8-90e0-752d-9e9a-1638d0407ec4",
            "location": null,
            "owner_profile_id": "alice",
            "parent_id": null,
            "rdate": null,
            "recurrence_id_raw": null,
            "recurrence_key": null,
            "recurrence_range": null,
            "rev": 1,
            "rrule": null,
            "rrule_unsupported": null,
            "scope": "profile",
            "sequence": 0,
            "start": {
              "date": "2026-03-20",
              "local": null,
              "tz": null,
              "utc": "2026-03-20T00:00:00Z"
            },
            "start_utc": 1773964800,
            "status": "CONFIRMED",
            "summary": "Holiday",
            "transparency": "OPAQUE",
            "uid": "01a1d0d8-90e0-7f0a-80bc-8cad7677199b@hermes.local",
            "updated_utc": 1794505216,
            "url": null
          },
          "ok": true,
          "scope": "profile"
        }
        """

        const val RECURRING_OCCURRENCE_LIST = """
        {
          "count": 1,
          "events": [
            {
              "all_day": false,
              "calendar_id": "cal_01a1d0d8-90a4-714d-872d-f49906a620e2",
              "categories": [],
              "classification": "PUBLIC",
              "created_utc": 1794505216,
              "deleted_utc": null,
              "description": null,
              "duration_seconds": 1800,
              "end": {
                "date": null,
                "local": "2026-03-02T09:30:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T14:30:00Z"
              },
              "end_utc": 1772461800,
              "exdate": [
                "2026-03-16T09:00:00"
              ],
              "floating": false,
              "id": "01a1d0d8-90d6-77c8-b842-7a2556537ab1",
              "is_override": false,
              "location": null,
              "master_id": "01a1d0d8-90d6-77c8-b842-7a2556537ab1",
              "master_rev": 1,
              "master_rrule": "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
              "occurrence_end": {
                "date": null,
                "local": "2026-03-02T09:30:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T14:30:00Z"
              },
              "occurrence_end_utc": 1772461800,
              "occurrence_start": {
                "date": null,
                "local": "2026-03-02T09:00:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T14:00:00Z"
              },
              "occurrence_start_utc": 1772460000,
              "override_event_id": null,
              "owner_profile_id": "alice",
              "parent_id": null,
              "rdate": null,
              "recurrence_id_raw": null,
              "recurrence_key": "T:1772460000",
              "recurrence_range": null,
              "rev": 1,
              "rrule": "FREQ=WEEKLY;BYDAY=MO;COUNT=3",
              "rrule_unsupported": null,
              "scope": "profile",
              "sequence": 0,
              "start": {
                "date": null,
                "local": "2026-03-02T09:00:00",
                "tz": "America/New_York",
                "utc": "2026-03-02T14:00:00Z"
              },
              "start_utc": 1772460000,
              "status": "CONFIRMED",
              "summary": "Weekly sync",
              "transparency": "OPAQUE",
              "uid": "01a1d0d8-90d6-76b9-ab1a-100adc87da36@hermes.local",
              "updated_utc": 1794505216,
              "url": null
            }
          ],
          "ok": true,
          "range": {
            "end": 1772946000,
            "start": 1772341200,
            "timezone": "America/New_York"
          },
          "recurrence_expanded": true,
          "scope": "profile"
        }
        """

        const val CALENDARS = """
        {
          "calendars": [
            {
              "color": "#2F6FEB",
              "created_utc": 1794505216,
              "default_tz": "America/New_York",
              "description": "Team calendar",
              "id": "cal_01a1d0d8-90a4-714d-872d-f49906a620e2",
              "name": "Work",
              "owner_profile_id": "alice",
              "read_only": false,
              "remote_id": null,
              "scope": "profile",
              "source_id": "src_local",
              "updated_utc": 1794505216
            }
          ],
          "count": 1,
          "ok": true,
          "scope": "profile"
        }
        """

        const val NOTIFICATIONS = """
        {
          "count": 2,
          "limit": 2,
          "notifications": [
            {
              "attempt": 1,
              "event_id": "01a1d0d8-9123-7674-862a-39da11023693",
              "fire_at": "2027-01-15T08:02:00Z",
              "id": "rmi_01a1d0d8-9137-73c3-aa40-b0b1c3cf6d0f",
              "last_delivery": {
                "attempt": 1,
                "channel": "in_app",
                "error": null,
                "exit_code": null,
                "finished": "2027-01-15T08:10:00Z",
                "latency_ms": 0,
                "outcome": "success",
                "provider_id": null,
                "started": "2027-01-15T08:10:00Z"
              },
              "occurrence_start": "2027-01-15T08:12:00Z",
              "priority": "normal",
              "recurrence_key": "T:1800000720",
              "snooze_until": null,
              "state": "delivered",
              "store_id": "profile:alice",
              "title": "Profile 3",
              "updated_at": {
                "epoch": 1800000600,
                "iso": "2027-01-15T08:10:00Z"
              }
            },
            {
              "attempt": 1,
              "event_id": "01a1d0d8-9120-79b5-9ab5-9bce9cd0d4f1",
              "fire_at": "2027-01-15T08:01:00Z",
              "id": "rmi_01a1d0d8-9137-7203-a209-e82e5c96b8aa",
              "last_delivery": {
                "attempt": 1,
                "channel": "in_app",
                "error": null,
                "exit_code": null,
                "finished": "2027-01-15T08:10:00Z",
                "latency_ms": 0,
                "outcome": "success",
                "provider_id": null,
                "started": "2027-01-15T08:10:00Z"
              },
              "occurrence_start": "2027-01-15T08:11:00Z",
              "priority": "normal",
              "recurrence_key": "T:1800000660",
              "snooze_until": null,
              "state": "delivered",
              "store_id": "profile:alice",
              "title": "Profile 2",
              "updated_at": {
                "epoch": 1800000600,
                "iso": "2027-01-15T08:10:00Z"
              }
            }
          ],
          "offset": 0,
          "ok": true,
          "server_time": {
            "epoch": 1794505216,
            "iso": "2026-11-12T17:40:16Z"
          },
          "total": 4
        }
        """

        const val OLD_SERVER_NOTIFICATIONS = """
        {
          "count": 1,
          "limit": 1,
          "notifications": [
            {
              "attempt": 0,
              "event_id": "01a1d0d8-9123-7674-862a-39da11023693",
              "fire_at": "2027-01-15T08:02:00Z",
              "id": "rmi_01a1d0d8-9137-73c3-aa40-b0b1c3cf6d0f",
              "occurrence_start": "2027-01-15T08:12:00Z",
              "priority": "normal",
              "recurrence_key": "T:1800000720",
              "snooze_until": null,
              "state": "scheduled",
              "store_id": "profile:alice",
              "title": "Old server"
            }
          ],
          "offset": 0,
          "ok": true,
          "total": 1
        }
        """

        const val VERSION = """{"api_version":"1","ok":true,"plugin_version":"0.0.1"}"""

        const val STATUS = """
        {
          "databases": {
            "global": {
              "exists": false,
              "path": "/tmp/hermes-calendar-docs-tbfspfhp/root/plugin-data/hermes-calendar/calendar-global.db"
            },
            "profile": {
              "exists": false,
              "path": "/tmp/hermes-calendar-docs-tbfspfhp/root/profiles/alice/plugin-data/hermes-calendar/calendar.db"
            }
          },
          "global_data_dir": "/tmp/hermes-calendar-docs-tbfspfhp/root/plugin-data/hermes-calendar",
          "ok": true,
          "plugin": "hermes-calendar",
          "profile_data_dir": "/tmp/hermes-calendar-docs-tbfspfhp/root/profiles/alice/plugin-data/hermes-calendar",
          "shared_store": {
            "detail": "shared store is reachable, but only this profile has registered; shared status is unknown",
            "ok": true,
            "peers_seen": [],
            "shared": null,
            "writable": true
          },
          "version": "0.0.1"
        }
        """

        const val REVISION_CONFLICT = """
        {
          "code": "revision_conflict",
          "error": "event 01a1d0d8-90b1-7b48-98a6-c044f3589baa changed: expected rev 1, retry after re-reading",
          "event_id": "01a1d0d8-90b1-7b48-98a6-c044f3589baa",
          "expected_rev": 1,
          "ok": false
        }
        """

        const val INVALID_ARGUMENT = """
        {
          "code": "invalid_argument",
          "error": "summary is required",
          "ok": false
        }
        """

        const val NOT_FOUND = """
        {
          "code": "not_found",
          "error": "notification rmi_missing not found",
          "error_type": "NotificationNotFoundError",
          "ok": false
        }
        """
    }
}
