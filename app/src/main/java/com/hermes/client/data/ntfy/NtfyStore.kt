package com.hermes.client.data.ntfy

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.hermes.client.data.diagnostics.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.ntfyDataStore by preferencesDataStore(name = "ntfy")

/**
 * Device-local ntfy settings: the server, user and topic list as one JSON document in DataStore, and
 * the password in EncryptedSharedPreferences (Keystore-backed), never alongside the rest.
 */
class NtfyStore(private val context: Context, private val json: Json) {
    private val kConfig = stringPreferencesKey("config")

    val config: Flow<NtfyConfig> = context.ntfyDataStore.data.map { p ->
        p[kConfig]?.let { runCatching { json.decodeFromString<NtfyConfig>(it) }.getOrNull() } ?: NtfyConfig()
    }

    suspend fun update(transform: (NtfyConfig) -> NtfyConfig) {
        context.ntfyDataStore.edit { p ->
            val current = p[kConfig]?.let { runCatching { json.decodeFromString<NtfyConfig>(it) }.getOrNull() } ?: NtfyConfig()
            p[kConfig] = json.encodeToString(NtfyConfig.serializer(), transform(current))
        }
    }

    private val secure: SharedPreferences? by lazy {
        runCatching {
            EncryptedSharedPreferences.create(
                "ntfy_credentials",
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure { DebugLog.log("ntfy", "credential store unavailable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    fun password(): String = secure?.getString("password", null).orEmpty()

    /** Saves the password; blank clears it. Returns false when secure storage is unavailable. */
    fun setPassword(value: String): Boolean {
        val p = secure ?: return false
        p.edit().apply { if (value.isBlank()) remove("password") else putString("password", value) }.apply()
        return true
    }
}
