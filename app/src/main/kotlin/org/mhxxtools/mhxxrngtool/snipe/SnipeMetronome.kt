package org.mhxxtools.mhxxrngtool.snipe

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import kotlin.math.roundToLong

/**
 * mhxx-snipe-navigator の metronome 相当。
 * BPM でトーンを鳴らす（簡易実装）。
 */
class SnipeMetronome(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null
    private var running = false
    private var bpm: Int = 60

    fun start(bpm: Int) {
        stop()
        this.bpm = bpm.coerceIn(20, 300)
        running = true
        try {
            tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (_: Exception) {
            tone = null
        }
        schedule()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { tone?.release() } catch (_: Exception) {}
        tone = null
    }

    val isRunning: Boolean get() = running

    private fun schedule() {
        if (!running) return
        try {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
        } catch (_: Exception) {}
        val interval = (60_000.0 / bpm).roundToLong().coerceAtLeast(50L)
        handler.postDelayed({ schedule() }, interval)
    }
}
