package org.mhxxtools.mhxxrngtool.ui.psim

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PsimData {
    var armor: List<ArmorSeries> = emptyList()
        private set
    var decorations: List<Decoration> = emptyList()
        private set
    var skills: Map<String, List<SkillTier>> = emptyMap()
        private set
    var equipMeta: Map<String, EquipMeta> = emptyMap()
        private set
    var charms: CharmTypeInfo? = null
        private set
    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val am = context.assets
            armor = parseArmor(JSONArray(am.open("psim/armor.json").bufferedReader().readText()))
            decorations = parseDecos(JSONArray(am.open("psim/decorations.json").bufferedReader().readText()))
            skills = parseSkills(JSONObject(am.open("psim/skills.json").bufferedReader().readText()))
            equipMeta = parseMeta(JSONObject(am.open("psim/equip_meta.json").bufferedReader().readText()))
            charms = parseCharms(JSONObject(am.open("psim/charms.json").bufferedReader().readText()))
            loaded = true
        }
    }

    fun skillOptions(): List<SkillOption> {
        val list = mutableListOf<SkillOption>()
        for ((sys, tiers) in skills) {
            for (t in tiers) list += SkillOption(sys, t.points, t.name)
        }
        return list.sortedWith(compareBy({ it.system }, { it.points }))
    }

    fun activatedSkill(system: String, points: Int): String {
        val tiers = skills[system] ?: return ""
        var best = ""
        for (t in tiers) if (t.points <= points) best = t.name
        return best
    }

    fun metaOf(name: String): EquipMeta? {
        if (name.isEmpty()) return null
        equipMeta[name]?.let { return it }
        val norm = name.map { c ->
            val o = c.code
            when {
                o == 0x3000 -> ' '
                o in 0xFF01..0xFF5E -> (o - 0xFEE0).toChar()
                else -> c
            }
        }.joinToString("")
        return equipMeta[norm]
    }

    private fun parseArmor(arr: JSONArray): List<ArmorSeries> {
        val out = ArrayList<ArmorSeries>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val piecesArr = o.getJSONArray("pieces")
            val pieces = ArrayList<ArmorPiece>(piecesArr.length())
            for (j in 0 until piecesArr.length()) {
                val p = piecesArr.getJSONArray(j)
                pieces += ArmorPiece(p.getInt(0), p.getString(1), p.getInt(2), jsonIntMap(p.getJSONObject(3)))
            }
            out += ArmorSeries(o.getString("series"), o.optString("sex", "b"), o.optString("role", "b"), pieces)
        }
        return out
    }

    private fun parseDecos(arr: JSONArray): List<Decoration> {
        val out = ArrayList<Decoration>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Decoration(o.getString("name"), o.getInt("size"), jsonIntMap(o.getJSONObject("skills")))
        }
        return out
    }

    private fun parseSkills(obj: JSONObject): Map<String, List<SkillTier>> {
        val out = mutableMapOf<String, List<SkillTier>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = obj.getJSONArray(k)
            val tiers = ArrayList<SkillTier>(arr.length())
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                tiers += SkillTier(a.getInt(0), a.getString(1))
            }
            out[k] = tiers
        }
        return out
    }

    private fun parseMeta(obj: JSONObject): Map<String, EquipMeta> {
        val out = mutableMapOf<String, EquipMeta>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val o = obj.getJSONObject(k)
            out[k] = EquipMeta(
                o.optInt("def", 0),
                o.optInt("slots", 0),
                jsonIntMap(o.optJSONObject("skills") ?: JSONObject()),
                o.optInt("part", 0)
            )
        }
        return out
    }

    private fun parseCharms(obj: JSONObject): CharmTypeInfo {
        val typesArr = obj.getJSONArray("types")
        val types = List(typesArr.length()) { typesArr.getString(it) }
        val slotsArr = obj.getJSONArray("slots")
        val slots = List(slotsArr.length()) { slotsArr.getInt(it) }
        val maxObj = obj.getJSONObject("max")
        val max = mutableMapOf<String, List<List<Int?>?>>()
        val keys = maxObj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val arr = maxObj.getJSONArray(k)
            val perType = ArrayList<List<Int?>?>(arr.length())
            for (i in 0 until arr.length()) {
                if (arr.isNull(i)) perType += null
                else {
                    val pair = arr.getJSONArray(i)
                    perType += listOf(
                        if (pair.isNull(0)) null else pair.getInt(0),
                        if (pair.isNull(1)) null else pair.getInt(1)
                    )
                }
            }
            max[k] = perType
        }
        return CharmTypeInfo(types, slots, max)
    }

    private fun jsonIntMap(obj: JSONObject): Map<String, Int> {
        val m = mutableMapOf<String, Int>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            m[k] = obj.getInt(k)
        }
        return m
    }
}
