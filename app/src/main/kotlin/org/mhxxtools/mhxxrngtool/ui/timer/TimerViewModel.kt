package org.mhxxtools.mhxxrngtool.ui.timer

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SnipeMode {
    /** タイトル画面待機法: wait = (F-700)/30, grace = 0 */
    TITLE,
    /** コンティニュー連打法: wait = (F-700)%735/30 + 25.33, grace = ceil(count*60/BPM)+11 */
    CONTINUE
}

data class TimerUiState(
    // countdown
    val display: String = "00 分 30 秒 00",
    val phaseLabel: String = "待機中",
    val countdownMin: String = "0",
    val countdownSec: String = "30",
    val delayMin: String = "0",
    val delaySec: String = "0",
    val earlyMin: String = "0",
    val earlySec: String = "0",
    val loop: Boolean = false,
    val sound: Boolean = true,
    val isRunning: Boolean = false,
    // metronome
    val bpm: String = "120",
    val targetCount: String = "1000",
    val metCount: Int = 0,
    val metRunning: Boolean = false,
    /** 結果タップ時の自動入力方式 */
    val snipeMode: SnipeMode = SnipeMode.TITLE,
    val lastAppliedFrame: Long = -1L
)

class TimerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TimerUiState())
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var metJob: Job? = null
    private var tone: ToneGenerator? = null

    private fun beep(high: Boolean = true) {
        if (!_state.value.sound) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone?.startTone(if (high) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 80)
        } catch (_: Exception) {}
    }

    fun setCountdownMin(v: String) = _state.update { it.copy(countdownMin = v) }
    fun setCountdownSec(v: String) = _state.update { it.copy(countdownSec = v) }
    fun setDelayMin(v: String) = _state.update { it.copy(delayMin = v) }
    fun setDelaySec(v: String) = _state.update { it.copy(delaySec = v) }
    fun setEarlyMin(v: String) = _state.update { it.copy(earlyMin = v) }
    fun setEarlySec(v: String) = _state.update { it.copy(earlySec = v) }
    fun setLoop(v: Boolean) = _state.update { it.copy(loop = v) }
    fun setSound(v: Boolean) = _state.update { it.copy(sound = v) }
    fun setBpm(v: String) = _state.update { it.copy(bpm = v) }
    fun setTargetCount(v: String) = _state.update { it.copy(targetCount = v) }
    fun setSnipeMode(mode: SnipeMode) {
        _state.update { it.copy(snipeMode = mode) }
        val f = _state.value.lastAppliedFrame
        if (f >= 0) applyFromFrame(f) // 切り替え時に同じフレームで再計算
    }

    private fun parseMs(minS: String, secS: String): Long {
        val m = minS.toIntOrNull() ?: 0
        val s = secS.toDoubleOrNull() ?: 0.0
        return ((m * 60 + s) * 1000).toLong()
    }

    private fun formatMs(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val totalSec = t / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        val cs = (t % 1000) / 10
        return "%02d 分 %02d 秒 %02d".format(m, s, cs)
    }

    fun startCountdown() {
        if (countdownJob?.isActive == true) return
        val s = _state.value
        val totalMs = parseMs(s.countdownMin, s.countdownSec)
        val delayMs = parseMs(s.delayMin, s.delaySec)
        val earlyMs = parseMs(s.earlyMin, s.earlySec)
        val activeMs = (totalMs - earlyMs).coerceAtLeast(0)

        _state.update { it.copy(isRunning = true) }
        countdownJob = viewModelScope.launch {
            // delay phase
            if (delayMs > 0) {
                _state.update { it.copy(phaseLabel = "開始までの猶予...") }
                val t0 = System.currentTimeMillis()
                while (isActive) {
                    val left = delayMs - (System.currentTimeMillis() - t0)
                    if (left <= 0) break
                    _state.update { it.copy(display = formatMs(left)) }
                    delay(16)
                }
            }
            // active phase
            _state.update { it.copy(phaseLabel = "カウント中") }
            val t1 = System.currentTimeMillis()
            var lastBeepSec = -1
            while (isActive) {
                val left = activeMs - (System.currentTimeMillis() - t1)
                if (left <= 0) break
                val secLeft = (left / 1000).toInt()
                if (secLeft in 1..10 && secLeft != lastBeepSec) {
                    beep(true)
                    lastBeepSec = secLeft
                }
                _state.update { it.copy(display = formatMs(left)) }
                delay(16)
            }
            if (isActive) {
                beep(false)
                if (_state.value.loop) {
                    startCountdown()
                } else {
                    _state.update {
                        it.copy(isRunning = false, phaseLabel = "終了", display = formatMs(0))
                    }
                }
            }
        }
    }

    fun stopCountdown() {
        countdownJob?.cancel()
        _state.update { it.copy(isRunning = false, phaseLabel = "停止") }
    }

    fun resetCountdown() {
        countdownJob?.cancel()
        _state.update {
            it.copy(
                isRunning = false, phaseLabel = "待機中",
                display = formatMs(parseMs(it.countdownMin, it.countdownSec))
            )
        }
    }

    fun toggleMetronome() {
        if (metJob?.isActive == true) {
            stopMetronome()
            return
        }
        val bpm = (_state.value.bpm.toIntOrNull() ?: 120).coerceIn(40, 300)
        val target = (_state.value.targetCount.toIntOrNull() ?: 1000).coerceAtLeast(1)
        val interval = 60_000L / bpm
        _state.update { it.copy(metRunning = true, metCount = 0) }
        metJob = viewModelScope.launch {
            while (isActive) {
                delay(interval)
                val c = _state.value.metCount + 1
                _state.update { it.copy(metCount = c) }
                beep(c % 10 == 0)
                if (c >= target) {
                    stopMetronome()
                    break
                }
            }
        }
    }

    fun stopMetronome() {
        metJob?.cancel()
        _state.update { it.copy(metRunning = false) }
    }

    fun resetMetronome() {
        metJob?.cancel()
        _state.update { it.copy(metRunning = false, metCount = 0) }
    }


    /**
     * 鑑定/検索結果のフレームをタップしたとき。
     * [snipeMode] に応じて通常 / コンティニュー連打の計算式を切り替える（HTML 準拠）。
     *
     * 通常 (TITLE):
     *   waitSec  = (frame - 700) / 30
     *   graceSec = 0
     *
     * コンティニュー連打 (CONTINUE):
     *   FPC=735, BPM=57, LOAD_OFF=25.33, MARGIN=11
     *   finalFrame = frame - 700
     *   count      = floor(finalFrame / 735)
     *   waitSec    = (finalFrame % 735) / 30 + 25.33
     *   graceSec   = ceil(count * 60 / 57) + 11
     *   メトロノーム目標 = count, BPM = 57
     */
    fun applyFromFrame(frame: Long) {
        val OFFSET = 700L
        val FPS = 30.0
        val mode = _state.value.snipeMode

        val waitSec: Double
        val graceSec: Double
        val phase: String
        var metTarget = "0"
        var metBpm = _state.value.bpm.ifBlank { "120" }

        when (mode) {
            SnipeMode.TITLE -> {
                waitSec = if (frame > OFFSET) (frame - OFFSET) / FPS else frame / FPS
                graceSec = 0.0
                // 連打回数も参考表示用にメトロノームへ
                val (mashes, _, _) = org.mhxxtools.mhxxrngtool.rng.continueMashInfo(frame)
                metTarget = mashes.coerceAtLeast(0).toString()
                phase = "通常スナイプ F$frame → 待機後にContinue (±30f)"
            }
            SnipeMode.CONTINUE -> {
                // HTML calcContinueMethod
                val AC_FPC = 735.0
                val AC_BPM = 57
                val AC_LOAD_OFF = 25.33
                val AC_MARGIN = 11.0
                val finalFrame = (frame - OFFSET).coerceAtLeast(0L).toDouble()
                val count = kotlin.math.floor(finalFrame / AC_FPC).toLong()
                val remFrame = finalFrame % AC_FPC
                waitSec = remFrame / FPS + AC_LOAD_OFF
                graceSec = kotlin.math.ceil(count * 60.0 / AC_BPM) + AC_MARGIN
                metTarget = count.coerceAtLeast(0).toString()
                metBpm = AC_BPM.toString()
                phase = "連打スナイプ F$frame → 連打${count}回 + 待機 (BPM$AC_BPM)"
            }
        }

        val wMin = (waitSec / 60).toInt()
        val wSec = waitSec % 60
        val wSecStr = String.format(java.util.Locale.US, "%.3f", wSec)
        val gMin = (graceSec / 60).toInt()
        val gSec = graceSec % 60
        val gSecStr = String.format(java.util.Locale.US, "%.3f", gSec)

        _state.update {
            it.copy(
                lastAppliedFrame = frame,
                countdownMin = wMin.toString(),
                countdownSec = wSecStr,
                delayMin = gMin.toString(),
                delaySec = gSecStr,
                earlyMin = "0",
                earlySec = "0",
                display = formatMs(parseMs(wMin.toString(), wSecStr)),
                phaseLabel = phase,
                targetCount = metTarget,
                bpm = metBpm,
                metCount = 0
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        metJob?.cancel()
        tone?.release()
        tone = null
    }
}
