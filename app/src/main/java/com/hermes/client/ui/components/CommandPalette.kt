package com.hermes.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** One palette entry: a label, optional keywords for matching, and the route it navigates to. */
data class Command(val label: String, val route: String, val keywords: String = "")

/** The fixed set of destinations the palette can jump to. Kept here so it's the single source. */
val PALETTE_COMMANDS: List<Command> = listOf(
    Command("Messages", "messages", "chats rooms matrix talk"),
    Command("Sessions", "sessions", "history conversations"),
    Command("Oneshot dispatch", "oneshot", "task background bridge voice"),
    Command("Brain", "brain", "settings config"),
    Command("Skills", "agents_tools", "abilities tools"),
    Command("MCP servers", "settings_mcp", "tools servers"),
    Command("Webhooks", "webhooks", "triggers inbound"),
    Command("Scheduled jobs", "cron", "cron schedule recurring"),
    Command("Files", "files", "browse documents"),
    Command("Obsidian vault", "files_at/%2Fhome%2Fspaulding%2Fstacks%2Fobsidian%2Fvault", "notes journal brain"),
    Command("NetOps", "netops", "network status reachability services"),
    Command("Models", "models", "model switch"),
    Command("Profiles", "profiles", "tenant rename accent colour"),
    Command("Messaging", "messaging", "slack telegram channels"),
    Command("Agent activity", "activity", "mission control feed runs"),
    Command("Usage", "usage", "tokens cost spend"),
    Command("Memory", "settings_memory", "remember"),
    Command("Settings", "settings", "connection appearance diagnostics"),
    Command("Appearance", "settings_appearance", "theme dark amber palette"),
    Command("Diagnostics", "settings_diagnostics", "logs debug"),
)

private fun Command.matches(q: String): Boolean {
    if (q.isBlank()) return true
    val needle = q.trim().lowercase()
    return label.lowercase().contains(needle) || keywords.lowercase().contains(needle)
}

/**
 * A `Ctrl-K`-style launcher: type to filter, tap to jump anywhere in the app. On-theme (monospace,
 * a "> " prompt) and the single fast path to every screen once the app has many of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPalette(onNavigate: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { PALETTE_COMMANDS.filter { it.matches(query) } }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Text(">", fontFamily = FontFamily.Monospace) },
                    placeholder = { Text("Jump to…") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(results, key = { it.route }) { cmd ->
                        ListItem(
                            headlineContent = { Text(cmd.label) },
                            trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                onNavigate(cmd.route)
                            },
                        )
                    }
                }
            }
        }
    }
}
