package com.hermes.client.ui.plugins.workbook

import com.hermes.client.data.auth.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject

/**
 * Client for the Workbook plugin's JSON routes (`/api/plugins/hermes-workbook/`). Contract:
 * `docs/design/dashboard-api.md` in the plugin repository.
 *
 * Reuses the app's shared [OkHttpClient] (its cookie jar and authenticator handle the
 * password-protected mode) and sends `X-Hermes-Session-Token` when a token is configured.
 * Tokens are never logged. Every call sends `?profile=<profile>` when a profile is given.
 */
class WorkbookApi @Inject constructor(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val store: CredentialStore,
) {
    suspend fun workbooks(scope: String?, profile: String?): List<WorkbookSummary> =
        parseWorkbooks(json, execute(get(listOf("workbooks"), profile, "scope" to scope)))

    suspend fun sheets(name: String, scope: String?, profile: String?): WorkbookSheets =
        parseSheets(json, execute(get(listOf("workbooks", name, "sheets"), profile, "scope" to scope)))

    suspend fun data(name: String, scope: String?, sheet: String?, profile: String?): WorkbookData =
        parseData(
            json,
            execute(get(listOf("workbooks", name, "data"), profile, "scope" to scope, "sheet" to sheet)),
        )

    /** Returns the new revision (null from older plugins). */
    suspend fun create(
        name: String,
        path: String,
        scope: String,
        profile: String?,
    ): String? {
        val body = buildJsonObject {
            put("name", name)
            put("path", path)
            put("scope", scope)
        }
        return parseRevision(json, execute(post(listOf("workbooks"), profile, body)))
    }

    suspend fun write(
        name: String,
        scope: String,
        sheet: String?,
        start: String,
        values: JsonArray,
        ifRevision: String?,
        profile: String?,
    ): String? {
        val body = buildJsonObject {
            put("scope", scope)
            sheet?.let { put("sheet", it) }
            put("start", start)
            put("values", values)
            ifRevision?.let { put("if_revision", it) }
        }
        return parseRevision(json, execute(post(listOf("workbooks", name, "write"), profile, body)))
    }

    suspend fun append(
        name: String,
        scope: String,
        sheet: String?,
        rows: JsonArray,
        ifRevision: String?,
        profile: String?,
    ): String? {
        val body = buildJsonObject {
            put("scope", scope)
            sheet?.let { put("sheet", it) }
            put("rows", rows)
            ifRevision?.let { put("if_revision", it) }
        }
        return parseRevision(json, execute(post(listOf("workbooks", name, "append"), profile, body)))
    }

    private fun url(segments: List<String>, profile: String?, query: List<Pair<String, String?>>): HttpUrl {
        val cfg = store.load() ?: throw WorkbookApiException(0, "no_gateway", "No gateway configured")
        val base = cfg.baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: throw WorkbookApiException(0, "no_gateway", "Invalid gateway address")
        val b = base.newBuilder().addPathSegment("api").addPathSegment("plugins").addPathSegment(PLUGIN)
        segments.forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> if (!v.isNullOrBlank()) b.addQueryParameter(k, v) }
        if (!profile.isNullOrBlank()) b.addQueryParameter("profile", profile)
        return b.build()
    }

    private fun authed(builder: Request.Builder): Request.Builder {
        val token = store.load()?.token.orEmpty()
        if (token.isNotBlank()) builder.header("X-Hermes-Session-Token", token)
        return builder
    }

    private fun get(segments: List<String>, profile: String?, vararg query: Pair<String, String?>): Request =
        authed(Request.Builder().url(url(segments, profile, query.toList()))).get().build()

    private fun post(segments: List<String>, profile: String?, body: JsonObject): Request =
        authed(Request.Builder().url(url(segments, profile, emptyList())))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .build()

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            okHttp.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw parseError(json, resp.code, text)
                text
            }
        } catch (e: IOException) {
            // Deliberately generic: do not surface URLs or exception text.
            throw WorkbookApiException(0, "network", "Could not reach the gateway")
        }
    }

    private companion object {
        const val PLUGIN = "hermes-workbook"
        val JSON = "application/json".toMediaType()
    }
}

internal fun jsonRow(values: List<String>): JsonArray = JsonArray(values.map(::textToCell))

internal fun jsonRows(rows: List<List<String>>): JsonArray = JsonArray(rows.map(::jsonRow))

