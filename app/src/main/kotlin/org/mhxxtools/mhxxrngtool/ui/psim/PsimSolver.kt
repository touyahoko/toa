package org.mhxxtools.mhxxrngtool.ui.psim

/**
 * Pシミュ相当の装備探索（Kotlin移植）。
 * 目標スキルに対し防具+装飾品+護石で届く構成を列挙する。
 */
object PsimSolver {

    private const val MAX_OUTS_PER_PART = 80
    private const val MAX_STATES = 4000
    private const val MAX_RESULTS = 120
    private const val MAX_BUILDS_PER_NEED = 20

    private class St(val rem: IntArray, val build: Array<String?>)

    fun solve(
        targets: Map<String, Int>,
        sex: String,
        job: String,
        weaponSlots: Int
    ): List<PsimResult> {
        if (targets.isEmpty()) return emptyList()
        val systems = targets.keys.toList()
        val dim = systems.size
        val need0 = IntArray(dim) { targets[systems[it]] ?: 0 }

        val partOuts = Array(5) { part -> buildPartOutcomes(part, systems, sex, job) }
        val weaponOuts = buildSlotGains(weaponSlots, systems)

        data class Stage(val tag: String?, val part: Int?, val outs: List<PartOutcome>)
        val order = (0..4).sortedBy { partOuts[it].size }
        val stages = order.map { Stage(null, it, partOuts[it]) } +
            listOf(Stage("武器", null, weaponOuts))

        var states = listOf(St(need0.copyOf(), arrayOfNulls(6)))
        for (stage in stages) {
            val seen = LinkedHashMap<String, St>()
            for (st in states) {
                for (o in stage.outs) {
                    val rem = IntArray(dim) { i -> st.rem[i] - o.points[i] }
                    val key = rem.joinToString(",")
                    if (seen.containsKey(key)) continue
                    if (seen.size >= MAX_STATES) break
                    val nb = st.build.copyOf()
                    val idx = if (stage.tag != null) 5 else stage.part!!
                    nb[idx] = o.label
                    seen[key] = St(rem, nb)
                }
                if (seen.size >= MAX_STATES) break
            }
            states = seen.values.sortedBy { it.rem.sum() }.take(MAX_STATES)
        }

        val results = ArrayList<PsimResult>()
        val perNeed = HashMap<String, Int>()
        val seenBuild = HashSet<String>()

        for (charmSlots in 0..3) {
            val charmGains = buildSlotGains(charmSlots, systems)
            for (st in states) {
                for (cg in charmGains) {
                    val needs = mutableMapOf<String, Int>()
                    var nPos = 0
                    for (i in 0 until dim) {
                        val r = st.rem[i] - cg.points[i]
                        if (r > 0) {
                            needs[systems[i]] = r
                            nPos++
                        }
                    }
                    if (nPos > 2) continue
                    if (!charmFeasible(needs, charmSlots)) continue

                    val needKey = "$charmSlots|" + systems.joinToString(",") { "${needs[it] ?: 0}" }
                    val cnt = perNeed[needKey] ?: 0
                    if (cnt >= MAX_BUILDS_PER_NEED) continue

                    val build = st.build.toList()
                    val bKey = build.joinToString("|") + "#" + needKey
                    if (!seenBuild.add(bKey)) continue

                    val label = if (needs.isEmpty()) "護石なし"
                    else needs.entries.joinToString(" ") { "${it.key}${it.value}" } + " スロ$charmSlots"

                    results += PsimResult(
                        build = build,
                        needs = needs,
                        slots = charmSlots,
                        charmLabel = label,
                        charmOk = charmTypesOk(needs, charmSlots),
                        neset = false
                    )
                    perNeed[needKey] = cnt + 1
                    if (results.size >= MAX_RESULTS) {
                        return results.sortedBy { it.slots * 100 + it.needs.values.sum() }
                    }
                }
            }
        }
        return results.sortedBy { it.slots * 100 + it.needs.values.sum() }
    }

