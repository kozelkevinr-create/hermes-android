package com.hermes.client.data.matrix

import android.content.Context
import android.content.SharedPreferences
import java.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.hermes.client.data.diagnostics.DebugLog
import java.security.SecureRandom

/** Minimal string key-value backing so the store is JVM-testable with a fake. */
interface SecretKeyValueStore {
    fun getString(key: String): String?

    /** Applies all entries atomically; a null value removes the key. */
    fun putAll(entries: Map<String, String?>)
}

/** [SecretKeyValueStore] over EncryptedSharedPreferences (same mechanism as EncryptedCredentialStore). */
class EncryptedKeyValueStore(private val context: Context) : SecretKeyValueStore {
    private val prefs: SharedPreferences by lazy { openOrReset() }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    // A corrupt keyset lives in this prefs file: wipe it once and start fresh (the user logs in
    // again). Unlike EncryptedCredentialStore this never deletes the shared Keystore master key,
    // since that would also invalidate the gateway credentials.
    private fun openOrReset(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        DebugLog.log("matrix", "account store unreadable, resetting: ${e.javaClass.simpleName}")
        runCatching { context.deleteSharedPreferences(PREFS_NAME) }
        create()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putAll(entries: Map<String, String?>) {
        val e = prefs.edit()
        entries.forEach { (k, v) -> if (v == null) e.remove(k) else e.putString(k, v) }
        e.apply()
    }

    private companion object {
        const val PREFS_NAME = "hermes_matrix_account"
    }
}

/**
 * Encrypted persistence of the Matrix account: session, bot MXID, optional CA, and the random
 * SDK store passphrase (generated once, kept across logouts, never shown). The login password is
 * never stored.
 */
class MatrixAccountStore(
    private val kv: SecretKeyValueStore,
    private val randomBytes: () -> ByteArray = { ByteArray(32).also { SecureRandom().nextBytes(it) } },
) {
    fun load(): MatrixAccount? {
        val token = kv.getString(K_ACCESS) ?: return null
        val user = kv.getString(K_USER) ?: return null
        val device = kv.getString(K_DEVICE) ?: return null
        val homeserver = kv.getString(K_HOMESERVER) ?: return null
        return MatrixAccount(
            session = MatrixSessionData(
                accessToken = token,
                refreshToken = kv.getString(K_REFRESH),
                userId = user,
                deviceId = device,
                homeserverUrl = homeserver,
                oauthData = kv.getString(K_OAUTH),
                slidingSyncVersion = kv.getString(K_SLIDING) ?: "NATIVE",
            ),
            botMxid = kv.getString(K_BOT).orEmpty(),
            caCerts = kv.getString(K_CA).orEmpty().lines().filter { it.isNotBlank() }
                .map { Base64.getDecoder().decode(it) },
            storeSlot = kv.getString(K_SLOT) ?: "s0",
        )
    }

    fun save(account: MatrixAccount) {
        kv.putAll(
            sessionEntries(account.session) + mapOf(
                K_BOT to account.botMxid,
                K_SLOT to account.storeSlot,
                K_CA to account.caCerts.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { Base64.getEncoder().encodeToString(it) },
            ),
        )
    }

    /**
     * Persists a refreshed session. No-op when no account is saved (e.g. after logout) or when the
     * saved account is a different login (user/device), so a stray client can't overwrite it.
     */
    fun updateSession(session: MatrixSessionData) {
        if (kv.getString(K_ACCESS) == null) return
        if (kv.getString(K_USER) != session.userId || kv.getString(K_DEVICE) != session.deviceId) return
        kv.putAll(sessionEntries(session))
    }

    /** Removes the account but keeps the store passphrase. */
    fun clear() {
        kv.putAll(
            listOf(K_ACCESS, K_REFRESH, K_USER, K_DEVICE, K_HOMESERVER, K_OAUTH, K_SLIDING, K_BOT, K_CA, K_SLOT)
                .associateWith { null },
        )
    }

    fun hasAccount(): Boolean = kv.getString(K_ACCESS) != null

    /** Random 32 bytes (hex), created on first use and returned unchanged afterwards. */
    @Synchronized
    fun storePassphrase(): String {
        kv.getString(K_PASSPHRASE)?.let { return it }
        val fresh = randomBytes().joinToString("") { "%02x".format(it) }
        kv.putAll(mapOf(K_PASSPHRASE to fresh))
        return fresh
    }

    private fun sessionEntries(s: MatrixSessionData): Map<String, String?> = mapOf(
        K_ACCESS to s.accessToken,
        K_REFRESH to s.refreshToken,
        K_USER to s.userId,
        K_DEVICE to s.deviceId,
        K_HOMESERVER to s.homeserverUrl,
        K_OAUTH to s.oauthData,
        K_SLIDING to s.slidingSyncVersion,
    )

    private companion object {
        const val K_ACCESS = "access_token"
        const val K_REFRESH = "refresh_token"
        const val K_USER = "user_id"
        const val K_DEVICE = "device_id"
        const val K_HOMESERVER = "homeserver_url"
        const val K_OAUTH = "oauth_data"
        const val K_SLIDING = "sliding_sync_version"
        const val K_BOT = "bot_mxid"
        const val K_SLOT = "store_slot"
        const val K_CA = "ca_der_b64"
        const val K_PASSPHRASE = "store_passphrase"
    }
}
