package com.hermes.client.ui.matrix

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    val name = room?.name ?: roomId
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
                },
            )
        },
        bottomBar = {
            if (state is RoomTimelineState.Ready) {
                MessageComposer(
                    sending = sending,
                    sendFailed = sendFailed,
                    draft = draft,
                    onDraftChange = vm::updateDraft,
                    onSend = vm::send,
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
                is RoomTimelineState.Ready -> TimelineContent(
                    s,
                    vm::loadMore,
                    onRetrySend = vm::retrySend,
                    onAbortSend = vm::abortSend,
                )
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
                is TimelineRow.Message -> MessageRow(row, onRetrySend, onAbortSend)
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

@Composable
private fun MessageRow(
    row: TimelineRow.Message,
    onRetrySend: (EventOrTxnId) -> Unit,
    onAbortSend: (EventOrTxnId) -> Unit,
) {
    when {
        row.isUtd -> Bubble(row, onRetrySend, onAbortSend, content = { MutedText("Encrypted message") })
        row.kind == MessageKind.IMAGE ->
            Bubble(row, onRetrySend, onAbortSend, content = { MutedText(attachmentLabel("image", row.body)) })
        row.kind == MessageKind.FILE ->
            Bubble(row, onRetrySend, onAbortSend, content = { MutedText(attachmentLabel("file", row.body)) })
        else -> Bubble(row, onRetrySend, onAbortSend, content = { BodyText(row) })
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

@Composable
private fun Bubble(
    row: TimelineRow.Message,
    onRetrySend: (EventOrTxnId) -> Unit,
    onAbortSend: (EventOrTxnId) -> Unit,
    content: @Composable () -> Unit,
) {
    val isOwn = row.isOwn
    val bubbleBg = if (isOwn) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val shape = if (isOwn) UserBubbleShape else OtherBubbleShape
    val avatarSpacer = Modifier.width(AvatarSize + 6.dp)
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
            Surface(color = bubbleBg, shape = shape) {
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

/** Text composer above the IME / navigation bar. Slash commands are sent as-is for Hermes to parse. */
@Composable
private fun MessageComposer(
    sending: Boolean,
    sendFailed: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
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
            if (sendFailed) {
                Text(
                    "Couldn't send",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { submit() }, enabled = canSend) {
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

private data class PaginationSignal(
    val total: Int,
    val lastVisibleIndex: Int?,
    val isScrollInProgress: Boolean,
    val atStart: Boolean,
)
