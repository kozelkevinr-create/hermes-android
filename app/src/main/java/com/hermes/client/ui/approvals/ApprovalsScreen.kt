package com.hermes.client.ui.approvals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.repository.ApprovalInbox
import com.hermes.client.data.repository.PendingApproval
import com.hermes.client.ui.chat.ApprovalChoice
import com.hermes.client.ui.chat.ApprovalSheet
import com.hermes.client.ui.chat.ApprovalTier
import com.hermes.client.ui.chat.tierFor
import com.hermes.client.ui.components.HermesTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ApprovalsViewModel @Inject constructor(private val inbox: ApprovalInbox) : ViewModel() {
    val pending: StateFlow<List<PendingApproval>> =
        inbox.pending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), inbox.pending.value)

    fun respond(item: PendingApproval, choice: ApprovalChoice) {
        viewModelScope.launch { inbox.respond(item, choice) }
    }
}

/** Every approval the agent is waiting on, oldest first; tap one to review it in full and decide. */
@Composable
fun ApprovalsScreen(onBack: () -> Unit, vm: ApprovalsViewModel = hiltViewModel()) {
    val pending by vm.pending.collectAsStateWithLifecycle()
    var openRpcId by remember { mutableStateOf<String?>(null) }
    val open = pending.firstOrNull { it.rpcId == openRpcId }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Approvals",
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
                Text("No approvals waiting", style = MaterialTheme.typography.titleMedium)
                Text(
                    "When the agent needs your permission to run something, it shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pending, key = { it.rpcId + it.request.requestId }) { item ->
                    ApprovalCard(item, onOpen = { openRpcId = item.rpcId }, onDeny = { vm.respond(item, ApprovalChoice.DENY) })
                }
            }
        }
    }

    open?.let { item ->
        ApprovalSheet(
            req = item.request,
            onRespond = { choice -> vm.respond(item, choice); openRpcId = null },
            onDismiss = { openRpcId = null },
        )
    }
}

@Composable
private fun ApprovalCard(item: PendingApproval, onOpen: () -> Unit, onDeny: () -> Unit) {
    val elevated = tierFor(item.request.allowPermanent) == ApprovalTier.ELEVATED
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                (if (elevated) "Elevated" else "Approval needed") +
                    (item.request.patternKeys.firstOrNull()?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                color = if (elevated) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (item.request.command.isNotBlank()) {
                Text(
                    item.request.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (item.request.description.isNotBlank()) {
                Text(
                    item.request.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap to review", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onDeny) { Text("Deny") }
            }
        }
    }
}
