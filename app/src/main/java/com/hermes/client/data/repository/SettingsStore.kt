package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** Device-local app preferences: theme mode and tool-call display verbosity. */
class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val toolDisplayKey = stringPreferencesKey("tool_call_display") // "product" | "technical"
    private val debugLoggingKey = booleanPreferencesKey("debug_logging")
    private val matrixEncryptedKey = booleanPreferencesKey("matrix_rooms_encrypted")

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: "DARK") }.getOrDefault(ThemeMode.DARK)
    }

    /** True = show full tool input/output (Technical); false = hide payloads (Product). */
    val toolCallTechnical: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        (prefs[toolDisplayKey] ?: "technical") == "technical"
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setToolCallTechnical(technical: Boolean) {
        context.settingsDataStore.edit { it[toolDisplayKey] = if (technical) "technical" else "product" }
    }

    /** Diagnostic logging toggle (Settings → Diagnostics). Off by default. */
    val debugLogging: Flow<Boolean> = context.settingsDataStore.data.map { it[debugLoggingKey] ?: false }

    suspend fun setDebugLogging(enabled: Boolean) {
        context.settingsDataStore.edit { it[debugLoggingKey] = enabled }
    }

    /**
     * Encrypted-by-default for new Matrix rooms (the new-room sheet's toggle position on open).
     * On by default: private rooms with the bot work end-to-encrypted.
     */
    val matrixRoomsEncrypted: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        runCatching { prefs[matrixEncryptedKey] ?: true }.getOrDefault(true)
    }

    suspend fun setMatrixRoomsEncrypted(enabled: Boolean) {
        context.settingsDataStore.edit { it[matrixEncryptedKey] = enabled }
    }
}
