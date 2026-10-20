package com.hermes.client.ui.matrix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.matrix.MatrixRoomSummary
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.theme.LocalProfileAccent
import com.hermes.client.ui.theme.rememberProfileAccent

/** The Chats tab: the Matrix room list, one row per room in the SDK's recency order. */
@Composable
fun MatrixRoomListScreen(
    onOpenRoom: (String) -> Unit,
    onSetup: () -> Unit,
    vm: MatrixRoomListViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val sheet by vm.newRoomSheet.collectAsStateWithLifecycle()
    val ready = ui is RoomListUiState.Ready
    LaunchedEffect(Unit) {
        vm.createdRoomId.collect { id -> onOpenRoom(id) }
    }
    Scaffold(
        topBar = { HermesTopBar(title = "Chats") },
        floatingActionButton = {
            if (ready) {
                FloatingActionButton(onClick = { vm.openNewRoomSheet() }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New room")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val s = ui) {
                RoomListUiState.SignedOut -> EmptyState(
                    title = "Matrix isn't set up",
                    subtitle = "Sign in to your homeserver to chat with Hermes in Matrix rooms.",
                    actionLabel = "Set up Matrix",
                    onAction = onSetup,
                )
                RoomListUiState.Loading -> LoadingState(label = "Connecting to Matrix…")
                is RoomListUiState.RestoreFailed -> RestoreBanner(
                    message = s.message,
                    retryable = s.retryable,
                    onRetry = { vm.retry() },
                    onSetup = onSetup,
                )
                is RoomListUiState.Failed -> EmptyState(
                    title = "Matrix sign-in failed",
                    subtitle = s.message,
                    icon = Icons.Rounded.ErrorOutline,
                    actionLabel = "Set up Matrix",
                    onAction = onSetup,
                )
                is RoomListUiState.Ready -> ReadyContent(
                    state = s,
                    onQueryChange = vm::onQueryChange,
                    onOpenRoom = onOpenRoom,
                )
            }
        }
    }
    if (sheet.open) {
        NewRoomSheet(
            sheet,
            onDraftChange = vm::updateNewRoomDraft,
            onCreate = vm::createNewRoom,
            onDismiss = vm::dismissNewRoomSheet,
        )
    }
}

@Composable
private fun ReadyContent(
    state: RoomListUiState.Ready,
    onQueryChange: (String) -> Unit,
    onOpenRoom: (String) -> Unit,
) {
    if (!state.hasAnyRooms) {
        EmptyState(
            title = "No rooms yet",
            subtitle = "Rooms you share with the Hermes bot will show up here.",
        )
        return
    }
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search rooms…") },
        singleLine = true,
        trailingIcon = {
            if (state.query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
    if (state.rooms.isEmpty()) {
        Text(
            "No rooms match \"${state.query}\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    // Read the clock once per recomposition; labels only change when the list does.
    val nowMs = System.currentTimeMillis()
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        items(state.rooms, key = { it.id }) { room ->
            RoomRow(room = room, nowMs = nowMs, onClick = { onOpenRoom(room.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun RestoreBanner(message: String, retryable: Boolean, onRetry: () -> Unit, onSetup: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                "Couldn't reconnect to Matrix. $message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (retryable) {
                TextButton(onClick = onRetry) { Text("Retry") }
            } else {
                TextButton(onClick = onSetup) { Text("Set up Matrix") }
            }
        }
    }
}

@Composable
private fun RoomRow(room: MatrixRoomSummary, nowMs: Long, onClick: () -> Unit) {
    val accent = rememberProfileAccent(room.name, isSystemInDarkTheme())
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(accent.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(room.avatarLetter, color = accent.onAccent, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (room.isEncrypted) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = "Encrypted",
                        modifier = Modifier.padding(start = 6.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                previewLine(room),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                formatRoomActivity(room.lastActivityTs, nowMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (room.unread > 0) {
                val badge = LocalProfileAccent.current
                Box(
                    Modifier.defaultMinSize(minWidth = 20.dp).height(20.dp).clip(CircleShape).background(badge.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (room.unread > 99) "99+" else room.unread.toString(),
                        color = badge.onAccent,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/** "sender: preview", the preview alone, or a placeholder when the room has no messages yet. */
private fun previewLine(room: MatrixRoomSummary): String {
    val preview = room.lastMessagePreview ?: return "No messages yet"
    val sender = room.lastMessageSender
    return if (sender.isNullOrBlank()) preview else "$sender: $preview"
}

/**
 * The new-room sheet: a named (optionally encrypted) room or a direct message to the invited
 * peer — prefilled with the Hermes bot. Encrypted-by-default comes from the setting.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NewRoomSheet(
    sheet: MatrixRoomListViewModel.NewRoomSheetUi,
    onDraftChange: (NewRoomDraft) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("New room", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sheet.draft.mode == NewRoomMode.ROOM,
                    onClick = { onDraftChange(sheet.draft.copy(mode = NewRoomMode.ROOM)) },
                    label = { Text("Room") },
                )
                FilterChip(
                    selected = sheet.draft.mode == NewRoomMode.DM,
                    onClick = { onDraftChange(sheet.draft.copy(mode = NewRoomMode.DM)) },
                    label = { Text("Direct message") },
                )
            }
            if (sheet.draft.mode == NewRoomMode.ROOM) {
                OutlinedTextField(
                    value = sheet.draft.name,
                    onValueChange = { onDraftChange(sheet.draft.copy(name = it)) },
                    placeholder = { Text("Room name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = sheet.draft.invite,
                onValueChange = { onDraftChange(sheet.draft.copy(invite = it)) },
                placeholder = { Text("Invite @user:server (comma separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Encrypted", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "New rooms are end-to-end encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = sheet.draft.encrypted,
                    onCheckedChange = { onDraftChange(sheet.draft.copy(encrypted = it)) },
                    modifier = Modifier.semantics { contentDescription = "Encrypted" },
                )
            }
            if (sheet.error != null) {
                Text(
                    sheet.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onCreate,
                enabled = !sheet.creating,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Create" },
            ) {
                if (sheet.creating) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    Text(if (sheet.draft.mode == NewRoomMode.DM) "Create DM" else "Create room")
                }
            }
        }
    }
}
