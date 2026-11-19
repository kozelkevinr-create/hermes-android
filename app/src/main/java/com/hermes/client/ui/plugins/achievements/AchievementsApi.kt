package com.hermes.client.ui.plugins.achievements

import com.hermes.client.data.auth.CredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

class AchievementsException(val code: Int, message: String) : Exception(message)

/**
 * Client for the hermes-achievements dashboard plugin. Reuses the shared [OkHttpClient]
 * (cookie jar for gated dashboards) and the session-token header. Nothing is logged.
 */
@Singleton
class AchievementsApi @Inject constructor(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val credentials: CredentialStore,
) {
    private val basePath = "/api/plugins/hermes-achievements"

    suspend fun load(): AchievementsSnapshot = parseAchievements(call("GET", "/achievements"), json)

    /** Forces a rescan (can take a while) and returns the fresh snapshot. */
    suspend fun rescan(): AchievementsSnapshot = parseAchievements(call("POST", "/rescan"), json)

    private suspend fun call(method: String, path: String): String = withContext(Dispatchers.IO) {
        val cfg = credentials.load() ?: throw AchievementsException(0, "No gateway configured")
        val rb = Request.Builder().url("${cfg.baseUrl.trimEnd('/')}$basePath$path")
        if (cfg.token.isNotBlank()) rb.header("X-Hermes-Session-Token", cfg.token)
        if (method == "POST") rb.post(ByteArray(0).toRequestBody(null)) else rb.get()
        try {
            okHttp.newCall(rb.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> body
                    resp.code == 404 || resp.code == 405 ->
                        throw AchievementsException(resp.code, "Achievements plugin isn't available on this server")
                    resp.code == 401 || resp.code == 403 ->
                        throw AchievementsException(resp.code, "Not authorised — check Server & token")
                    else -> throw AchievementsException(resp.code, "Server error (HTTP ${resp.code})")
                }
            }
        } catch (e: java.io.IOException) {
            throw AchievementsException(0, "Couldn't reach the server")
        }
    }
}
