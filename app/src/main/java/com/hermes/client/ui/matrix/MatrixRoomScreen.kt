package com.hermes.client.ui.matrix

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.matrix.EventOrTxnId
import com.hermes.client.data.matrix.MessageKind
import com.hermes.client.data.matrix.MessageSendState
import com.hermes.client.data.matrix.avatarLetterFor
import com.hermes.client.ui.components.AccentChrome
import com.hermes.client.ui.components.EmptyState
import com.hermes.client.ui.components.ErrorState
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.LoadingState
import com.hermes.client.ui.theme.UserBubbleShape
import com.hermes.client.ui.theme.rememberProfileAccent
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

private val AvatarSize = 26.dp

/** Other-side bubble: the mirror of [UserBubbleShape] so the sharp corner points at the sender. */
private val OtherBubbleShape = CutCornerShape(topStart = 0.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 0.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatrixRoomScreen(roomId: String, onBack: () -> Unit, onSetup: () -> Unit, vm: MatrixRoomViewModel = hiltViewModel()) {
    val room by vm.room.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val sendFailed by vm.sendFailed.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val editFailed by vm.editFailed.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val picker by vm.modelPicker.collectAsStateWithLifecycle()
    val name = room?.name ?: roomId
    val ready = state is RoomTimelineState.Ready
    var quickActionsOpen by remember { mutableStateOf(false) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.sendImage(uri.toString())
    }
    Scaffold(
        topBar = {
            HermesTopBar(
                title = name,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (room?.isEncrypted == true) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = "Encrypted",
                            tint = AccentChrome.onBar,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                    // Native hooks for the Hermes commands: New session, stop, model picker.
                    // Over Matrix they are sent as their slash commands for the bot to parse.
                    IconButton(onClick = { quickActionsOpen = true }, enabled = ready) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Quick actions",
                            tint = AccentChrome.onBar,
                        )
                    }
                    DropdownMenu(expanded = quickActionsOpen, onDismissRequest = { quickActionsOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("New session") },
                            onClick = {
                                quickActionsOpen = false
                                vm.sendCommand("/new")
                            },
                            enabled = ready && !sending,
                        )
                        DropdownMenuItem(
                            text = { Text("Stop") },
                            onClick = {
                                quickActionsOpen = false
                                vm.sendCommand("/stop")
                            },
                            enabled = ready && !sending,
                        )
                        DropdownMenuItem(
                            text = { Text("Choose model…") },
                            onClick = {
                                quickActionsOpen = false
                                vm.openModelPicker()
                            },
                            enabled = ready,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (state is RoomTimelineState.Ready) {
                val isEditing = editing != null
                MessageComposer(
                    sending = sending,
                    sendFailed = sendFailed && !isEditing,
                    editing = isEditing,
                    editFailed = editFailed,
                    draft = draft,
                    onDraftChange = vm::updateDraft,
                    onSend = { if (isEditing) vm.submitEdit() else vm.send() },
                    onCancelEdit = vm::cancelEdit,
                    onPickImage = { pickImage.launch("image/*") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                RoomTimelineState.SignedOut -> EmptyState(
                    title = "Matrix isn't set up",
                    subtitle = "Sign in to your homeserver to read this room.",
                    actionLabel = "Set up Matrix",
                    onAction = onSetup,
                )
                RoomTimelineState.Opening -> LoadingState(Modifier.fillMaxSize(), label = "Opening room…")
                is RoomTimelineState.Failed -> ErrorState(
                    message = s.message,
                    onRetry = { vm.retry() },
                )
                is RoomTimelineState.Ready -> {
                    // Slash-command palette: while the draft composes a "/word", the timeline is
                    // replaced by the matching command picker (except in edit mode), mirroring the
                    // gateway chat. Sending the finished command is unchanged: it goes as text.
                    val matches = filterCommands(commands, commandPaletteQuery(draft))
                        .takeIf { editing == null } ?: emptyList()
                    if (matches.isNotEmpty()) {
                        CommandPalette(matches.map { it.name to it.description }) { name ->
                            vm.updateDraft(commandDraft(name))
                        }
                    } else {
                        TimelineContent(
                            s,
                            vm::loadMore,
                            onRetrySend = vm::retrySend,
                            onAbortSend = vm::abortSend,
                            onBeginEdit = vm::beginEdit,
                            onVisibleKeysChanged = vm::onVisibleKeysChanged,
                        )
                    }
                }
            }
        }
    }

    if (picker.open) {
        ModelPickerSheet(
            picker,
            onPick = vm::selectModel,
            onRetry = {
                vm.dismissModelPicker()
                vm.openModelPicker()
            },
            onDismiss = vm::dismissModelPicker,
        )
    }
}

/** The quick actions' model picker: providers from the gateway, models under each provider. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    picker: ModelPickerUi,
    onPick: (provider: String, model: String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text(
                "Choose a model",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            when {
                picker.loading -> CircularProgressIndicator(Modifier.padding(vertical = 24.dp))
                picker.failed -> {
                    Text(
                        "Couldn't load the model list.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                picker.providers.isEmpty() ->
                    Text(
                        "No models available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                else -> LazyColumn(Modifier.height(400.dp)) {
                    picker.providers.forEach { provider ->
                        item(key = "provider-${provider.slug}") {
                            Text(
                                provider.name ?: provider.slug,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 4.dp),
                            )
                        }
                        items(provider.models, key = { "${provider.slug}:$it" }) { model ->
                            ListItem(
                                headlineContent = { Text(model) },
                                modifier = Modifier.clickable { onPick(provider.slug, model) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineContent(
    state: RoomTimelineState.Ready,
    onLoadMore: () -> Unit,
    onRetrySend: (EventOrTxnId) -> Unit,
    onAbortSend: (EventOrTxnId) -> Unit,
    onBeginEdit: (EventOrTxnId, String) -> Unit,
    onVisibleKeysChanged: (Set<String>, Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    val rows = state.rows
    val pagination = remember { TimelinePagination() }
    val atStart = rememberUpdatedState(state.isAtStart)

    // With reverseLayout the oldest messages sit at the top, so "near the top" means the highest
    // visible index approaching the last item. Only page after a real scroll gesture, or a bounded
    // initial fill (TimelinePagination caps it), so layout alone can never page through the history.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            PaginationSignal(
                total = info.totalItemsCount,
                lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index,
                isScrollInProgress = listState.isScrollInProgress,
                atStart = atStart.value,
            )
        }.collect { signal ->
            if (pagination.shouldLoadMore(signal.total, signal.lastVisibleIndex, signal.isScrollInProgress, signal.atStart)) {
                onLoadMore()
            }
        }
    }

    // Read receipts: what matters is which rows are actually rendered, not scroll position, so the
    // visible keys (not just the last index) are passed to the tracker.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.map { it.key.toString() }.toSet() to listState.isScrollInProgress
        }.collect { (keys, scrolling) ->
            onVisibleKeysChanged(keys, scrolling)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(rows.asReversed(), key = { timelineRowKey(it) }) { row ->
            when (row) {
                is TimelineRow.DayDivider -> CentredLabel(row.label)
                is TimelineRow.State -> CentredLabel(row.text)
                is TimelineRow.Message -> MessageRow(row, onRetrySend, onAbortSend, onBeginEdit)
            }
        }
        // The footer is the last item, so with reverseLayout it sits at the very top (oldest edge).
        item(key = "timeline-footer") {
            when {
                state.isLoadingMore -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                state.isAtStart -> Text(
                    "Beginning of conversation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
                else -> Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun CentredLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Full-area command picker replacing the timeline while a slash word is being composed. */
@Composable
private fun CommandPalette(matches: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    Text(
        "COMMANDS",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
    )
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(matches, key = { it.first }) { (name, desc) ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(name) },
                supportingContent = { if (desc.isNotBlank()) Text(desc) },
                modifier = Modifier.clickable { onSelect(name) },
            )
        }
    }
}

