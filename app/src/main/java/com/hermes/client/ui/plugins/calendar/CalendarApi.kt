package com.hermes.client.ui.plugins.calendar

import com.hermes.client.data.auth.CredentialStore
import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the hermes-calendar dashboard plugin.
 *
 * Reuses the app's shared [OkHttpClient] (its cookie jar carries gated-dashboard session cookies,
 * and its authenticator retries a 401) and the same token header/base-URL approach as
 * [com.hermes.client.data.network.HermesRestApi]. Every request carries `?profile=<active name>`
 * because Hermes routes plugin calls per profile.
 *
 * No request/response payloads, tokens, or cookies are logged.
 */
@Singleton
class CalendarApi @Inject constructor(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val credentials: CredentialStore,
) {
    private val basePath = "/api/plugins/hermes-calendar"

    private enum class Method { GET, POST, PATCH, DELETE }

    // --- Reads --------------------------------------------------------------------------------

    suspend fun version(profile: String): ApiResult<CalendarVersion> =
        request(Method.GET, "/version", profileParams(profile)) {
            json.decodeFromString(VersionDto.serializer(), it).toDomain()
        }

    suspend fun status(profile: String): ApiResult<CalendarPluginStatus> =
        request(Method.GET, "/status", profileParams(profile)) {
            json.decodeFromString(StatusDto.serializer(), it).toDomain()
        }

    suspend fun calendars(profile: String, scope: CalendarScope): ApiResult<List<Calendar>> =
        request(
            Method.GET,
            "/calendars",
            listOf("profile" to profile, "scope" to scope.wire),
        ) { body ->
            json.decodeFromString(CalendarsResponseDto.serializer(), body).calendars.map { it.toDomain() }
        }

    suspend fun events(
        profile: String,
        scope: CalendarScope,
        start: String,
        end: String,
        timezone: String,
        calendarId: String? = null,
    ): ApiResult<List<CalendarEvent>> = request(
        Method.GET,
        "/events",
        listOf(
            "profile" to profile,
            "scope" to scope.wire,
            "start" to start,
            "end" to end,
            "timezone" to timezone,
            "calendar_id" to calendarId,
        ),
    ) { body ->
        json.decodeFromString(EventsResponseDto.serializer(), body).events.map { it.toDomain() }
    }

    suspend fun notifications(
        profile: String,
        limit: Int = 50,
        offset: Int = 0,
        includeDismissed: Boolean = false,
        since: Long? = null,
    ): ApiResult<NotificationPage> = request(
        Method.GET,
        "/notifications",
        listOf(
            "profile" to profile,
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "include_dismissed" to includeDismissed.toString(),
            "since" to since?.toString(),
        ),
    ) { body ->
        val dto = json.decodeFromString(NotificationsResponseDto.serializer(), body)
        NotificationPage(
            notifications = dto.notifications.map { it.toDomain() },
            total = dto.total,
            serverTimeEpoch = dto.serverTime?.epoch,
        )
    }

    // --- Writes -------------------------------------------------------------------------------

    suspend fun createCalendar(
        profile: String,
        scope: CalendarScope,
        name: String,
        description: String? = null,
        color: String? = null,
        defaultTz: String? = null,
    ): ApiResult<Calendar> = request(
        Method.POST,
        "/calendar",
        listOf("profile" to profile, "scope" to scope.wire),
        buildJsonObject {
            put("name", name)
            put("scope", scope.wire)
            if (!description.isNullOrBlank()) put("description", description)
            if (!color.isNullOrBlank()) put("color", color)
            if (!defaultTz.isNullOrBlank()) put("default_tz", defaultTz)
        },
    ) { body ->
        json.decodeFromString(CalendarResponseDto.serializer(), body).calendar?.toDomain()
            ?: throw IllegalStateException("calendar missing from response")
    }

    suspend fun createEvent(profile: String, draft: EventDraft): ApiResult<CalendarEvent> = request(
        Method.POST,
        "/events",
        listOf("profile" to profile, "scope" to draft.scope.wire),
        buildJsonObject {
            put("calendar_id", draft.calendarId)
            put("summary", draft.summary)
            put("start", draft.start)
            put("end", draft.end)
            put("all_day", draft.allDay)
            put("scope", draft.scope.wire)
            if (!draft.allDay && !draft.timezone.isNullOrBlank()) put("timezone", draft.timezone)
            putTextOrNull("description", draft.description)
            putTextOrNull("location", draft.location)
            putTextOrNull("url", draft.url)
            if (!draft.rrule.isNullOrBlank()) put("rrule", draft.rrule)
        },
    ) { body ->
        json.decodeFromString(EventResponseDto.serializer(), body).event?.toDomain()
            ?: throw IllegalStateException("event missing from response")
    }

    suspend fun updateEvent(profile: String, patch: EventPatch): ApiResult<CalendarEvent> = request(
        Method.PATCH,
        "/events/${encodePath(patch.eventId)}",
        listOf("profile" to profile, "scope" to patch.scope.wire),
        buildJsonObject {
            put("expected_rev", patch.expectedRev)
            put("summary", patch.summary)
            put("start", patch.start)
            put("end", patch.end)
            put("scope", patch.scope.wire)
            if (!patch.timezone.isNullOrBlank()) put("timezone", patch.timezone)
            putTextOrNull("description", patch.description)
            putTextOrNull("location", patch.location)
            putTextOrNull("url", patch.url)
            put("series_scope", patch.seriesScope.wire)
            if (patch.seriesScope == SeriesScope.THIS) {
                // Per the contract, `this` accepts occurrence routing only, not RRULE changes.
                if (!patch.occurrenceStart.isNullOrBlank()) put("occurrence_start", patch.occurrenceStart)
            } else {
                put("rrule", patch.rrule?.let(::JsonPrimitive) ?: JsonNull)
            }
        },
    ) { body ->
        json.decodeFromString(EventResponseDto.serializer(), body).event?.toDomain()
            ?: throw IllegalStateException("event missing from response")
    }

    suspend fun deleteEvent(
        profile: String,
        scope: CalendarScope,
        eventId: String,
        expectedRev: Long,
        occurrenceStart: String? = null,
        seriesScope: SeriesScope = SeriesScope.ALL,
    ): ApiResult<Unit> = request(
        Method.DELETE,
        "/events/${encodePath(eventId)}",
        listOf(
            "profile" to profile,
            "scope" to scope.wire,
            "expected_rev" to expectedRev.toString(),
            "series_scope" to seriesScope.wire,
            "occurrence_start" to occurrenceStart,
        ),
        null,
    ) { Unit }

    suspend fun dismissNotification(profile: String, notificationId: String): ApiResult<CalendarNotification?> =
        request(
            Method.POST,
            "/notifications/${encodePath(notificationId)}/dismiss",
            profileParams(profile),
            null,
        ) { body ->
            json.decodeFromString(NotificationResponseDto.serializer(), body).notification?.toDomain()
        }

    suspend fun snoozeNotification(
        profile: String,
        notificationId: String,
        minutes: Int,
    ): ApiResult<CalendarNotification?> = request(
        Method.POST,
        "/notifications/${encodePath(notificationId)}/snooze",
        profileParams(profile),
        buildJsonObject { put("minutes", minutes) },
    ) { body ->
        json.decodeFromString(NotificationResponseDto.serializer(), body).notification?.toDomain()
    }

    // --- Transport ----------------------------------------------------------------------------

    private suspend fun <T> request(
        method: Method,
        path: String,
        params: List<Pair<String, String?>>,
        body: JsonObject? = null,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val config = credentials.load()
            ?: return@withContext ApiResult.Failure(CalendarError.Network)
        val requestBuilder = Request.Builder().url(buildUrl(config, path, params))
        // Loopback/token mode. In password mode the shared client's cookie jar authenticates.
        if (config.token.isNotBlank()) requestBuilder.header("X-Hermes-Session-Token", config.token)
        val payload = body?.let {
            json.encodeToString(JsonObject.serializer(), it).toRequestBody(JSON_MEDIA)
        }
        val request = when (method) {
            Method.GET -> requestBuilder.get()
            Method.POST -> requestBuilder.post(payload ?: EMPTY_JSON)
            Method.PATCH -> requestBuilder.patch(payload ?: EMPTY_JSON)
            Method.DELETE -> requestBuilder.delete()
        }.build()

        try {
            okHttp.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    ApiResult.Failure(parseApiError(text = text, status = response.code, json = json))
                } else {
                    try {
                        ApiResult.Success(parse(text))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ApiResult.Failure(CalendarError.Parse(e.message ?: "Malformed response"))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            ApiResult.Failure(CalendarError.Network)
        } catch (e: Exception) {
            ApiResult.Failure(CalendarError.Server(0, e.message ?: "Request failed"))
        }
    }

    private fun buildUrl(config: GatewayConfig, path: String, params: List<Pair<String, String?>>): String {
        val query = params
            .filter { it.second != null }
            .joinToString("&") { (key, value) -> "${encodeQuery(key)}=${encodeQuery(value.orEmpty())}" }
        val suffix = if (query.isEmpty()) "" else "?$query"
        return "${config.baseUrl.trimEnd('/')}$basePath$path$suffix"
    }

    private fun profileParams(profile: String): List<Pair<String, String?>> =
        listOf("profile" to profile)

    private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun kotlinx.serialization.json.JsonObjectBuilder.putTextOrNull(
        key: String,
        value: String?,
    ) {
        put(key, value?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
        val EMPTY_JSON: RequestBody = "{}".toRequestBody(JSON_MEDIA)
    }
}

// --- Result / error types ---------------------------------------------------------------------

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: CalendarError) : ApiResult<Nothing>
}

