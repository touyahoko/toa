package org.mhxxtools.mhxxrngtool.rng

import java.math.BigInteger

/**
 * MHXX XorShift128 乱数エンジン (mhxx_rng.py の完全 Kotlin 移植)
 *
 * - 32 bit 値を Long で保持 (符号なし演算のため)
 * - jump() の GF(2) 多項式演算には BigInteger を使用
 * - スレッドセーフでないため検索ごとに新インスタンスを作ること
 */
class MHXXEngine(kind: Int = 0) {

    var table: KindTable = KIND_TABLES[kind]!!
        private set

    // XorShift128 state (32-bit 値を Long に格納)
    // companion から直接アクセスするため internal
    internal var x: Long = 0L
    internal var y: Long = 0L
    internal var z: Long = 0L
    internal var w: Long = 0L
    private var t: Long = 0L
    var f: Long = 0L

    // 7 フレーム履歴リングバッファ
    internal var r0: Long = 0L
    private var r1: Long = 0L
    private var r2: Long = 0L
    private var r3: Long = 0L
    private var r4: Long = 0L
    private var r5: Long = 0L
    private var r6: Long = 0L

    val kind: Int get() = table.index
    val th: Int get() = table.th

    fun setKind(k: Int) { table = KIND_TABLES[k]!! }

    // ── RNG コア ──────────────────────────────────────────────────────────

    private fun initState() {
        x = 0x0194FD72L; y = 0x79E6C985L; z = 0x08DD9701L; w = 0x41CFCE91L
        t = 0L; f = 0L
    }

    fun ascend() {
        val tt = (x xor (x shl 15)) and MASK32
        x = y; y = z; z = w
        w = (w xor (w ushr 21) xor tt xor (tt ushr 4)) and MASK32
        t = tt; f++
    }

    fun descend() {
        var tt = (w xor z xor (z ushr 21)) and MASK32
        tt = tt xor (tt ushr 4)
        tt = tt xor (tt ushr 8)
        tt = tt xor (tt ushr 16)
        w = z; z = y; y = x
        x = (tt xor (tt shl 15) xor (tt shl 30)) and MASK32
        t = tt; f--
    }

    fun roll() {
        r0 = r1; r1 = r2; r2 = r3; r3 = r4; r4 = r5; r5 = r6; r6 = w
        ascend()
    }

    fun jump(frame: Long) {
        initState()
        val exp = BigInteger.valueOf(frame).mod(BI_2_128_MINUS_1)
        var rPoly = polyPowMod(BigInteger.TWO, exp, JUMP_MODULUS_BI)
        var sX = 0L; var sY = 0L; var sZ = 0L; var sW = 0L
        while (rPoly > BigInteger.ZERO) {
            if (rPoly.testBit(0)) {
                sX = sX xor x; sY = sY xor y; sZ = sZ xor z; sW = sW xor w
            }
            rPoly = rPoly.shiftRight(1)
            ascend()
        }
        x = sX; y = sY; z = sZ; w = sW
        f = frame
        repeat(7) { roll() }
    }

    // ── お守り生成 ─────────────────────────────────────────────────────────

    private fun slotCount(fill: Int, num1: Long): Int {
        val sv = table.slotvalue[fill - 1]
        return when {
            num1 >= sv[2] -> 3
            num1 >= sv[1] -> 2
            num1 >= sv[0] -> 1
            else -> 0
        }
    }

    private fun rare(slot: Int, fill: Int): Int {
        val n = slot * 2 + fill
        return when (table.index) {
            0 -> if (n >= 13) 10 else if (n >= 8) 9 else 8
            1 -> if (n >= 13) 7  else if (n >= 8) 6 else 5
            2 -> if (n >= 8)  4  else 3
            else -> if (n >= 8) 2 else 1
        }
    }

