package com.hermes.client.data.matrix

import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface MatrixState {
    data object LoggedOut : MatrixState
    data object Restoring : MatrixState
    data object LoggingIn : MatrixState
    data class Ready(
        val userId: String,
        val deviceId: String,
        val homeserverUrl: String,
        val botMxid: String,
    ) : MatrixState
    /** A login attempt failed; any previously saved session is untouched. */
    data class Error(val message: String) : MatrixState
    /** The saved session could not be brought up. It is kept; [retryable] false means the server rejected it. */
    data class RestoreFailed(val message: String, val retryable: Boolean) : MatrixState
}

/**
 * App-wide owner of the Matrix client: login, restore, logout and the live room list. All SDK
 * access goes through [MatrixSdk]. Operations run as jobs on the manager's own long-lived scope
 * (so leaving a screen never orphans a half-finished login) and are serialised by [lock]; the
 * encrypted account store is only touched on [ioDispatcher]. Everything a client owns (collector
 * coroutines, SDK objects) is torn down in one place, [teardown].
 *
 * The password is only ever passed through to the SDK: never stored, logged, or put in state.
 */
class MatrixClientManager(
    private val sdk: MatrixSdk,
    private val store: MatrixAccountStore,
    /** Parent of the per-session store slots; wiped on logout. */
    private val storeDir: File,
    private val dispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val redact: (String) -> String = DebugLog::redact,
) {
    private val _state = MutableStateFlow<MatrixState>(MatrixState.LoggedOut)
    val state: StateFlow<MatrixState> = _state.asStateFlow()

    private val _rooms = MutableStateFlow<List<MatrixRoomSummary>>(emptyList())

    /** Immutable snapshots; replaced wholesale, never mutated. */
    val rooms: StateFlow<List<MatrixRoomSummary>> = _rooms.asStateFlow()

    private val _loginError = MutableStateFlow<String?>(null)

    /** Why the last login attempt was rejected or failed (redacted); shown next to the form. */
    val loginError: StateFlow<String?> = _loginError.asStateFlow()

    private val lock = Mutex()
    private val managerScope = CoroutineScope(SupervisorJob() + dispatcher)

    // Every in-flight login/restore/logout job, so logout and shutdown can cancel all of them.
    private val ops = mutableSetOf<Job>()

    // Session-lifetime scope for the room-list collector; replaced per client.
    private var scope: CoroutineScope? = null
    private var handle: MatrixClientHandle? = null

    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) { block() }

    /**
     * Logs in on the manager's scope. Ignored while a login/restore is running or a session is
     * live (log out first), and a blank password is rejected before anything is touched.
     */
    fun login(homeserver: String, username: String, password: String, botMxid: String, caDer: ByteArray?): Job =
        launchOp { doLogin(homeserver, username, password, botMxid, caDer) }

    /** Called from app start; does nothing without a saved session. Also the "Retry" action. */
    fun restoreOnStart(): Job = launchOp { doRestore() }

    /**
     * Opens a live read-only timeline for [roomId], or null when the room is unknown or no session is
     * live. The handle lookup and `client.getRoom`/`room.timeline` run under [lock] — the same mutex
     * logout and shutdown hold around [teardown] — so an open can never observe a client mid-destroy.
     *
     * The lock is held only for opening: the returned handle owns its own collector, and nothing here
     * collects or awaits it. [lock] is also held across a whole login (network I/O), so when no session
     * is live we return null before taking the lock; opening a room therefore never blocks behind a long
     * login. The remaining wait is only for a logout/teardown already in flight, which is short.
     */
    suspend fun openTimeline(roomId: String): MatrixTimelineHandle? {
        if (_state.value !is MatrixState.Ready) return null
        return lock.withLock {
            withContext(dispatcher) { handle?.openTimeline(roomId) }
        }
    }

    /**
     * Creates a room under the live session and returns its id, or null when no session is live
     * or creation failed. Same lock discipline as [openTimeline]: the client call runs under
     * [lock], so a logout can never destroy the client mid-create.
     */
    suspend fun createRoom(room: MatrixNewRoom): String? {
        if (_state.value !is MatrixState.Ready) return null
        return lock.withLock {
            withContext(dispatcher) { handle?.createRoom(room) }
        }
    }

    /** Server logout (best effort), then stop, destroy and wipe everything local. Preempts a running login/restore. */
    fun logout(): Job {
        cancelAllOps()
        return launchOp {
            withContext(NonCancellable) {
                handle?.let { h ->
                    try {
                        withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { h.logout() }
                    } catch (e: Exception) {
                        DebugLog.log("matrix-error", "server logout failed: ${describe(e)}")
                    }
                }
                teardown()
                io {
                    runCatching { storeDir.deleteRecursively() }
                    store.clear()
                }
                _loginError.value = null
                _state.value = MatrixState.LoggedOut
            }
        }
    }

    /** Stops the client but keeps the saved session (for process/app teardown). */
    suspend fun shutdown() {
        cancelAllOps()
        lock.withLock {
            withContext(NonCancellable) { teardown() }
            _state.value = MatrixState.LoggedOut
        }
    }

    private fun launchOp(block: suspend () -> Unit): Job {
        val job = managerScope.launch(start = CoroutineStart.LAZY) { lock.withLock { block() } }
        synchronized(ops) { ops += job }
        job.invokeOnCompletion { synchronized(ops) { ops -= job } }
        job.start()
        return job
    }

    private fun cancelAllOps() {
        val running = synchronized(ops) { ops.toList() }
        running.forEach { it.cancel() }
    }

    private suspend fun doLogin(homeserver: String, username: String, password: String, botMxid: String, caDer: ByteArray?) {
        if (password.isBlank()) {
            reject(PASSWORD_REQUIRED)
            return
        }
        when (_state.value) {
            MatrixState.LoggingIn, MatrixState.Restoring, is MatrixState.Ready -> return
            else -> Unit
        }
        val before = _state.value
        _loginError.value = null
        _state.value = MatrixState.LoggingIn
        val old = io { store.load() }
        var h: MatrixClientHandle? = null
        var newDir: File? = null
        var serverLoggedIn = false
        var saved = false
        var committed = false
        try {
            val url = homeserver.trim().trimEnd('/')
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "Homeserver URL must start with http:// or https://"
            }
            val certs = caDer?.let { CertificateBytes.parse(it) }.orEmpty()
            // A fresh slot, so a failed attempt never touches the previous session's database.
            val slot = if (old?.storeSlot == SLOT_A) SLOT_B else SLOT_A
            newDir = File(storeDir, slot)
            io { newDir.deleteRecursively() }
            h = sdk.createClient(configFor(url, certs, slot))
            val session = h.login(username.trim(), password, DEVICE_NAME)
            serverLoggedIn = true
            val account = MatrixAccount(session, botMxid.trim(), certs, slot)
            io { store.save(account) }
            saved = true
            activate(h, account)
            committed = true
            // From here on the login is done; a cancel must not roll it back.
            if (old != null && old.storeSlot != slot) {
                withContext(NonCancellable) { io { runCatching { File(storeDir, old.storeSlot).deleteRecursively() } } }
            }
        } catch (e: Throwable) {
            if (committed) {
                if (e is CancellationException) throw e
                return
            }
            withContext(NonCancellable) {
                // Don't leave an orphaned device behind on the server.
                if (serverLoggedIn) {
                    try {
                        withTimeoutOrNull(LOGOUT_TIMEOUT_MS) { h?.logout() }
                    } catch (_: Exception) {
                    }
                }
                teardown()
                runCatching { h?.close() }
                io {
                    runCatching { newDir?.deleteRecursively() }
                    // Put the previous session back; a failed login never costs the user a good one.
                    if (saved) { if (old != null) store.save(old) else store.clear() }
                }
            }
            if (e is CancellationException) {
                _state.value = if (before == MatrixState.LoggingIn) MatrixState.LoggedOut else before
                throw e
            }
            // Keep Retry/Discard reachable: a failed login must not hide a saved, restorable session.
            val msg = describe(e)
            _loginError.value = msg
            _state.value = if (before is MatrixState.RestoreFailed) before else MatrixState.Error(msg)
        }
    }

    /** Rejects a login without touching a session: message always shown; state only if nothing is saved/live. */
    private fun reject(message: String) {
        _loginError.value = message
        when (_state.value) {
            MatrixState.LoggedOut, is MatrixState.Error -> _state.value = MatrixState.Error(message)
            else -> Unit
        }
    }

    private suspend fun doRestore() {
        when (_state.value) {
            MatrixState.LoggedOut, is MatrixState.Error, is MatrixState.RestoreFailed -> Unit
            else -> return
        }
        val account = io { store.load() } ?: return
        _state.value = MatrixState.Restoring
        var h: MatrixClientHandle? = null
        try {
            h = sdk.createClient(configFor(account.session.homeserverUrl, account.caCerts, account.storeSlot))
            h.restore(account.session)
            activate(h, account)
        } catch (e: Throwable) {
            withContext(NonCancellable) { teardown(); runCatching { h?.close() } }
            if (e is CancellationException) {
                _state.value = MatrixState.RestoreFailed("Restore was interrupted", retryable = true)
                throw e
            }
            val msg = describe(e)
            DebugLog.log("matrix-error", "restore failed: $msg")
            // The session stays saved either way; only a server rejection makes retrying pointless.
            _state.value = MatrixState.RestoreFailed(msg, retryable = !isPermanentAuthFailure(msg))
        }
    }

    private suspend fun configFor(homeserverUrl: String, certs: List<ByteArray>, slot: String): MatrixClientConfig {
        val passphrase = io { store.storePassphrase() }
        return MatrixClientConfig(
            homeserverUrl = homeserverUrl,
            caCerts = certs,
            storePassphrase = passphrase,
            dataDir = File(storeDir, "$slot/data"),
            cacheDir = File(storeDir, "$slot/cache"),
            // These run on SDK threads, never the main thread.
            onSessionRefreshed = { store.updateSession(it) },
            currentSession = { userId -> store.load()?.session?.takeIf { it.userId == userId } },
        )
    }

    private suspend fun activate(h: MatrixClientHandle, account: MatrixAccount) {
        val s = CoroutineScope(SupervisorJob() + dispatcher)
        scope = s
        handle = h
        // Single collector, so diffs apply in order; each publish is a fresh immutable list.
        s.launch {
            h.roomDiffs.collect { diffs -> _rooms.value = applyDiffs(_rooms.value, diffs) }
        }
        h.startSync()
        _state.value = MatrixState.Ready(
            userId = account.session.userId,
            deviceId = account.session.deviceId,
            homeserverUrl = account.session.homeserverUrl,
            botMxid = account.botMxid,
        )
    }

    /** Cancels the owned scope first, then stops sync and destroys SDK objects (handle order). */
    private suspend fun teardown() {
        scope?.cancel()
        scope = null
        val h = handle
        handle = null
        _rooms.value = emptyList()
        if (h != null) {
            try {
                // No timeout here: close() awaits every open timeline's teardown, and a timeout would
                // let us destroy the client while a native call is still in flight — a use-after-free.
                h.close()
            } catch (e: Exception) {
                DebugLog.log("matrix-error", "close failed: ${describe(e)}")
            }
        }
    }

    private fun describe(e: Throwable): String = redactedCauseChain(e, redact)

    companion object {
        private const val PASSWORD_REQUIRED = "A password is required"
        private const val DEVICE_NAME = "Hermes Local"
        private const val LOGOUT_TIMEOUT_MS = 10_000L
        private const val SLOT_A = "s0"
        private const val SLOT_B = "s1"

        /** True when the homeserver itself rejected the session, so retrying cannot help. */
        fun isPermanentAuthFailure(message: String) =
            listOf("M_UNKNOWN_TOKEN", "M_USER_DEACTIVATED", "M_USER_LOCKED").any { message.contains(it) }
    }
}
