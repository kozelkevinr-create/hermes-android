package com.hermes.client.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.ui.components.HermesTopBar
import com.hermes.client.ui.components.HubRow
import com.hermes.client.ui.theme.ACCENT_SWATCHES
import com.hermes.client.ui.theme.LocalProfileAccent
import com.hermes.client.ui.theme.accentFromHsl
import com.hermes.client.ui.theme.colorArgbToHsl
import com.hermes.client.ui.theme.hslToColorArgb

// Accent-colour picker for a profile, extracted from the retired "You" hub. Lives next to the
// profile list now: a per-profile colour is a property of that profile, not a separate tab.

@Composable
internal fun AccentColorDialog(
    profile: String,
    selected: Int?,
    onPick: (Int) -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accent colour · $profile") },
        text = {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val initHsl = selected?.let { colorArgbToHsl(it) }
            var hue by remember { mutableStateOf(initHsl?.first ?: 210f) }
            var sat by remember { mutableStateOf(initHsl?.second ?: 0.62f) }
            var light by remember { mutableStateOf(initHsl?.third ?: 0.44f) }
            val preview = accentFromHsl(hue, sat, light, dark)

            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Pick a swatch, dial in a custom colour, or Auto for the automatic hue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                ACCENT_SWATCHES.chunked(6).forEach { rowColors ->
                    Row {
                        rowColors.forEach { argb ->
                            Box(
                                Modifier
                                    .padding(6.dp)
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .clickable { onPick(argb) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (argb == selected) {
                                    // Adaptive tick colour: neon swatches are bright, so white would vanish on most of them.
                                    Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color(com.hermes.client.ui.theme.onColorFor(argb)))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Text(
                    "CUSTOM",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
                // Live preview of the dialled-in colour (accent + its adaptive, always-legible on-colour).
                Box(
                    Modifier.fillMaxWidth().height(44.dp).clip(MaterialTheme.shapes.small).background(preview.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Preview", color = preview.onAccent, style = MaterialTheme.typography.labelLarge)
                }
                SliderRow("Hue", hue, 0f..360f) { hue = it }
                SliderRow("Saturation", sat, 0f..1f) { sat = it }
                SliderRow("Lightness", light, 0f..1f) { light = it }
                Button(
                    onClick = { onPick(hslToColorArgb(hue, sat, light)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text("Use custom colour") }
            }
        },
        confirmButton = { TextButton(onClick = onAuto) { Text("Auto") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

/** Quick-switch avatar row: each profile's initial in a chip tinted to that profile's own accent. */
@Composable
internal fun ProfileAvatarRow(profiles: List<String>, active: String?, onSwitch: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        profiles.forEach { name ->
            val selected = name == active
            // Each avatar previews that profile's own accent hue (via ProfileAvatar), so the
            // switcher itself teaches the color mapping.
            Column(
                Modifier.padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                com.hermes.client.ui.components.ProfileAvatar(
                    name,
                    modifier = Modifier.clickable { onSwitch(name) },
                    size = 48.dp,
                )
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) LocalProfileAccent.current.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