    /**
     * お守り1件生成 — mhxx-rng.ipynb の getcharm() と同一ロジック。
     * sp1/sp2 は [0]=下限寄り, [1]=上限寄りだが、fill 計算の s1/s2 は常に [1] を使う
     * （sp2 に (10,3) のような逆転ペアがあるため max を取ってはいけない）。
     */
    fun getcharm(origin: Int): RawCharm {
        val tbl = table
        val id1 = (r0 % tbl.skill1.size).toInt()
        val id2 = (r3 % tbl.skill2.size).toInt()

        // Python: tmp1 = r1 % (sp1[id1][1] - sp1[id1][0] + 1) + sp1[id1][0]
        val sp1a = tbl.sp1[id1][0]
        val sp1b = tbl.sp1[id1][1]
        val c0 = tbl.skill1[id1]
        val c1 = (r1 % (sp1b - sp1a + 1L) + sp1a).toInt()

        var c2  = -1
        var c3  = 0
        var q5  = r3  // スキル2なし時は r3（notebook 準拠）

        if (r2 % 100 >= tbl.th) {
            c2 = tbl.skill2[id2]
            if (origin == 1 && r4 % 2 == 0L) {
                // 炭鉱 + 偶数: マイナスポイント扱い
                val q4 = r5; q5 = r6
                c3 = (q4 % (tbl.sp2[id2][0] + 1L) - tbl.sp2[id2][0]).toInt()
            } else {
                val q4 = if (origin == 1) { q5 = r6; r5 } else { q5 = r5; r4 }
                // Python: tmp2 = q4 % sp2[id2][1] + 1  ← 必ず [1]
                c3 = (q4 % tbl.sp2[id2][1] + 1).toInt()
            }
        }

        // Python: tmp2=0 when no skill2 / same skill / negative pts
        val eff2 = if (c2 == -1 || c2 == c0 || c3 < 0) 0 else c3
        // Python: s1 = sp1[id1][1]; s2 = sp2[id2][1]  （max ではない）
        val s1 = tbl.sp1[id1][1]
        val s2 = tbl.sp2[id2][1]
        val fill = if (s1 != 0 && s2 != 0)
            (c1 * s2 + eff2 * s1) * 10 / (s1 * s2)
        else 1
        val slotRoll = (q5 % 100).toInt()
        val slot = slotCount(fill.coerceIn(1, 20), slotRoll.toLong())
        val rarity = rare(slot, fill.coerceIn(1, 20))

        return RawCharm(c0, c1, c2, c3, slot, fill, slotRoll, rarity)
    }

    // ── 経過時間換算（notebook watch: 固定 30fps）──────────────────────────

    fun watch(frame: Long): ElapsedTime {
        // notebook: 2592000 / 108000 / 1800 / 30
        val d  = frame / 2_592_000L
        val h  = (frame % 2_592_000L) / 108_000L
        val m  = (frame % 108_000L) / 1_800L
        val s  = (frame % 1_800L) / 30L
        val fr = frame % 30L
        return ElapsedTime(d, h, m, s, fr)
    }

    private fun makeResult(raw: RawCharm, frame: Long) =
        CharmResult(frame, watch(frame), Charm.fromRaw(raw))

    /** 指定フレームのお守り内容を1件返す（notebook: jump(frame) → getcharm） */
    fun charmAt(frame: Long, origin: Int = 0): CharmResult {
        jump(frame)
        val raw = getcharm(origin)
        return CharmResult(frame, watch(frame), Charm.fromRaw(raw))
    }

    // ── 完全一致検索 ───────────────────────────────────────────────────────

