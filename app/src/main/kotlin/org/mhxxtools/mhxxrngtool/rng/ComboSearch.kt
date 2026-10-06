package org.mhxxtools.mhxxrngtool.rng

/**
 * mhxx-combo-scan combo-core/src/search.rs の Searcher を Kotlin に移植。
 *
 * - STRIDE = 5
 * - 生産数: (w & 0xFFFF) % 100 → 0..24=2, 25..74=3, 75..99=4
 * - 先頭3件・末尾99をカットして差分列を作る
 * - jump は 7roll なしの純粋 jump (rng.rs の jump)
 * - frame = offset + consumed - STRIDE*(n-1)
 *   offset = start - STRIDE*3 - 15 + 2*(raw_len - 1)
 */

/** 調合1回で乱数が進む数 */
private const val STRIDE = 5

/** 乱数 (0〜99) から生産数へ */
private fun yieldOf(w: Long): Int {
    val r = ((w and 0xFFFFL) % 100L).toInt()
    return when {
        r < 25 -> 2
        r < 75 -> 3
        else -> 4
    }
}

/**
 * 累計の並びを検索用の差分列に直す。
 * @return Pair(差分列, 元の並びの長さ) / null = TooShort or InvalidDifference
 */
fun patternFrom(cumulative: List<Int>): Pair<List<Int>, Int>? {
    // 先頭3つは調合開始前の乱数消費と噛み合わないので使わない
    var pos = cumulative.drop(3).toMutableList()
    // 上限に張り付いた最後の1回は生産数が削られているので使わない
    if (pos.isNotEmpty() && pos.last() == 99) {
        pos.removeAt(pos.lastIndex)
    }
    if (pos.size <= 1) return null

    val diffs = mutableListOf<Int>()
    for (i in 0 until pos.size - 1) {
        val d = pos[i + 1] - pos[i]
        if (d !in 2..4) return null
        diffs.add(d)
    }
    return diffs to cumulative.size
}

private fun kmpPrefix(a: List<Int>): IntArray {
    val pi = IntArray(a.size)
    var j = 0
    for (i in 1 until a.size) {
        while (j > 0 && a[i] != a[j]) j = pi[j - 1]
        if (a[i] == a[j]) j++
        pi[i] = j
    }
    return pi
}

/**
 * search.rs の Searcher 相当。
 * step() で乱数を進め、ヒットした絶対フレーム位置を返す。
 */
class ComboSearcher private constructor(
    private var x: Long,
    private var y: Long,
    private var z: Long,
    private var w: Long,
    private val pattern: List<Int>,
    private val pi: IntArray,
    private val offset: Long
) {
    private val kmp = IntArray(STRIDE)
    private var r = 0
    /** 開始位置から進めた数 */
    var consumed: Long = 0
        private set

    val patternLen: Int get() = pattern.size

    companion object {
        /**
         * @param cumulative 調合累計個数の並び
         * @param startFrame 探し始めるフレーム位置
         * @return null = 並びが短すぎる / 不正な差分
         */
        fun create(cumulative: List<Int>, startFrame: Long): ComboSearcher? {
            val (pattern, rawLen) = patternFrom(cumulative) ?: return null
            val eng = MHXXEngine(0)
            eng.jumpPure(startFrame)
            return ComboSearcher(
                x = eng.x, y = eng.y, z = eng.z, w = eng.w,
                pattern = pattern,
                pi = kmpPrefix(pattern),
                // 使わなかった先頭3回ぶん、初回調合の遅延、各調合による進行2の補正
                offset = startFrame - (STRIDE * 3L) - 15L + 2L * (rawLen - 1L)
            )
        }
    }

    private fun ascend() {
        val t = (x xor (x shl 15)) and 0xFFFFFFFFL
        x = y; y = z; z = w
        w = (w xor (w ushr 21) xor t xor (t ushr 4)) and 0xFFFFFFFFL
    }

    /**
     * count 回ぶん乱数を進めて、その間に確定したフレーム位置を返す。
     */
    fun step(count: Long, shouldStop: () -> Boolean = { false }): List<Long> {
        val n = pattern.size
        val hits = mutableListOf<Long>()
        var i = 0L
        while (i < count) {
            if (i % 4096L == 0L && shouldStop()) break
            val symbol = yieldOf(w)
            ascend()

            var k = kmp[r]
            while (k > 0 && pattern[k] != symbol) k = pi[k - 1]
            if (pattern[k] == symbol) k++
            if (k == n) {
                hits.add(offset + consumed - (STRIDE * (n - 1L)))
                k = pi[n - 1]
            }
            kmp[r] = k
            r = (r + 1) % STRIDE
            consumed++
            i++
        }
        return hits
    }
}

