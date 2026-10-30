package org.mhxxtools.mhxxrngtool.ui.psim

data class ArmorSeries(
    val series: String,
    val sex: String,
    val role: String,
    val pieces: List<ArmorPiece>
)

data class ArmorPiece(
    val part: Int,
    val name: String,
    val slots: Int,
    val skills: Map<String, Int>
)

data class Decoration(
    val name: String,
    val size: Int,
    val skills: Map<String, Int>
)

data class SkillTier(val points: Int, val name: String)

data class SkillOption(
    val system: String,
    val points: Int,
    val displayName: String
)

data class EquipMeta(
    val def: Int,
    val slots: Int,
    val skills: Map<String, Int>,
    val part: Int
)

data class CharmTypeInfo(
    val types: List<String>,
    val slots: List<Int>,
    val max: Map<String, List<List<Int?>?>>
)

data class PsimTarget(
    val system: String,
    val points: Int,
    val displayName: String
)

data class PsimResult(
    val build: List<String?>,
    val needs: Map<String, Int>,
    val slots: Int,
    val charmLabel: String,
    val charmOk: List<Boolean>,
    val neset: Boolean = false
)

data class PartOutcome(
    val points: IntArray,
    val label: String,
    val baseName: String,
    val slots: Int
)
