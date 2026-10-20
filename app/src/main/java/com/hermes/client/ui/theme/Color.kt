package com.hermes.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Cyberpunk / 90s-terminal palette. Dark ("Neon Night") is the signature look: phosphor-green
// ink on a near-black CRT, with cyan and magenta neon as secondary/tertiary signals. Light
// ("Daylight Grid") keeps the same hue roles at print-safe contrast for outdoor use.
//
// Every surface role is set explicitly — Material3 otherwise fills surfaceContainer* with its
// baseline purple-greys, which would leak into cards, sheets and the bottom bar.

/** Raw neon swatches, for the few call sites that need a fixed signal colour (status, widget). */
object Neon {
    val Phosphor = Color(0xFF33FF77)
    val Cyan = Color(0xFF00E5FF)
    val Magenta = Color(0xFFFF2BD6)
    val Amber = Color(0xFFFFB000)
    val Red = Color(0xFFFF3B5C)
    val Void = Color(0xFF05070A)
}

// Neon Night (dark)
private val NightBackground = Color(0xFF040A06)
private val NightInk = Color(0xFFC8FFD8)
private val NightInkDim = Color(0xFF7FB894)

val HermesDarkColors = darkColorScheme(
    primary = Neon.Phosphor,
    onPrimary = Color(0xFF001A08),
    primaryContainer = Color(0xFF0B3D1E),
    onPrimaryContainer = Color(0xFF9DFFB8),
    inversePrimary = Color(0xFF0B6B30),
    secondary = Color(0xFF7CFFA8),
    onSecondary = Color(0xFF00210C),
    secondaryContainer = Color(0xFF0D4A24),
    onSecondaryContainer = Color(0xFFBFFFD2),
    tertiary = Color(0xFFC8FF4D),
    onTertiary = Color(0xFF1B2600),
    tertiaryContainer = Color(0xFF35461A),
    onTertiaryContainer = Color(0xFFE4FFA8),
    error = Neon.Red,
    onError = Color(0xFF2D0008),
    errorContainer = Color(0xFF4D0A18),
    onErrorContainer = Color(0xFFFFB3C0),
    background = NightBackground,
    onBackground = NightInk,
    surface = NightBackground,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF102016),
    onSurfaceVariant = NightInkDim,
    surfaceTint = Neon.Phosphor,
    inverseSurface = NightInk,
    inverseOnSurface = NightBackground,
    outline = Color(0xFF2E6B45),
    outlineVariant = Color(0xFF1A2E22),
    scrim = Color.Black,
    surfaceBright = Color(0xFF1F2B24),
    surfaceDim = NightBackground,
    surfaceContainerLowest = Color(0xFF030405),
    surfaceContainerLow = Color(0xFF0A0E0C),
    surfaceContainer = Color(0xFF0E1411),
    surfaceContainerHigh = Color(0xFF131B17),
    surfaceContainerHighest = Color(0xFF18231D),
)

// Daylight Grid (light)
private val GridPaper = Color(0xFFEEF3EF)
private val GridInk = Color(0xFF0A1F12)

val HermesLightColors = lightColorScheme(
    primary = Color(0xFF006B2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F5C8),
    onPrimaryContainer = Color(0xFF00210B),
    inversePrimary = Neon.Phosphor,
    secondary = Color(0xFF2E7D4F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBCF0CE),
    onSecondaryContainer = Color(0xFF00210C),
    tertiary = Color(0xFF4F6B18),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F5A0),
    onTertiaryContainer = Color(0xFF162000),
    error = Color(0xFFC0002A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDADC),
    onErrorContainer = Color(0xFF40000A),
    background = GridPaper,
    onBackground = GridInk,
    surface = GridPaper,
    onSurface = GridInk,
    surfaceVariant = Color(0xFFD9E3DC),
    onSurfaceVariant = Color(0xFF3B4A40),
    surfaceTint = Color(0xFF006B2E),
    inverseSurface = Color(0xFF0E1411),
    inverseOnSurface = NightInk,
    outline = Color(0xFF5E7A68),
    outlineVariant = Color(0xFFB6C9BC),
    scrim = Color.Black,
    surfaceBright = GridPaper,
    surfaceDim = Color(0xFFD3DBD5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFE8EEE9),
    surfaceContainer = Color(0xFFE2E9E4),
    surfaceContainerHigh = Color(0xFFDCE4DE),
    surfaceContainerHighest = Color(0xFFD6DFD8),
)

// Amber ("monochrome amber CRT") alt-palette — an optional nod to amber phosphor monitors.
// Derived from the green dark scheme so only the hue-bearing roles change; neutrals/surfaces stay.
val Amber = Color(0xFFFFB000)
val HermesAmberDarkColors = HermesDarkColors.copy(
    primary = Amber,
    onPrimary = Color(0xFF241A00),
    primaryContainer = Color(0xFF4A3400),
    onPrimaryContainer = Color(0xFFFFDF9E),
    inversePrimary = Color(0xFF6B4E0B),
    secondary = Color(0xFFFFD27C),
    onSecondary = Color(0xFF241A00),
    secondaryContainer = Color(0xFF4A3400),
    onSecondaryContainer = Color(0xFFFFE8BF),
    tertiary = Color(0xFFFFC14D),
    onTertiary = Color(0xFF261900),
    surfaceTint = Amber,
    onBackground = Color(0xFFFFE7C8),
    onSurface = Color(0xFFFFE7C8),
    outline = Color(0xFF6B5A2E),
)
