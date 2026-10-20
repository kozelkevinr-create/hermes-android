package com.hermes.client.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import com.hermes.client.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hermes.client.ui.theme.BlinkingCursor
import com.hermes.client.ui.theme.Neon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import kotlin.random.Random

// Boot sequence: a Matrix-style digital rain of green digits, which thins out to reveal the
// braille portrait before handing off to the app. Timings are deliberately short — a splash you have to
// sit through twice stops being fun, so the whole thing is under two seconds.

private const val RAIN_MILLIS = 1150
private const val LOGO_MILLIS = 700
private const val PORTRAIT_WIDTH_FRACTION = 0.62f
private const val PORTRAIT_ASPECT = 576f / 896f

/** One falling column of glyphs. Head is the bright leading character; the tail fades behind it. */
private class RainColumn(val xPx: Float, var headPx: Float, val speedPxPerSec: Float, val tail: Int)

/**
 * Full-screen boot animation. Calls [onFinished] once when the sequence completes; the caller
 * swaps in the real UI. Rendering uses a frame clock rather than an infinite transition so the
 * whole thing stops cleanly instead of animating forever behind the app.
 */
@Composable
fun MatrixSplash(onFinished: () -> Unit) {
    val density = LocalDensity.current
    val glyphPx = with(density) { 15.sp.toPx() }
    val cellPx = glyphPx * 1.15f

    var elapsedMs by remember { mutableFloatStateOf(0f) }
    var showLogo by remember { mutableStateOf(false) }
    val columns = remember { mutableStateOf<List<RainColumn>?>(null) }

    // Drive the rain from the frame clock, then hand off. withFrameNanos pauses with the
    // composition, so a backgrounded app doesn't burn frames here.
    LaunchedEffect(Unit) {
        var last = 0L
        while (elapsedMs < RAIN_MILLIS) {
            withFrameNanos { now ->
                if (last != 0L) elapsedMs += (now - last) / 1_000_000f
                last = now
            }
        }
        showLogo = true
        kotlinx.coroutines.delay(LOGO_MILLIS.toLong())
        onFinished()
    }

    // Rain fades out over the last third so the logo arrives on near-black rather than a hard cut.
    val rainAlpha by animateFloatAsState(
        targetValue = if (showLogo) 0f else 1f,
        animationSpec = tween(durationMillis = 320, easing = LinearEasing),
        label = "rainFade",
    )

    Box(
        Modifier.fillMaxSize().background(Neon.Void),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cols = columns.value ?: buildColumns(cellPx).also { columns.value = it }
            drawRain(cols, elapsedMs, cellPx, glyphPx, rainAlpha)
        }

        AnimatedVisibility(
            visible = showLogo,
            enter = fadeIn(animationSpec = tween(260)),
            exit = fadeOut(animationSpec = tween(160)),
        ) {
            // Braille portrait, tinted phosphor green at draw time so it follows the palette.
            // FilterQuality.None keeps the 1-bit dots crisp when scaled.
            Image(
                bitmap = ImageBitmap.imageResource(R.drawable.braille_portrait),
                contentDescription = "Hermes Local",
                colorFilter = ColorFilter.tint(Neon.Phosphor),
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxWidth(PORTRAIT_WIDTH_FRACTION).aspectRatio(PORTRAIT_ASPECT),
            )
        }
    }
}

/** Seed one column per glyph-width of screen, each starting above the fold at a random offset. */
private fun DrawScope.buildColumns(cellPx: Float): List<RainColumn> {
    val count = (size.width / cellPx).toInt().coerceAtLeast(1)
    return List(count) { i ->
        RainColumn(
            xPx = i * cellPx,
            headPx = -Random.nextFloat() * size.height,
            speedPxPerSec = cellPx * (14f + Random.nextFloat() * 20f),
            tail = 6 + Random.nextInt(10),
        )
    }
}

private val GLYPHS = "0123456789".toCharArray()

private fun DrawScope.drawRain(
    columns: List<RainColumn>,
    elapsedMs: Float,
    cellPx: Float,
    glyphPx: Float,
    alpha: Float,
) {
    if (alpha <= 0.01f) return
    val paint = android.graphics.Paint().apply {
        textSize = glyphPx
        typeface = android.graphics.Typeface.MONOSPACE
        isAntiAlias = true
    }
    val seconds = elapsedMs / 1000f
    drawContext.canvas.nativeCanvas.let { canvas ->
        columns.forEachIndexed { ci, col ->
            val head = col.headPx + col.speedPxPerSec * seconds
            for (t in 0 until col.tail) {
                val y = head - t * cellPx
                if (y < 0f || y > size.height + cellPx) continue
                // Head glyph is near-white; the tail decays to transparent phosphor.
                val fade = 1f - (t / col.tail.toFloat())
                val a = (fade * fade * alpha).coerceIn(0f, 1f)
                paint.color = if (t == 0) {
                    android.graphics.Color.argb((255 * alpha).toInt(), 220, 255, 230)
                } else {
                    android.graphics.Color.argb((255 * a).toInt(), 51, 255, 119)
                }
                // Cheap deterministic glyph churn: varies per column, row and time slice without
                // allocating or storing per-cell state.
                val churn = (seconds * 12f).toInt()
                val g = GLYPHS[((ci * 31 + (y / cellPx).toInt() * 17 + churn) and 0x7fffffff) % GLYPHS.size]
                canvas.drawText(g.toString(), col.xPx, y, paint)
            }
        }
    }
}
