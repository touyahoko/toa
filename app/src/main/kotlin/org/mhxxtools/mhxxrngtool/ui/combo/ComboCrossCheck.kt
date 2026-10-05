package org.mhxxtools.mhxxrngtool.ui.combo

/**
 * mhxx-combo-scan combo-core/src/cross.rs の完全移植。
 *
 * 素材の減り = 調合回数、完成品の増え = 生産数の合計。
 * 素材表示が変わってから次に変わるまでの完成品増加を、その間の回数で割り振る。
 */
object ComboCrossCheck {

    private const val YIELD_MIN = 2
    private const val YIELD_MAX = 4
    /** 本家 MAX_CANDIDATES = 256 */
    private const val MAX_CANDIDATES = 256
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

    /**
     * 素材欄2つから所持数を1つの値に決める (本家 Materials)。
     * 差は変わらないので、片方の誤読を補える。値は素材1側に揃える。
     */
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
                        // 差が合わない = どちらかの読み違い。前の値と辻褄が合う方
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

    /** 合計 total を k 回の調合 (各 2〜4) に割り振る全パターン */
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

    private fun craftCumulative(c: Craft): List<Int?> {
        val yields: List<Int>? = when (val r = c.resolution) {
            is Resolution.Exact -> r.yields
            is Resolution.Inferred -> r.yields
            is Resolution.Capped -> r.yields
            is Resolution.Ambiguous, Resolution.Failed -> null
        }
        return if (yields != null) {
            var v = c.before
            yields.map { n ->
                v = (v + n).coerceAtMost(255)
                v
            }
        } else {
            List(c.count - 1) { null } + listOf(c.after)
        }
    }

    /**
     * 本家 segments() 完全準拠。
     * - 両欄が揃って増えたら別調合として数え直し
     * - 片方だけの増加は読み間違いとして無視
     * - 新区間開始時は直前の完成品を引き継ぐ (表示遅れ対策)
     */
    private fun segments(rows: List<ComboFrameReader.FrameReading>): List<Seg> {
        val segs = mutableListOf<Seg>()
        var prevM: Int? = null
        var prev1: Int? = null
        var prev2: Int? = null
        val materials = Materials()

        for (r in rows.filter { it.crafting }) {
            // 両欄が揃って増えている → 別の調合が始まった
            val a1 = r.material1
            val a2 = r.material2
            if (a1 != null && a2 != null &&
                prev1 != null && a1 > prev1 &&
                prev2 != null && a2 > prev2
            ) {
                segs.clear()
                materials.restart()
                prevM = null
            }

            val m = materials.value(r.material1, r.material2, prevM) ?: continue

            // 素材が増えることは1回の調合ではあり得ない (片方誤読)
            if (prevM != null && m > prevM) {
                // prev1/prev2 は更新して次の判定に使う
                prev1 = r.material1 ?: prev1
                prev2 = r.material2 ?: prev2
                continue
            }

            if (segs.isEmpty() || segs.last().material != m) {
                // 新区間: 直前の完成品を引き継ぐ (本家 carry)
                val carry = segs.lastOrNull()?.lastProduct
                segs.add(Seg(m, r.t, carry))
            }
            if (r.product != null) {
                val last = segs.last()
                segs[segs.lastIndex] = last.copy(lastProduct = r.product)
            }
            prevM = m
            prev1 = r.material1 ?: prev1
            prev2 = r.material2 ?: prev2
        }
        return segs
    }

    /**
     * フレーム読み取り列から解析結果を作る (本家 cross_check)。
     */
    fun analyze(rows: List<ComboFrameReader.FrameReading>): Analysis {
        val segs = segments(rows)
        if (segs.size < 2) {
            return Analysis(
                emptyList(), emptyList(),
                segs.firstOrNull()?.material, segs.lastOrNull()?.material,
                listOf(if (segs.isEmpty()) "調合画面が見つかりません" else "調合区間が足りません")
            )
        }

        val crafts = mutableListOf<Craft>()
        val issues = mutableListOf<String>()
        var ai = 0
        for (bi in 1 until segs.size) {
            val a = segs[ai]
            val b = segs[bi]
            val before = a.lastProduct
            if (before == null) {
                ai = bi
                continue
            }
            val after = b.lastProduct ?: continue

            val count = a.material - b.material
            if (count <= 0) {
                ai = bi
                continue
            }
            val gain = after - before
            // 素材は減ったのに完成品が変わっていない = 表示遅れ。次とまとめる
            if (gain == 0) continue

            val resolution = if (after == CAP) {
                Resolution.Capped(if (count == 1) listOf(gain) else null)
            } else {
                val sp = splits(gain, count)
                when {
                    sp.isEmpty() -> {
                        issues.add("説明できない増分 t=${"%.2f".format(b.t)} count=$count gain=$gain")
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
        else listOf(crafts.first().before) + crafts.flatMap { craftCumulative(it) }

        return Analysis(
            crafts = crafts,
            cumulative = cumulative,
            materialFrom = segs.first().material,
            materialTo = segs.last().material,
            issues = issues
        )
    }

    /**
     * 累計列から検索用の確定列を作る。
     * 本家どおり、不明値 (null) が1つでもあれば null を返す (無理につなげない)。
     */
    fun toSearchSequence(cumulative: List<Int?>): List<Int>? {
        if (cumulative.isEmpty() || cumulative.any { it == null }) return null
        return cumulative.map { it!! }
    }
}
