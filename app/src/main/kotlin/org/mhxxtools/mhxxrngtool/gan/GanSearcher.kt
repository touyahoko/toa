package org.mhxxtools.mhxxrngtool.gan

/**
 * 願シミュ風スキル検索。
 * 目標発動スキル → 必要系統ポイントを満たす防具5部位 + 装飾品の組み合わせを列挙。
 */
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

        // 装飾品: 目標系統にプラスがあるもの（スロットサイズ別）
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

        // スコア上位に絞って探索量を抑える
        fun rank(list: List<EquipPiece>): List<EquipPiece> {
            val scored = list.map { p ->
                val s = trees.sumOf { t -> maxOf(0, p.skills[t] ?: 0) } * 10 + p.slots
                p to s
            }.sortedByDescending { it.second }
            val top = scored.take(80).map { it.first }
            // 空きスロット多めの「穴」候補も少し入れる
            val fillers = list.filter { it.slots >= 2 }.take(20)
            return (top + fillers).distinctBy { it.name }
        }

        val H = rank(heads).ifEmpty { listOf(null) }
        val B = rank(bodies).ifEmpty { listOf(null) }
        val A = rank(arms).ifEmpty { listOf(null) }
        val W = rank(wsts).ifEmpty { listOf(null) }
        val L = rank(legs).ifEmpty { listOf(null) }

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
                if (best == null || bestScore <= 0 || bestHoleIdx < 0) break
                val cap = holes[bestHoleIdx]
                val left = cap - best.slotSize
                holes.removeAt(bestHoleIdx)
                if (left > 0) holes.add(left)
                holes.sortDescending()
                best.skills.forEach { (t, v) -> totals[t] = (totals[t] ?: 0) + v }
                used += best.name
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
                                onProgress?.invoke(results.size, "探索中… $checked")
                            }
                            val acc = mutableMapOf<String, Int>()
                            addSkills(acc, h as EquipPiece?)
                            addSkills(acc, b as EquipPiece?)
                            addSkills(acc, a as EquipPiece?)
                            addSkills(acc, w as EquipPiece?)
                            addSkills(acc, leg as EquipPiece?)

                            // MHXX: スロット数 n = 容量 n の穴が1つ（3なら○○○）
                            val holes = listOf(h, b, a, w, leg).mapNotNull { p ->
                                val piece = p as EquipPiece?
                                piece?.slots?.takeIf { it > 0 }
                            }.sortedDescending().toMutableList()

                            val filled = tryFillDecos(acc, holes)
                            if (filled != null) {
                                val (totals, decoNames) = filled
                                val activated = targets.filter { t ->
                                    (totals[t.tree] ?: 0) >= t.need
                                }.map { it.skillName }
                                if (activated.size == targets.size) {
                                    results += ArmorSet(
                                        head = h as EquipPiece?,
                                        body = b as EquipPiece?,
                                        arm = a as EquipPiece?,
                                        wst = w as EquipPiece?,
                                        leg = leg as EquipPiece?,
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
        }
        onProgress?.invoke(results.size, "完了 $checked 通り")
        return results
    }
}
