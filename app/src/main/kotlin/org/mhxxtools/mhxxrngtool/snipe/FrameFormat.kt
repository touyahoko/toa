package org.mhxxtools.mhxxrngtool.snipe

/** mhxx-snipe-navigator src/lib/frame.ts */

object FrameFormat {

    fun frameToShortTime(frame: Int): String {
        val f = ((frame % FPS) + FPS) % FPS
        val s = frame / FPS
        val parts = listOf(
            (s / 86400) to "d",
            ((s / 3600) % 24) to "h",
            ((s / 60) % 60) to "m",
            (s % 60) to "s"
        )
        val first = parts.indexOfFirst { it.first > 0 }
        val shown = buildList {
            if (first >= 0) addAll(parts.subList(first, parts.size))
            add(f to "f")
        }
        return shown.mapIndexed { i, (v, u) ->
            if (i == 0) "$v$u" else "${v.toString().padStart(2, '0')}$u"
        }.joinToString(" ")
    }

    fun approximateTime(frame: Int): String {
        if (frame <= 0) return "0秒"
        val seconds = frame.toDouble() / FPS
        return when {
            seconds >= 86400 -> "約${(seconds / 86400).toInt()}日"
            seconds >= 3600 -> "約${(seconds / 3600).toInt()}時間"
            seconds >= 60 -> "約${(seconds / 60).toInt()}分"
            else -> "約${seconds.toInt()}秒"
        }
    }

    fun showDrift(frames: Int): String =
        if (frames == 0) "±0F" else "${if (frames > 0) "+" else ""}${frames}F"

    fun formatOffset(frames: Double): String {
        val rounded = kotlin.math.round(frames * 10) / 10.0
        if (rounded == 0.0) return "±0.0F"
        return "${if (rounded > 0) "+" else ""}${"%.1f".format(rounded)}F"
    }
}
