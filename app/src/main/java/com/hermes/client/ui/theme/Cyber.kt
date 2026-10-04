package com.hermes.client.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

// Signature cyberpunk flourishes that sit on top of the Material theme tokens.

/** Sender bubble: chamfered with a sharp bottom-end "tail", mirroring the old asymmetric round. */
val UserBubbleShape = CutCornerShape(topStart = 12.dp, topEnd = 0.dp, bottomEnd = 0.dp, bottomStart = 12.dp)

/** Small chamfer for inline panels (tool cards, highlights, error blocks, thumbnails). */
val PanelShape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)

/**
 * CRT scanlines: a faint dark line every [periodPx] pixels drawn over the content. Built as a
 * tiled 1×N shader so it's a single drawRect per frame rather than hundreds of drawLine calls.
 * Purely visual — it doesn't intercept touches.
 */
fun Modifier.crtScanlines(
    enabled: Boolean = true,
    color: Color = Color.Black,
    strength: Float = 0.16f,
    periodPx: Int = 3,
): Modifier = if (!enabled) this else drawWithCache {
    val tile = ImageBitmap(1, periodPx)
    val pixels = IntArray(periodPx) { if (it == periodPx - 1) color.copy(alpha = strength).toArgb() else 0 }
    tile.asAndroidBitmap().setPixels(pixels, 0, 1, 0, 0, 1, periodPx)
    val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    onDrawWithContent {
        drawContent()
        drawRect(brush)
    }
}

// Square-wave easing: off for the first half of each cycle, on for the second.
private val SquareWave = Easing { f -> if (f < 0.5f) 0f else 1f }

/** A hard-blinking terminal block cursor (on/off, no fade — like a real VT). */
@Composable
fun BlinkingCursor(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val off by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1060, easing = SquareWave),
            repeatMode = RepeatMode.Restart,
        ),
        label = "cursorBlink",
    )
    Text("█", color = color, style = MaterialTheme.typography.bodyLarge, modifier = modifier.alpha(1f - off))
}
