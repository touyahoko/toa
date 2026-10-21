package org.mhxxtools.mhxxrngtool.gan

data class SkillDef(
    val name: String,
    val tree: String,
    val points: Int,
    val type: Int
)

data class EquipPiece(
    val name: String,
    val part: Part,
    val gender: Int,
    val type: Int,
    val rare: Int,
    val slots: Int,
    val skills: Map<String, Int>
)

enum class Part { HEAD, BODY, ARM, WST, LEG }

data class Decoration(
    val name: String,
    val rare: Int,
    val slotSize: Int,
    val skills: Map<String, Int>
)

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
    val targetSkills: List<String>,
    val typeFilter: Int = 0,
    val maxResults: Int = 40
)
