package com.hermes.client.data.diagnostics

import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide diagnostic log, toggled by the user in Settings → Diagnostics.
 *
 * It is a plain object (not DI) so it can be called from non-injected code such as the
 * WebSocket listener. When [enabled] is false, [log] is a cheap no-op and nothing is
 * retained, so there is zero overhead in normal use. When enabled, entries are kept in a
 * bounded in-memory ring buffer (cleared on app kill), exposed as [entries] for the live
 * in-app view and mirrored to logcat under [TAG].
 *
 * A registered session token is masked in every message so the log is safe to share.
 */
object DebugLog {
    const val MAX_ENTRIES = 500
    private const val TAG = "HermesDebug"

    data class LogEntry(val timeMillis: Long, val category: String, val message: String)

    @Volatile private var enabled = false
    @Volatile private var tokenToRedact: String? = null

    // Thread-safe (unlike SimpleDateFormat); the log is written from background threads.
    private val exportFmt =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Register the session token so it is never written to the log in plain text. */
    fun setTokenToRedact(token: String?) {
        tokenToRedact = token?.takeIf { it.isNotBlank() }
    }

    fun log(category: String, message: String) {
        if (!enabled) return
        val safe = redact(message)
        val entry = LogEntry(System.currentTimeMillis(), category, safe)
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
            _entries.value = buffer.toList()
        }
        Log.d(TAG, "[$category] $safe")
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }

    /** Plain-text dump for the Share sheet, oldest first. */
    fun export(): String {
        val snapshot = synchronized(lock) { buffer.toList() }
        if (snapshot.isEmpty()) return "(no diagnostic entries)"
        return buildString {
            append("Hermes Local diagnostic log — ${snapshot.size} entries\n")
            snapshot.forEach { e ->
                append("${exportFmt.format(Instant.ofEpochMilli(e.timeMillis))} [${e.category}] ${e.message}\n")
            }
        }
    }

    // Matrix credentials that can show up in SDK / HTTP traces: bearer headers and
    // access_token / refresh_token / password fields (JSON, query string or key=value).
    // "Authorization: <scheme> <credential>" headers: the whole credential after the optional scheme
    // is masked. Backslash-escaped JSON keys (\"authorization\":\"...\") are matched too; the
    // credential stops at a backslash or quote so it never swallows the escaping.
    private val authHeaderRegex =
        Regex("""(?i)(authorization\\*["']?\s*[:=]\s*\\*["']?(?:(?:bearer|basic|digest|negotiate|token)\s+)?)[^\s"',}\\]+""")
    // A bare "Bearer <token>": the token must be 8+ token characters containing a digit or
    // punctuation, so prose like "bearer of bad news" is left alone.
    private val bearerRegex =
        Regex("(?i)\\b(bearer\\s+)(?=[A-Za-z0-9._~+/=-]*[0-9._~+/=-])[A-Za-z0-9._~+/=-]{8,}")
    private const val SECRET_KEYS =
        "(?:access_token|refresh_token|password|login_token|loginToken|id_token|client_secret|recovery_key)"
    // Quoted values, plain or backslash-escaped JSON (e.g. password\":\"x). The opening quote and
    // its backslash prefix are captured, and the value runs only up to the SAME quote with the SAME
    // backslash prefix, so "it's a pw" is masked whole and an inner \" does not end the match early.
    // (?s): values spanning several lines are masked too.
    private val quotedSecretRegex =
        Regex("""(?is)($SECRET_KEYS\\*["']?\s*[:=]\s*(?<bs>\\*)(?<q>["']))(?:(?!(?<!\\)\k<bs>\k<q>).)*""")
    // key=value / key: value with no quotes (query strings, plain logs).
    private val bareSecretRegex = Regex("(?i)($SECRET_KEYS\\s*[:=]\\s*)(?![\\\\\"'])[^\\s&,}\"']+")

    fun redact(message: String): String {
        var out = message
        tokenToRedact?.let { out = out.replace(it, "***") }
        return out
            .replace(authHeaderRegex, "$1***")
            .replace(bearerRegex, "$1***")
            .replace(quotedSecretRegex, "$1***")
            .replace(bareSecretRegex, "$1***")
    }
}
