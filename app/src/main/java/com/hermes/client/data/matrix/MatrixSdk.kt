package com.hermes.client.data.matrix

import kotlinx.coroutines.flow.Flow
import java.io.File

/** Parameters for building a client. Paths must be unique per session. */
class MatrixClientConfig(
    val homeserverUrl: String,
    /** DER certificates; non-empty means "trust only these" (the platform verifier cannot merge roots). */
    val caCerts: List<ByteArray>,
    val storePassphrase: String,
    val dataDir: File,
    val cacheDir: File,
    /** Called (on an SDK thread) when the SDK refreshes the session tokens. */
    val onSessionRefreshed: (MatrixSessionData) -> Unit,
    /** The persisted session for [userId], if any (SDK keychain lookup). */
    val currentSession: (userId: String) -> MatrixSessionData?,
)

/** Thin seam over the matrix-rust-sdk FFI so the account manager is JVM-testable with fakes. */
interface MatrixSdk {
    suspend fun createClient(config: MatrixClientConfig): MatrixClientHandle
}

interface MatrixClientHandle {
    suspend fun login(username: String, password: String, deviceName: String): MatrixSessionData
    suspend fun restore(session: MatrixSessionData)

    /**
     * Room-list diffs, already mapped to summaries. Single-collector; collect before [startSync].
     * Completes when the handle is closed.
     */
    val roomDiffs: Flow<List<ListDiff<MatrixRoomSummary>>>

    suspend fun startSync()
    suspend fun stopSync()

    /** Server-side logout (invalidates the access token). */
    suspend fun logout()

    /** Stops sync, then destroys every SDK object this handle holds. Idempotent. */
    suspend fun close()
}
