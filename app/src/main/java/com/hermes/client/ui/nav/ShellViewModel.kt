package com.hermes.client.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.network.ProfileDto
import com.hermes.client.data.repository.ProfileAccentStore
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val rest: com.hermes.client.data.network.HermesRestApi,
    private val profileManager: ProfileManager,
    private val accentStore: ProfileAccentStore,
    private val healthMonitor: com.hermes.client.data.network.GatewayHealthMonitor,
) : ViewModel() {
    val profiles: StateFlow<List<ProfileDto>> = profileManager.list
    val active: StateFlow<String?> = profileManager.active

    /** Backend health for the shell's status strip + You-tab badge. */
    val health: StateFlow<com.hermes.client.data.network.GatewayHealth> = healthMonitor.health

    init { viewModelScope.launch { profileManager.refresh() } }

    fun switchProfile(name: String) = viewModelScope.launch { profileManager.switchTo(name) }

    /** Last rename failure, surfaced by the Profiles screen. Null once a rename succeeds. */
    private val _renameError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val renameError: StateFlow<String?> = _renameError

    /**
     * Rename [name] to [newName]. For the default profile the gateway stores this as a display
     * name and keeps the id "default"; for others it renames the profile directory, which briefly
     * restarts the gateway.
     */
    fun renameProfile(name: String, newName: String) = viewModelScope.launch {
        _renameError.value = null
        runCatching { rest.renameProfile(name, newName.trim()) }
            .onSuccess { profileManager.refresh() }
            .onFailure { _renameError.value = it.message ?: "Rename failed" }
    }

    private val _createError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val createError: StateFlow<String?> = _createError

    /** Create [name] (optionally cloned from [cloneFrom]); [onDone] gets the new id. */
    fun createProfile(
        name: String,
        cloneFrom: String?,
        description: String,
        keepSkills: List<String>,
        provider: String?,
        model: String?,
        onDone: (String) -> Unit,
    ) = viewModelScope.launch {
            _createError.value = null
            runCatching { rest.createProfile(name, cloneFrom, description, keepSkills, provider, model) }
                .onSuccess { profileManager.refresh(); onDone(name) }
                .onFailure { _createError.value = it.message ?: "Could not create profile" }
        }

    /** Names of the skills a new profile could start with (the dashboard profile's set). */
    suspend fun availableSkills(): List<String> =
        runCatching { rest.skills().map { it.name }.sorted() }.getOrDefault(emptyList())

    /** Provider and model catalog for the new-profile model picker. */
    suspend fun modelProviders() = runCatching { rest.modelProviders() }.getOrDefault(emptyList())

    fun clearCreateError() { _createError.value = null }

    fun clearRenameError() { _renameError.value = null }

    /** Set a custom accent colour for [profile] (persisted; overrides the auto-hashed hue). */
    fun setAccent(profile: String, argb: Int) = viewModelScope.launch { accentStore.setColor(profile, argb) }

    /** Clear [profile]'s custom colour, reverting to the auto hue. */
    fun clearAccent(profile: String) = viewModelScope.launch { accentStore.clear(profile) }

    /** Fire an immediate health probe (Re-check button). */
    fun recheckHealth() = healthMonitor.recheck()

    /** Foreground/background gating for periodic probing. */
    fun onAppForeground() = healthMonitor.startForeground()
    fun onAppBackground() = healthMonitor.stopForeground()
}
