package com.hermes.client.ui.nav

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermes.client.data.network.isUnhealthy
import com.hermes.client.ui.admin.SessionAdminScreen
import com.hermes.client.ui.chat.ChatScreen
import com.hermes.client.ui.components.HealthSheet
import com.hermes.client.ui.components.HealthStrip
import com.hermes.client.ui.cron.CronDetailScreen
import com.hermes.client.ui.cron.CronEditScreen
import com.hermes.client.ui.cron.CronScreen
import com.hermes.client.ui.messaging.MessagingScreen
import com.hermes.client.ui.messaging.MessagingSetupScreen
import com.hermes.client.ui.models.ModelsScreen
import com.hermes.client.ui.profiles.ProfilesScreen
import com.hermes.client.ui.sessions.SessionsScreen
import com.hermes.client.ui.settings.AboutScreen
import com.hermes.client.ui.settings.AppearanceScreen
import com.hermes.client.ui.settings.EnvScreen
import com.hermes.client.ui.settings.McpSettingsScreen
import com.hermes.client.ui.settings.MemorySettingsScreen
import com.hermes.client.ui.settings.SettingsScreen
import com.hermes.client.ui.setup.SetupScreen
import com.hermes.client.ui.tools.AgentsToolsScreen
import com.hermes.client.ui.usage.UsageScreen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Psychology

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("messages", "Messages", Icons.AutoMirrored.Rounded.Chat),
    Tab("sessions", "Sessions", Icons.Rounded.Forum),
    Tab("brain", "Brain", Icons.Rounded.Psychology),
)

/**
 * Root navigation host with a three-tab bottom bar (Chats · Agent Activity · You).
 *
 * The bottom bar shows only on the three tab roots; pushed screens (chat, detail/edit, settings
 * sub-pages, admin, archived) are full-screen with a back arrow. First-launch gating: when
 * [hasConfig] is false the start destination is "setup" and the bar is hidden there.
 *
 * onUnauthorized clears the back stack and routes to "setup" so an expired token forces re-entry.
 *
 * [deepLinkRoute], when non-null, is navigated to once (keyed by value) — used to jump straight
 * to a session when the activity is launched or resumed from a tapped notification.
 * [onDeepLinkConsumed] is invoked right after that navigation so the caller can clear its
 * pending-route state; otherwise a config change (rotation, dark-mode/font-scale) would recreate
 * the activity, re-read the same intent extra, and re-navigate to the same chat.
 */
