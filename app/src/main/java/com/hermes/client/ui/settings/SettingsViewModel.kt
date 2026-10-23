package com.hermes.client.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.HermesRestApi
import com.hermes.client.data.repository.AppearancePrefs
import com.hermes.client.data.repository.FontStyle
import com.hermes.client.data.repository.SettingsStore
import com.hermes.client.data.repository.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val rest: HermesRestApi,
) : ViewModel() {
    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
    val toolCallTechnical: StateFlow<Boolean> =
        settings.toolCallTechnical.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val amberPalette: StateFlow<Boolean> =
        settings.amberPalette.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val soundEffects: StateFlow<Boolean> =
        settings.soundEffects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val appearance: StateFlow<AppearancePrefs> =
        settings.appearance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearancePrefs())

    fun setAmoled(on: Boolean) = viewModelScope.launch { settings.setAmoled(on) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { settings.setDynamicColor(on) }
    fun setTextScale(scale: Float) = viewModelScope.launch { settings.setTextScale(scale) }
    fun setFontStyle(style: FontStyle) = viewModelScope.launch { settings.setFontStyle(style) }
    fun setCompactChat(on: Boolean) = viewModelScope.launch { settings.setCompactChat(on) }
    fun resetAppearance() = viewModelScope.launch {
        settings.setAmoled(false); settings.setDynamicColor(false); settings.setTextScale(1f)
        settings.setFontStyle(FontStyle.TERMINAL); settings.setCompactChat(false)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }
    fun setAmberPalette(on: Boolean) = viewModelScope.launch { settings.setAmberPalette(on) }
    fun setSoundEffects(on: Boolean) = viewModelScope.launch { settings.setSoundEffects(on) }
    fun setToolCallTechnical(technical: Boolean) =
        viewModelScope.launch { settings.setToolCallTechnical(technical) }

    suspend fun gatewayVersion(): String? = runCatching { rest.gatewayStatus().version }.getOrNull()
}
