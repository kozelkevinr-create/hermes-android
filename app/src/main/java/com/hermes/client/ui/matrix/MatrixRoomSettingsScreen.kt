package com.hermes.client.ui.matrix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.matrix.MatrixRoomDetails
import com.hermes.client.data.matrix.MatrixRoomMember
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.LoadingState

/**
 * Per-room options: name, topic, favourite and unread flags, members and invites, and leaving.
 * [onLeft] runs once the room has been left, so the caller can return to the room list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixRoomSettingsScreen(
    onBack: () -> Unit,
    onLeft: () -> Unit,
    vm: MatrixRoomSettingsViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val left = (ui as? RoomSettingsUi.Ready)?.left == true
    LaunchedEffect(left) { if (left) onLeft() }

    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Room settings",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = ui) {
            RoomSettingsUi.Loading -> LoadingState(Modifier.padding(padding).fillMaxSize())
            RoomSettingsUi.Unavailable -> ErrorState(
                message = "This room isn't available right now.",
                modifier = Modifier.padding(padding).fillMaxSize(),
                onRetry = vm::reload,
            )
            is RoomSettingsUi.Ready -> SettingsBody(state, vm, Modifier.padding(padding))
        }
    }
}

@Composable
private fun SettingsBody(state: RoomSettingsUi.Ready, vm: MatrixRoomSettingsViewModel, modifier: Modifier) {
    val details = state.details
    var confirmLeave by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<MatrixRoomMember?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.notice?.let {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = vm::dismissNotice) { Text("OK") }
            }
        }

        SaveableField(
            label = "Name",
            current = details.name,
            enabled = details.canRename && !state.busy,
            onSave = vm::rename,
        )
        SaveableField(
            label = "Topic",
            current = details.topic,
            enabled = details.canSetTopic && !state.busy,
            singleLine = false,
            onSave = vm::setTopic,
        )
        if (!details.canRename || !details.canSetTopic) {
            Text(
                "You don't have permission to change everything in this room.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        ToggleRow("Favourite", "Pinned to the top of Messages.", details.isFavourite, !state.busy, vm::setFavourite)
        ToggleRow("Mark as unread", "Shows a dot until you open the room.", details.isMarkedUnread, !state.busy, vm::setMarkedUnread)

        HorizontalDivider()
        Text("People", style = MaterialTheme.typography.titleSmall)
        if (details.canInvite) {
            InviteRow(enabled = !state.busy, onInvite = vm::invite)
        }
        details.members.forEach { member ->
            MemberRow(member, enabled = !state.busy, onRemove = { removing = member })
        }

        HorizontalDivider()
        OutlinedButton(
            onClick = { confirmLeave = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Leave room", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave ${details.name}?") },
            text = { Text(leaveWarning(details)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; vm.leave() }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
    removing?.let { member ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${member.displayName ?: member.userId}?") },
            text = { Text("They will no longer be in this room.") },
            confirmButton = {
                TextButton(onClick = { removing = null; vm.remove(member.userId) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** What leaving costs, so the confirmation says it plainly. */
internal fun leaveWarning(details: MatrixRoomDetails): String =
    if (details.isDirect) {
        "You will stop receiving this conversation. You can be invited back."
    } else {
        "You will stop receiving messages from this room. You can only come back if someone invites you."
    }

@Composable
private fun SaveableField(
    label: String,
    current: String,
    enabled: Boolean,
    onSave: (String) -> Unit,
    singleLine: Boolean = true,
) {
    var text by remember(current) { mutableStateOf(current) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            enabled = enabled,
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(text) }, enabled = enabled && text.trim() != current.trim()) { Text("Save $label") }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun InviteRow(enabled: Boolean, onInvite: (String) -> Unit) {
    var id by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("Invite (@name:server)") },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { onInvite(id); id = "" }, enabled = enabled && id.isNotBlank()) { Text("Invite") }
    }
}

@Composable
private fun MemberRow(member: MatrixRoomMember, enabled: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val title = (member.displayName ?: member.userId) + if (member.isMe) " (you)" else ""
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = buildList {
                if (member.displayName != null) add(member.userId)
                if (member.isInvited) add("invited")
            }.joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (member.canRemove) TextButton(onClick = onRemove, enabled = enabled) { Text("Remove") }
    }
}
