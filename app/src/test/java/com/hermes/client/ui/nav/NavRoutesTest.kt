package com.hermes.client.ui.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the nav-graph regression where `composable(...)` destinations were deleted:
 * NavHost routes are plain strings, so a dropped destination compiles fine and only crashes at
 * runtime. Every entry point below must resolve to a pattern declared in [NAV_ROUTES].
 */
class NavRoutesTest {

    // The three bottom-bar tab roots (mirrors TABS in HermesNav and TAB_ROUTES in DeepLinkMapper).
    private val tabRoutes = listOf("activity", "sessions", "you")

    // Start destinations picked in HermesNav ("setup" gates first launch).
    private val startDestinations = listOf("activity", "setup")

    @Test
    fun every_tab_is_a_declared_route() {
        tabRoutes.forEach { route ->
            assertTrue("tab \"$route\" is not a declared route", isDeclaredRoute(route))
        }
    }

    @Test
    fun every_start_destination_is_declared() {
        startDestinations.forEach { route ->
            assertTrue("start destination \"$route\" is not declared", isDeclaredRoute(route))
        }
    }

    @Test
    fun every_deep_link_output_is_declared() {
        val links = listOf(
            "hermes://tab/sessions",
            "hermes://tab/activity",
            "hermes://tab/you",
            "hermes://gateway/sessions",
            "hermes://chat/abc-123",
        )
        links.forEach { link ->
            val route = deepLinkRouteFor(link)
            assertTrue("deep link $link → $route is not a declared route", route != null && isDeclaredRoute(route))
        }
    }

    @Test
    fun every_widget_route_is_declared() {
        // From HermesWidget: "> new_chat" is a hermes://new link (create-session), not a nav route,
        // so it is deliberately absent here. The other two launch into the nav graph.
        val widgetRoutes = listOf("gateway_sessions", "activity")
        widgetRoutes.forEach { route ->
            assertTrue("widget route \"$route\" is not declared", isDeclaredRoute(route))
        }
    }

    @Test
    fun parameterized_pattern_matches_concrete_route() {
        assertTrue(navPatternMatches("chat/{id}", "chat/x"))
        assertTrue(navPatternMatches("matrix_room/{roomId}", "matrix_room/!abc:def"))
        assertFalse(navPatternMatches("chat/{id}", "chat/a/b"))
        assertFalse(navPatternMatches("chat/{id}", "activity"))
    }

    @Test
    fun concrete_routes_resolve_to_declared_patterns() {
        assertTrue(isDeclaredRoute("chat/x"))
        assertTrue(isDeclaredRoute("matrix_room/!abc:def"))
        assertTrue(isDeclaredRoute("cron_detail/42"))
        assertFalse(isDeclaredRoute("chat/x/y"))
        assertFalse(isDeclaredRoute("bogus"))
    }
}
