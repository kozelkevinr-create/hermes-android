package com.hermes.client.ui.plugins

import androidx.compose.runtime.Composable
import com.hermes.client.ui.plugins.calendar.CalendarPluginView

/**
 * A native (Compose) screen for a Hermes dashboard plugin.
 *
 * Each plugin adds ONE file in its own package (`ui/plugins/<plugin>/`) that exposes a
 * [NativePluginView], and lists it in [NativePluginViews.all] (one added line). Screens get
 * their data through the app's shared [com.hermes.client.data.network.HermesRestApi] (auth is
 * handled there) and the active profile from
 * [com.hermes.client.data.repository.ProfileManager], and send `?profile=<name>` on plugin calls.
 */
interface NativePluginView {
    /** Plugin name exactly as `GET /api/dashboard/plugins` reports it, e.g. "hermes-calendar". */
    val pluginName: String

    /** Title shown in the app's top bar and the Plugins list. */
    val title: String

    /** The screen. Draw inside the app's Scaffold content; [onBack] pops the screen. */
    @Composable
    fun Content(onBack: () -> Unit)
}

object NativePluginViews {
    /** Add `YourPluginView` here. Plugins without an entry fall back to the WebView. */
    val all: List<NativePluginView> = listOf(
        CalendarPluginView(),
        com.hermes.client.ui.plugins.workbook.WorkbookPluginView(),
    )

    fun forPlugin(name: String): NativePluginView? = all.firstOrNull { it.pluginName == name }
}
