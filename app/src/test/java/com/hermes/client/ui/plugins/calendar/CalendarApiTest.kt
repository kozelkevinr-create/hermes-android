package com.hermes.client.ui.plugins.calendar

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "http://127.0.0.1:9119"

    private fun api(server: FakeServer): CalendarApi = CalendarApi(
        okHttp = OkHttpClient.Builder().addInterceptor(server).build(),
        json = json,
        credentials = FakeCredentialStore(GatewayConfig(baseUrl = baseUrl, token = "secret")),
    )

    @Test
    fun everyCallCarriesProfileQueryAndTokenHeader() = runTest {
        val server = FakeServer().apply {
            responder = { request ->
                when {
                    request.url.encodedPath.endsWith("/version") -> 200 to """{"ok":true,"api_version":"1","plugin_version":"0.0.1"}"""
                    request.url.encodedPath.endsWith("/calendars") -> 200 to """{"ok":true,"scope":"profile","calendars":[]}"""
                    request.url.encodedPath.endsWith("/notifications") -> 200 to """{"ok":true,"notifications":[]}"""
                    request.url.encodedPath.endsWith("/dismiss") -> 200 to """{"ok":true}"""
                    request.url.encodedPath.endsWith("/snooze") -> 200 to """{"ok":true}"""
                    else -> 200 to "{}"
                }
            }
        }
        val client = api(server)

        client.version("alice")
        client.calendars("alice", CalendarScope.PROFILE)
        client.notifications("alice")
        client.dismissNotification("alice", "rmi_1")
        client.snoozeNotification("alice", "rmi_1", 15)

        assertEquals(5, server.requests.size)
        server.requests.forEach { recorded ->
            assertEquals("alice", recorded.request.url.queryParameter("profile"))
            assertEquals("secret", recorded.request.header("X-Hermes-Session-Token"))
            assertTrue(recorded.request.url.toString().startsWith("$baseUrl/api/plugins/hermes-calendar/"))
        }
    }

    @Test
    fun eventsSendsRangeScopeTimezoneAndCalendar() = runTest {
        val server = FakeServer().apply {
            responder = { 200 to """{"ok":true,"scope":"profile","count":0,"events":[]}""" }
        }

        val result = api(server).events(
            profile = "alice",
            scope = CalendarScope.GLOBAL,
            start = "2026-03-01T00:00:00",
            end = "2026-04-01T00:00:00",
            timezone = "America/New_York",
            calendarId = "cal_global",
        )

        assertTrue(result is ApiResult.Success)
        val url = server.requests.single().request.url
        assertEquals("/api/plugins/hermes-calendar/events", url.encodedPath)
        assertEquals("alice", url.queryParameter("profile"))
        assertEquals("global", url.queryParameter("scope"))
        assertEquals("2026-03-01T00:00:00", url.queryParameter("start"))
        assertEquals("2026-04-01T00:00:00", url.queryParameter("end"))
        assertEquals("America/New_York", url.queryParameter("timezone"))
        assertEquals("cal_global", url.queryParameter("calendar_id"))
    }

    @Test
    fun version404DegradesToNotFound() = runTest {
        val server = FakeServer().apply {
            responder = { 404 to """{"detail":"Not Found"}""" }
        }

        val result = api(server).version("alice")

        assertTrue(result is ApiResult.Failure)
        assertTrue((result as ApiResult.Failure).error is CalendarError.NotFound)
    }

    @Test
    fun revisionConflictIsMappedWithEventIdAndRev() = runTest {
        val server = FakeServer().apply {
            responder = {
                409 to """
                {
                  "code": "revision_conflict",
                  "error": "event 01a1d0d8-90b1-7b48-98a6-c044f3589baa changed: expected rev 1, retry after re-reading",
                  "event_id": "01a1d0d8-90b1-7b48-98a6-c044f3589baa",
                  "expected_rev": 1,
                  "ok": false
                }
                """
            }
        }

        val result = api(server).updateEvent(
            "alice",
            EventPatch(
                eventId = "01a1d0d8-90b1-7b48-98a6-c044f3589baa",
                expectedRev = 1,
                scope = CalendarScope.PROFILE,
                summary = "Stale rewrite",
                start = "2026-03-02T09:00:00",
                end = "2026-03-02T10:00:00",
                timezone = "America/New_York",
                description = null,
                location = null,
                url = null,
                rrule = null,
                occurrenceStart = null,
                seriesScope = SeriesScope.ALL,
            ),
        )

        assertTrue(result is ApiResult.Failure)
        val error = (result as ApiResult.Failure).error
        assertTrue(error is CalendarError.Conflict)
        val conflict = error as CalendarError.Conflict
        assertEquals("01a1d0d8-90b1-7b48-98a6-c044f3589baa", conflict.eventId)
        assertEquals(1L, conflict.expectedRev)

        val recorded = server.requests.single()
        assertEquals("PATCH", recorded.request.method)
        assertEquals("/api/plugins/hermes-calendar/events/01a1d0d8-90b1-7b48-98a6-c044f3589baa", recorded.request.url.encodedPath)
        assertEquals("alice", recorded.request.url.queryParameter("profile"))
        val body = recorded.bodyJson(json)
        assertEquals("1", body["expected_rev"]?.jsonPrimitive?.content)
    }

    @Test
    fun validation400CarriesTheServerMessage() = runTest {
        val server = FakeServer().apply {
            responder = {
                400 to """{"code":"invalid_argument","error":"summary is required","ok":false}"""
            }
        }

        val result = api(server).createEvent(
            "alice",
            EventDraft(
                calendarId = "cal",
                summary = "",
                start = "2026-03-02T09:00:00",
                end = "2026-03-02T10:00:00",
                timezone = "America/New_York",
                description = null,
                location = null,
                url = null,
                rrule = null,
                allDay = false,
                scope = CalendarScope.PROFILE,
            ),
        )

        val error = (result as ApiResult.Failure).error
        assertTrue(error is CalendarError.Validation)
        assertEquals("summary is required", error.message)
    }

    @Test
    fun unauthorizedMapsToUnauthorized() = runTest {
        val server = FakeServer().apply { responder = { 401 to """{"detail":"Invalid credentials"}""" } }
        val result = api(server).calendars("alice", CalendarScope.PROFILE)
        assertTrue((result as ApiResult.Failure).error is CalendarError.Unauthorized)
    }

    @Test
    fun deleteSendsExpectedRevOccurrenceAndSeriesScope() = runTest {
        val server = FakeServer().apply {
            responder = { 200 to """{"ok":true,"deleted":true,"already_deleted":false}""" }
        }

        val result = api(server).deleteEvent(
            profile = "alice",
            scope = CalendarScope.PROFILE,
            eventId = "master-1",
            expectedRev = 2,
            occurrenceStart = "2026-03-02T09:00:00",
            seriesScope = SeriesScope.THIS,
        )

        assertTrue(result is ApiResult.Success)
        val url = server.requests.single().request.url
        assertEquals("DELETE", server.requests.single().request.method)
        assertEquals("2", url.queryParameter("expected_rev"))
        assertEquals("this", url.queryParameter("series_scope"))
        assertEquals("2026-03-02T09:00:00", url.queryParameter("occurrence_start"))
        assertEquals("alice", url.queryParameter("profile"))
    }

    @Test
    fun allDayCreateOmitsTimezoneAndSendsExclusiveDates() = runTest {
        val server = FakeServer().apply {
            responder = {
                200 to """{"ok":true,"scope":"profile","event":{"id":"e1","calendar_id":"cal","all_day":true,"summary":"Holiday","start":{"date":"2026-03-20"},"end":{"date":"2026-03-21"},"rev":1}}"""
            }
        }

        api(server).createEvent(
            "alice",
            EventDraft(
                calendarId = "cal",
                summary = "Holiday",
                start = "2026-03-20",
                end = "2026-03-21",
                timezone = null,
                description = null,
                location = null,
                url = null,
                rrule = null,
                allDay = true,
                scope = CalendarScope.PROFILE,
            ),
        )

        val body = server.requests.single().bodyJson(json)
        assertTrue(body["all_day"]?.jsonPrimitive?.boolean == true)
        assertEquals("2026-03-20", body["start"]?.jsonPrimitive?.content)
        assertEquals("2026-03-21", body["end"]?.jsonPrimitive?.content)
        assertNull(body["timezone"])
        assertEquals("profile", body["scope"]?.jsonPrimitive?.content)
    }

    @Test
    fun notificationsSinceIsSentOnlyWhenProvided() = runTest {
        val server = FakeServer().apply {
            responder = { 200 to """{"ok":true,"notifications":[],"server_time":{"epoch":100,"iso":"2026-01-01T00:00:00Z"}}""" }
        }
        val client = api(server)

        client.notifications("alice", since = 100)
        assertNotNull(server.requests.last().request.url.queryParameter("since"))
        assertEquals("100", server.requests.last().request.url.queryParameter("since"))

        client.notifications("alice")
        assertNull(server.requests.last().request.url.queryParameter("since"))
    }

    @Test
    fun notificationsOldServerPayloadParsesWithoutServerTimeOrUpdatedAt() = runTest {
        val server = FakeServer().apply {
            responder = {
                200 to """
                {
                  "count": 1,
                  "limit": 1,
                  "notifications": [
                    {
                      "attempt": 0,
                      "event_id": "e1",
                      "fire_at": "2027-01-15T08:02:00Z",
                      "id": "rmi_1",
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
            }
        }

        val result = api(server).notifications("alice")

        assertTrue(result is ApiResult.Success)
        val page = result.valueOrNull()!!
        assertNull(page.serverTimeEpoch)
        assertEquals(1, page.notifications.size)
        assertNull(page.notifications.single().updatedAt)
        assertEquals(1800000120L, page.notifications.single().fireAt?.epochSecond)
    }

    @Test
    fun createUpdateAndDismissResponsesMapBackToDomain() = runTest {
        val server = FakeServer().apply {
            responder = { request ->
                when {
                    request.url.encodedPath.endsWith("/dismiss") -> 200 to """
                    {"ok":true,"notification":{"id":"rmi_1","title":"Profile 3","state":"acknowledged","fire_at":"2027-01-15T08:02:00Z","updated_at":{"epoch":1794505216,"iso":"2026-11-12T17:40:16Z"}}}
                    """.trimIndent()
                    else -> 200 to """
                    {"ok":true,"event":{"id":"e1","calendar_id":"cal","summary":"Design review","start":{"local":"2026-03-02T09:00:00","tz":"America/New_York","utc":"2026-03-02T14:00:00Z"},"end":{"local":"2026-03-02T10:00:00","tz":"America/New_York","utc":"2026-03-02T15:00:00Z"},"rev":2,"master_rev":2}}
                    """.trimIndent()
                }
            }
        }
        val client = api(server)

        val created = client.createEvent(
            "alice",
            EventDraft(
                calendarId = "cal",
                summary = "Design review",
                start = "2026-03-02T09:00:00",
                end = "2026-03-02T10:00:00",
                timezone = "America/New_York",
                description = null,
                location = null,
                url = null,
                rrule = null,
                allDay = false,
                scope = CalendarScope.PROFILE,
            ),
        )
        assertEquals("Design review", created.valueOrNull()!!.summary)
        assertEquals(2L, created.valueOrNull()!!.seriesRev)

        val dismissed = client.dismissNotification("alice", "rmi_1")
        val notification = dismissed.valueOrNull()
        assertNotNull(notification)
        assertEquals("acknowledged", notification?.state)
        assertEquals(1794505216L, notification?.updatedAtEpoch)
    }

    @Test
    fun parseFailureOnMalformedSuccessBody() = runTest {
        val server = FakeServer().apply { responder = { 200 to """{"ok":true,"events":""" } }
        val result = api(server).events("alice", CalendarScope.PROFILE, "a", "b", "UTC")
        assertTrue((result as ApiResult.Failure).error is CalendarError.Parse)
    }

    @Test
    fun networkFailureIsReported() = runTest {
        val client = CalendarApi(
            okHttp = OkHttpClient(),
            json = json,
            credentials = FakeCredentialStore(null),
        )
        val result = client.version("alice")
        assertTrue((result as ApiResult.Failure).error is CalendarError.Network)
    }

    private class FakeCredentialStore(private val config: GatewayConfig?) : CredentialStore {
        private var stored: GatewayConfig? = config
        override fun load(): GatewayConfig? = stored
        override fun save(config: GatewayConfig) { stored = config }
        override fun clear() { stored = null }
    }

    private class FakeServer : Interceptor {
        val requests = mutableListOf<RecordedRequest>()
        var responder: (Request) -> Pair<Int, String> = { 200 to "{}" }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val bodyBuffer = Buffer()
            request.body?.writeTo(bodyBuffer)
            requests += RecordedRequest(request, bodyBuffer.readUtf8())
            val (code, text) = responder(request)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Error")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private data class RecordedRequest(val request: Request, val body: String) {
        fun bodyJson(json: Json) = json.parseToJsonElement(body).jsonObject
    }
}
