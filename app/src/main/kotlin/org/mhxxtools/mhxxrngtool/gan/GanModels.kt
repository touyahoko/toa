package org.mhxxtools.mhxxrngtool.gan

/** 発動スキル定義（例: 攻撃力UP【大】 = 攻撃 20pt） */
data class SkillDef(
    val name: String,
    val tree: String,
    val points: Int,
    val type: Int // 0=両方 1=剣士 2=ガンナー
)

/** 防具1部位 */
data class EquipPiece(
    val name: String,
    val part: Part,
    val gender: Int,
    val type: Int,
    val rare: Int,
    val slots: Int,
    val skills: Map<String, Int> // 系統 -> ポイント
)

enum class Part { HEAD, BODY, ARM, WST, LEG }

/** 装飾品 */
data class Decoration(
    val name: String,
    val rare: Int,
    val slotSize: Int,
    val skills: Map<String, Int>
)

/** 検索結果の1セット */
data class ArmorSet(
    val head: EquipPiece?,
    val body: EquipPiece?,
    val arm: EquipPiece?,
    val wst: EquipPiece?,
    val leg: EquipPiece?,
    val decos: List<String>,
    val skillTotals: Map<String, Int>,
    val activated: List<String>
)

data class SearchRequest(
    /** 発動したいスキル名（攻撃力UP【大】など） */
    val targetSkills: List<String>,
    val typeFilter: Int = 0, // 0=両方可 1=剣士 2=ガンナー
    val maxResults: Int = 40
)
