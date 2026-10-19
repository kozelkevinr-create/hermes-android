package com.hermes.client.ui.matrix

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar

/** Placeholder room route. The timeline arrives in Phase 3; for now it only names the room. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixRoomScreen(roomId: String, onBack: () -> Unit, vm: MatrixRoomViewModel = hiltViewModel()) {
    val rooms by vm.rooms.collectAsStateWithLifecycle()
    val name = rooms.firstOrNull { it.id == roomId }?.name ?: roomId
    Scaffold(
        topBar = {
            HermesTopBar(
                title = name,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Room view arrives in Phase 3",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}
