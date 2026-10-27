package com.hermes.client.ui.approvals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ClarifyInbox
import com.hermes.client.data.repository.PendingClarify
import com.hermes.client.ui.components.HermesTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ClarificationsViewModel @Inject constructor(private val inbox: ClarifyInbox) : ViewModel() {
    val pending: StateFlow<List<PendingClarify>> =
        inbox.pending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), inbox.pending.value)

    fun respond(item: PendingClarify, answer: String) {
        viewModelScope.launch { inbox.respond(item, answer) }
    }
}

/** Every question the agent is waiting on, oldest first; tap one to read it in full and answer. */
@Composable
fun ClarificationsScreen(onBack: () -> Unit, vm: ClarificationsViewModel = hiltViewModel()) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    var openId by remember { mutableStateOf<String?>(null) }
    val open = pending.firstOrNull { it.requestId == openId }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Clarifications",
                subtitle = if (pending.isEmpty()) "Nothing waiting" else "${pending.size} waiting",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (pending.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("No questions waiting", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When the agent needs to ask you something, it shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pending, key = { it.requestId }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { openId = item.requestId }) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Agent question", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(
                                item.question,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                "Tap to answer",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    open?.let { item ->
        var answer by remember(item.requestId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { openId = null },
            title = { Text("Clarification") },
            text = {
                Column {
                    Text(item.question)
                    OutlinedTextField(value = answer, onValueChange = { answer = it }, modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = answer.isNotBlank(), onClick = { vm.respond(item, answer.trim()); openId = null }) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { openId = null }) { Text("Close") } },
        )
    }
}
