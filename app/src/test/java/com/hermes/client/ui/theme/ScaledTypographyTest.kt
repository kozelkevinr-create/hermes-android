package com.hermes.client.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScaledTypographyTest {
    @Test fun scalesSizesAndLineHeights() {
        val t = scaledTypography(1.2f, systemFont = false)
        assertEquals(HermesTypography.bodyLarge.fontSize.value * 1.2f, t.bodyLarge.fontSize.value, 0.001f)
        assertEquals(HermesTypography.bodyLarge.lineHeight.value * 1.2f, t.bodyLarge.lineHeight.value, 0.001f)
    }

    @Test fun unitScaleKeepsTerminalFonts() {
        val t = scaledTypography(1f, systemFont = false)
        assertEquals(HermesTypography.bodyMedium.fontFamily, t.bodyMedium.fontFamily)
        assertEquals(HermesTypography.titleLarge.fontFamily, t.titleLarge.fontFamily)
    }

    @Test fun systemFontReplacesEveryFamily() {
        val t = scaledTypography(1f, systemFont = true)
        assertEquals(FontFamily.Default, t.bodyMedium.fontFamily)
        assertEquals(FontFamily.Default, t.displayLarge.fontFamily)
        assertNotEquals(HermesTypography.displayLarge.fontFamily, t.displayLarge.fontFamily)
    }
}