// ── diagnose (search.rs 診断) ─────────────────────────────────────────────

private const val WINDOW = 12
private const val MIN_VOTES = 2
private const val MAX_ANCHORS = 1 shl 18
private const val MAX_CANDIDATES_DIAG = 1 shl 16
private const val MAX_STEP_DRIFT = 2L
private const val MAX_TOTAL_DRIFT = 5L
private const val LOOKAHEAD = 3
private const val SYMBOLS = 3
/** 3^12 = 531441 */
private const val CODES = 531441
private const val DROP = CODES / SYMBOLS
private const val NONE = 0xFFFF

data class ComboDrift(val craft: Int, val steps: Int)

data class ComboDiagnosis(
    val frame: Long,
    val drifts: List<ComboDrift>,
    val totalDrift: Int,
    val leadingSkipped: Boolean
) {
    fun note(): String {
        val parts = mutableListOf<String>()
        if (leadingSkipped) parts.add("先頭1件を除外")
        for (d in drifts) {
            val sign = if (d.steps > 0) "+${d.steps}" else "${d.steps}"
            parts.add("${d.craft}回目あたりで $sign")
        }
        return parts.joinToString(" / ")
    }
}

private fun jumpState(frame: Long): LongArray {
    val eng = MHXXEngine(0)
    eng.jumpPure(frame)
    return longArrayOf(eng.x, eng.y, eng.z, eng.w)
}

private fun ascendState(s: LongArray): LongArray {
    val t = (s[0] xor (s[0] shl 15)) and 0xFFFFFFFFL
    return longArrayOf(
        s[1], s[2], s[3],
        (s[3] xor (s[3] ushr 21) xor t xor (t ushr 4)) and 0xFFFFFFFFL
    )
}

private class WindowTable(pattern: List<Int>) {
    val head = IntArray(CODES) { NONE }
    val next: IntArray
    init {
        val count = pattern.size - WINDOW + 1
        next = IntArray(count) { NONE }
        for (k in 0 until count) {
            var code = 0
            for (j in 0 until WINDOW) {
                code = code * SYMBOLS + (pattern[k + j] - 2)
            }
            next[k] = head[code]
            head[code] = k
        }
    }
}

private fun findAnchors(
    pattern: List<Int>, startFrame: Long, total: Long,
    shouldStop: () -> Boolean
): List<Pair<Long, Int>> {
    val table = WindowTable(pattern)
    val lead = (STRIDE * (WINDOW - 1)).toLong()
    var state = jumpState(startFrame)
    val code = IntArray(STRIDE)
    val filled = IntArray(STRIDE)
    var r = 0
    val out = mutableListOf<Pair<Long, Int>>()

    for (i in 0L until total) {
        if (i % 65536L == 0L && shouldStop()) break
        val x = yieldOf(state[3])
        state = ascendState(state)
        code[r] = (code[r] % DROP) * SYMBOLS + (x - 2)
        if (filled[r] < WINDOW) filled[r]++
        if (filled[r] == WINDOW) {
            var k = table.head[code[r]]
            while (k != NONE) {
                val end = startFrame + i
                val base = end - lead - (STRIDE * k).toLong()
                if (base >= 0) out.add(base to k)
                k = table.next[k]
            }
            if (out.size >= MAX_ANCHORS) break
        }
        r = (r + 1) % STRIDE
    }
    return out
}

private data class Fit(
    val shift: Long,
    val drifts: List<ComboDrift>,
    val totalDrift: Long,
    val leadingSkipped: Boolean
)