@Composable
fun HermesNav(hasConfig: Boolean, deepLinkRoute: String? = null, onDeepLinkConsumed: () -> Unit = {}) {
    val nav = rememberNavController()
    val start = if (hasConfig) NavRoutes.MESSAGES else NavRoutes.SETUP

    // Guard the navigate: a hermes:// deep link is untrusted, and even the notification path could
    // carry a stale/unknown route — an unresolved route must be ignored, never crash.
    LaunchedEffect(deepLinkRoute) {
        deepLinkRoute?.let {
            runCatching { nav.navigate(it) }
            onDeepLinkConsumed()
        }
    }

    val backStackEntry by nav.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    var showPalette by rememberSaveable { mutableStateOf(false) }
    val shellVm: ShellViewModel = hiltViewModel()
    val health by shellVm.health.collectAsStateWithLifecycle()
    var showHealthSheet by rememberSaveable { mutableStateOf(false) }

    // Probe only while the app is foregrounded (in-app-only v1). ProcessLifecycleOwner replays its
    // current state on addObserver, so ON_START fires immediately if already foregrounded.
    DisposableEffect(Unit) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> shellVm.onAppForeground()
                Lifecycle.Event.ON_STOP -> shellVm.onAppBackground()
                else -> {}
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(obs)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(obs) }
    }

    val onUnauthorized: () -> Unit = {
        nav.navigate("setup") { popUpTo(0) { inclusive = true } }
    }
    // Pushed screens navigate "up"; their top-bar nav icon (formerly the drawer hamburger) is a
    // back arrow wired to this.
    val back: () -> Unit = { nav.popBackStack() }
    // Drill into a screen from a tab hub (Agent Activity / You).
    val push: (String) -> Unit = { dest -> nav.navigate(dest) { launchSingleTop = true } }
    // Switch top-level tab with state save/restore so each tab keeps its own back stack position.
    val switchTab: (String) -> Unit = { dest ->
        nav.navigate(dest) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val showBottomBar = route in TABS.map { it.route }.toSet()

    Scaffold(
        // Let each destination's own Scaffold own the top/side insets; this outer one exists
        // only to host the bottom bar, so it contributes bottom padding and nothing else
        // (otherwise the status-bar inset would be applied twice and push titles down).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { switchTab(tab.route) },
                            icon = {
                                if (tab.route == "brain" && hasConfig && health.isUnhealthy()) {
                                    BadgedBox(badge = { Badge() }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            // Renders nothing when healthy. When shown it owns the status-bar inset, so the content
            // below consumes that inset to avoid a second top gap under the strip.
            if (hasConfig && health.isUnhealthy()) {
                HealthStrip(health = health, onClick = { showHealthSheet = true })
            }
            val contentModifier =
                if (hasConfig && health.isUnhealthy()) Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)
                else Modifier.weight(1f)
            NavHost(
                navController = nav,
                startDestination = start,
                modifier = contentModifier,
            ) {
            composable(NavRoutes.SETUP) {
                SetupScreen(
                    onSaved = {
                        nav.navigate(NavRoutes.MESSAGES) { popUpTo(NavRoutes.SETUP) { inclusive = true } }
                    },
                )
            }
            // ---- Tab roots ----
            composable(NavRoutes.MESSAGES) {
                // The Messages tab is the Matrix room list.
                com.hermes.client.ui.matrix.MatrixRoomListScreen(
                    // Room ids contain '!' and ':', so they travel URL-encoded as one path segment.
                    onOpenRoom = { id -> nav.navigate("matrix_room/${Uri.encode(id)}") },
                    onSetup = { nav.navigate("settings_matrix") { launchSingleTop = true } },
                )
            }
            // The Sessions tab: past gateway sessions; tapping one opens it to continue the chat.
            composable(NavRoutes.SESSIONS) {
                SessionsScreen(
                    onOpen = { id -> nav.navigate("chat/$id") },
                    onOpenArchived = { nav.navigate("archived") },
                    onUnauthorized = onUnauthorized,
                )
            }
            composable(NavRoutes.MATRIX_ROOM) { entry ->
                com.hermes.client.ui.matrix.MatrixRoomScreen(
                    roomId = entry.arguments?.getString("roomId") ?: "",
                    onBack = back,
                    onSetup = { nav.navigate("settings_matrix") { launchSingleTop = true } },
                    onSettings = { id -> nav.navigate("matrix_room_settings/${Uri.encode(id)}") { launchSingleTop = true } },
                )
            }
            composable(NavRoutes.MATRIX_ROOM_SETTINGS) {
                com.hermes.client.ui.matrix.MatrixRoomSettingsScreen(
                    onBack = back,
                    // Leaving closes the settings and the room it belonged to.
                    onLeft = { nav.popBackStack(NavRoutes.MESSAGES, inclusive = false) },
                )
            }
            composable(NavRoutes.ACTIVITY) { com.hermes.client.ui.activity.MissionControlScreen(onNavigate = push) }
            composable(NavRoutes.BRAIN) { BrainHubScreen(onNavigate = push, onOpenPalette = { showPalette = true }) }
            composable(NavRoutes.ONESHOT) {
                com.hermes.client.ui.oneshot.OneshotScreen(onOpenPalette = { showPalette = true })
            }
            composable(NavRoutes.NETOPS) {
                com.hermes.client.ui.netops.NetOpsScreen(onBack = back, onTools = { push("nettools") })
            }
            composable(NavRoutes.NETTOOLS) {
                com.hermes.client.ui.netops.NetToolsScreen(onBack = back)
            }
            composable(NavRoutes.WEBHOOKS) {
                com.hermes.client.ui.webhooks.WebhooksScreen(onBack = back)
            }
            composable(NavRoutes.FILES) {
                com.hermes.client.ui.files.FilesScreen(
                    onBack = back,
                    onOpenFile = { path ->
                        nav.navigate("file_edit/" + java.net.URLEncoder.encode(path, "UTF-8"))
                    },
                )
            }
            // Files rooted at a specific directory (the Obsidian vault shortcut uses this).
            composable(NavRoutes.FILES_AT) { entry ->
                val raw = entry.arguments?.getString("path").orEmpty()
                com.hermes.client.ui.files.FilesScreen(
                    onBack = back,
                    onOpenFile = { path ->
                        nav.navigate("file_view/" + java.net.URLEncoder.encode(path, "UTF-8"))
                    },
                    startPath = java.net.URLDecoder.decode(raw, "UTF-8"),
                    readOnly = true,
                    // Can't climb above the vault root from the vault shortcut.
                    boundaryPath = java.net.URLDecoder.decode(raw, "UTF-8"),
                )
            }
            // Read-only viewer, used by the Obsidian vault so phone edits can't race Obsidian's
            // own autosave or leave uncommitted changes in the vault's git repo.
            composable(NavRoutes.FILE_VIEW) { entry ->
                val raw = entry.arguments?.getString("path").orEmpty()
                com.hermes.client.ui.files.FileEditorScreen(
                    path = java.net.URLDecoder.decode(raw, "UTF-8"),
                    onBack = back,
                    readOnly = true,
                )
            }
            composable(NavRoutes.FILE_EDIT) { entry ->
                val raw = entry.arguments?.getString("path").orEmpty()
                com.hermes.client.ui.files.FileEditorScreen(
                    path = java.net.URLDecoder.decode(raw, "UTF-8"),
                    onBack = back,
                )
            }

            // ---- Pushed screens (back arrow) ----
            composable(NavRoutes.ARCHIVED) {
                com.hermes.client.ui.sessions.ArchivedSessionsScreen(
                    onOpen = { id -> nav.navigate("chat/$id") },
                    onBack = back,
                    onUnauthorized = onUnauthorized,
                )
            }
            composable(NavRoutes.CHAT) { entry ->
                ChatScreen(
                    sessionId = entry.arguments?.getString("id") ?: "",
                    onMenu = back,
                    onUnauthorized = onUnauthorized,
                )
            }
            composable(NavRoutes.MODELS) { ModelsScreen(onMenu = back) }
            composable(NavRoutes.PROFILES) {
                ProfilesScreen(onMenu = back, onEdit = { name -> nav.navigate("profile_edit/${Uri.encode(name)}") })
            }
            composable(NavRoutes.PROFILE_EDIT) {
                com.hermes.client.ui.profiles.ProfileEditScreen(onBack = back)
            }
            composable(NavRoutes.CRON) {
                CronScreen(
                    onMenu = back,
                    onOpen = { id -> nav.navigate("cron_detail/$id") },
                    onNew = { seed -> nav.navigate("cron_edit/$seed") },
                )
            }
            composable(NavRoutes.CRON_DETAIL) { entry ->
                val id = entry.arguments?.getString("id") ?: ""
                CronDetailScreen(
                    jobId = id,
                    onBack = back,
                    onEdit = { nav.navigate("cron_edit/$id") },
                )
            }
            composable(NavRoutes.CRON_EDIT) { entry ->
                CronEditScreen(
                    jobId = entry.arguments?.getString("id") ?: "new",
                    onDone = { nav.popBackStack() },
                )
            }
            composable(NavRoutes.MESSAGING) {
                MessagingScreen(
                    onMenu = back,
                    onSetup = { id -> nav.navigate("messaging_setup/$id") },
                )
            }
            composable(NavRoutes.MESSAGING_SETUP) { entry ->
                MessagingSetupScreen(
                    platformId = entry.arguments?.getString("id") ?: "",
                    onDone = { nav.popBackStack() },
                )
            }
            composable(NavRoutes.USAGE) { UsageScreen(onMenu = back) }
            composable(NavRoutes.SETTINGS) {
                SettingsScreen(
                    onMenu = back,
                    onNavigate = { dest -> nav.navigate(dest) { launchSingleTop = true } },
                )
            }
            composable(NavRoutes.SETTINGS_APPEARANCE) { AppearanceScreen(onBack = { nav.popBackStack() }) }
            composable(NavRoutes.SETTINGS_NOTIFICATIONS) {
                com.hermes.client.ui.settings.NotificationsScreen(onBack = { nav.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_MEMORY) { MemorySettingsScreen(onBack = { nav.popBackStack() }) }
            composable(NavRoutes.SETTINGS_PROMPTS) {
                com.hermes.client.ui.settings.PromptLibraryScreen(onBack = { nav.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_MCP) { McpSettingsScreen(onBack = { nav.popBackStack() }) }
            composable(NavRoutes.SETTINGS_ENV) { EnvScreen(onBack = { nav.popBackStack() }) }
            composable(NavRoutes.SETTINGS_CONNECTION) {
                com.hermes.client.ui.settings.ConnectionSettingsScreen(onBack = { nav.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_MATRIX) {
                com.hermes.client.ui.matrix.MatrixAccountScreen(onBack = { nav.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_DIAGNOSTICS) {
                com.hermes.client.ui.settings.DiagnosticsScreen(onBack = { nav.popBackStack() })
            }
            composable(NavRoutes.SETTINGS_ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
            composable(NavRoutes.SESSION_ADMIN) {
                SessionAdminScreen(
                    onMenu = back,
                    onOpen = { id -> nav.navigate("chat/$id") },
                )
            }
            composable(NavRoutes.AGENTS_TOOLS) { AgentsToolsScreen(onMenu = back) }
            }
        }
    }

    if (showHealthSheet) {
        HealthSheet(
            health = health,
            onRecheck = { shellVm.recheckHealth() },
            onDismiss = { showHealthSheet = false },
        )
    }

    if (showPalette) {
        com.hermes.client.ui.components.CommandPalette(
            onNavigate = { route -> nav.navigate(route) { launchSingleTop = true } },
            onDismiss = { showPalette = false },
        )
    }
}
