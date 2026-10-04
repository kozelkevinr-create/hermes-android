package com.hermes.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hermes.client.R

// Terminal type system. Two bundled OFL fonts (licences in docs/licenses/):
//  - VT323: a DEC VT320 pixel face for display/headline/titleLarge — the "90s terminal" voice.
//    Pixel glyphs render small for their em size, so these sizes run larger than Material's.
//  - JetBrains Mono: everything else. Monospace keeps the hacker feel while staying legible for
//    long agent turns; it's wide, so body sizes sit ~1sp under Material's defaults.

val TerminalDisplay = FontFamily(Font(R.font.vt323_regular, FontWeight.Normal))

val TerminalMono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_medium, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

private fun display(size: Int, line: Int, tracking: Double = 0.5) = TextStyle(
    fontFamily = TerminalDisplay,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private fun mono(weight: FontWeight, size: Double, line: Int, tracking: Double) = TextStyle(
    fontFamily = TerminalMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val HermesTypography = Typography(
    displayLarge = display(64, 68),
    displayMedium = display(52, 56),
    displaySmall = display(42, 46),
    headlineLarge = display(38, 42),
    headlineMedium = display(34, 38),
    headlineSmall = display(30, 34),
    titleLarge = display(28, 30),
    titleMedium = mono(FontWeight.Medium, 15.0, 22, 0.0),
    titleSmall = mono(FontWeight.Bold, 12.0, 18, 1.2),
    bodyLarge = mono(FontWeight.Normal, 14.5, 23, 0.0),
    bodyMedium = mono(FontWeight.Normal, 13.0, 20, 0.0),
    bodySmall = mono(FontWeight.Normal, 11.5, 16, 0.1),
    labelLarge = mono(FontWeight.Medium, 13.0, 20, 0.6),
    labelMedium = mono(FontWeight.Medium, 11.5, 16, 0.8),
    labelSmall = mono(FontWeight.Medium, 10.5, 14, 0.8),
)
