package com.hermes.client.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Typeface for UI text: the bundled terminal faces, or the device's default sans. */
enum class FontStyle { TERMINAL, SYSTEM }

/** Look-and-feel options beyond light/dark. Defaults reproduce the original look. */
data class AppearancePrefs(
    val amoled: Boolean = false,
    val dynamicColor: Boolean = false,
    val textScale: Float = 1f,
    val fontStyle: FontStyle = FontStyle.TERMINAL,
    val compactChat: Boolean = false,
) {
    companion object {
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.4f
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** Device-local app preferences: theme mode and tool-call display verbosity. */
class SettingsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val toolDisplayKey = stringPreferencesKey("tool_call_display") // "product" | "technical"
    private val debugLoggingKey = booleanPreferencesKey("debug_logging")
    private val matrixEncryptedKey = booleanPreferencesKey("matrix_rooms_encrypted")
    private val amberKey = booleanPreferencesKey("amber_palette")
    private val soundKey = booleanPreferencesKey("sound_effects")

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

    /** Amber ("monochrome amber CRT") alt-palette instead of the default phosphor green. */
    val amberPalette: Flow<Boolean> = context.settingsDataStore.data.map { it[amberKey] ?: false }

    suspend fun setAmberPalette(on: Boolean) {
        context.settingsDataStore.edit { it[amberKey] = on }
    }

    /** Synthesized terminal blips. Off by default. */
    val soundEffects: Flow<Boolean> = context.settingsDataStore.data.map { it[soundKey] ?: false }

    suspend fun setSoundEffects(on: Boolean) {
        context.settingsDataStore.edit { it[soundKey] = on }
    }

    private val amoledKey = booleanPreferencesKey("appearance_amoled")
    private val dynamicKey = booleanPreferencesKey("appearance_dynamic_color")
    private val textScaleKey = floatPreferencesKey("appearance_text_scale")
    private val fontStyleKey = stringPreferencesKey("appearance_font_style")
    private val compactChatKey = booleanPreferencesKey("appearance_compact_chat")

    val appearance: Flow<AppearancePrefs> = context.settingsDataStore.data.map { p ->
        AppearancePrefs(
            amoled = p[amoledKey] ?: false,
            dynamicColor = p[dynamicKey] ?: false,
            textScale = (p[textScaleKey] ?: 1f)
                .coerceIn(AppearancePrefs.MIN_SCALE, AppearancePrefs.MAX_SCALE),
            fontStyle = runCatching { FontStyle.valueOf(p[fontStyleKey] ?: "") }
                .getOrDefault(FontStyle.TERMINAL),
            compactChat = p[compactChatKey] ?: false,
        )
    }

    suspend fun setAmoled(on: Boolean) { context.settingsDataStore.edit { it[amoledKey] = on } }
    suspend fun setDynamicColor(on: Boolean) { context.settingsDataStore.edit { it[dynamicKey] = on } }
    suspend fun setTextScale(scale: Float) { context.settingsDataStore.edit { it[textScaleKey] = scale } }
    suspend fun setFontStyle(style: FontStyle) { context.settingsDataStore.edit { it[fontStyleKey] = style.name } }
    suspend fun setCompactChat(on: Boolean) { context.settingsDataStore.edit { it[compactChatKey] = on } }

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
