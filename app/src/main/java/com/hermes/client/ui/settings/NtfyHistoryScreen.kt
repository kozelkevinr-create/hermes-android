package com.hermes.client.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NtfyHistoryViewModel @Inject constructor(
    private val history: NtfyHistory,
    store: NtfyStore,
) : ViewModel() {
    /** A message and whether it is pinned. Pinned ones come first, each group newest first. */
    data class Entry(val message: NtfyMessage, val pinned: Boolean)

    val entries: StateFlow<List<Entry>> =
        combine(history.messages, history.pinned) { all, pins ->
            all.map { Entry(it, it.id in pins) }.sortedByDescending { it.pinned } // stable: stays newest first
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun setPinned(m: NtfyMessage, pinned: Boolean) { viewModelScope.launch { history.setPinned(m.id, pinned) } }

    fun dismiss(m: NtfyMessage) { viewModelScope.launch { history.dismiss(m.id) } }

    /** Clears everything that is not pinned. */
    fun clear() {
        val ids = entries.value.filterNot { it.pinned }.map { it.message.id }
        viewModelScope.launch { history.clear(ids) }
    }
}

/** Every ntfy message the app has received, newest first. Tap one to read all of it. */
@Composable
fun NtfyHistoryScreen(onBack: () -> Unit, vm: NtfyHistoryViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val messages = entries.map { it.message }
    val clearable = entries.count { !it.pinned }
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
                    if (clearable > 0) {
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
                items(entries, key = { it.message.id }) { e ->
                    SwipeToRemove(enabled = !e.pinned, onRemove = { vm.dismiss(e.message) }) {
                        NtfyHistoryCard(e.message, e.pinned, topics[e.message.topic], onTogglePin = { vm.setPinned(e.message, !e.pinned) })
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear $clearable messages?") },
            text = { Text("Pinned messages stay. Cleared ones won't come back, even if the server still has them.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NtfyHistoryCard(m: NtfyMessage, pinned: Boolean, topic: Pair<String, NtfyKind>?, onTogglePin: () -> Unit) {
    var expanded by rememberSaveable(m.id) { mutableStateOf(false) }
    val whenText = DateUtils.getRelativeTimeSpanString(
        m.timeSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()
    val source = (topic?.first ?: m.topic) + if (topic?.second == NtfyKind.APPROVALS) " · approval" else ""
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$source · $whenText" + if (m.priority >= 4) " · urgent" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (m.priority >= 4) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePin) {
                    Icon(
                        if (pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (pinned) "Unpin" else "Pin",
                        tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (m.title.isNotBlank()) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp, end = 12.dp))
            }
            if (m.message.isNotBlank()) {
                Text(
                    m.message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, end = 12.dp),
                )
            }
        }
    }
}

/** Swipe either way to remove [content]; off for pinned entries, which must be unpinned first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(enabled: Boolean, onRemove: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value -> (value != SwipeToDismissBoxValue.Settled).also { if (it) onRemove() } },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium).padding(horizontal = 20.dp),
                contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        content = { content() },
    )
}
