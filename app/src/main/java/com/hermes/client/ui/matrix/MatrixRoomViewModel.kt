package com.hermes.client.ui.matrix

import androidx.lifecycle.ViewModel
import com.hermes.client.data.matrix.MatrixClientManager
import com.hermes.client.data.matrix.MatrixRoomSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Room rows for the placeholder room screen, which only needs the room's name for its top bar. */
@HiltViewModel
class MatrixRoomViewModel @Inject constructor(manager: MatrixClientManager) : ViewModel() {
    val rooms: StateFlow<List<MatrixRoomSummary>> = manager.rooms
}
