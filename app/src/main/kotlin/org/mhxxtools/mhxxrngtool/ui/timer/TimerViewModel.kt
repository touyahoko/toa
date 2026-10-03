package org.mhxxtools.mhxxrngtool.ui.timer

import android.app.Application
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SnipeMode {
    /** 残りに応じて自動選択 (連打回数>=1 → 連打、それ以外 → 通常) */
    AUTO,
    /** 通常スナイプ (こめこ): wait = 残りF / FPS */
    TITLE,
    /** コンティニュー連打 (こめこ): count=floor(残り/730), wait=(余り)/FPS */
    CONTINUE
}

data class TimerUiState(
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
    val bpm: String = "120",
    val targetCount: String = "1000",
    val metCount: Int = 0,
    val metRunning: Boolean = false,
    val snipeMode: SnipeMode = SnipeMode.AUTO,
    val lastAppliedFrame: Long = -1L,
    val currentPosFrame: Long = -1L,
    val targetFrame: Long = -1L,
    val remainingFrames: Long = -1L,
    val mashCount: Long = 0L,
    val remainderFrames: Long = 0L,
    val calcSummary: String = "①調合で現在地 → ②検索で目標お守り → 自動で残り計算",
    val appliedModeLabel: String = ""
)

class TimerViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TimerUiState())
    val state: StateFlow<TimerUiState> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var metJob: Job? = null
    private var tone: ToneGenerator? = null

    companion object {
        const val FPS = 30.0
        const val SKIP_SIZE = 730L
    }

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
        recalculateSnipe()
    }

    /** 調合結果タップ: 現在地フレーム */
    fun setCurrentPosFrame(frame: Long) {
        _state.update {
            it.copy(currentPosFrame = frame.coerceAtLeast(0L), lastAppliedFrame = frame)
        }
        recalculateSnipe()
    }

    /** お守り検索結果タップ: 目標フレーム */
    fun setTargetFrame(frame: Long) {
        _state.update {
            it.copy(targetFrame = frame.coerceAtLeast(0L), lastAppliedFrame = frame)
        }
        recalculateSnipe()
    }

    fun clearSnipeFrames() {
        _state.update {
            it.copy(
                currentPosFrame = -1L,
                targetFrame = -1L,
                remainingFrames = -1L,
                mashCount = 0L,
                remainderFrames = 0L,
                calcSummary = "①調合で現在地 → ②検索で目標お守り → 自動で残り計算",
                appliedModeLabel = "",
                lastAppliedFrame = -1L
            )
        }
    }

    /** 後方互換: 単体タップは目標として扱う */
    fun applyFromFrame(frame: Long) = setTargetFrame(frame)

    /**
     * 残り = 目標 − 現在地
     * 短い → 通常待機 (÷FPS)
     * 長い → 連打 (÷730 + 余り待機)
     */
    fun recalculateSnipe() {
        val s = _state.value
        val cur = s.currentPosFrame
        val tgt = s.targetFrame

        if (cur < 0 && tgt < 0) {
            _state.update {
                it.copy(calcSummary = "①調合で現在地 → ②検索で目標お守り → 自動で残り計算")
            }
            return
        }
        if (cur < 0) {
            applyRemaining(tgt, "現在地未設定のため目標 F$tgt を絶対値で計算（現在地=0）")
            return
        }
        if (tgt < 0) {
            _state.update {
                it.copy(
                    remainingFrames = -1L,
                    mashCount = 0L,
                    remainderFrames = 0L,
                    calcSummary = "現在地 F$cur セット済み。検索タブで目標お守りをタップしてください",
                    appliedModeLabel = ""
                )
            }
            return
        }

        val rem = tgt - cur
        if (rem < 0) {
            _state.update {
                it.copy(
                    remainingFrames = rem,
                    mashCount = 0L,
                    remainderFrames = 0L,
                    calcSummary = "⚠ 目標 F$tgt は現在地 F$cur より前です（残り $rem F）。\n再起動するか別候補を選んでください",
                    appliedModeLabel = "エラー"
                )
            }
            return
        }
        applyRemaining(rem, "現在地 F$cur → 目標 F$tgt")
    }

    private fun applyRemaining(remaining: Long, note: String) {
        val rem = remaining.coerceAtLeast(0L)
        val mash = rem / SKIP_SIZE
        val rest = rem % SKIP_SIZE
        val modePref = _state.value.snipeMode

        val effective = when (modePref) {
            SnipeMode.AUTO -> if (mash >= 1L) SnipeMode.CONTINUE else SnipeMode.TITLE
            SnipeMode.TITLE -> SnipeMode.TITLE
            SnipeMode.CONTINUE -> SnipeMode.CONTINUE
        }

        val waitSec: Double
        val phase: String
        val metTarget: String
        val summary: String
        val modeLabel: String

        if (effective == SnipeMode.CONTINUE) {
            waitSec = rest / FPS
            metTarget = mash.coerceAtLeast(0).toString()
            modeLabel = "コンテニュー連打"
            phase = "連打 ${mash}回 + 余り ${rest}F 待機"
            summary = "$note\n残り ${rem}F\n→ 連打 ${mash}回（1回=${SKIP_SIZE}F）\n→ 余り ${rest}F（約 ${fmt1(waitSec)} 秒）をタイマーへ"
        } else {
            waitSec = rem / FPS
            metTarget = mash.coerceAtLeast(0).toString()
            modeLabel = "通常スナイプ"
            phase = "通常待機 残り ${rem}F"
            val wMin = (waitSec / 60).toInt()
            val wSec = waitSec % 60
            summary = "$note\n残り ${rem}F（約 ${wMin}分 ${fmt1(wSec)}秒）\n→ 通常待機（フレーム ÷ ${FPS.toInt()}fps）"
        }

        val wMin = (waitSec / 60).toInt()
        val wSec = waitSec % 60
        val wSecStr = String.format(java.util.Locale.US, "%.3f", wSec)

        _state.update {
            it.copy(
                remainingFrames = rem,
                mashCount = mash,
                remainderFrames = rest,
                countdownMin = wMin.toString(),
                countdownSec = wSecStr,
                targetCount = metTarget,
                phaseLabel = phase,
                calcSummary = summary,
                appliedModeLabel = modeLabel,
                display = formatMs(((wMin * 60 + wSec) * 1000).toLong())
            )
        }
    }

    private fun fmt1(v: Double): String =
        String.format(java.util.Locale.US, "%.1f", v)

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

    override fun onCleared() {
        countdownJob?.cancel()
        metJob?.cancel()
        tone?.release()
        tone = null
        super.onCleared()
    }
}
