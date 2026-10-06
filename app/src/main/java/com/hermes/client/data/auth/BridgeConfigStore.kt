package com.hermes.client.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class BridgeConfig(val baseUrl: String, val token: String) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

/**
 * Credentials for the Hermes Dispatch Bridge — the server-side sidecar that owns long-running
 * runs so they survive the phone sleeping, and pushes progress to ntfy.
 *
 * Kept separate from [CredentialStore] because the bridge is a different service with its own
 * bearer token; conflating them would mean a gateway re-auth silently invalidating bridge access.
 * Stored encrypted: the token grants the ability to task the agent.
 */
class BridgeConfigStore(private val context: Context) {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): BridgeConfig =
        BridgeConfig(
            baseUrl = prefs.getString(KEY_URL, "").orEmpty(),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
        )

    fun save(config: BridgeConfig) {
        prefs.edit()
            .putString(KEY_URL, config.baseUrl.trimEnd('/'))
            .putString(KEY_TOKEN, config.token)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "hermes_bridge_prefs"
        const val KEY_URL = "bridge_url"
        const val KEY_TOKEN = "bridge_token"
    }
}
