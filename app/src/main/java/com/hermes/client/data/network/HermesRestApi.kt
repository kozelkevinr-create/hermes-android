package com.hermes.client.data.network

import com.hermes.client.data.auth.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import okio.source

class HermesApiException(val code: Int, message: String) : Exception(message)

class HermesRestApi(
    private val okHttp: OkHttpClient,
    private val json: Json,
    private val configProvider: () -> GatewayConfig?,
) {
    private fun config(): GatewayConfig =
        configProvider() ?: throw HermesApiException(0, "no gateway configured")

    private fun builder(path: String): Request.Builder {
        val cfg = config()
        // Keep the diagnostic log's redaction current with whatever token is active, so a
        // shared log can never contain the session token in plain text.
        com.hermes.client.data.diagnostics.DebugLog.setTokenToRedact(cfg.token)
        // Trim trailing slashes so a user-entered "http://host:9119/" doesn't produce
        // "//api/..." — the gateway routes a double slash to its web UI (HTML), not the API.
        val b = Request.Builder().url("${cfg.baseUrl.trimEnd('/')}$path")
        if (cfg.token.isNotBlank()) b.header("X-Hermes-Session-Token", cfg.token)
        return b
    }

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        com.hermes.client.data.diagnostics.DebugLog.log("rest", "GET $path")
        okHttp.newCall(builder(path).get().build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                com.hermes.client.data.diagnostics.DebugLog.log(
                    "rest", "GET $path ← ${resp.code} ${body.take(200)}",
                )
                throw HermesApiException(resp.code, body.ifBlank { "HTTP ${resp.code}" })
            }
            com.hermes.client.data.diagnostics.DebugLog.log("rest", "GET $path ← ${resp.code}")
            json.decodeFromString<T>(body)
        }
    }

    /**
     * T10b: test connectivity using explicitly supplied credentials WITHOUT reading from
     * configProvider. Used by SetupViewModel.test() so unverified creds are never persisted.
     */
    suspend fun statusFor(baseUrl: String, token: String): GatewayProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val rb = Request.Builder().url("${baseUrl.trimEnd('/')}/api/status").get()
                if (token.isNotBlank()) rb.header("X-Hermes-Session-Token", token)
                okHttp.newCall(rb.build()).execute().use { resp ->
                    when {
                        resp.isSuccessful -> GatewayProbeResult.Connected
                        resp.code == 401 || resp.code == 403 -> GatewayProbeResult.TokenRejected
                        else -> GatewayProbeResult.Unexpected(resp.code)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.toProbeFailure()
            }
        }

    /** Delegates to [statusFor] using the current stored config. */
    suspend fun status(): Boolean {
        val cfg = configProvider() ?: return false
        return statusFor(cfg.baseUrl, cfg.token) == GatewayProbeResult.Connected
    }

    /** Public /api/status — gateway version + running state. */
    suspend fun gatewayStatus(): GatewayStatusDto = get("/api/status")

    suspend fun sessions(limit: Int, offset: Int, profile: String? = null): List<SessionDto> =
        get<SessionListDto>(
            "/api/sessions?limit=$limit&offset=$offset&order=recent${profileParam(profile)}",
        ).sessions

    /**
     * Cross-profile session list — every session tagged with its true `profile`, plus
     * `profile_totals` for group headers. The active list excludes archived by default;
     * pass [archivedOnly] to fetch only archived sessions (`?archived=only`). One page of
     * [limit] covers current volume (no offset paging in MVP).
     */
    suspend fun profileSessions(limit: Int = 500, archivedOnly: Boolean = false): ProfileSessionsDto =
        get("/api/profiles/sessions?limit=$limit&order=recent${if (archivedOnly) "&archived=only" else ""}")

    suspend fun messages(sessionId: String, profile: String? = null): List<MessageDto> =
        get<MessagesDto>("/api/sessions/$sessionId/messages${profileParam(profile, first = true)}").messages

    /** "&profile=x" (or "?profile=x" when [first]) — empty when profile is null/blank. */
    private fun profileParam(profile: String?, first: Boolean = false): String {
        if (profile.isNullOrBlank()) return ""
        val sep = if (first) "?" else "&"
        return "${sep}profile=${java.net.URLEncoder.encode(profile, "UTF-8")}"
    }

    suspend fun profiles(): List<ProfileDto> = get<ProfilesDto>("/api/profiles").profiles

    /** The gateway's currently-active profile name (current takes precedence over active). */
    suspend fun activeProfile(): String? =
        get<ActiveProfileDto>("/api/profiles/active").let { it.current ?: it.active }

    /** Raw provider list (each provider carries its model strings + an is_current flag). */
    suspend fun modelProviders(): List<ModelProviderDto> =
        get<ModelOptionsDto>("/api/model/options").providers

    suspend fun modelOptions(): List<ModelOptionDto> =
        get<ModelOptionsDto>("/api/model/options").providers.flatMap { p ->
            p.models.map { m -> ModelOptionDto(provider = p.slug, model = m, label = m) }
        }

    suspend fun setModel(provider: String, model: String) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            put("scope", "main")   // required by /api/model/set (the primary model slot)
            put("provider", provider)
            put("model", model)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/model/set").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set model failed")
        }
    }

    /**
     * Rename and/or archive a session via PATCH /api/sessions/{id}. [profile] MUST identify the
     * session's profile (sent in the body): the gateway resolves the session against a per-profile
     * DB, and without it a session in a non-default profile returns 404 — so the archive/rename
     * silently no-ops and the session never leaves the list.
     */
    suspend fun patchSession(
        sessionId: String,
        title: String? = null,
        archived: Boolean? = null,
        profile: String? = null,
    ) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            if (title != null) put("title", title)
            if (archived != null) put("archived", archived)
            if (!profile.isNullOrBlank()) put("profile", profile)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/sessions/$sessionId").patch(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "update session failed")
        }
    }

    /** Delete a session. [profile] (query param) scopes it to the right per-profile DB. */
    suspend fun deleteSession(sessionId: String, profile: String? = null) = withContext(Dispatchers.IO) {
        val path = "/api/sessions/$sessionId${profileParam(profile, first = true)}"
        okHttp.newCall(builder(path).delete().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "delete session failed")
        }
    }

    suspend fun sessionStats(profile: String? = null): SessionStatsDto =
        get("/api/sessions/stats${profileParam(profile, first = true)}")

    suspend fun searchSessions(query: String, profile: String? = null): List<SearchResultDto> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        return get<SearchResultsDto>("/api/sessions/search?q=$q&limit=30${profileParam(profile)}").results
    }

    suspend fun archivedSessions(profile: String? = null): List<SessionDto> =
        get<SessionListDto>(
            "/api/sessions?archived=only&limit=50&order=recent${profileParam(profile)}",
        ).sessions

    suspend fun cronJobs(profile: String? = null): List<CronJobDto> =
        get("/api/cron/jobs${profileParam(profile, first = true)}")

    suspend fun cronJob(jobId: String, profile: String? = null): CronJobDto =
        get("/api/cron/jobs/$jobId${profileParam(profile, first = true)}")

    suspend fun cronRuns(jobId: String, profile: String? = null): List<CronRunDto> =
        get<CronRunsDto>("/api/cron/jobs/$jobId/runs${profileParam(profile, first = true)}").runs

    /** POST a cron action (pause | resume | trigger); empty body. */
    private suspend fun cronAction(jobId: String, action: String, profile: String?) =
        withContext(Dispatchers.IO) {
            val body = "{}".toRequestBody("application/json".toMediaType())
            val path = "/api/cron/jobs/$jobId/$action${profileParam(profile, first = true)}"
            okHttp.newCall(builder(path).post(body).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, "$action failed")
            }
        }

    suspend fun pauseCron(jobId: String, profile: String? = null) = cronAction(jobId, "pause", profile)
    suspend fun resumeCron(jobId: String, profile: String? = null) = cronAction(jobId, "resume", profile)
    suspend fun triggerCron(jobId: String, profile: String? = null) = cronAction(jobId, "trigger", profile)

    suspend fun createCron(prompt: String, schedule: String, name: String, profile: String? = null) =
        withContext(Dispatchers.IO) {
            val obj = buildJsonObject {
                put("prompt", prompt); put("schedule", schedule)
                if (name.isNotBlank()) put("name", name)
            }
            val payload = json.encodeToString(JsonObject.serializer(), obj)
                .toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder("/api/cron/jobs${profileParam(profile, first = true)}").post(payload).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(160)
                        throw HermesApiException(resp.code, "create cron failed: $body")
                    }
                }
        }

    suspend fun updateCron(jobId: String, prompt: String, schedule: String, name: String, profile: String? = null) =
        withContext(Dispatchers.IO) {
            val obj = buildJsonObject {
                put("prompt", prompt); put("schedule", schedule); put("name", name)
            }
            val payload = json.encodeToString(JsonObject.serializer(), obj)
                .toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder("/api/cron/jobs/$jobId${profileParam(profile, first = true)}").put(payload).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty().take(160)
                        throw HermesApiException(resp.code, "update cron failed: $body")
                    }
                }
        }

    suspend fun deleteCron(jobId: String, profile: String? = null) = withContext(Dispatchers.IO) {
        okHttp.newCall(builder("/api/cron/jobs/$jobId${profileParam(profile, first = true)}").delete().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, "delete cron failed")
            }
    }

    suspend fun analyticsUsage(profile: String? = null): UsageDto =
        get("/api/analytics/usage${profileParam(profile, first = true)}")

    suspend fun analyticsModels(profile: String? = null): List<ModelUsageDto> =
        get<ModelsUsageDto>("/api/analytics/models${profileParam(profile, first = true)}").models

    // ---- Config (whole-object GET-modify-PUT so no fields are ever dropped) ----

    suspend fun getConfig(profile: String? = null): JsonObject =
        get("/api/config${profileParam(profile, first = true)}")

    suspend fun putConfig(config: JsonObject, profile: String? = null) = withContext(Dispatchers.IO) {
        // PUT /api/config expects the config wrapped as {"config": {...}} (422 otherwise).
        val wrapped = JsonObject(mapOf("config" to config))
        val payload = json.encodeToString(JsonObject.serializer(), wrapped)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/config${profileParam(profile, first = true)}").put(payload).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(180)
                    throw HermesApiException(resp.code, "HTTP ${resp.code}: $body")
                }
            }
    }

    /** Create a profile. [cloneFrom] copies that profile's config, skills and SOUL (null = blank). */
    suspend fun createProfile(
        name: String,
        cloneFrom: String?,
        description: String?,
        keepSkills: List<String> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            put("name", name)
            if (!cloneFrom.isNullOrBlank()) put("clone_from", cloneFrom)
            if (!description.isNullOrBlank()) put("description", description)
            // Non-empty means "keep only these"; the gateway disables the other seeded skills.
            if (keepSkills.isNotEmpty()) put("keep_skills", kotlinx.serialization.json.JsonArray(keepSkills.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/profiles").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, resp.body?.string().orEmpty().ifBlank { "create failed" })
        }
    }

    /** Delete a profile and its directory. Irreversible. */
    suspend fun deleteProfile(name: String) = withContext(Dispatchers.IO) {
        okHttp.newCall(builder("/api/profiles/$name").delete().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, resp.body?.string().orEmpty().ifBlank { "delete failed" })
        }
    }

    suspend fun profileSoul(name: String): String =
        get<JsonObject>("/api/profiles/$name/soul")["content"]?.jsonPrimitive?.content.orEmpty()

    suspend fun setProfileSoul(name: String, content: String) = putProfileField(name, "soul", "content", content)

    suspend fun setProfileDescription(name: String, description: String) =
        putProfileField(name, "description", "description", description)

    private suspend fun putProfileField(name: String, endpoint: String, field: String, value: String) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject { put(field, value) }
                .toString().toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder("/api/profiles/$name/$endpoint").put(payload).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, resp.body?.string().orEmpty().ifBlank { "save failed" })
            }
        }

    suspend fun setProfileModel(name: String, provider: String, model: String) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("provider", provider); put("model", model) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/profiles/$name/model").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set profile model failed")
        }
    }

    // ---- Env / API keys (per-key endpoints — safe by design) ----

    suspend fun envVars(profile: String? = null): Map<String, EnvVarDto> =
        get("/api/env${profileParam(profile, first = true)}")

    suspend fun setEnv(key: String, value: String, profile: String? = null) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            put("key", key); put("value", value); if (profile != null) put("profile", profile)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/env").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set env failed")
        }
    }

    suspend fun revealEnv(key: String, profile: String? = null): String = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("key", key); if (profile != null) put("profile", profile) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/env/reveal").post(payload).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "reveal env failed (HTTP ${resp.code}) ${body.take(120)}".trim())
            json.decodeFromString<JsonObject>(body)["value"]?.jsonPrimitive?.content ?: ""
        }
    }

    /**
     * Transcribe a recorded voice note. [dataUrl] is a base64 data URL (data:<mime>;base64,<b64>)
     * the gateway's POST /api/audio/transcribe accepts; returns the trimmed transcript ("" if the
     * STT backend returned nothing). Throws HermesApiException on a non-2xx (e.g. no STT configured).
     */
    suspend fun transcribe(dataUrl: String, mimeType: String): String = withContext(Dispatchers.IO) {
        val obj = buildJsonObject { put("data_url", dataUrl); put("mime_type", mimeType) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/audio/transcribe").post(payload).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "transcription failed")
            // Treat an explicit JSON null (out-of-contract, but guards against a phantom "null"
            // transcript) the same as a missing field → "".
            val el = json.decodeFromString<JsonObject>(body)["transcript"]
            if (el == null || el is JsonNull) "" else el.jsonPrimitive.content.trim()
        }
    }

    suspend fun skills(profile: String? = null): List<SkillDto> = get("/api/skills${profileParam(profile, first = true)}")

    suspend fun toggleSkill(name: String, enabled: Boolean, profile: String? = null) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject {
            put("name", name); put("enabled", enabled)
            if (!profile.isNullOrBlank()) put("profile", profile)
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/skills/toggle").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "toggle skill failed")
        }
    }

    suspend fun toolsets(profile: String? = null): List<ToolsetDto> =
        get("/api/tools/toolsets${profileParam(profile, first = true)}")

    suspend fun toggleToolset(name: String, enabled: Boolean, profile: String? = null) =
        toggleProfileScoped("/api/tools/toolsets/${java.net.URLEncoder.encode(name, "UTF-8")}", enabled, profile)

    suspend fun mcpServers(profile: String? = null): List<McpServerDto> =
        get<McpServersDto>("/api/mcp/servers${profileParam(profile, first = true)}").servers

    suspend fun toggleMcpServer(name: String, enabled: Boolean, profile: String? = null) =
        toggleProfileScoped("/api/mcp/servers/${java.net.URLEncoder.encode(name, "UTF-8")}/enabled", enabled, profile)

    /** Add an MCP server to [profile]'s config. Give [url] for a remote server or [command] for a local one. */
    suspend fun addMcpServer(
        profile: String?,
        name: String,
        url: String?,
        command: String?,
        args: List<String>,
        env: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("name", name)
            if (!url.isNullOrBlank()) put("url", url)
            if (!command.isNullOrBlank()) put("command", command)
            if (args.isNotEmpty()) put("args", kotlinx.serialization.json.JsonArray(args.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
            if (!profile.isNullOrBlank()) put("profile", profile)
        }.toString().toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/mcp/servers").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, errorDetail(resp.body?.string().orEmpty(), "add server failed"))
        }
    }

    /** Connect to the server, list its tools and disconnect. Can take several seconds. */
    suspend fun testMcpServer(name: String, profile: String?): McpTestResultDto = withContext(Dispatchers.IO) {
        val path = "/api/mcp/servers/${java.net.URLEncoder.encode(name, "UTF-8")}/test${profileParam(profile, first = true)}"
        // A slow server must not be cut off by the default read timeout.
        val client = okHttp.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
        client.newCall(builder(path).post(ByteArray(0).toRequestBody(null)).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HermesApiException(resp.code, errorDetail(body, "test failed (HTTP ${resp.code})"))
            json.decodeFromString<McpTestResultDto>(body)
        }
    }

    suspend fun deleteMcpServer(name: String, profile: String?) = withContext(Dispatchers.IO) {
        val path = "/api/mcp/servers/${java.net.URLEncoder.encode(name, "UTF-8")}${profileParam(profile, first = true)}"
        okHttp.newCall(builder(path).delete().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, errorDetail(resp.body?.string().orEmpty(), "delete failed"))
        }
    }

    suspend fun mcpCatalog(profile: String?): List<McpCatalogEntryDto> =
        get<McpCatalogDto>("/api/mcp/catalog${profileParam(profile, first = true)}").entries

    suspend fun installMcpCatalogEntry(profile: String?, name: String, env: Map<String, String>) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("name", name)
                put("enable", true)
                if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
                if (!profile.isNullOrBlank()) put("profile", profile)
            }.toString().toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder("/api/mcp/catalog/install").post(payload).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, errorDetail(resp.body?.string().orEmpty(), "install failed"))
            }
        }

    /** The gateway reports failures as {"detail": "..."}; fall back to [default] for anything else. */
    private fun errorDetail(body: String, default: String): String =
        runCatching { json.decodeFromString<JsonObject>(body)["detail"]?.jsonPrimitive?.content }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: default

    /** PUT {enabled, profile} to [path]; the gateway applies it to that profile's config. */
    private suspend fun toggleProfileScoped(path: String, enabled: Boolean, profile: String?) =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("enabled", enabled)
                if (!profile.isNullOrBlank()) put("profile", profile)
            }.toString().toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder(path).put(payload).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw HermesApiException(resp.code, resp.body?.string().orEmpty().ifBlank { "toggle failed" })
            }
        }

    suspend fun messagingPlatforms(): List<MessagingPlatformDto> =
        get<MessagingPlatformsDto>("/api/messaging/platforms").platforms

    suspend fun setMessagingEnabled(platformId: String, enabled: Boolean) =
        configureMessaging(platformId, emptyMap(), enabled)

    /** Configure a messaging platform: set env vars and/or enable/disable it. */
    suspend fun configureMessaging(
        platformId: String,
        env: Map<String, String>,
        enabled: Boolean?,
    ) = withContext(Dispatchers.IO) {
        val obj = buildJsonObject {
            if (enabled != null) put("enabled", enabled)
            if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
        }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/messaging/platforms/$platformId").put(payload).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty().take(180)
                    throw HermesApiException(resp.code, "configure platform failed: $body")
                }
            }
    }

    suspend fun setActiveProfile(name: String) = withContext(Dispatchers.IO) {
        val obj: JsonObject = buildJsonObject { put("name", name) }
        val payload = json.encodeToString(JsonObject.serializer(), obj)
            .toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/profiles/active").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "set active profile failed")
        }
    }

    // --- Managed files --------------------------------------------------------------------
    // Paths are passed through verbatim; the gateway enforces the locked root (403 outside it)
    // and the credential denylist (403 on .env/config.yaml/auth.json/...), so those guarantees
    // hold regardless of what this client asks for.

    suspend fun listFiles(path: String? = null): FileListDto =
        get("/api/files" + (path?.let { "?path=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""))

    suspend fun readFile(path: String): FileReadDto =
        get("/api/files/read?path=" + java.net.URLEncoder.encode(path, "UTF-8"))

    /** Write (or create) a file from raw bytes, sent as the data-URL the upload endpoint expects. */
    suspend fun writeFile(path: String, bytes: ByteArray, mimeType: String = "text/plain"): Unit =
        withContext(Dispatchers.IO) {
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val payload = buildJsonObject {
                put("path", path)
                put("data_url", "data:$mimeType;base64,$b64")
                put("overwrite", true)
            }.toString().toRequestBody("application/json".toMediaType())
            okHttp.newCall(builder("/api/files/upload").post(payload).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw HermesApiException(resp.code, resp.body?.string().orEmpty().ifBlank { "upload failed" })
                }
            }
        }

    suspend fun mkdir(path: String): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("path", path) }
            .toString().toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/files/mkdir").post(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "mkdir failed")
        }
    }

    // --- Webhooks -------------------------------------------------------------------------

    suspend fun webhooks(): WebhooksDto = get("/api/webhooks")

    suspend fun setWebhookEnabled(name: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("enabled", enabled) }
            .toString().toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/webhooks/$name/enabled").put(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw HermesApiException(resp.code, "toggle webhook failed")
        }
    }

    /**
     * Rename a profile. For the default profile the gateway treats this as a presentation-only
     * display_name and keeps the canonical id "default", so callers keying on the id stay valid.
     * Note the gateway briefly stops to rename a non-default profile's directory.
     */
    suspend fun renameProfile(name: String, newName: String): Unit = withContext(Dispatchers.IO) {
        val payload = buildJsonObject { put("new_name", newName) }
            .toString().toRequestBody("application/json".toMediaType())
        okHttp.newCall(builder("/api/profiles/$name").patch(payload).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw HermesApiException(resp.code, resp.body?.string().orEmpty().ifBlank { "rename failed" })
            }
        }
    }

    /**
     * Upload a file via the gateway's chunked multipart endpoint.
     *
     * The body streams from [openStream] rather than being buffered: the server accepts up to
     * 100 MB, which would OOM a phone if read into a ByteArray first. OkHttp may re-send the body
     * (redirect/retry), so [openStream] must return a fresh stream each call.
     *
     * The server writes to a sibling temp file and atomically renames, so a dropped connection
     * cannot leave a half-written file in place of a good one.
     */
    suspend fun uploadFileStreaming(
        destPath: String,
        fileName: String,
        mimeType: String,
        contentLength: Long,
        openStream: () -> java.io.InputStream,
    ): Unit = withContext(Dispatchers.IO) {
        val fileBody = object : okhttp3.RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength() = contentLength
            override fun writeTo(sink: okio.BufferedSink) {
                openStream().use { input -> sink.writeAll(input.source().buffer()) }
            }
        }
        val multipart = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("path", destPath)
            .addFormDataPart("overwrite", "true")
            .addFormDataPart("file", fileName, fileBody)
            .build()
        okHttp.newCall(builder("/api/files/upload-stream").post(multipart).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val detail = resp.body?.string().orEmpty()
                throw HermesApiException(
                    resp.code,
                    when (resp.code) {
                        413 -> "File is too large (100 MB limit)"
                        403 -> "Upload refused: outside the allowed folder"
                        else -> detail.ifBlank { "Upload failed (${resp.code})" }
                    },
                )
            }
        }
    }

}
