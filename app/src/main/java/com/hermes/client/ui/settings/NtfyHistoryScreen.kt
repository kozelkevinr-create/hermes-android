package com.hermes.client.ui.settings

import android.text.format.DateUtils
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
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.hermes.client.data.ntfy.NtfyHistory
import com.hermes.client.data.ntfy.NtfyKind
import com.hermes.client.data.ntfy.NtfyMessage
import com.hermes.client.data.ntfy.NtfyStore
import com.hermes.client.ui.components.HermesTopBar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NtfyHistoryViewModel @Inject constructor(
    private val history: NtfyHistory,
    store: NtfyStore,
) : ViewModel() {
    val messages: StateFlow<List<NtfyMessage>> =
        history.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Topic id → (display name, kind), to label each entry with where it came from. */
    val topics: StateFlow<Map<String, Pair<String, NtfyKind>>> =
        store.config.map { c -> c.topics.associate { it.topic to (it.name to it.kind) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun noticeShown() { _notice.value = null }

    /** Pulls in what the server still caches, so messages from while the app was not listening show up. */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            history.refreshFromServer()
                .onFailure { _notice.value = "Couldn't reach the ntfy server: ${it.message ?: it.javaClass.simpleName}" }
            _refreshing.value = false
        }
    }

    fun clear() { viewModelScope.launch { history.clear() } }
}

/** Every ntfy message the app has received, newest first. Tap one to read all of it. */
@Composable
fun NtfyHistoryScreen(onBack: () -> Unit, vm: NtfyHistoryViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val topics by vm.topics.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Pick up anything the server kept from while the app was not listening.
    LaunchedEffect(Unit) { vm.refresh() }
    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.noticeShown() }
    }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Notification history",
                subtitle = if (refreshing) "Checking the server…" else if (messages.isEmpty()) "Nothing yet" else "${messages.size} messages",
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !refreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Check the server for older messages")
                    }
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear history")
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (messages.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
                Text("No messages yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Messages from your ntfy topics are kept here after their notification is gone.",
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
                items(messages, key = { it.id }) { m -> NtfyHistoryCard(m, topics[m.topic]) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear ${messages.size} messages?") },
            text = { Text("This only clears the list in the app. Older messages the server still keeps can be pulled back with refresh.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NtfyHistoryCard(m: NtfyMessage, topic: Pair<String, NtfyKind>?) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val whenText = DateUtils.getRelativeTimeSpanString(
        m.timeSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    val source = (topic?.first ?: m.topic) + if (topic?.second == NtfyKind.APPROVALS) " · approval" else ""
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "$source · $whenText" + if (m.priority >= 4) " · urgent" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (m.priority >= 4) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            if (m.title.isNotBlank()) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            }
            if (m.message.isNotBlank()) {
                Text(
                    m.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
