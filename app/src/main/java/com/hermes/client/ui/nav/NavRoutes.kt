package com.hermes.client.ui.nav

/**
 * The route patterns declared by the NavHost in [HermesNav]. Every `composable(...)` uses one of
 * these constants so [isDeclaredRoute] can verify that tab routes, start destinations, deep-link
 * outputs and widget targets all resolve to a real destination — a dropped `composable` compiles
 * fine (NavHost routes are plain strings) and only crashes at runtime, so this list makes that
 * regression testable.
 *
 * A `{name}` segment marks a single path-segment parameter (e.g. `chat/{id}` matches `chat/abc`).
 */
object NavRoutes {
    const val SETUP = "setup"
    const val ACTIVITY = "activity"
    const val MESSAGES = "messages"
    const val SESSIONS = "sessions"
    const val BRAIN = "brain"
    const val ONESHOT = "oneshot"
    const val NETOPS = "netops"
    const val NETTOOLS = "nettools"
    const val WEBHOOKS = "webhooks"
    const val FILES = "files"
    const val FILES_AT = "files_at/{path}"
    const val FILE_VIEW = "file_view/{path}"
    const val FILE_EDIT = "file_edit/{path}"
    const val MATRIX_ROOM = "matrix_room/{roomId}"
    const val MATRIX_ROOM_SETTINGS = "matrix_room_settings/{roomId}"
    const val ARCHIVED = "archived"
    const val CHAT = "chat/{id}"
    const val MODELS = "models"
    const val PROFILES = "profiles"
    const val PROFILE_EDIT = "profile_edit/{name}"
    const val CRON = "cron"
    const val CRON_DETAIL = "cron_detail/{id}"
    const val CRON_EDIT = "cron_edit/{id}"
    const val MESSAGING = "messaging"
    const val MESSAGING_SETUP = "messaging_setup/{id}"
    const val USAGE = "usage"
    const val SETTINGS = "settings"
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_NTFY = "settings_ntfy"
    const val SETTINGS_MEMORY = "settings_memory"
    const val SETTINGS_PROMPTS = "settings_prompts"
    const val SETTINGS_MCP = "settings_mcp"
    const val SETTINGS_ENV = "settings_env"
    const val SETTINGS_CONNECTION = "settings_connection"
    const val SETTINGS_MATRIX = "settings_matrix"
    const val SETTINGS_DIAGNOSTICS = "settings_diagnostics"
    const val SETTINGS_ABOUT = "settings_about"
    const val SESSION_ADMIN = "session_admin"
    const val AGENTS_TOOLS = "agents_tools"
    const val APPROVALS = "approvals"
}

/** Every route pattern a `composable(...)` may declare, in the order they appear in the NavHost. */
val NAV_ROUTES: List<String> = listOf(
    NavRoutes.SETUP,
    NavRoutes.ACTIVITY,
    NavRoutes.MESSAGES,
    NavRoutes.SESSIONS,
    NavRoutes.BRAIN,
    NavRoutes.ONESHOT,
    NavRoutes.NETOPS,
    NavRoutes.NETTOOLS,
    NavRoutes.WEBHOOKS,
    NavRoutes.FILES,
    NavRoutes.FILES_AT,
    NavRoutes.FILE_VIEW,
    NavRoutes.FILE_EDIT,
    NavRoutes.MATRIX_ROOM,
    NavRoutes.MATRIX_ROOM_SETTINGS,
    NavRoutes.ARCHIVED,
    NavRoutes.CHAT,
    NavRoutes.MODELS,
    NavRoutes.PROFILES,
    NavRoutes.PROFILE_EDIT,
    NavRoutes.CRON,
    NavRoutes.CRON_DETAIL,
    NavRoutes.CRON_EDIT,
    NavRoutes.MESSAGING,
    NavRoutes.MESSAGING_SETUP,
    NavRoutes.USAGE,
    NavRoutes.SETTINGS,
    NavRoutes.SETTINGS_APPEARANCE,
    NavRoutes.SETTINGS_NOTIFICATIONS,
    NavRoutes.SETTINGS_NTFY,
    NavRoutes.SETTINGS_MEMORY,
    NavRoutes.SETTINGS_PROMPTS,
    NavRoutes.SETTINGS_MCP,
    NavRoutes.SETTINGS_ENV,
    NavRoutes.SETTINGS_CONNECTION,
    NavRoutes.SETTINGS_MATRIX,
    NavRoutes.SETTINGS_DIAGNOSTICS,
    NavRoutes.SETTINGS_ABOUT,
    NavRoutes.SESSION_ADMIN,
    NavRoutes.AGENTS_TOOLS,
    NavRoutes.APPROVALS,
)

/**
 * True when [pattern] (a NavHost route pattern) matches the concrete [route].
 *
 * Contract:
 * - A `{arg}` segment is a single-segment wildcard: it matches exactly one path segment
 *   (`chat/{id}` matches `chat/abc`, but not `chat` or `chat/a/b`).
 * - Any `?query` suffix on [route] is ignored, so `chat/abc?x=1` matches `chat/{id}`.
 */
fun navPatternMatches(pattern: String, route: String): Boolean {
    val segments = pattern.split('/')
    val actual = route.substringBefore('?').split('/')
    if (segments.size != actual.size) return false
    return segments.indices.all { i ->
        val segment = segments[i]
        (segment.startsWith("{") && segment.endsWith("}")) || segment == actual[i]
    }
}

/** True when [route] resolves to one of the declared [NAV_ROUTES] patterns. */
fun isDeclaredRoute(route: String): Boolean = NAV_ROUTES.any { navPatternMatches(it, route) }
