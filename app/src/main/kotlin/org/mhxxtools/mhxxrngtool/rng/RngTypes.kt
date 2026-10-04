package org.mhxxtools.mhxxrngtool.rng

// ---------------------------------------------------------------------------
// 結果表現用データクラス
// ---------------------------------------------------------------------------

data class RawCharm(
    val skill1Idx: Int,
    val skill1Pts: Int,
    val skill2Idx: Int,   // -1 = none
    val skill2Pts: Int,
    val slot: Int,
    val fill: Int,
    val slotRoll: Int,
    val rarity: Int
)

data class Charm(
    val skill1Name: String,
    val skill1Pts: Int,
    val skill2Name: String?,
    val skill2Pts: Int,
    val slot: Int,
    val rarity: Int,
    val rarityColor: Long   // ARGB as Long (0xFFRRGGBB)
) {
    fun skillText(): String {
        val sign1 = if (skill1Pts >= 0) "+" else ""
        val s1 = "${skill1Name.replace("　", "").trim()} $sign1$skill1Pts"
        return if (skill2Name == null) s1
               else {
                   val sign2 = if (skill2Pts >= 0) "+" else ""
                   "$s1 / ${skill2Name.replace("　", "").trim()} $sign2$skill2Pts"
               }
    }

    companion object {
        fun fromRaw(raw: RawCharm): Charm {
            val isCollision = raw.skill2Idx != -1 && raw.skill2Idx == raw.skill1Idx
            val skill2Name: String?
            val skill2Pts: Int
            if (raw.skill2Idx == -1 || isCollision) {
                skill2Name = null; skill2Pts = 0
            } else {
                skill2Name = SKILL_NAMES[raw.skill2Idx]; skill2Pts = raw.skill2Pts
            }
            return Charm(
                skill1Name = SKILL_NAMES[raw.skill1Idx],
                skill1Pts = raw.skill1Pts,
                skill2Name = skill2Name,
                skill2Pts = skill2Pts,
                slot = raw.slot,
                rarity = raw.rarity,
                rarityColor = RARITY_COLORS[raw.rarity] ?: 0xFFE8E8EAL
            )
        }
    }
}

data class CharmResult(
    val frame: Long,
    val elapsed: ElapsedTime,
    val charm: Charm
)

data class FrameResult(
    val frame: Long,
    val elapsed: ElapsedTime
)

data class ElapsedTime(val days: Long, val hours: Long, val mins: Long, val secs: Long, val frames: Long) {
    fun text(): String = "${days}日 ${hours}時間 ${mins}分 ${secs}秒 ${frames}f"
}

data class SearchTarget(
    val skill1Idx: Int?,   // null = any
    val skill1Pts: Int,
    val skill2Idx: Int?,   // null = no skill2
    val skill2Pts: Int,
    val slot: Int,
    val origin: Int
)

data class AimPointRow(val label: String, val count: Int, val pattern: String)

// コンテニュー連打法
const val CONTINUE_MASH_FRAME_COST = 700L

fun continueMashInfo(frame: Long): Triple<Long, Long, Long> {
    if (frame <= 0) return Triple(0L, 0L, 0L)
    val mashes = frame / CONTINUE_MASH_FRAME_COST
    val reached = mashes * CONTINUE_MASH_FRAME_COST
    val remainder = frame - reached
    return Triple(mashes, reached, remainder)
}
