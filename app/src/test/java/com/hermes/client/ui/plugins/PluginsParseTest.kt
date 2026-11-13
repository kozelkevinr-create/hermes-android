package com.hermes.client.ui.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginsParseTest {
    @Test fun bareArrayWithTabPath() {
        val r = parseDashboardPlugins("""[{"name":"hermes-calendar","tab":{"path":"/calendar","label":"Calendar"}}]""")
        assertEquals(listOf(DashboardPlugin("hermes-calendar", "Calendar", "/calendar")), r)
    }
    @Test fun wrappedAndFallbackPath() {
        val r = parseDashboardPlugins("""{"plugins":[{"name":"kanban"}]}""")
        assertEquals("/kanban", r.single().path)
        assertEquals(true, r.single().description.startsWith("Multi-agent"))
    }
    @Test fun garbageIsEmpty() {
        assertEquals(emptyList<DashboardPlugin>(), parseDashboardPlugins("""{"x":1}"""))
    }

    @Test fun descriptionFromEndpointWins() {
        val r = parseDashboardPlugins("""[{"name":"kanban","description":"mine"}]""")
        assertEquals("mine", r.single().description)
    }
}
