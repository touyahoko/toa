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
