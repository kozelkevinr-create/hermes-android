package com.hermes.client.data.matrix

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

private class FakeHandle(
    private val log: MutableList<String>,
    private val loginError: Throwable? = null,
    private val restoreError: Throwable? = null,
    private val syncError: Throwable? = null,
    private val restoreGate: CompletableDeferred<Unit>? = null,
    private val loginGate: CompletableDeferred<Unit>? = null,
    private val openTimelineGate: CompletableDeferred<Unit>? = null,
) : MatrixClientHandle {
    val diffs = Channel<List<ListDiff<MatrixRoomSummary>>>(Channel.UNLIMITED)
    override val roomDiffs: Flow<List<ListDiff<MatrixRoomSummary>>> = diffs.receiveAsFlow()
    var closed = false
    var serverLoggedOut = false
    var timelineOpenedWhileClosed = false

    override suspend fun login(username: String, password: String, deviceName: String): MatrixSessionData {
        log += "login:$username"
        loginGate?.await()
        loginError?.let { throw it }
        return sampleSession(token = "tok-$username")
    }

    override suspend fun restore(session: MatrixSessionData) {
        log += "restore"
        restoreGate?.await()
        restoreError?.let { throw it }
    }

    override suspend fun startSync() {
        log += "startSync"
        syncError?.let { throw it }
    }

    override suspend fun stopSync() { log += "stopSync" }

    override suspend fun openTimeline(roomId: String): MatrixTimelineHandle? {
        if (closed) {
            timelineOpenedWhileClosed = true
            return null
        }
        openTimelineGate?.await()
        log += "openTimeline:$roomId"
        return FakeTimelineHandle()
    }

    override suspend fun logout() {
        check(!closed) { "logout after close" }
        serverLoggedOut = true
        log += "serverLogout"
    }
    override suspend fun close() {
        if (closed) return
        closed = true
        diffs.close()
        log += "close"
    }
}

private class FakeTimelineHandle : MatrixTimelineHandle {
    override val items: StateFlow<List<TimelineEntry>> = MutableStateFlow(emptyList())
    override val paginationState: StateFlow<PaginationState> = MutableStateFlow(PaginationState.Idle)
    override suspend fun paginateBackwards(count: Int): Boolean = false
    override fun close() {}
}

private class FakeSdk(var next: () -> FakeHandle) : MatrixSdk {
    val configs = mutableListOf<MatrixClientConfig>()
    val handles = mutableListOf<FakeHandle>()
    override suspend fun createClient(config: MatrixClientConfig): MatrixClientHandle {
        configs += config
        return next().also { handles += it }
    }
}

