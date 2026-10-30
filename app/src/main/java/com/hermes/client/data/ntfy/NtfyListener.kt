package com.hermes.client.data.ntfy

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where the ntfy connection stands, for the settings screen. */
sealed interface NtfyStatus {
    data object Off : NtfyStatus
    data object Connecting : NtfyStatus
    data class Connected(val topics: Int) : NtfyStatus
    data class Failed(val message: String) : NtfyStatus
}

/** An approval prompt that is still answerable on cold start was published at most this long ago. */
private const val APPROVAL_REPLAY_WINDOW = "35m"
private val BACKOFF_SECONDS = longArrayOf(2, 5, 10, 30, 60)

/**
 * Holds one long-lived `/json` stream per active topic and republishes what arrives. Approvals
 * topics replay the last [APPROVAL_REPLAY_WINDOW] on a cold start (so one missed while the app was
 * dead can still be answered); every other topic delivers only new messages. After a drop the
 * stream resumes from the last message id it saw.
 */
@Singleton
class NtfyListener @Inject constructor(
    okHttp: OkHttpClient,
    private val store: NtfyStore,
    private val json: Json,
) {
    // The shared client carries gateway cookies and a re-login authenticator; neither belongs here.
    private val client: OkHttpClient = okHttp.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .build()

    private val _events = MutableSharedFlow<NtfyEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<NtfyEvent> = _events.asSharedFlow()

    private val _status = MutableStateFlow<NtfyStatus>(NtfyStatus.Off)
    val status: StateFlow<NtfyStatus> = _status.asStateFlow()

    private val lastId = ConcurrentHashMap<String, String>()
    private val live = ConcurrentHashMap<String, Boolean>()
    private var supervisor: Job? = null

    /** Follows the saved config: (re)starts the streams whenever it changes, stops them when off. */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (supervisor?.isActive == true) return
        supervisor = scope.launch {
            store.config.distinctUntilChanged().collect { cfg ->
                streams.forEach { it.cancel() }
                streams = emptyList()
                live.clear()
                if (!cfg.isUsable) {
                    _status.value = NtfyStatus.Off
                    return@collect
                }
                _status.value = NtfyStatus.Connecting
                val password = store.password()
                streams = cfg.activeTopics.map { t -> launch { stream(cfg, t, password) } }
            }
        }
    }

    private var streams: List<Job> = emptyList()

    @Synchronized
    fun stop() {
        supervisor?.cancel()
        supervisor = null
        streams.forEach { it.cancel() }
        streams = emptyList()
        _status.value = NtfyStatus.Off
    }

    private suspend fun stream(cfg: NtfyConfig, topic: NtfyTopic, password: String) {
        var attempt = 0
        val base = normalizeServerUrl(cfg.serverUrl)
        while (currentCoroutineContext().isActive) {
            val since = lastId[topic.topic] ?: if (topic.kind == NtfyKind.APPROVALS) APPROVAL_REPLAY_WINDOW else null
            val url = "$base/${topic.topic}/json" + (since?.let { "?since=$it" } ?: "")
            try {
                val req = Request.Builder().url(url).apply {
                    if (cfg.username.isNotBlank()) header("Authorization", Credentials.basic(cfg.username, password))
                }.build()
                val call = client.newCall(req)
                val job = currentCoroutineContext()[Job]
                val handle = job?.invokeOnCompletion { call.cancel() }
                try {
                    withContext(Dispatchers.IO) {
                        call.execute().use { resp ->
                            if (!resp.isSuccessful) {
                                _status.value = NtfyStatus.Failed(
                                    if (resp.code == 401 || resp.code == 403) "Not allowed (${resp.code}) — check user and password"
                                    else "Server answered ${resp.code}",
                                )
                                throw java.io.IOException("http ${resp.code}")
                            }
                            attempt = 0
                            live[topic.topic] = true
                            _status.value = NtfyStatus.Connected(live.size)
                            val source = resp.body.source()
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (line.isBlank()) continue
                                val ev = parseNtfyLine(json, line) ?: continue
                                (ev as? NtfyEvent.Message)?.let { lastId[topic.topic] = it.message.id }
                                _events.tryEmit(ev)
                            }
                        }
                    }
                } finally {
                    handle?.dispose()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                live.remove(topic.topic)
                if (_status.value !is NtfyStatus.Failed) _status.value = NtfyStatus.Failed(e.message ?: e.javaClass.simpleName)
                DebugLog.log("ntfy", "stream ${topic.topic} dropped: ${e.javaClass.simpleName}")
            }
            val delaySec = BACKOFF_SECONDS[minOf(attempt, BACKOFF_SECONDS.size - 1)]
            attempt++
            delay(delaySec * 1000)
        }
    }

    /**
     * What each active topic's server still caches (`poll=1&since=all`), for the history screen: it
     * covers messages that arrived while the app was not listening. Per-topic failures are skipped;
     * [Result.failure] only when nothing could be read at all.
     */
    suspend fun fetchCached(): Result<List<NtfyMessage>> = withContext(Dispatchers.IO) {
        val cfg = store.config.first()
        if (!cfg.isUsable) return@withContext Result.success(emptyList())
        val base = normalizeServerUrl(cfg.serverUrl)
        val password = store.password()
        val out = mutableListOf<NtfyMessage>()
        var failure: Exception? = null
        var ok = 0
        for (t in cfg.activeTopics) {
            try {
                val req = Request.Builder().url("$base/${t.topic}/json?poll=1&since=all").apply {
                    if (cfg.username.isNotBlank()) header("Authorization", Credentials.basic(cfg.username, password))
                }.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("server answered ${resp.code}")
                    val source = resp.body.source()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        (parseNtfyLine(json, line) as? NtfyEvent.Message)?.let { out += it.message }
                    }
                }
                ok++
            } catch (e: Exception) {
                failure = e
                DebugLog.log("ntfy", "cache fetch ${t.topic} failed: ${e.javaClass.simpleName}")
            }
        }
        if (ok == 0 && failure != null) Result.failure(failure) else Result.success(out)
    }

    /** One-off reachability check for the settings screen: null when the server answers, else why not. */
    suspend fun check(serverUrl: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        val base = normalizeServerUrl(serverUrl)
        if (base.isEmpty()) return@withContext "Enter the server address"
        try {
            val req = Request.Builder().url("$base/v1/account").apply {
                if (username.isNotBlank()) header("Authorization", Credentials.basic(username, password))
            }.build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> "Not allowed (${resp.code}) — check user and password"
                    resp.code >= 500 -> "Server error (${resp.code})"
                    else -> null // 200 on an account-enabled server, 404 on a plain one: either way it is ntfy and reachable
                }
            }
        } catch (e: Exception) {
            "Can't reach the server (${e.javaClass.simpleName})"
        }
    }
}
