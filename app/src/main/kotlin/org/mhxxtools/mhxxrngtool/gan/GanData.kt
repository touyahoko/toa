package org.mhxxtools.mhxxrngtool.gan

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object GanData {

    @Volatile private var loaded = false
    var skills: List<SkillDef> = emptyList()
        private set
    var skillByName: Map<String, SkillDef> = emptyMap()
        private set
    var skillsByTree: Map<String, List<SkillDef>> = emptyMap()
        private set
    var equip: Map<Part, List<EquipPiece>> = emptyMap()
        private set
    var decos: List<Decoration> = emptyList()
        private set
    var trees: List<String> = emptyList()
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val am = context.assets
            skills = parseSkills(readCsv(am, "gansimu/MHXX_SKILL.csv"))
            skillByName = skills.associateBy { it.name }
            skillsByTree = skills.groupBy { it.tree }.mapValues { (_, v) ->
                v.sortedByDescending { it.points }
            }
            trees = skills.map { it.tree }.distinct().sorted()
            equip = mapOf(
                Part.HEAD to parseEquip(readCsv(am, "gansimu/MHXX_EQUIP_HEAD.csv"), Part.HEAD),
                Part.BODY to parseEquip(readCsv(am, "gansimu/MHXX_EQUIP_BODY.csv"), Part.BODY),
                Part.ARM to parseEquip(readCsv(am, "gansimu/MHXX_EQUIP_ARM.csv"), Part.ARM),
                Part.WST to parseEquip(readCsv(am, "gansimu/MHXX_EQUIP_WST.csv"), Part.WST),
                Part.LEG to parseEquip(readCsv(am, "gansimu/MHXX_EQUIP_LEG.csv"), Part.LEG)
            )
            decos = parseDeco(readCsv(am, "gansimu/MHXX_DECO.csv"))
            loaded = true
        }
    }

    private fun readCsv(am: android.content.res.AssetManager, path: String): List<List<String>> {
        val lines = BufferedReader(InputStreamReader(am.open(path), Charsets.UTF_8)).use { it.readLines() }
        return lines.filter { it.isNotBlank() && !it.startsWith("#") }.map { parseLine(it) }
    }

    private fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> inQ = !inQ
                c == ',' && !inQ -> {
                    out += sb.toString().trim()
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    private fun parseSkills(rows: List<List<String>>): List<SkillDef> =
        rows.mapNotNull { r ->
            if (r.size < 3) return@mapNotNull null
            val name = r[0]
            val tree = r[1]
            val pts = r[2].toIntOrNull() ?: return@mapNotNull null
            val type = r.getOrNull(3)?.toIntOrNull() ?: 0
            if (name.isBlank() || tree.isBlank()) null
            else SkillDef(name, tree, pts, type)
        }

    private fun parseEquip(rows: List<List<String>>, part: Part): List<EquipPiece> =
        rows.mapNotNull { r ->
            if (r.size < 16) return@mapNotNull null
            val name = r[0]
            if (name.isBlank()) return@mapNotNull null
            val gender = r.getOrNull(1)?.toIntOrNull() ?: 0
            val type = r.getOrNull(2)?.toIntOrNull() ?: 0
            val rare = r.getOrNull(3)?.toIntOrNull() ?: 1
            val slots = r.getOrNull(4)?.toIntOrNull() ?: 0
            val skills = linkedMapOf<String, Int>()
            var i = 15
            while (i + 1 < r.size && i <= 23) {
                val tree = r[i]
                val pts = r[i + 1].toIntOrNull()
                if (tree.isNotBlank() && pts != null && pts != 0) {
                    skills[tree] = (skills[tree] ?: 0) + pts
                }
                i += 2
            }
            EquipPiece(name, part, gender, type, rare, slots.coerceIn(0, 3), skills)
        }

    private fun parseDeco(rows: List<List<String>>): List<Decoration> =
        rows.mapNotNull { r ->
            if (r.size < 8) return@mapNotNull null
            val name = r[0]
            if (name.isBlank()) return@mapNotNull null
            val rare = r.getOrNull(1)?.toIntOrNull() ?: 1
            val slot = r.getOrNull(2)?.toIntOrNull() ?: 1
            val skills = linkedMapOf<String, Int>()
            val t1 = r.getOrNull(6).orEmpty()
            val p1 = r.getOrNull(7)?.toIntOrNull()
            if (t1.isNotBlank() && p1 != null && p1 != 0) skills[t1] = p1
            val t2 = r.getOrNull(8).orEmpty()
            val p2 = r.getOrNull(9)?.toIntOrNull()
            if (t2.isNotBlank() && p2 != null && p2 != 0) skills[t2] = (skills[t2] ?: 0) + p2
            Decoration(name, rare, slot.coerceIn(1, 3), skills)
        }
}
