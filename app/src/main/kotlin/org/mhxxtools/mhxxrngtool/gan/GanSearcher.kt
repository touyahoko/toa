package org.mhxxtools.mhxxrngtool.gan

object GanSearcher {

    data class Target(val skillName: String, val tree: String, val need: Int)

    fun search(
        req: SearchRequest,
        onProgress: ((Int, String) -> Unit)? = null
    ): List<ArmorSet> {
        val targets = req.targetSkills.mapNotNull { name ->
            val def = GanData.skillByName[name] ?: return@mapNotNull null
            Target(name, def.tree, def.points)
        }
        if (targets.isEmpty()) return emptyList()

        val needByTree = mutableMapOf<String, Int>()
        for (t in targets) {
            needByTree[t.tree] = maxOf(needByTree[t.tree] ?: 0, t.need)
        }
        val trees = needByTree.keys.toList()

        fun relevant(p: EquipPiece): Boolean {
            if (req.typeFilter != 0 && p.type != 0 && p.type != req.typeFilter) return false
            return p.skills.keys.any { it in needByTree } || p.slots > 0
        }

        val heads = GanData.equip[Part.HEAD].orEmpty().filter(::relevant)
        val bodies = GanData.equip[Part.BODY].orEmpty().filter(::relevant)
        val arms = GanData.equip[Part.ARM].orEmpty().filter(::relevant)
        val wsts = GanData.equip[Part.WST].orEmpty().filter(::relevant)
        val legs = GanData.equip[Part.LEG].orEmpty().filter(::relevant)

        val decoBySize = (1..3).associateWith { size ->
            GanData.decos.filter { d ->
                d.slotSize == size && d.skills.any { (t, v) -> t in needByTree && v > 0 }
            }.sortedByDescending { d ->
                trees.sumOf { t -> maxOf(0, d.skills[t] ?: 0) }
            }
        }

        val results = mutableListOf<ArmorSet>()
        var checked = 0
        val limit = req.maxResults

        fun rank(list: List<EquipPiece>): List<EquipPiece?> {
            val scored = list.map { p ->
                val s = trees.sumOf { t -> maxOf(0, p.skills[t] ?: 0) } * 10 + p.slots
                p to s
            }.sortedByDescending { it.second }
            val top = scored.take(80).map { it.first }
            val fillers = list.filter { it.slots >= 2 }.take(20)
            return (top + fillers).distinctBy { it.name }
        }

        val H: List<EquipPiece?> = rank(heads).ifEmpty { listOf(null) }
        val B: List<EquipPiece?> = rank(bodies).ifEmpty { listOf(null) }
        val A: List<EquipPiece?> = rank(arms).ifEmpty { listOf(null) }
        val W: List<EquipPiece?> = rank(wsts).ifEmpty { listOf(null) }
        val L: List<EquipPiece?> = rank(legs).ifEmpty { listOf(null) }

        fun addSkills(acc: MutableMap<String, Int>, piece: EquipPiece?) {
            piece?.skills?.forEach { (t, v) -> acc[t] = (acc[t] ?: 0) + v }
        }

        fun tryFillDecos(
            base: Map<String, Int>,
            holesIn: List<Int>
        ): Pair<Map<String, Int>, List<String>>? {
            val totals = base.toMutableMap()
            val used = mutableListOf<String>()
            val holes = holesIn.toMutableList()

            fun deficit(): Map<String, Int> {
                val d = mutableMapOf<String, Int>()
                for ((t, need) in needByTree) {
                    val cur = totals[t] ?: 0
                    if (cur < need) d[t] = need - cur
                }
                return d
            }

            var guard = 0
            while (holes.isNotEmpty() && guard++ < 24) {
                val def = deficit()
                if (def.isEmpty()) break
                var best: Decoration? = null
                var bestHoleIdx = -1
                var bestScore = -1
                for ((hi, cap) in holes.withIndex()) {
                    for (sz in 1..cap) {
                        for (d in decoBySize[sz].orEmpty()) {
                            val score = def.entries.sumOf { (t, need) ->
                                val add = d.skills[t] ?: 0
                                if (add > 0) minOf(add, need) * 10 else 0
                            }
                            if (score > bestScore) {
                                bestScore = score
                                best = d
                                bestHoleIdx = hi
                            }
                        }
                    }
                }
                val chosen = best
                if (chosen == null || bestScore <= 0 || bestHoleIdx < 0) break
                val cap = holes[bestHoleIdx]
                val left = cap - chosen.slotSize
                holes.removeAt(bestHoleIdx)
                if (left > 0) holes.add(left)
                holes.sortDescending()
                chosen.skills.forEach { (t, v) -> totals[t] = (totals[t] ?: 0) + v }
                used += chosen.name
            }
            return if (deficit().isEmpty()) totals to used else null
        }

        outer@ for (h in H) {
            for (b in B) {
                for (a in A) {
                    for (w in W) {
                        for (leg in L) {
                            checked++
                            if (checked % 5000 == 0) {
                                onProgress?.invoke(results.size, "search $checked")
                            }
                            val acc = mutableMapOf<String, Int>()
                            addSkills(acc, h)
                            addSkills(acc, b)
                            addSkills(acc, a)
                            addSkills(acc, w)
                            addSkills(acc, leg)

                            val holes = listOf(h, b, a, w, leg).mapNotNull { p ->
                                p?.slots?.takeIf { it > 0 }
                            }.sortedDescending()

                            val filled = tryFillDecos(acc, holes) ?: continue
                            val (totals, decoNames) = filled
                            val activated = targets.filter { t ->
                                (totals[t.tree] ?: 0) >= t.need
                            }.map { it.skillName }
                            if (activated.size == targets.size) {
                                results += ArmorSet(
                                    head = h,
                                    body = b,
                                    arm = a,
                                    wst = w,
                                    leg = leg,
                                    decos = decoNames,
                                    skillTotals = totals.filterKeys { it in needByTree },
                                    activated = activated
                                )
                                if (results.size >= limit) break@outer
                            }
                        }
                    }
                }
            }
        }
        onProgress?.invoke(results.size, "done $checked")
        return results
    }
}