@Composable
private fun MessageRow(
    row: TimelineRow.Message,
    onRetrySend: (EventOrTxnId) -> Unit,
    onAbortSend: (EventOrTxnId) -> Unit,
    onBeginEdit: (EventOrTxnId, String) -> Unit,
) {
    when {
        row.isUtd -> Bubble(row, onRetrySend, onAbortSend, onBeginEdit, content = { MutedText("Encrypted message") })
        row.kind == MessageKind.IMAGE ->
            Bubble(row, onRetrySend, onAbortSend, onBeginEdit, content = { MutedText(attachmentLabel("image", row.body)) })
        row.kind == MessageKind.FILE ->
            Bubble(row, onRetrySend, onAbortSend, onBeginEdit, content = { MutedText(attachmentLabel("file", row.body)) })
        else -> Bubble(row, onRetrySend, onAbortSend, onBeginEdit, content = { BodyText(row) })
    }
}

@Composable
private fun BodyText(row: TimelineRow.Message) {
    if (row.isOwn) {
        Text(row.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    } else if (row.body.isBlank()) {
        Text("(unsupported message)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Markdown(content = row.body, colors = markdownColor(), typography = markdownTypography())
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    row: TimelineRow.Message,
    onRetrySend: (EventOrTxnId) -> Unit,
    onAbortSend: (EventOrTxnId) -> Unit,
    onBeginEdit: (EventOrTxnId, String) -> Unit,
    content: @Composable () -> Unit,
) {
    val isOwn = row.isOwn
    val bubbleBg = if (isOwn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val shape = if (isOwn) UserBubbleShape else OtherBubbleShape
    val avatarSpacer = Modifier.width(AvatarSize + 6.dp)
    val itemId = row.itemId
    // Own text messages open for editing on a long press; everything else stays inert.
    val editModifier = if (row.isEditable && itemId != null) {
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = { onBeginEdit(itemId, row.body) },
        )
    } else {
        Modifier
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isOwn) {
            if (row.showSender) {
                AvatarCircle(row.sender)
                Spacer(Modifier.width(6.dp))
            } else {
                Spacer(avatarSpacer)
            }
        }
        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            if (!isOwn && row.showSender) {
                Text(
                    row.sender,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                )
            }
            Surface(color = bubbleBg, shape = shape, modifier = editModifier) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    content()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.time,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (row.isEdited) {
                            Text(
                                " (edited)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (row.isOwn) {
                            SendStatus(row, onRetrySend, onAbortSend)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarCircle(name: String) {
    val accent = rememberProfileAccent(name, isSystemInDarkTheme())
    Box(
        Modifier.size(AvatarSize).clip(CircleShape).background(accent.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(avatarLetterFor(name), color = accent.onAccent, style = MaterialTheme.typography.labelMedium)
    }
}

/** "[image] filename" or "[file] filename"; empty body falls back to just "[image]". */
private fun attachmentLabel(prefix: String, filename: String): String =
    if (filename.isBlank()) "[$prefix]" else "[$prefix] $filename"

/** Clock while the send is in flight; a tappable "Not sent" offering retry/delete when it failed. */
@Composable
private fun SendStatus(
    row: TimelineRow.Message,
    onRetrySend: (EventOrTxnId) -> Unit,
    onAbortSend: (EventOrTxnId) -> Unit,
) {
    when (row.sendState) {
        MessageSendState.NOT_SENT_YET -> Icon(
            Icons.Rounded.Schedule,
            contentDescription = "Sending",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).size(12.dp),
        )
        MessageSendState.SENDING_FAILED -> {
            val itemId = row.itemId
            var menu by remember { mutableStateOf(false) }
            Box {
                Text(
                    "Not sent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .then(if (itemId != null) Modifier.clickable { menu = true } else Modifier)
                        .padding(start = 6.dp),
                )
                if (itemId != null) {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Retry") },
                            onClick = {
                                menu = false
                                onRetrySend(itemId)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = {
                                menu = false
                                onAbortSend(itemId)
                            },
                        )
                    }
                }
            }
        }
        MessageSendState.SENT -> Unit
    }
}

/** Text composer above the IME / navigation bar; doubles as the editor when [editing] is set.
 *  Slash commands are sent as-is for Hermes to parse. */
@Composable
private fun MessageComposer(
    sending: Boolean,
    sendFailed: Boolean,
    editing: Boolean,
    editFailed: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelEdit: () -> Unit,
    onPickImage: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !sending

    fun submit() {
        if (!canSend) return
        onSend()
    }

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            if (editing) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Editing message",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onCancelEdit() }.padding(start = 8.dp),
                    )
                }
            }
            when {
                editFailed -> ComposerBanner("Couldn't save")
                sendFailed -> ComposerBanner("Couldn't send")
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (editing) {
                    Spacer(Modifier.width(52.dp))
                } else {
                    // Sending an image and editing a text never mix; the picker hides while editing.
                    IconButton(onClick = onPickImage, enabled = !sending) {
                        Icon(
                            Icons.Rounded.AddPhotoAlternate,
                            contentDescription = "Send image",
                            tint = if (sending) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (editing) "Edit message…" else "Message…") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { submit() }, enabled = canSend) {
                    if (editing) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = "Save edit",
                            tint = if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerBanner(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
    )
}

private data class PaginationSignal(
    val total: Int,
    val lastVisibleIndex: Int?,
    val isScrollInProgress: Boolean,
    val atStart: Boolean,
)
