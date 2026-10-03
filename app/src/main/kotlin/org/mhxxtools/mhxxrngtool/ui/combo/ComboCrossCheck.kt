package org.mhxxtools.mhxxrngtool.ui.combo

/**
 * mhxx-combo-scan の cross.rs を Kotlin 移植。
 * フレーム読み取り列 → 調合区間 → 累計個数列（検索用）
 */
object ComboCrossCheck {

    private const val YIELD_MIN = 2
    private const val YIELD_MAX = 4
    private const val MAX_CANDIDATES = 64
    private const val CAP = 99

    data class Craft(
        val t: Double,
        val count: Int,
        val gain: Int,
        val before: Int,
        val after: Int,
        val resolution: Resolution
    )

    sealed class Resolution {
        data class Exact(val yields: List<Int>) : Resolution()
        data class Inferred(val yields: List<Int>) : Resolution()
        data class Ambiguous(val candidates: List<List<Int>>) : Resolution()
        data class Capped(val yields: List<Int>?) : Resolution()
        object Failed : Resolution()
    }

    data class Analysis(
        val crafts: List<Craft>,
        /** 検索に渡す累計個数。先頭は最初の before。途中不明は null */
        val cumulative: List<Int?>,
        val materialFrom: Int?,
        val materialTo: Int?,
        val issues: List<String>
    )

    private data class Seg(
        val material: Int,
        val t: Double,
        val lastProduct: Int?
    )

    private class Materials {
        var offset: Int? = null
        fun restart() { offset = null }
        fun value(m1: Int?, m2: Int?, prev: Int?): Int? {
            fun shifted(b: Int, d: Int): Int? {
                val v = b + d
                return if (v in 0..99) v else null
            }
            return when {
                m1 != null && m2 != null -> {
                    val d = offset
                    if (d == null) {
                        offset = m1 - m2
                        m1
                    } else if (m1 - m2 == d) {
                        m1
                    } else {
                        val candidates = listOfNotNull(
                            m1.takeIf { prev == null || it <= prev },
                            shifted(m2, d)?.takeIf { prev == null || it <= prev }
                        ).distinct()
                        if (candidates.size == 1) candidates[0] else null
                    }
                }
                m1 != null -> m1
                m2 != null -> offset?.let { shifted(m2, it) }
                else -> null
            }
        }
    }

    private fun splits(total: Int, k: Int): List<List<Int>> {
        if (k <= 0) return emptyList()
        val out = mutableListOf<List<Int>>()
        fun rec(rem: Int, left: Int, cur: MutableList<Int>) {
            if (out.size >= MAX_CANDIDATES) return
            if (left == 0) {
                if (rem == 0) out.add(cur.toList())
                return
            }
            for (v in YIELD_MIN..YIELD_MAX) {
                val nRem = rem - v
                val nLeft = left - 1
                if (nRem < YIELD_MIN * nLeft || nRem > YIELD_MAX * nLeft) continue
                cur.add(v)
                rec(nRem, nLeft, cur)
                cur.removeAt(cur.lastIndex)
            }
        }
        rec(total, k, mutableListOf())
        return out
    }

    private fun craftCumulative(c: Craft): Sequence<Int?> = sequence {
        when (val r = c.resolution) {
            is Resolution.Exact -> {
                var acc = c.before
                for (y in r.yields) {
                    acc += y
                    yield(acc)
                }
            }
            is Resolution.Inferred -> {
                var acc = c.before
                for (y in r.yields) {
                    acc += y
                    yield(acc)
                }
            }
            is Resolution.Ambiguous -> {
                // unknown intermediate; only final after known
                repeat(c.count - 1) { yield(null) }
                yield(c.after)
            }
            is Resolution.Capped -> {
                if (r.yields != null) {
                    var acc = c.before
                    for (y in r.yields) {
                        acc += y
                        yield(minOf(acc, CAP))
                    }
                } else {
                    repeat(c.count - 1) { yield(null) }
                    yield(CAP)
                }
            }
            Resolution.Failed -> {
                repeat(c.count) { yield(null) }
            }
        }
    }

    /**
     * フレーム読み取り列から解析結果を作る。
     * crafting=false の行は無視する。
     */
    fun analyze(rows: List<ComboFrameReader.FrameReading>): Analysis {
        val crafting = rows.filter { it.crafting }
        if (crafting.isEmpty()) {
            return Analysis(emptyList(), emptyList(), null, null, listOf("調合画面が見つかりません"))
        }

        val mats = Materials()
        val segs = mutableListOf<Seg>()
        var prevMat: Int? = null

        for (row in crafting) {
            val m = mats.value(row.material1, row.material2, prevMat)
            if (m == null) continue

            // 素材が増えた → 別の調合が始まった
            if (prevMat != null && m > prevMat) {
                mats.restart()
                val m2 = mats.value(row.material1, row.material2, null) ?: continue
                segs.clear()
                segs.add(Seg(m2, row.t, row.product))
                prevMat = m2
                continue
            }

            if (segs.isEmpty() || segs.last().material != m) {
                segs.add(Seg(m, row.t, row.product))
            } else {
                // same material segment: keep last product
                val last = segs.last()
                segs[segs.lastIndex] = last.copy(lastProduct = row.product ?: last.lastProduct)
            }
            prevMat = m
        }

        if (segs.size < 2) {
            return Analysis(emptyList(), emptyList(), segs.firstOrNull()?.material, segs.lastOrNull()?.material,
                listOf("調合区間が足りません"))
        }

        val crafts = mutableListOf<Craft>()
        val issues = mutableListOf<String>()
        var ai = 0
        for (bi in 1 until segs.size) {
            val a = segs[ai]
            val b = segs[bi]
            val before = a.lastProduct
            val after = b.lastProduct
            if (before == null) {
                ai = bi
                continue
            }
            if (after == null) continue

            val count = a.material - b.material
            if (count <= 0) {
                ai = bi
                continue
            }
            val gain = after - before
            if (gain == 0) continue // product lag; merge later

            val resolution = if (after == CAP) {
                Resolution.Capped(if (count == 1) listOf(gain) else null)
            } else {
                val sp = splits(gain, count)
                when {
                    sp.isEmpty() -> {
                        issues.add("説明できない増分 t=${b.t} count=$count gain=$gain")
                        Resolution.Failed
                    }
                    sp.size == 1 && count == 1 -> Resolution.Exact(sp[0])
                    sp.size == 1 -> Resolution.Inferred(sp[0])
                    else -> Resolution.Ambiguous(sp)
                }
            }
            crafts.add(Craft(b.t, count, gain, before, after, resolution))
            ai = bi
        }
        if (ai != segs.lastIndex) {
            issues.add("最後の完成品増加が読めていません")
        }

        val cumulative: List<Int?> = if (crafts.isEmpty()) emptyList()
        else {
            val first = listOf<Int?>(crafts.first().before)
            first + crafts.flatMap { craftCumulative(it).toList() }
        }

        return Analysis(
            crafts = crafts,
            cumulative = cumulative,
            materialFrom = segs.first().material,
            materialTo = segs.last().material,
            issues = issues
        )
    }

    /** 累計列から検索用の確定差分列を作る（不明値があると null） */
    fun toSearchSequence(cumulative: List<Int?>): List<Int>? {
        if (cumulative.any { it == null }) return null
        return cumulative.map { it!! }
    }
}
