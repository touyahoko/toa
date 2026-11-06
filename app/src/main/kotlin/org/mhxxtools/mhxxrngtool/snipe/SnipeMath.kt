package org.mhxxtools.mhxxrngtool.snipe

import kotlin.math.roundToInt

/** mhxx-snipe-navigator src/lib/snipe.ts / frame.ts の計算 */

object SnipeMath {

    /** 連打終了時の推定着地フレーム */
    fun mashLanding(s: MashSettings, count: Int): Int {
        val per = s.advance + (FPS * 60.0) / s.bpm
        return s.offset + (count * per).roundToInt()
    }

    fun drift(actual: Int, targetFrame: Int): Int = actual - targetFrame

    fun hit(goseki: Goseki, actual: Int): Boolean =
        goseki.aims.any { it.frame == actual }

    fun targetKey(frame: Int, index: Int) = "$frame-$index"

    fun gosekiKey(g: Goseki) = "${g.melding}-${g.position}-${g.kind}"

    fun gosekiOf(t: Target) = Goseki(t.melding, t.charm, t.kind, t.position, t.aims)

    fun targetsOf(g: Goseki): List<Target> =
        g.aims.map { a ->
            Target(g.melding, g.charm, g.kind, g.position, g.aims, a.frame, a.index)
        }

    /**
     * 現在地 current から目標 targetFrame までの待機。
     * 補正 waitCorrection を加算（+で遅く＝待機を増やす）。
     */
    fun waitFrames(current: Int, targetFrame: Int, waitCorrection: Int = 0): Int =
        (targetFrame - current + waitCorrection).coerceAtLeast(0)

    /** 通常スナイプ: 待機秒 = wait / 30 */
    fun waitSeconds(waitF: Int): Double = waitF.toDouble() / FPS

    /**
     * 連打法: 長い待ちを BPM 連打に分解。
     * remaining = target - current
     * 1回あたり advance + (30*60/bpm) F
     */
    fun mashPlan(
        current: Int,
        targetFrame: Int,
        mash: MashSettings,
        waitCorrection: Int = 0
    ): MashPlan {
        val need = waitFrames(current, targetFrame, waitCorrection)
        val per = mash.advance + (FPS * 60.0) / mash.bpm
        if (per <= 0) return MashPlan(0, need, need)
        val count = (need / per).toInt().coerceAtLeast(0)
        val landing = mashLanding(mash, count)
        val remainAfter = (targetFrame - (current + (landing - mash.offset))).coerceAtLeast(0)
        // より単純: count 回後の余り待機
        val advanced = (count * per).roundToInt()
        val rest = (need - advanced).coerceAtLeast(0)
        return MashPlan(count = count, restWaitFrames = rest, totalWaitFrames = need)
    }

    data class MashPlan(
        val count: Int,
        val restWaitFrames: Int,
        val totalWaitFrames: Int
    )

    /** カウントダウン表示用: 残りフレーム → 秒 + 端数F */
    fun splitFrames(frames: Int): Pair<Int, Int> {
        val f = frames.coerceAtLeast(0)
        return (f / FPS) to (f % FPS)
    }
}
