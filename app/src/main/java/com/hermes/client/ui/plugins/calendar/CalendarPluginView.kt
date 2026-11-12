package com.hermes.client.ui.plugins.calendar

import androidx.compose.runtime.Composable
import com.hermes.client.ui.plugins.NativePluginView

/** Native Compose calendar screen for the `hermes-calendar` dashboard plugin. */
class CalendarPluginView : NativePluginView {
    override val pluginName: String = "hermes-calendar"
    override val title: String = "Calendar"

    @Composable
    override fun Content(onBack: () -> Unit) {
        CalendarScreen(onBack = onBack)
    }
}
