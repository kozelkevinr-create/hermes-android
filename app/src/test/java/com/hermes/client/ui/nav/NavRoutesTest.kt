package com.hermes.client.ui.nav

import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards against the nav-graph regression where `composable(...)` destinations were deleted:
 * NavHost routes are plain strings, so a dropped destination compiles fine and only crashes at
 * runtime. Every entry point below must resolve to a pattern declared in [NAV_ROUTES].
 */
class NavRoutesTest {

    // The four bottom-bar tab roots (mirrors TABS in HermesNav and TAB_ROUTES in DeepLinkMapper).
    private val tabRoutes = listOf("messages", "sessions", "brain", "oneshot")

    // Start destinations picked in HermesNav ("setup" gates first launch).
    private val startDestinations = listOf("messages", "setup")

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
            "hermes://tab/brain",
            "hermes://tab/oneshot",
            "hermes://tab/messages",
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
        // so it is deliberately absent here. The others launch into the nav graph.
        val widgetRoutes = listOf("oneshot", "sessions", "brain")
        widgetRoutes.forEach { route ->
            assertTrue("widget route \"$route\" is not declared", isDeclaredRoute(route))
        }
    }

    @Test
    fun declared_composables_match_nav_routes_exactly() {
        val source = hermesNavSource()
        val declaredConstants = NAV_ROUTE_REFERENCE.findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        val valuesByConstant = navRouteValues()
        val unknown = declaredConstants - valuesByConstant.keys
        assertTrue("HermesNav references unknown NavRoutes constants: $unknown", unknown.isEmpty())

        val declaredRoutes = declaredConstants.map { valuesByConstant.getValue(it) }.toSet()
        val expected = NAV_ROUTES.toSet()
        val missing = expected - declaredRoutes
        val extra = declaredRoutes - expected
        assertTrue(
            "composable(...) destinations are out of sync with NAV_ROUTES — " +
                "missing from HermesNav: $missing, extra in HermesNav: $extra",
            missing.isEmpty() && extra.isEmpty(),
        )
    }

    @Test
    fun nav_host_uses_nav_route_constants_not_string_literals() {
        val literals = STRING_LITERAL_COMPOSABLE.findAll(hermesNavSource()).map { it.value.trim() }.toList()
        assertTrue("HermesNav must use NavRoutes constants, found string literals: $literals", literals.isEmpty())
    }

    @Test
    fun parameterized_pattern_matches_concrete_route() {
        assertTrue(navPatternMatches("chat/{id}", "chat/x"))
        assertTrue(navPatternMatches("matrix_room/{roomId}", "matrix_room/!abc:def"))
        assertFalse(navPatternMatches("chat/{id}", "chat/a/b"))
        assertFalse(navPatternMatches("chat/{id}", "activity"))
    }

    @Test
    fun wildcard_matches_exactly_one_segment() {
        assertTrue(navPatternMatches("chat/{id}", "chat/abc"))
        assertFalse("a wildcard must not span several segments", navPatternMatches("chat/{id}", "chat/a/b"))
        assertFalse("a wildcard must not match a missing segment", navPatternMatches("chat/{id}", "chat"))
    }

    @Test
    fun query_string_is_ignored_when_matching() {
        assertTrue(navPatternMatches("chat/{id}", "chat/abc?x=1"))
        assertTrue(navPatternMatches("chat/{id}", "chat/abc?x=1&y=2"))
        assertTrue(navPatternMatches("activity", "activity?tab=home"))
        assertTrue(isDeclaredRoute("matrix_room/!abc:def?focus=1"))
        assertFalse(navPatternMatches("chat/{id}", "chat/a/b?x=1"))
    }

    private fun hermesNavSource(): String {
        val relative = "src/main/java/com/hermes/client/ui/nav/HermesNav.kt"
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "Cannot find HermesNav.kt from ${File(".").absolutePath}; tried " +
                candidates.joinToString { it.absolutePath },
            file,
        )
        return file!!.readText()
    }

    private fun navRouteValues(): Map<String, String> =
        NavRoutes::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .associate { it.name to (it.get(null) as String) }

    private companion object {
        val NAV_ROUTE_REFERENCE = Regex("""composable\(\s*NavRoutes\.([A-Za-z0-9_]+)""")
        val STRING_LITERAL_COMPOSABLE = Regex("""composable\(\s*"""")
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
