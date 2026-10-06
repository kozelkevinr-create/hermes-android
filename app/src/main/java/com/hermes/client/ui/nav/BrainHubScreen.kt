package com.hermes.client.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.HubRow

/**
 * "Brain" tab — everything that shapes how the agent thinks and what it can reach: its skills,
 * memory, tools/MCP, context files, scheduled work, and the profile configs that scope all of it.
 *
 * Grouped by what you're changing rather than by which API serves it: Knowledge (what it knows),
 * Capabilities (what it can do), Identity (who it is), then app settings.
 */
/** Where Hermes keeps its Obsidian vault on the gateway host. */
private const val OBSIDIAN_VAULT_PATH = "/home/spaulding/stacks/obsidian/vault"

@Composable
fun BrainHubScreen(onNavigate: (String) -> Unit, onOpenPalette: () -> Unit = {}) {
    Scaffold(
        topBar = {
            HermesTopBar(
                title = "Brain",
                subtitle = "What the agent knows and can do",
                actions = {
                    androidx.compose.material3.IconButton(onClick = onOpenPalette) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Rounded.Search,
                            contentDescription = "Command palette",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("KNOWLEDGE")
            HubRow(Icons.Rounded.AutoAwesome, "Skills", "Browse, toggle and edit skills") {
                onNavigate("agents_tools")
            }
            HubRow(Icons.Rounded.Memory, "Memory", "What the agent remembers") {
                onNavigate("settings_memory")
            }
            HubRow(Icons.Rounded.Folder, "Files", "Context files, handbook, uploads") {
                onNavigate("files")
            }
            // Opens the Files browser straight into the Obsidian vault the agent journals into.
            // The vault lives under the gateway's locked root, so this reuses the same API rather
            // than talking to the Obsidian container (which is VNC-over-HTTPS — unusable on a phone).
            HubRow(Icons.Rounded.MenuBook, "Obsidian vault", "Journal, topics and reference notes") {
                onNavigate("files_at/" + java.net.URLEncoder.encode(OBSIDIAN_VAULT_PATH, "UTF-8"))
            }

            HorizontalDivider()
            SectionLabel("CAPABILITIES")
            HubRow(Icons.Rounded.Hub, "MCP servers", "Connected tool servers") {
                onNavigate("settings_mcp")
            }
            HubRow(Icons.Rounded.Webhook, "Webhooks", "Inbound triggers") {
                onNavigate("webhooks")
            }
            HubRow(Icons.Rounded.Schedule, "Scheduled jobs", "Cron and recurring work") {
                onNavigate("cron")
            }
            HubRow(Icons.Rounded.Build, "Models", "Pick the active model") {
                onNavigate("models")
            }
            HubRow(Icons.Rounded.Forum, "Messaging", "Slack, Telegram and other channels") {
                onNavigate("messaging")
            }

            HorizontalDivider()
            SectionLabel("TOOLS")
            HubRow(Icons.Rounded.Lan, "NetOps", "Reachability of your services") {
                onNavigate("netops")
            }

            HorizontalDivider()
            SectionLabel("IDENTITY")
            HubRow(Icons.Rounded.People, "Profiles", "Switch, rename and configure profiles") {
                onNavigate("profiles")
            }
            HubRow(Icons.Rounded.AdminPanelSettings, "Session admin", "Search messages, archived sessions, stats") {
                onNavigate("session_admin")
            }

            HorizontalDivider()
            SectionLabel("ACTIVITY")
            // Mission Control was the old Home tab; it keeps a home here now that the bottom bar
            // is Chat/Sessions/Brain/Oneshot, rather than becoming unreachable.
            HubRow(Icons.Rounded.Timeline, "Agent activity", "Live feed of runs and cron") {
                onNavigate("activity")
            }
            HubRow(Icons.Rounded.BarChart, "Usage", "Token spend by day and model") {
                onNavigate("usage")
            }

            HorizontalDivider()
            SectionLabel("APP")
            HubRow(Icons.Rounded.Settings, "Settings", "Connection, appearance, diagnostics") {
                onNavigate("settings")
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
