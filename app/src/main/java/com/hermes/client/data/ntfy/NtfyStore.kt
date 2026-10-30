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
import kotlinx.serialization.builtins.serializer
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

    private val kHistory = stringPreferencesKey("history")
    private val kPinned = stringPreferencesKey("history_pinned")
    private val kDismissed = stringPreferencesKey("history_dismissed")
    private val kSeen = androidx.datastore.preferences.core.longPreferencesKey("history_seen")

    /** Epoch seconds when the history was last looked at; messages newer than this are unread. */
    val lastSeen: Flow<Long> = context.ntfyDataStore.data.map { it[kSeen] ?: 0L }

    suspend fun markSeen(seconds: Long) {
        context.ntfyDataStore.edit { p -> if (seconds > (p[kSeen] ?: 0L)) p[kSeen] = seconds }
    }
    private val historySer = kotlinx.serialization.builtins.ListSerializer(NtfyMessage.serializer())
    private val idsSer = kotlinx.serialization.builtins.ListSerializer(String.serializer())

    private fun androidx.datastore.preferences.core.Preferences.ids(key: androidx.datastore.preferences.core.Preferences.Key<String>): List<String> =
        this[key]?.let { runCatching { json.decodeFromString(idsSer, it) }.getOrNull() } ?: emptyList()

    /** Messages received so far, newest first (see [mergeNtfyHistory]). */
    val history: Flow<List<NtfyMessage>> = context.ntfyDataStore.data.map { p ->
        p[kHistory]?.let { runCatching { json.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
    }

    /** Ids of pinned messages: kept at the top, out of the size cap, and out of Clear. */
    val pinned: Flow<Set<String>> = context.ntfyDataStore.data.map { it.ids(kPinned).toSet() }

    /** Folds new messages in. Anything the user already removed stays removed, so a server refresh cannot bring it back. */
    suspend fun updateHistory(transform: (history: List<NtfyMessage>, pinned: Set<String>) -> List<NtfyMessage>) {
        context.ntfyDataStore.edit { p ->
            val current = p[kHistory]?.let { runCatching { json.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
            val gone = p.ids(kDismissed).toSet()
            p[kHistory] = json.encodeToString(historySer, transform(current, p.ids(kPinned).toSet()).filterNot { it.id in gone })
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) {
        context.ntfyDataStore.edit { p ->
            val ids = p.ids(kPinned).filterNot { it == id } + listOfNotNull(id.takeIf { pinned })
            p[kPinned] = json.encodeToString(idsSer, ids)
        }
    }

    /** Removes [ids] from the history for good (pinned ones are skipped). */
    suspend fun dismiss(ids: Collection<String>) {
        context.ntfyDataStore.edit { p ->
            val pinned = p.ids(kPinned).toSet()
            val drop = ids.filterNot { it in pinned }.toSet()
            if (drop.isEmpty()) return@edit
            val current = p[kHistory]?.let { runCatching { json.decodeFromString(historySer, it) }.getOrNull() } ?: emptyList()
            p[kHistory] = json.encodeToString(historySer, current.filterNot { it.id in drop })
            p[kDismissed] = json.encodeToString(idsSer, (p.ids(kDismissed) + drop).distinct().takeLast(1000))
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