    /**
     * 完全一致検索 — mhxx-rng.ipynb の search() を 1 行ずつ移植。
     *
     * ```
     * jump(start)
     * for i in range(step):
     *   roll()
     *   if r0%len1==id1 and (skip2 or r2%100>=th and r3%len2==id2):
     *     c = getcharm(origin)
     *     if c[1]==sp1 and c[4]==slot:
     *       if (skip2 and (c[2]==-1 or c[2]==c[0] or c[3]==0)) or c[3]==sp2:
     *         yield f-7
     * ```
     */
    fun search(
        start: Long, step: Long, target: SearchTarget,
        shouldStop: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Sequence<CharmResult> = sequence {
        val id1 = target.skill1Idx ?: 0
        val sp1 = target.skill1Pts
        val id2 = target.skill2Idx ?: -1
        val sp2 = target.skill2Pts
        val slotTgt = target.slot
        val origin = target.origin
        val skip2 = target.skill2Idx == null
        val skipSlot = slotTgt < 0
        val len1 = table.skill1.size
        val len2 = table.skill2.size

        // start==0 は jump 多項式を使わず init+7roll（notebook jump(0) と同等・確実）
        if (start == 0L) {
            initState()
            repeat(7) { roll() }
        } else {
            jump(start)
        }

        var i = 0L
        while (i < step) {
            if (i % 4096L == 0L && shouldStop()) return@sequence
            roll()
            val hitPre = (r0 % len1).toInt() == id1 &&
                (skip2 || ((r2 % 100L).toInt() >= th && (r3 % len2).toInt() == id2))
            if (hitPre) {
                val c = getcharm(origin)
                val slotOk = skipSlot || c.slot == slotTgt
                if (c.skill1Pts == sp1 && slotOk) {
                    val cond = if (skip2) {
                        c.skill2Idx == -1 || c.skill2Idx == c.skill1Idx || c.skill2Pts == 0
                    } else {
                        // notebook: c[3] == _sp2 のみ（スキル種別は事前フィルタ済み）
                        c.skill2Pts == sp2
                    }
                    if (cond) yield(makeResult(c, f - 7))
                }
            }
            if (i % 50_000L == 0L) onProgress(i, step)
            i++
        }
    }

    // ── しきい値以上検索 ──────────────────────────────────────────────────

    fun searchGreater(
        start: Long, step: Long, target: SearchTarget,
        shouldStop: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Sequence<CharmResult> = sequence {
        val id1     = target.skill1Idx ?: -1
        val sp1     = target.skill1Pts
        val id2     = target.skill2Idx ?: -1
        val sp2     = target.skill2Pts
        val slotTgt = target.slot
        val origin  = target.origin
        val skip1   = id1 == -1
        val skip2   = id2 == -1
        val len1    = table.skill1.size
        val len2    = table.skill2.size
        jump(start)
        for (i in 0L until step) {
            if (i % 4096 == 0L && shouldStop()) return@sequence
            roll()
            if ((skip1 || (r0 % len1).toInt() == id1) &&
                (skip2 || (r2 % 100 >= th && (r3 % len2).toInt() == id2))) {
                val c = getcharm(origin)
                // notebook: if skip1 and c[0] == c[2]: continue
                if (skip1 && c.skill1Idx == c.skill2Idx) continue
                val ok1   = skip1 || c.skill1Pts >= sp1
                val ok2   = skip2 || c.skill2Pts >= sp2
                val okSlot= c.slot >= slotTgt
                if (ok1 && ok2 && okSlot) yield(makeResult(c, f - 7))
            }
            if (i % 50_000L == 0L) onProgress(i, step)
        }
    }

    // ── 調合列検索 ─────────────────────────────────────────────────────────

    /**
     * 調合列検索（notebook search_combo 準拠）
     *
     * - 先頭3件・末尾99をカットして差分列 (2/3/4) を作る
     * - stride=5 で KMP 検索
     * - ヒット位置の補正:
     *     j = i - 5*3 - 15 + 2*(len(raw)-1)
     *   （カットした3回分 + 初回遅延15 + 各調合の進行2）
     */
    fun searchCombo(
        start: Long, step: Long, comboPositions: List<Int>,
        shouldStop: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Sequence<FrameResult> = sequence {
        // notebook: 先頭3件・末尾99をカット
        var pos = comboPositions.drop(3).toMutableList()
        if (pos.isNotEmpty() && pos.last() == 99) pos.removeAt(pos.lastIndex)
        if (pos.size <= 1) return@sequence

        val fullDif = pos.zipWithNext { a, b -> b - a }
        val lut = comboLookupTable()

        // 候補パターン構築（OCRノイズ耐性）
        // 1) 全差分が 2/3/4 ならフルパターン
        // 2) 末尾 20/15/12 の短縮
        // 3) 無効増分がある場合: 最長の連続有効セグメント
        val candidates = mutableListOf<Pair<List<Int>, Int>>() // dif to coveredRawLen

        fun addCandidate(dif: List<Int>, coveredRawLen: Int) {
            if (dif.size >= 2 && dif.all { it in 2..4 }) {
                candidates.add(dif to coveredRawLen)
            }
        }

        if (fullDif.isNotEmpty() && fullDif.all { it in 2..4 }) {
            addCandidate(fullDif, comboPositions.size)
            for (takeN in listOf(20, 15, 12)) {
                if (fullDif.size > takeN + 2) {
                    addCandidate(fullDif.takeLast(takeN), takeN + 1 + 3)
                }
            }
        } else {
            // 連続する有効増分の最長区間を探す
            var bestStart = 0
            var bestLen = 0
            var i = 0
            while (i < fullDif.size) {
                if (fullDif[i] in 2..4) {
                    val s = i
                    while (i < fullDif.size && fullDif[i] in 2..4) i++
                    val len = i - s
                    if (len > bestLen) {
                        bestLen = len
                        bestStart = s
                    }
                } else i++
            }
            if (bestLen >= 2) {
                val seg = fullDif.subList(bestStart, bestStart + bestLen)
                // coveredRawLen: 先頭3 + セグメント内の位置数
                addCandidate(seg, bestLen + 1 + 3)
                for (takeN in listOf(20, 15, 12)) {
                    if (seg.size > takeN) {
                        addCandidate(seg.takeLast(takeN), takeN + 1 + 3)
                    }
                }
            }
        }

        if (candidates.isEmpty()) return@sequence

        val seenFrames = mutableSetOf<Long>()
        var emitted = 0L

        for ((dif, coveredRawLen) in candidates) {
            if (shouldStop()) return@sequence
            val se = MHXXEngine(this@MHXXEngine.kind)
            se.jump(start)
            repeat(7) { se.descend() }

            val hits = mutableListOf<Long>()
            searchStride(se, step, dif, 5, lut, shouldStop).forEach { hits.add(it) }

            for (hitI in hits) {
                // notebook: j = i - 5*3 - 15 + 2*(len(raw_A)-1)
                val resultFrame = start + hitI - 5L * 3L - 15L + 2L * (coveredRawLen - 1L)
                if (resultFrame >= 0 && seenFrames.add(resultFrame)) {
                    yield(FrameResult(resultFrame, watch(resultFrame)))
                    emitted++
                    onProgress(emitted, hits.size.toLong().coerceAtLeast(1L))
                }
            }
            if (emitted > 0) break
        }
    }


    // ── クエスト報酬から現在位置特定 (notebook search_reward) ─────────────

    /**
     * @param bonusTh 通常=28 / 激運=... notebook の bonus_th
     * @param targetItems 報酬アイテム名リスト（4〜8件）
     * @param rewardTable 累積閾値テーブル 例: 謎骨=20,釣り=40,...
     */
    fun searchReward(
        start: Long, step: Long,
        bonusTh: Int,
        targetItems: List<String>,
        rewardTable: Map<String, Int>,
        shouldStop: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): List<FrameResult> {
        if (targetItems.size !in 4..8) return emptyList()
        val keys = rewardTable.keys.toList()
        val rewardIndex = keys.withIndex().associate { it.value to it.index }
        val targetIndex = targetItems.mapNotNull { rewardIndex[it] }
        if (targetIndex.size != targetItems.size) return emptyList()

        val lut = IntArray(100)
        var prev = 0
        keys.forEachIndexed { i, k ->
            val th = rewardTable[k] ?: return@forEachIndexed
            for (j in prev until th.coerceIn(0, 100)) lut[j] = i
            prev = th
        }

        val se = MHXXEngine(this.kind)
        se.jump(start)
        repeat(7) { se.descend() }

        val hits = mutableListOf<Long>()
        searchStride(se, step, targetIndex, 1, lut, shouldStop).forEach { hits.add(it) }

        val out = mutableListOf<FrameResult>()
        for ((hitIdx, hitI) in hits.withIndex()) {
            if (shouldStop()) break
            val probe = MHXXEngine(this.kind)
            probe.jump(start + hitI - 7 - 1)
            val four = longArrayOf(probe.x, probe.y, probe.z, probe.w)
            if (checkBonus(targetItems.size, four, bonusTh)) {
                val j = hitI - minOf(targetItems.size - 3L, 4L)
                val frame = start + j
                if (frame >= 0) out.add(FrameResult(frame, watch(frame)))
            }
            onProgress(hitIdx + 1L, hits.size.toLong().coerceAtLeast(1L))
        }
        return out
    }

    private fun checkBonus(len1: Int, fourNums: LongArray, bonusTh: Int): Boolean {
        val flag = BooleanArray(4) { i -> (fourNums[i] and 0x1F) < bonusTh }
        if (len1 == 8) return flag.all { it }
        val k = len1 - 4
        if (k <= 0) return !flag[3]
        if (flag[3]) return false
        for (i in (3 - k) until 3) if (!flag[i]) return false
        return true
    }

    // ── 周辺確認 ───────────────────────────────────────────────────────────

    fun around(frame: Long, num: Int, origin: Int): List<Pair<Long, Charm>> {
        val results = mutableListOf<Pair<Long, Charm>>()
        jump(frame - num)
        repeat(num * 2 + 1) {
            val fr = f - 7
            results.add(Pair(fr, Charm.fromRaw(getcharm(origin))))
            roll()
        }
        return results
    }

    // ── 狙い目 ────────────────────────────────────────────────────────────

    fun aimpointQuest(numOfCharms: Int): AimPointRow {
        val n   = numOfCharms.coerceAtLeast(2)
        val num = (n - 1) * 7 + 1
        val a   = LongArray(num + 4)
        val b   = IntArray(num + 4) { n + 1 }
        b[0] = 1
        repeat(7) { descend() }
        for (i in 0 until num) { descend(); if (i + 3 < num + 4) a[i + 3] = w }
        repeat(num + 7) { ascend() }
        for (i in 0 until num) {
            if (i + 4 < num + 4 && a[i + 4] % 100 < th) b[i + 4] = minOf(b[i + 4], b[i] + 1)
            if (i + 7 < num + 4 && a[i + 7] % 100 >= th) b[i + 7] = minOf(b[i + 7], b[i] + 1)
        }
        return formatAimpoint(b.take(num)) { it <= n }
    }

    fun aimpointHalcyon(showAllRanks: Boolean = true): List<AimPointRow> =
        (if (showAllRanks) listOf(4, 3, 2, 1) else listOf(4)).map { rank ->
            aimpointMelding((6 - 1) * 6 + 1, { seed, r -> halcyonColors(seed, r) }, rank)
        }

    fun aimpointJuju(showAllRanks: Boolean = true): List<AimPointRow> =
        (if (showAllRanks) listOf(4, 3, 2, 1) else listOf(4)).map { rank ->
            aimpointMelding((4 - 1) * 6 + 1, { seed, r -> jujuColors(seed, r) }, rank)
        }

    private fun aimpointMelding(
        num: Int,
        colorFunc: (Long, Int) -> List<Int>,
        rank: Int
    ): AimPointRow {
        val origKind = table.index
        val a = LongArray(num + 4)
        val b = BooleanArray(num + 4)
        repeat(7) { descend() }
        for (i in 0 until num) { descend(); if (i + 3 < num + 4) a[i + 3] = w }
        repeat(num + 7) { ascend() }
        for (i in 0 until num) {
            var c = i
            val colors = colorFunc(a[i + 3], rank)
            for (col in colors) {
                setKind(col)
                if (c == 0 && origKind == col) b[i] = true
                if (c >= 0) c -= if (c < a.size && a[c] % 100 < th) 4 else 6
            }
        }
        setKind(origKind)
        return formatAimpoint(b.take(num)) { it }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Companion (GF(2) 演算 / KMP / 静的ユーティリティ)
    // ────────────────────────────────────────────────────────────────────────
    companion object {
        internal const val MASK32 = 0xFFFF_FFFFL
        private val JUMP_MODULUS_BI = BigInteger("100000201A8362F671442057EEA368001", 16)
        private val BI_2_128_MINUS_1 = BigInteger.TWO.pow(128).subtract(BigInteger.ONE)

        // ── GF(2) 多項式演算 ────────────────────────────────────────────────
        private fun polyMul(p1: BigInteger, p2: BigInteger): BigInteger {
            var res = BigInteger.ZERO; var pp1 = p1; var pp2 = p2
            while (pp2 > BigInteger.ZERO) {
                if (pp2.testBit(0)) res = res.xor(pp1)
                pp1 = pp1.shiftLeft(1); pp2 = pp2.shiftRight(1)
            }
            return res
        }

        private fun polyMod(p: BigInteger, m: BigInteger): BigInteger {
            var pp = p; val mLen = m.bitLength()
            while (pp.bitLength() >= mLen) pp = pp.xor(m.shiftLeft(pp.bitLength() - mLen))
            return pp
        }

        internal fun polyPowMod(base: BigInteger, exp: BigInteger, mod: BigInteger): BigInteger {
            var res = BigInteger.ONE; var b = polyMod(base, mod); var e = exp
            while (e > BigInteger.ZERO) {
                if (e.testBit(0)) res = polyMod(polyMul(res, b), mod)
                b = polyMod(polyMul(b, b), mod); e = e.shiftRight(1)
            }
            return res
        }

        // ── KMP パターンマッチング ──────────────────────────────────────────
        private fun kmpPrefix(pattern: List<Int>): IntArray {
            val n = pattern.size; val pi = IntArray(n); var j = 0
            for (i in 1 until n) {
                while (j > 0 && pattern[i] != pattern[j]) j = pi[j - 1]
                if (pattern[i] == pattern[j]) j++
                pi[i] = j
            }
            return pi
        }

        /**
         * notebook search_stride_nj 準拠。
         * 重要: シンボルは (w & 0xFFFF) % 100 で引く（w % 100 ではない）
         */
        internal fun searchStride(
            engine: MHXXEngine, step: Long, pattern: List<Int>, stride: Int,
            lut: IntArray, shouldStop: () -> Boolean = { false }
        ): Sequence<Long> = sequence {
            val n = pattern.size; if (n == 0) return@sequence
            val pi = kmpPrefix(pattern); val state = IntArray(stride)
            var r = 0
            for (i in 0L until step) {
                if (i % 4096 == 0L && shouldStop()) return@sequence
                // notebook: lut[(js[3] & 0xFFFF) % 100]
                val symbol = lut[((engine.w and 0xFFFFL) % 100L).toInt()]
                engine.ascend()
                var k = state[r]
                while (k > 0 && pattern[k] != symbol) k = pi[k - 1]
                if (pattern[k] == symbol) k++
                if (k == n) {
                    yield(i - stride.toLong() * (n - 1L))
                    k = pi[n - 1]
                }
                state[r] = k
                r = (r + 1) % stride
            }
        }

        fun comboLookupTable(): IntArray = IntArray(100) { when { it < 25 -> 2; it < 75 -> 3; else -> 4 } }

        fun parseComboSequence(comboPositions: List<Int>): Pair<List<Int>, List<Int>> {
            var pos = comboPositions.drop(3)
            if (pos.isNotEmpty() && pos.last() == 99) pos = pos.dropLast(1)
            if (pos.size <= 1) return Pair(emptyList(), emptyList())
            val dif = pos.zipWithNext { a, b -> b - a }
            val invalid = dif.indices.filter { dif[it] !in 2..4 }
            return Pair(dif, invalid)
        }

        // ── 狙い目補助 ──────────────────────────────────────────────────────
        private fun rand(num1: Long, num2: Int): Long {
            var n = num1 and 0xFFFFL
            repeat(num2) { n = maxOf(n, 1L) * 176L % 65363L }
            return n
        }

        private fun halcyonColors(seed: Long, rank: Int): List<Int> {
            val (width, minimum) = when (rank) {
                1 -> 3 to 1; 2 -> 4 to 1; 3 -> 4 to 2; else -> 5 to 2
            }
            var a = 1
            val num = (rand(seed, a) % width + minimum).toInt(); a++
            return List(num) {
                while (rand(seed, a) % 100 >= 85) a++
                val c = rand(seed, a++) % 100
                if (c < 10) 3 else if (c < 55) 2 else 1
            }
        }

        private fun jujuColors(seed: Long, rank: Int): List<Int> {
            val params = when (rank) {
                1    -> listOf(2, 0, 1, 1)
                2    -> listOf(2, 0, 2, 1)
                3    -> listOf(2, 1, 2, 1)
                else -> listOf(1, 2, 2, 1)
            }
            val w1 = params[0]; val m1 = params[1]; val w2 = params[2]; val m2 = params[3]
            val num1 = (rand(seed, 1) % w1 + m1).toInt()
            val num2 = (rand(seed, 2) % w2 + m2).toInt()
            return List(num1) { 0 } + List(num2) { 1 }
        }

        private fun <T> formatAimpoint(array: List<T>, predicate: (T) -> Boolean): AimPointRow {
            val count = array.count { predicate(it) }
            val sb = StringBuilder("[")
            for (i in array.indices.reversed()) {
                if (i % 10 == 0 && i != array.size - 1) sb.append(' ')
                sb.append(if (predicate(array[i])) "◯" else "Ｘ")
            }
            sb.append("]")
            return AimPointRow("", count, sb.toString())
        }

        // ── 公開ユーティリティ ───────────────────────────────────────────────
        fun formatElapsed(frame: Long): String =
            MHXXEngine().watch(frame).text()

        fun formatMash(frame: Long): String {
            val (mashes, _, remainder) = continueMashInfo(frame)
            return "${mashes}回 (残${remainder}f)"
        }
    }
}