    private fun buildPartOutcomes(
        part: Int,
        systems: List<String>,
        sex: String,
        job: String
    ): List<PartOutcome> {
        val dim = systems.size
        val outs = ArrayList<PartOutcome>()
        val seen = HashSet<String>()

        for (set in PsimData.armor) {
            if (set.sex != "b" && set.sex != sex) continue
            for (piece in set.pieces) {
                if (piece.part != part) continue
                if (part != 0 && set.role != "b" && set.role != job) continue
                if (piece.skills.containsKey("胴系統倍加")) continue

                val base = IntArray(dim) { systems[it].let { s -> piece.skills[s] ?: 0 } }
                for (g in slotGainVectors(piece.slots, systems)) {
                    val pts = IntArray(dim) { base[it] + g.first[it] }
                    val key = pts.joinToString(",") + "/" + piece.slots
                    if (!seen.add(key) && outs.size > 12) continue
                    val decoLabel = if (g.second.isEmpty()) piece.name
                    else piece.name + "［" + g.second.joinToString("・") + "］"
                    outs += PartOutcome(pts, decoLabel, piece.name, piece.slots)
                    if (outs.size >= MAX_OUTS_PER_PART) {
                        return outs.sortedByDescending { it.points.sum() }
                    }
                }
            }
        }
        return outs.sortedByDescending { it.points.sum() }.take(MAX_OUTS_PER_PART)
    }

    private fun buildSlotGains(slots: Int, systems: List<String>): List<PartOutcome> {
        return slotGainVectors(slots, systems).map { g ->
            val label = if (g.second.isEmpty()) ""
            else "［" + g.second.joinToString("・") + "］"
            PartOutcome(g.first, label, "", slots)
        }
    }

    private fun slotGainVectors(
        capacity: Int,
        systems: List<String>
    ): List<Pair<IntArray, List<String>>> {
        val dim = systems.size
        if (capacity <= 0) return listOf(IntArray(dim) to emptyList())
        val useful = PsimData.decorations.filter { d ->
            d.size <= capacity && systems.any { (d.skills[it] ?: 0) > 0 }
        }
        val found = ArrayList<Pair<IntArray, List<String>>>()
        found += IntArray(dim) to emptyList()

        fun rec(start: Int, remain: Int, vec: IntArray, names: List<String>) {
            if (found.size >= 60) return
            for (i in start until useful.size) {
                val d = useful[i]
                if (d.size > remain) continue
                val nv = IntArray(dim) { j -> vec[j] + (d.skills[systems[j]] ?: 0) }
                val nn = names + d.name
                found += nv to nn
                rec(i, remain - d.size, nv, nn)
            }
        }
        rec(0, capacity, IntArray(dim), emptyList())

        val kept = ArrayList<Pair<IntArray, List<String>>>()
        for (c in found.sortedByDescending { it.first.sum() }) {
            val dom = kept.any { k -> k.first.indices.all { i -> k.first[i] >= c.first[i] } }
            if (!dom) kept += c
            if (kept.size >= 40) break
        }
        return kept
    }

    private fun charmFeasible(needs: Map<String, Int>, slots: Int): Boolean {
        if (needs.isEmpty()) return true
        return charmTypesOk(needs, slots).any { it }
    }

    private fun charmTypesOk(needs: Map<String, Int>, slots: Int): List<Boolean> {
        val charms = PsimData.charms ?: return listOf(false, false, false)
        val entries = needs.entries.toList()
        return List(3) { t ->
            if (slots > charms.slots.getOrElse(t) { 0 }) return@List false
            if (entries.isEmpty()) return@List true
            fun fits(sys: String, v: Int, pos: Int): Boolean {
                val row = charms.max[sys] ?: return false
                if (t >= row.size) return false
                val pair = row[t] ?: return false
                val max = pair.getOrNull(pos) ?: return false
                return v <= max
            }
            when (entries.size) {
                1 -> {
                    val (k, v) = entries[0]
                    fits(k, v, 0) || fits(k, v, 1)
                }
                2 -> {
                    val (a, av) = entries[0]
                    val (b, bv) = entries[1]
                    (fits(a, av, 0) && fits(b, bv, 1)) || (fits(b, bv, 0) && fits(a, av, 1))
                }
                else -> false
            }
        }
    }
}
