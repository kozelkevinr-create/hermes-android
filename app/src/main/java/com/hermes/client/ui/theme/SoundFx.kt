package com.hermes.client.ui.theme

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Tiny synthesized terminal blips — no bundled audio assets, just square/sine tones generated on
 * the fly. Off unless [enabled] is set (wired to a Settings toggle in MainActivity), so the app is
 * silent by default. Each call renders a short PCM buffer on a throwaway thread and plays it once;
 * failures are swallowed because a cosmetic beep must never crash or block the UI.
 */
object SoundFx {
    @Volatile var enabled: Boolean = false

    private const val SAMPLE_RATE = 22_050

    /** A crisp key tick — used on send/record start. */
    fun tick() = play(freq = 880.0, ms = 35, square = true, volume = 0.18)

    /** A rising confirm blip — used on a successful dispatch. */
    fun blip() {
        if (!enabled) return
        play(freq = 660.0, ms = 55, square = false, volume = 0.22)
        play(freq = 990.0, ms = 70, square = false, volume = 0.22, delayMs = 55)
    }

    /** A low error buzz. */
    fun buzz() = play(freq = 160.0, ms = 180, square = true, volume = 0.22)

    private fun play(freq: Double, ms: Int, square: Boolean, volume: Double, delayMs: Long = 0) {
        if (!enabled) return
        thread(isDaemon = true) {
            runCatching {
                if (delayMs > 0) Thread.sleep(delayMs)
                val count = SAMPLE_RATE * ms / 1000
                val buf = ShortArray(count)
                for (i in 0 until count) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val raw = if (square) {
                        if (sin(2 * PI * freq * t) >= 0) 1.0 else -1.0
                    } else {
                        sin(2 * PI * freq * t)
                    }
                    // Exponential decay envelope so it reads as a "blip", not a sustained tone,
                    // and avoids a click at the tail.
                    val env = exp(-3.0 * t / (ms / 1000.0))
                    buf[i] = (raw * env * volume * Short.MAX_VALUE).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep((ms + 60).toLong())
                track.release()
            }
        }
    }
}