private fun align(pattern: List<Int>, anchor: Long): Fit? {
    val back = (MAX_TOTAL_DRIFT + STRIDE).toInt()
    if (anchor < back) return null
    val span = back + STRIDE * (pattern.size + LOOKAHEAD) + back
    val ys = IntArray(span)
    var s = jumpState(anchor - back)
    for (i in 0 until span) {
        ys[i] = yieldOf(s[3])
        s = ascendState(s)
    }
    fun at(i: Int, d: Long): Int? {
        val idx = back + STRIDE * i + d
        return if (idx in 0 until span) ys[idx.toInt()] else null
    }
    fun fits(i: Int, d: Long): Boolean {
        var t = 0
        while (t <= LOOKAHEAD && i + t < pattern.size) {
            if (at(i + t, d) != pattern[i + t]) return false
            t++
        }
        return true
    }

    var drift = 0L
    var shift = 0L
    val drifts = mutableListOf<ComboDrift>()
    var leadingSkipped = false
    for (i in pattern.indices) {
        if (at(i, drift) == pattern[i]) continue
        var found: Long? = null
        for (a in 1..MAX_STEP_DRIFT) {
            for (sign in longArrayOf(a, -a)) {
                if (kotlin.math.abs(drift + sign) <= MAX_TOTAL_DRIFT && fits(i, drift + sign)) {
                    found = sign
                    break
                }
            }
            if (found != null) break
        }
        if (found == null) {
            if (i == 0 && (1 until pattern.size).all { j -> at(j, 0) == pattern[j] }) {
                leadingSkipped = true
                break
            }
            return null
        }
        drift += found
        if (i == 0) shift = drift
        else drifts.add(ComboDrift(craft = i + 4, steps = found.toInt()))
    }
    return Fit(shift, drifts, drift - shift, leadingSkipped)
}

private data class Cand(var base: Long, var votes: Int, var firstK: Int)

private fun candidates(
    pattern: List<Int>, startFrame: Long, total: Long,
    shouldStop: () -> Boolean
): List<Cand> {
    val votes = sortedMapOf<Long, Pair<Int, Int>>() // base -> (n, firstK)
    for ((base, k) in findAnchors(pattern, startFrame, total, shouldStop)) {
        val e = votes[base]
        if (e == null) votes[base] = 1 to k
        else votes[base] = (e.first + 1) to minOf(e.second, k)
    }
    val out = mutableListOf<Cand>()
    for ((base, pair) in votes) {
        val (n, firstK) = pair
        val prev = out.lastOrNull()
        if (prev != null && base - prev.base <= MAX_TOTAL_DRIFT) {
            prev.votes += n
            if (firstK < prev.firstK) {
                prev.base = base
                prev.firstK = firstK
            }
        } else {
            out.add(Cand(base, n, firstK))
        }
    }
    out.sortByDescending { it.votes }
    return out.filter { it.votes >= MIN_VOTES }.take(MAX_CANDIDATES_DIAG)
}

/**
 * 本家 diagnose: 完全一致で見つからないとき、途中の乱数ずれを検出する。
 */
fun diagnoseCombo(
    cumulative: List<Int>,
    startFrame: Long,
    total: Long,
    shouldStop: () -> Boolean = { false }
): ComboDiagnosis? {
    val (pattern, rawLen) = patternFrom(cumulative) ?: return null
    if (pattern.size <= WINDOW) return null

    val fitted = mutableListOf<Triple<Int, Int, Pair<Long, Fit>>>() // (nDrifts, votes, base+fit)
    for (c in candidates(pattern, startFrame, total, shouldStop)) {
        if (shouldStop()) break
        val fit = align(pattern, c.base) ?: continue
        val base = c.base + fit.shift
        if (base < 0) continue
        fitted.add(Triple(fit.drifts.size, c.votes, base to fit))
    }
    fitted.sortWith(compareBy<Triple<Int, Int, Pair<Long, Fit>>> { it.first }.thenByDescending { it.second })

    if (fitted.size >= 2) {
        val a = fitted[0]; val b = fitted[1]
        if (a.first == b.first && a.second == b.second) return null
    }
    val best = fitted.firstOrNull() ?: return null
    val (base, fit) = best.third
    return ComboDiagnosis(
        frame = base - (STRIDE * 3L) - 15L + 2L * (rawLen - 1L),
        drifts = fit.drifts,
        totalDrift = fit.totalDrift.toInt(),
        leadingSkipped = fit.leadingSkipped
    )
}


