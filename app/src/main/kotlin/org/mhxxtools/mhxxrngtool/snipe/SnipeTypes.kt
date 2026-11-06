package org.mhxxtools.mhxxrngtool.snipe

/**
 * mhxx-snipe-navigator の型・既定値を Kotlin に移植。
 * 元: src/lib/snipe.ts / timing.ts / types.ts
 */

const val FPS = 30
const val FRAME_MS = 1000.0 / FPS

enum class Pattern { DOT, BAR, NUMBER }

enum class Melding { JUJU, HALCYON }

data class Aim(val frame: Int, val index: Int)

data class SnipeCharm(
    val skill1: Int,
    val skill1Pts: Int,
    val skill2: Int = -1,
    val skill2Pts: Int = 0,
    val slots: Int = 0
)

data class Goseki(
    val melding: Melding,
    val charm: SnipeCharm,
    val kind: Int, // 0..3
    val position: Int,
    val aims: List<Aim>
)

/** 狙い = 護石 + 錬金タイミング */
data class Target(
    val melding: Melding,
    val charm: SnipeCharm,
    val kind: Int,
    val position: Int,
    val aims: List<Aim>,
    val frame: Int,
    val index: Int
)

data class MashSettings(
    val bpm: Int = 60,
    val advance: Int = 702,
    val offset: Int = 0
)

data class TimingSettings(
    val pattern: Pattern = Pattern.DOT,
    val waitCorrection: Int = 0,
    val soundCorrection: Int = 0,
    val beepFrom: Int = 5
)

data class SnipeSettings(
    val timing: TimingSettings = TimingSettings(),
    val mash: MashSettings = MashSettings()
)

data class MashRecord(
    val bpm: Int,
    val advance: Int,
    val offset: Int,
    val count: Int,
    val landing: Int
)

data class Trial(
    val at: Long,
    val target: Target,
    val waitCorrection: Int,
    val soundCorrection: Int,
    val actual: Int? = null,
    val craftStart: Int? = null,
    val reached: Int? = null,
    val wait: Int? = null,
    val mash: MashRecord? = null
)

object SnipeLabels {
    fun melding(m: Melding) = when (m) {
        Melding.JUJU -> "マカ"
        Melding.HALCYON -> "天運"
    }
}
