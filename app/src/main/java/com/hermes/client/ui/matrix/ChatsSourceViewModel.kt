package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.repository.ChatsSourceStore
import com.hermes.client.data.repository.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Matrix | Gateway choice for the Chats tab.
 *
 * [source] is null until the saved choice has loaded, so the screen can draw nothing rather than
 * flicker. With nothing saved, the default is resolved from the Matrix account the first time both
 * inputs are known, then frozen: a login from LoggingIn to Ready does not flip the view. Only
 * [select] (an explicit user toggle, persisted) changes it afterwards.
 */
@HiltViewModel
class ChatsSourceViewModel internal constructor(
    saved: Flow<ChatsSource?>,
    matrix: StateFlow<MatrixState>,
    private val persist: suspend (ChatsSource) -> Unit,
    private val profileRefresh: suspend () -> Unit,
) : ViewModel() {
    @Inject constructor(store: ChatsSourceStore, manager: MatrixClientManager, profiles: ProfileManager) :
        this(store.saved, manager.state, { store.set(it) }, { profiles.refresh() })

    // Written only from the combine below, which runs on one collector coroutine.
    private var frozenDefault: ChatsSource? = null

    val source: StateFlow<ChatsSource?> = combine(saved, matrix) { chosen, m ->
        chosen ?: frozenDefault ?: resolveChatsSource(null, matrixHasSavedSession(m))
            .also { frozenDefault = it }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // The gateway ViewModel (which refreshes profiles) only exists on the Gateway tab, so
        // Matrix-first users would otherwise never get an active profile for the other tabs.
        // Refresh once here, off the main thread; failures are ignored (ProfileManager tolerates them).
        viewModelScope.launch(Dispatchers.IO) { runCatching { profileRefresh() } }
    }

    fun select(source: ChatsSource) {
        viewModelScope.launch { persist(source) }
    }
}
