package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.data.matrix.MatrixState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Drives the Matrix room list: account state and rooms from the manager, plus the search query. */
@HiltViewModel
class MatrixRoomListViewModel internal constructor(
    matrix: StateFlow<MatrixState>,
    rooms: StateFlow<List<MatrixRoomSummary>>,
    private val onRetry: () -> Unit,
) : ViewModel() {
    @Inject constructor(manager: MatrixClientManager) :
        this(manager.state, manager.rooms, { manager.restoreOnStart() })

    private val query = MutableStateFlow("")

    val uiState: StateFlow<RoomListUiState> = combine(matrix, rooms, query) { m, r, q ->
        roomListUiState(m, r, q)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        roomListUiState(matrix.value, rooms.value, ""),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Re-runs the saved-session restore (the banner's Retry). */
    fun retry() = onRetry()
}
