package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixState
import com.hermes.client.data.repository.ChatsSourceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Matrix | Gateway choice for the Chats tab, with the default resolved from the Matrix account. */
@HiltViewModel
class ChatsSourceViewModel internal constructor(
    saved: Flow<ChatsSource?>,
    matrix: StateFlow<MatrixState>,
    private val persist: suspend (ChatsSource) -> Unit,
) : ViewModel() {
    @Inject constructor(store: ChatsSourceStore, manager: MatrixClientManager) :
        this(store.saved, manager.state, { store.set(it) })

    val source: StateFlow<ChatsSource> = combine(saved, matrix) { s, m ->
        resolveChatsSource(s, matrixHasSavedSession(m))
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        resolveChatsSource(null, matrixHasSavedSession(matrix.value)),
    )

    fun select(source: ChatsSource) {
        viewModelScope.launch { persist(source) }
    }
}
