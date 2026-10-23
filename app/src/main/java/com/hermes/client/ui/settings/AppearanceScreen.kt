package com.hermes.client.ui.settings
import androidx.compose.material.icons.automirrored.rounded.ArrowBack

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import com.hermes.client.data.repository.AppearancePrefs
import com.hermes.client.data.repository.FontStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hermes.client.data.repository.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val technical by vm.toolCallTechnical.collectAsStateWithLifecycle()
    val amber by vm.amberPalette.collectAsStateWithLifecycle()
    val sound by vm.soundEffects.collectAsStateWithLifecycle()
    val look by vm.appearance.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            com.hermes.client.ui.components.HermesTopBar(
                title = "Appearance",
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Label("Color mode")
            val modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { vm.setThemeMode(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }

            Label("Tool-call display", top = 24.dp)
            Text(
                "Technical shows full tool input/output; Product hides raw payloads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
            )
            val options = listOf(false to "Product", true to "Technical")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = technical == value,
                        onClick = { vm.setToolCallTechnical(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }

            androidx.compose.material3.HorizontalDivider()
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                    Text("Amber CRT palette", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Monochrome amber instead of phosphor green (dark mode).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(checked = amber, onCheckedChange = { vm.setAmberPalette(it) })
            }
            androidx.compose.material3.HorizontalDivider()
            androidx.compose.foundation.layout.Row(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                    Text("Terminal sounds", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Synthesized blips on dictate, dispatch and errors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = sound,
                    onCheckedChange = { on ->
                        vm.setSoundEffects(on)
                        // Preview the blip immediately on enable (the pref flow updates a beat later).
                        if (on) {
                            com.hermes.client.ui.theme.SoundFx.enabled = true
                            com.hermes.client.ui.theme.SoundFx.blip()
                        }
                    },
                )
            }

            HorizontalDivider()
            Label("Surfaces", top = 24.dp)
            SwitchRow(
                "AMOLED black",
                "Pure black backgrounds in dark mode. Saves power on OLED screens.",
                look.amoled,
            ) { vm.setAmoled(it) }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                SwitchRow(
                    "Material You colors",
                    "Use your wallpaper's colors instead of the Hermes palette. Profile accents still apply.",
                    look.dynamicColor,
                ) { vm.setDynamicColor(it) }
            }

            Label("Text", top = 24.dp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Size", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${(look.textScale * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
            }
            Slider(
                value = look.textScale,
                onValueChange = { vm.setTextScale((it * 20).toInt() / 20f) },
                valueRange = AppearancePrefs.MIN_SCALE..AppearancePrefs.MAX_SCALE,
            )
            val fonts = listOf(FontStyle.TERMINAL to "Terminal", FontStyle.SYSTEM to "System")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                fonts.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = look.fontStyle == value,
                        onClick = { vm.setFontStyle(value) },
                        shape = SegmentedButtonDefaults.itemShape(i, fonts.size),
                    ) { Text(label) }
                }
            }
            Text(
                "The quick brown fox jumps over the lazy dog. 0123456789",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 12.dp),
            )

            Label("Chat", top = 24.dp)
            SwitchRow(
                "Compact bubbles",
                "Tighter padding around your messages.",
                look.compactChat,
            ) { vm.setCompactChat(it) }

            TextButton(onClick = { vm.resetAppearance() }, modifier = Modifier.padding(top = 16.dp)) {
                Text("Reset surfaces, text and chat to defaults")
            }
        }
    }
}

@Composable
private fun Label(text: String, top: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = top),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