/** Records the thread of every access, to prove the encrypted store is never hit off the IO dispatcher. */
private class ThreadRecordingKv : SecretKeyValueStore {
    val inner = FakeKeyValueStore()
    val threads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    override fun getString(key: String): String? { threads += Thread.currentThread().name; return inner.getString(key) }
    override fun putAll(entries: Map<String, String?>) { threads += Thread.currentThread().name; inner.putAll(entries) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MatrixClientManagerTest {
    private val log = mutableListOf<String>()
    private lateinit var dir: File
    private var kv: SecretKeyValueStore = FakeKeyValueStore()
    private lateinit var store: MatrixAccountStore
    private lateinit var sdk: FakeSdk

    @Before fun setUp() {
        dir = Files.createTempDirectory("matrix-test").toFile()
        sdk = FakeSdk { FakeHandle(log) }
        store = MatrixAccountStore(kv) { ByteArray(32) { 7 } }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun useKv(k: SecretKeyValueStore) {
        kv = k
        store = MatrixAccountStore(kv) { ByteArray(32) { 7 } }
    }

    private fun TestScope.manager(io: CoroutineDispatcher = StandardTestDispatcher(testScheduler)) =
        MatrixClientManager(sdk, store, File(dir, "matrix"), StandardTestDispatcher(testScheduler), io) { it }

    private fun MatrixClientManager.doLogin(user: String = "alice", pw: String = "pw", ca: ByteArray? = null) =
        login(" https://hs.example.test:8443/ ", " $user ", pw, " @bot:example.test ", ca)

    @Test fun login_success_goes_ready_and_saves_session() = runTest {
        val m = manager()
        m.doLogin().join()
        val s = m.state.value as MatrixState.Ready
        assertEquals("@alice:example.test", s.userId)
        assertEquals("DEV123", s.deviceId)
        assertEquals("@bot:example.test", s.botMxid)
        val saved = store.load()!!
        assertEquals("tok-alice", saved.session.accessToken)
        assertEquals("@bot:example.test", saved.botMxid)
        assertEquals(listOf("login:alice", "startSync"), log)
        assertEquals("https://hs.example.test:8443", sdk.configs.single().homeserverUrl)
        assertEquals(store.storePassphrase(), sdk.configs.single().storePassphrase)
        assertTrue((kv as FakeKeyValueStore).map.values.none { it == "pw" })
    }

    @Test fun login_failure_goes_error_and_persists_nothing() = runTest {
        sdk.next = { FakeHandle(log, loginError = RuntimeException("bad creds", IllegalStateException("M_FORBIDDEN"))) }
        val m = manager()
        m.doLogin().join()
        val s = m.state.value as MatrixState.Error
        assertTrue(s.message, s.message.contains("bad creds"))
        assertTrue(s.message, s.message.contains("M_FORBIDDEN"))
        assertNull(store.load())
        assertTrue(sdk.handles.single().closed)
        assertFalse(File(dir, "matrix/s0").exists())
    }

    @Test fun failed_login_keeps_a_previously_saved_session_and_its_store() = runTest {
        store.save(MatrixAccount(sampleSession("old-tok"), "@bot:example.test", storeSlot = "s0"))
        File(dir, "matrix/s0/data").mkdirs()
        sdk.next = { FakeHandle(log, loginError = RuntimeException("bad creds")) }
        val m = manager()
        m.doLogin().join()
        assertTrue(m.state.value is MatrixState.Error)
        assertEquals("old-tok", store.load()!!.session.accessToken)
        assertEquals("s0", store.load()!!.storeSlot)
        assertTrue(File(dir, "matrix/s0/data").exists())
        // The attempt used a different slot than the saved session.
        assertTrue(sdk.configs.single().dataDir.path.contains("/s1/"))
        assertFalse(File(dir, "matrix/s1").exists())
    }

    @Test fun successful_relogin_replaces_old_session_and_drops_old_store() = runTest {
        store.save(MatrixAccount(sampleSession("old-tok"), "@bot:example.test", storeSlot = "s0"))
        File(dir, "matrix/s0/data").mkdirs()
        val m = manager()
        m.doLogin().join()
        assertTrue(m.state.value is MatrixState.Ready)
        assertEquals("s1", store.load()!!.storeSlot)
        assertEquals("tok-alice", store.load()!!.session.accessToken)
        assertFalse(File(dir, "matrix/s0").exists())
    }

    @Test fun sync_start_failure_after_login_cleans_up_and_logs_out_the_new_device() = runTest {
        sdk.next = { FakeHandle(log, syncError = RuntimeException("sync boom")) }
        val m = manager()
        m.doLogin().join()
        assertTrue(m.state.value is MatrixState.Error)
        assertNull(store.load())
        val h = sdk.handles.single()
        assertTrue(h.closed)
        assertTrue(h.serverLoggedOut)
    }

    @Test fun sync_start_failure_restores_the_previous_saved_session() = runTest {
        store.save(MatrixAccount(sampleSession("old-tok"), "@bot:example.test"))
        sdk.next = { FakeHandle(log, syncError = RuntimeException("sync boom")) }
        val m = manager()
        m.doLogin().join()
        assertEquals("old-tok", store.load()!!.session.accessToken)
    }

    @Test fun invalid_homeserver_url_is_an_error_without_touching_sdk() = runTest {
        val m = manager()
        m.login("hs.example", "u", "p", "@b:x", null).join()
        assertTrue(m.state.value is MatrixState.Error)
        assertTrue(sdk.configs.isEmpty())
    }

    @Test fun blank_password_is_rejected_before_anything_is_touched() = runTest {
        val m = manager()
        m.doLogin(pw = "").join()
        assertTrue(m.state.value is MatrixState.Error)
        assertTrue(sdk.configs.isEmpty())
        m.doLogin(pw = "   ").join()
        assertTrue(sdk.configs.isEmpty())
    }

    @Test fun blank_password_never_disturbs_a_live_session() = runTest {
        val m = manager()
        m.doLogin().join()
        m.doLogin(pw = "").join()
        assertTrue(m.state.value is MatrixState.Ready)
        assertEquals("tok-alice", store.load()!!.session.accessToken)
        assertFalse(sdk.handles.single().closed)
    }

    @Test fun blank_password_keeps_restore_failed_state_and_saved_session() = runTest {
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        sdk.next = { FakeHandle(log, restoreError = RuntimeException("offline")) }
        val m = manager()
        m.restoreOnStart().join()
        m.doLogin(pw = "").join()
        assertTrue(m.state.value is MatrixState.RestoreFailed)
        assertNotNull(store.load())
    }

    @Test fun double_login_tap_does_not_destroy_the_session() = runTest {
        val m = manager()
        val a = m.doLogin()
        val b = m.doLogin(pw = "") // what a second tap after the VM blanked the password looks like
        val c = m.doLogin(user = "mallory")
        a.join(); b.join(); c.join()
        assertTrue(m.state.value is MatrixState.Ready)
        assertEquals(1, sdk.handles.size)
        assertFalse(sdk.handles.single().closed)
        assertEquals("tok-alice", store.load()!!.session.accessToken)
    }

    @Test fun login_while_ready_is_ignored() = runTest {
        val m = manager()
        m.doLogin().join()
        m.doLogin(user = "bob").join()
        assertEquals(1, sdk.handles.size)
        assertEquals("tok-alice", store.load()!!.session.accessToken)
    }

    @Test fun login_passes_ca_as_der_list() = runTest {
        val der = byteArrayOf(0x30, 1, 2, 3, 4, 5)
        val m = manager()
        m.doLogin(ca = der).join()
        assertEquals(1, sdk.configs.single().caCerts.size)
        assertEquals(1, store.load()!!.caCerts.size)
    }

    @Test fun restore_success_goes_ready_using_saved_session() = runTest {
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        val m = manager()
        m.restoreOnStart().join()
        assertTrue(m.state.value is MatrixState.Ready)
        assertEquals(listOf("restore", "startSync"), log)
        assertEquals("https://192.0.2.1:8443", sdk.configs.single().homeserverUrl)
    }

    @Test fun transient_restore_failure_keeps_session_and_is_retryable() = runTest {
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        var fail = true
        sdk.next = { FakeHandle(log, restoreError = if (fail) RuntimeException("network unreachable") else null) }
        val m = manager()
        m.restoreOnStart().join()
        val s = m.state.value as MatrixState.RestoreFailed
        assertTrue(s.retryable)
        assertTrue(s.message.contains("network unreachable"))
        assertNotNull(store.load())
        assertTrue(sdk.handles.single().closed)

        fail = false
        m.restoreOnStart().join() // Retry
        assertTrue(m.state.value is MatrixState.Ready)
    }

    @Test fun server_rejected_session_is_not_retryable_but_still_kept() = runTest {
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        sdk.next = { FakeHandle(log, restoreError = RuntimeException("M_UNKNOWN_TOKEN: Unknown access token")) }
        val m = manager()
        m.restoreOnStart().join()
        assertFalse((m.state.value as MatrixState.RestoreFailed).retryable)
        assertNotNull(store.load())
    }

    @Test fun failed_login_from_restore_failed_keeps_retry_available() = runTest {
        store.save(MatrixAccount(sampleSession("old-tok"), "@bot:example.test"))
        var restoreFails = true
        sdk.next = {
            FakeHandle(
                log,
                restoreError = if (restoreFails) RuntimeException("offline") else null,
                loginError = RuntimeException("bad creds"),
            )
        }
        val m = manager()
        m.restoreOnStart().join()
        m.doLogin().join()
        assertTrue(m.state.value is MatrixState.RestoreFailed)
        assertTrue(m.loginError.value!!.contains("bad creds"))
        restoreFails = false
        m.restoreOnStart().join()
        assertTrue(m.state.value is MatrixState.Ready)
    }

    @Test fun restore_is_allowed_again_after_a_login_error() = runTest {
        sdk.next = { FakeHandle(log, loginError = RuntimeException("bad creds")) }
        val m = manager()
        m.doLogin().join()
        assertTrue(m.state.value is MatrixState.Error)
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        sdk.next = { FakeHandle(log) }
        m.restoreOnStart().join()
        assertTrue(m.state.value is MatrixState.Ready)
    }

    @Test fun blank_password_message_is_shown_in_every_state() = runTest {
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        sdk.next = { FakeHandle(log, restoreError = RuntimeException("offline")) }
        val m = manager()
        m.doLogin(pw = "").join()
        assertEquals("A password is required", m.loginError.value)
        m.restoreOnStart().join()
        assertTrue(m.state.value is MatrixState.RestoreFailed)
        m.doLogin(pw = "").join()
        assertEquals("A password is required", m.loginError.value)
        assertTrue(m.state.value is MatrixState.RestoreFailed)
    }

    @Test fun logout_preempts_a_running_login_and_queued_ones() = runTest {
        val gate = CompletableDeferred<Unit>()
        sdk.next = { FakeHandle(log, loginGate = gate) }
        val m = manager()
        val first = m.doLogin()
        val queued = m.doLogin(user = "bob")
        runCurrent()
        assertEquals(MatrixState.LoggingIn, m.state.value)
        m.logout().join()
        first.join(); queued.join()
        assertEquals(MatrixState.LoggedOut, m.state.value)
        assertNull(store.load())
        assertEquals(1, sdk.handles.size)
        assertTrue(sdk.handles.single().closed)
    }

    @Test fun relogin_after_failed_restore_that_fails_keeps_the_saved_session() = runTest {
        store.save(MatrixAccount(sampleSession("old-tok"), "@bot:example.test"))
        sdk.next = { FakeHandle(log, restoreError = RuntimeException("offline"), loginError = RuntimeException("also offline")) }
        val m = manager()
        m.restoreOnStart().join()
        m.doLogin().join()
        assertEquals("old-tok", store.load()!!.session.accessToken)
    }

    @Test fun restore_without_saved_session_does_nothing() = runTest {
        val m = manager()
        m.restoreOnStart().join()
        assertEquals(MatrixState.LoggedOut, m.state.value)
        assertTrue(sdk.configs.isEmpty())
    }

    @Test fun logout_during_restore_cancels_it_and_wipes_everything() = runTest {
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        File(dir, "matrix/s0/data").mkdirs()
        val gate = CompletableDeferred<Unit>()
        sdk.next = { FakeHandle(log, restoreGate = gate) }
        val m = manager()
        val restore = m.restoreOnStart()
        runCurrent()
        assertEquals(MatrixState.Restoring, m.state.value)
        m.logout().join()
        restore.join()
        assertEquals(MatrixState.LoggedOut, m.state.value)
        assertNull(store.load())
        assertFalse(File(dir, "matrix").exists())
        assertTrue(sdk.handles.single().closed)
    }

    @Test fun session_refresh_callback_persists_new_tokens() = runTest {
        val m = manager()
        m.doLogin().join()
        // Same user/device as the saved account.
        val refreshed = sampleSession("tok-new", "ref-new")
        sdk.configs.single().onSessionRefreshed(refreshed)
        assertEquals("tok-new", store.load()!!.session.accessToken)
        assertEquals("tok-new", sdk.configs.single().currentSession("@alice:example.test")!!.accessToken)
        assertNull(sdk.configs.single().currentSession("@someone:else"))
    }

    @Test fun refresh_after_logout_does_not_resurrect_the_account() = runTest {
        val m = manager()
        m.doLogin().join()
        val cfg = sdk.configs.single()
        m.logout().join()
        cfg.onSessionRefreshed(sampleSession("late"))
        assertNull(store.load())
    }

    @Test fun logout_wipes_everything_and_destroys_in_order() = runTest {
        val m = manager()
        m.doLogin().join()
        File(dir, "matrix/s0/data").mkdirs()
        val h = sdk.handles.single()
        h.diffs.send(listOf(ListDiff.Append(listOf(MatrixRoomSummary("!r:x", "Room", true, 2, true)))))
        advanceUntilIdle()
        assertEquals(1, m.rooms.value.size)

        m.logout().join()
        assertEquals(MatrixState.LoggedOut, m.state.value)
        assertNull(store.load())
        assertTrue(m.rooms.value.isEmpty())
        assertFalse(File(dir, "matrix").exists())
        assertTrue(h.closed)
        assertTrue(log.indexOf("serverLogout") < log.indexOf("close"))
        // Passphrase is kept (generated once).
        assertNotNull((kv as FakeKeyValueStore).map.values.firstOrNull { it.length == 64 })
    }

    @Test fun open_timeline_serialises_with_logout_and_never_touches_a_closed_handle() = runTest {
        val gate = CompletableDeferred<Unit>()
        sdk.next = { FakeHandle(log, openTimelineGate = gate) }
        val m = manager()
        m.doLogin().join()
        val h = sdk.handles.single()

        val opened = async { m.openTimeline("!room:x") }
        runCurrent()
        // openTimeline holds the lock, blocked on the gate; logout must not have torn down yet.
        assertFalse(h.closed)

        val logoutJob = m.logout()
        runCurrent()
        // logout is waiting on the lock held by the in-flight open, so nothing is torn down.
        assertFalse(h.closed)
        assertTrue(logoutJob.isActive)

        gate.complete(Unit)
        val handle = opened.await()
        assertNotNull(handle) // the open completed before teardown
        logoutJob.join()
        assertTrue(h.closed)
        assertFalse(h.timelineOpenedWhileClosed)
    }

    @Test fun room_diffs_are_applied_as_immutable_snapshots() = runTest {
        val m = manager()
        m.doLogin().join()
        val h = sdk.handles.single()
        val a = MatrixRoomSummary("!a:x", "A", false, 0, true)
        val b = MatrixRoomSummary("!b:x", "B", true, 3, false)
        h.diffs.send(listOf(ListDiff.Append(listOf(a))))
        advanceUntilIdle()
        val first = m.rooms.value
        h.diffs.send(listOf(ListDiff.PushBack(b), ListDiff.Set(0, a.copy(unread = 5))))
        advanceUntilIdle()
        assertEquals(listOf(a), first)
        assertEquals(listOf(a.copy(unread = 5), b), m.rooms.value)
    }

    @Test fun shutdown_closes_client_but_keeps_saved_session() = runTest {
        val m = manager()
        m.doLogin().join()
        m.shutdown()
        assertTrue(sdk.handles.single().closed)
        assertNotNull(store.load())
        assertEquals(MatrixState.LoggedOut, m.state.value)
    }

    @Test fun account_store_is_only_touched_on_the_io_dispatcher() = runTest {
        val rec = ThreadRecordingKv()
        useKv(rec)
        store.save(MatrixAccount(sampleSession(), "@bot:example.test"))
        rec.threads.clear()
        val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "io-test") }
        try {
            val m = manager(io = exec.asCoroutineDispatcher())
            m.restoreOnStart().join()
            m.logout().join()
            m.doLogin().join()
            m.logout().join()
        } finally {
            exec.shutdown()
        }
        assertTrue(rec.threads.toString(), rec.threads.isNotEmpty())
        assertTrue(rec.threads.toString(), rec.threads.all { it.startsWith("io-test") })
    }
}