fun <T> ApiResult<T>.valueOrNull(): T? = (this as? ApiResult.Success)?.value

sealed class CalendarError(message: String) : Exception(message) {
    class Unauthorized(message: String = "Unauthorized") : CalendarError(message)
    class NotFound(message: String = "Not found") : CalendarError(message)

    /** Revision conflict (409). [eventId] is the row the client should re-read. */
    class Conflict(
        val eventId: String?,
        val expectedRev: Long?,
        val serverMessage: String,
    ) : CalendarError(serverMessage)

    class Validation(override val message: String) : CalendarError(message)
    data object Network : CalendarError("Network unavailable")
    class Server(val code: Int, message: String) : CalendarError(message)
    class Parse(message: String) : CalendarError(message)
}

internal fun parseApiError(text: String, status: Int, json: Json): CalendarError {
    val envelope = runCatching { json.decodeFromString(ApiErrorDto.serializer(), text) }.getOrNull()
    val message = envelope?.error?.takeIf { it.isNotBlank() }
    if (status == 401 || status == 403) return CalendarError.Unauthorized(message ?: "Unauthorized")
    when (envelope?.code) {
        "revision_conflict" -> return CalendarError.Conflict(
            eventId = envelope.eventId,
            expectedRev = envelope.expectedRev,
            serverMessage = message ?: "The event changed elsewhere",
        )
        "invalid_argument", "unsupported_recurrence" -> return CalendarError.Validation(message ?: "Invalid request")
        "not_found" -> return CalendarError.NotFound(message ?: "Not found")
        "conflict" -> return CalendarError.Conflict(null, null, message ?: "Conflict")
    }
    return when {
        status == 400 -> CalendarError.Validation(message ?: "Invalid request")
        status == 404 -> CalendarError.NotFound(message ?: "Not found")
        status == 409 -> CalendarError.Conflict(null, null, message ?: "Conflict")
        status >= 500 -> CalendarError.Server(status, message ?: "Server error")
        else -> CalendarError.Server(status, message ?: "HTTP $status")
    }
}

internal fun CalendarError.userMessage(): String = when (this) {
    is CalendarError.Unauthorized -> "Sign-in required. Check the dashboard connection."
    is CalendarError.NotFound -> message.orEmpty()
    is CalendarError.Conflict -> serverMessage.ifBlank { "The event changed elsewhere." }
    is CalendarError.Validation -> message
    CalendarError.Network -> "Can't reach the calendar server."
    is CalendarError.Server -> "Calendar server error${if (code > 0) " ($code)" else ""}."
    is CalendarError.Parse -> "The calendar server sent an unexpected response."
}
