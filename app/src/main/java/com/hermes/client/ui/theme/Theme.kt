package com.hermes.client.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.hermes.client.data.repository.AppearancePrefs
import com.hermes.client.data.repository.FontStyle
import androidx.compose.ui.platform.LocalContext

/** App-wide flag for tool-call verbosity (true = Technical/show payloads, false = Product/hide). */
val LocalToolCallTechnical = compositionLocalOf { true }

/** Tighter chat bubbles (Appearance → Compact chat). */
val LocalChatCompact = compositionLocalOf { false }

/** Pure-black surfaces for OLED panels; accents and containers are left alone. */
internal fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
)

@Composable
fun HermesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    amber: Boolean = false,
    appearance: AppearancePrefs = AppearancePrefs(),
    // Material You is opt-in and acts only as a neutral base — the per-profile accent still
    // wins for chrome. Off by default so the curated palette is the stable, screenshot-ready look.
    dynamicColor: Boolean = false,
    // Active profile name → chrome accent. Null keeps the brand (no-profile) accent.
    profile: String? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val base = when {
        (dynamicColor || appearance.dynamicColor) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme && amber -> HermesAmberDarkColors
        darkTheme -> HermesDarkColors
        else -> HermesLightColors
    }
    val colors = if (darkTheme && appearance.amoled) base.amoled() else base
    val typography = remember(appearance.textScale, appearance.fontStyle) {
        scaledTypography(appearance.textScale, appearance.fontStyle == FontStyle.SYSTEM)
    }
    // Honour a user-chosen colour for the active profile (falls back to the hashed hue).
    val accent = profileAccentColors(profile, darkTheme, LocalProfileAccentOverrides.current[profile])

    CompositionLocalProvider(
        LocalProfileAccent provides accent,
        LocalChatCompact provides appearance.compactChat,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = typography,
            shapes = HermesShapes,
            content = content,
        )
    }
}
