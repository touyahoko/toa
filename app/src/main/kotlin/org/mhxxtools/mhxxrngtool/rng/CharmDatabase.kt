package org.mhxxtools.mhxxrngtool.rng

/**
 * MHXX 全お守り有効組合せ データベース
 *
 * 4種類 × スキル1 × スキル1ポイント × (スキル2なし | スキル2 × スキル2ポイント)
 * の全組合せを KIND_TABLES から事前列挙し、OCR結果の検証・自動補正に使用する。
 *
 * ──────────────────────────────────────────────────────────────────
 * 設計方針
 * ──────────────────────────────────────────────────────────────────
 * • OCR が読み取った (skill1Name, skill1Pts, skill2Name?, skill2Pts?, slot) を
 *   このDBと照合することで「有効な組合せだけ」に絞り込む。
 * • ポイントが有効範囲外なら範囲内に丸め直す (OCR誤読 ±1〜2 を吸収)。
 * • スロットが fill 値と矛盾する場合は有効スロット集合を返す。
 * • 結果を RNG 検索に渡すことで 100% 一致検索を実現。
 * ──────────────────────────────────────────────────────────────────
 */
object CharmDatabase {

    // ─────────────────────────────────────────────────────────────────────────
    // データ構造
    // ─────────────────────────────────────────────────────────────────────────

    /** 有効な1組合せ */
    data class ValidCombo(
        val kindIdx: Int,
        val kindLabel: String,
        val skill1GlobalIdx: Int,
        val skill1Name: String,
        val skill1Pts: Int,
        val skill2GlobalIdx: Int,   // -1 = スキル2なし
        val skill2Name: String?,    // null = スキル2なし
        val skill2Pts: Int,         // 0 if no skill2
        val fill: Int,              // fillValue (1-20) — スロット確率決定に使用
        val possibleSlots: Set<Int> // この組合せで出現しうるスロット値 {0,1,2,3}
    ) {
        /** 表示テキスト（UI/デバッグ用） */
        fun displayText(): String {
            val s1 = "${skill1Name}+${skill1Pts}"
            val s2 = if (skill2GlobalIdx == -1) "スキル2なし"
                     else "${skill2Name}+${skill2Pts}"
            val slotStr = possibleSlots.sorted().joinToString("/") { "S$it" }
            return "[${kindLabel}] $s1 / $s2  [$slotStr]"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fill・スロット計算 (getcharm ロジックと完全一致)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * fill 値を計算。
     * sp1hi / sp2hi には KIND_TABLES の sp1[i][1] / sp2[j][1] を渡す（常に [1] を使う）。
     * スキル2なしの場合は s2pts=0, sp2hi=1 で呼ぶと (s1pts*10/sp1hi) と等価になる。
     */
    fun computeFill(sp1hi: Int, sp2hi: Int, s1pts: Int, s2pts: Int): Int {
        if (sp1hi == 0 || sp2hi == 0) return 1
        return (s1pts * sp2hi + s2pts * sp1hi) * 10 / (sp1hi * sp2hi)
    }

    /**
     * fill 値に対して出現可能なスロット値集合を返す。
     *
     * getcharm の slotCount ロジック:
     *   slotRoll = q5 % 100
     *   if slotRoll >= sv[2] → slot 3
     *   elif slotRoll >= sv[1] → slot 2
     *   elif slotRoll >= sv[0] → slot 1
     *   else → slot 0
     *
     * したがって:
     *   slot 0 は slotRoll < sv[0]  の範囲が存在するとき (sv[0] > 0)
     *   slot 1 は sv[0] <= slotRoll < sv[1]  の範囲が存在するとき (sv[1] > sv[0])
     *   slot 2 は sv[1] <= slotRoll < sv[2]  の範囲が存在するとき (sv[2] > sv[1])
     *   slot 3 は slotRoll >= sv[2]  の範囲が存在するとき (sv[2] < 100)
     */
    fun possibleSlotsFor(tbl: KindTable, fill: Int): Set<Int> {
        val f = fill.coerceIn(1, 20)
        val sv = tbl.slotvalue[f - 1]   // [v0, v1, v2]
        val slots = mutableSetOf<Int>()
        if (sv[0] > 0)       slots.add(0)
        if (sv[1] > sv[0])   slots.add(1)
        if (sv[2] > sv[1])   slots.add(2)
        if (sv[2] < 100)     slots.add(3)
        return slots
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 全組合せ列挙 (遅延初期化)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 全有効組合せのリスト。
     * 初回アクセス時に KIND_TABLES から自動生成し、以後はキャッシュを返す。
     *
     * 内訳:
     *   風化    62 skill1 × avg5pts × (1 + 94 skill2 × avg4pts) ≈ 117,000 件
     *   古び    56 skill1 × avg4pts × (1 + 105 skill2 × avg4pts) ≈ 112,000 件
     *   光る    71 skill1 × avg5pts × (1 + 74 skill2 × avg6pts) ≈ 158,000 件
     *   なぞ    59 skill1 × avg5pts × 1(スキル2なし) ≈ 295 件
     *
     * 合計 ≈ 387,000 件（JVM ヒープ上で約 80 MB 程度）
     */
    val allValidCombos: List<ValidCombo> by lazy { buildAllCombos() }

    // ─────────────────────────────────────────────────────────────────────────
    // 高速ルックアップ用インデックス (遅延初期化)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * (kindIdx, skill1GlobalIdx) → List<ValidCombo>
     * スキル1が一致するエントリを素早く絞り込む。
     */
    private val byKindSkill1: Map<Long, List<ValidCombo>> by lazy {
        allValidCombos.groupBy { it.kindIdx.toLong() * 1000L + it.skill1GlobalIdx }
    }

    /**
     * (kindIdx, skill1GlobalIdx, skill1Pts) → List<ValidCombo>
     * スキル1 + ポイントが一致するエントリを更に絞り込む。
     */
    private val byKindSkill1Pts: Map<Long, List<ValidCombo>> by lazy {
        allValidCombos.groupBy {
            it.kindIdx.toLong() * 1_000_000L +
            it.skill1GlobalIdx.toLong() * 1000L +
            it.skill1Pts.toLong()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 検証 API
    // ─────────────────────────────────────────────────────────────────────────

    /** 検証結果 */
    data class ValidationResult(
        val isValid: Boolean,
        /** 補正後スキル1ポイント（元値が有効範囲外の場合のみ non-null） */
        val correctedPts1: Int? = null,
        /** 補正後スキル2ポイント（元値が有効範囲外の場合のみ non-null） */
        val correctedPts2: Int? = null,
        /** この組合せで出現しうるスロット集合 */
        val validSlots: Set<Int> = emptySet(),
        /** スロットが矛盾していた場合に true */
        val slotCorrected: Boolean = false,
        /** エラー詳細（デバッグ・UI表示用） */
        val errorMessage: String = ""
    )

    /**
     * OCR から得た (kindIdx, skill1GlobalIdx, skill1Pts, skill2GlobalIdx, skill2Pts, slot)
     * が理論上有効かどうかを検証し、無効なら補正候補を返す。
     *
     * @param slot -1 = スロット不明（スロット検証をスキップ）
     */
    fun validate(
        kindIdx: Int,
        skill1GlobalIdx: Int,
        skill1Pts: Int,
        skill2GlobalIdx: Int = -1,
        skill2Pts: Int = 0,
        slot: Int = -1
    ): ValidationResult {
        val tbl = KIND_TABLES[kindIdx]
            ?: return ValidationResult(false, errorMessage = "お守り種類 $kindIdx は存在しない")

        // ─── スキル1 チェック ───────────────────────────────────────────────
        val s1Local = tbl.skill1.indexOf(skill1GlobalIdx)
        if (s1Local < 0) {
            val name = SKILL_NAMES.getOrElse(skill1GlobalIdx) { "idx=$skill1GlobalIdx" }
            return ValidationResult(
                false,
                errorMessage = "${tbl.label}のスキル1に「$name」は存在しない"
            )
        }
        val sp1 = tbl.sp1[s1Local]
        val sp1lo = minOf(sp1[0], sp1[1])
        val sp1hi = maxOf(sp1[0], sp1[1])
        val sp1hiVal = sp1[1]   // fill 計算は常に [1]

        val pts1Ok = skill1Pts in sp1lo..sp1hi
        val correctedPts1 = if (!pts1Ok) skill1Pts.coerceIn(sp1lo, sp1hi) else null

        val effectivePts1 = correctedPts1 ?: skill1Pts

        // ─── スキル2なし ────────────────────────────────────────────────────
        if (skill2GlobalIdx == -1) {
            val fill = effectivePts1 * 10 / sp1hiVal
            val validSlots = possibleSlotsFor(tbl, fill)
            val slotCorrected = slot >= 0 && slot !in validSlots
            return ValidationResult(
                isValid = pts1Ok && !slotCorrected,
                correctedPts1 = correctedPts1,
                validSlots = validSlots,
                slotCorrected = slotCorrected,
                errorMessage = when {
                    !pts1Ok -> "スキル1ポイント${skill1Pts}は無効（有効: ${sp1lo}〜${sp1hi}）→ ${correctedPts1}に補正"
                    slotCorrected -> "スロット${slot}はfill=${fill}では出現しない（有効: ${validSlots}）"
                    else -> ""
                }
            )
        }

        // ─── スキル2あり ────────────────────────────────────────────────────
        if (skill2GlobalIdx == skill1GlobalIdx) {
            return ValidationResult(
                false,
                correctedPts1 = correctedPts1,
                errorMessage = "スキル1とスキル2が同じ（同一スキル衝突）"
            )
        }

        val s2Local = tbl.skill2.indexOf(skill2GlobalIdx)
        if (s2Local < 0) {
            val name = SKILL_NAMES.getOrElse(skill2GlobalIdx) { "idx=$skill2GlobalIdx" }
            return ValidationResult(
                false,
                correctedPts1 = correctedPts1,
                errorMessage = "${tbl.label}のスキル2に「$name」は存在しない"
            )
        }

        val sp2 = tbl.sp2[s2Local]
        val sp2hi = sp2[1]  // fill 計算は常に [1]

        val pts2Ok = skill2Pts in 1..sp2hi
        val correctedPts2 = if (!pts2Ok) skill2Pts.coerceIn(1, sp2hi) else null

        val effectivePts2 = correctedPts2 ?: skill2Pts

        val fill = computeFill(sp1hiVal, sp2hi, effectivePts1, effectivePts2)
        val validSlots = possibleSlotsFor(tbl, fill)
        val slotCorrected = slot >= 0 && slot !in validSlots

        return ValidationResult(
            isValid = pts1Ok && pts2Ok && !slotCorrected,
            correctedPts1 = correctedPts1,
            correctedPts2 = correctedPts2,
            validSlots = validSlots,
            slotCorrected = slotCorrected,
            errorMessage = when {
                !pts1Ok -> "スキル1ポイント${skill1Pts}は無効（有効: ${sp1lo}〜${sp1hi}）→ ${correctedPts1}に補正"
                !pts2Ok -> "スキル2ポイント${skill2Pts}は無効（有効: 1〜${sp2hi}）→ ${correctedPts2}に補正"
                slotCorrected -> "スロット${slot}はfill=${fill}では出現しない（有効: ${validSlots}）"
                else -> ""
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 完全一致検索 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * OCR 結果から完全一致する有効組合せを返す。
     *
     * まず validate() で検証・補正し、補正後の値で byKindSkill1Pts を検索する。
     * slot が -1 なら全スロット候補を返す。
     */
    fun findExact(
        kindIdx: Int,
        skill1GlobalIdx: Int,
        skill1Pts: Int,
        skill2GlobalIdx: Int = -1,
        skill2Pts: Int = 0,
        slot: Int = -1
    ): List<ValidCombo> {
        val v = validate(kindIdx, skill1GlobalIdx, skill1Pts, skill2GlobalIdx, skill2Pts, slot)
        val ePts1 = v.correctedPts1 ?: skill1Pts
        val ePts2 = v.correctedPts2 ?: skill2Pts

        val key = kindIdx.toLong() * 1_000_000L +
                  skill1GlobalIdx.toLong() * 1000L +
                  ePts1.toLong()
        val candidates = byKindSkill1Pts[key] ?: return emptyList()

        return candidates.filter { c ->
            if (skill2GlobalIdx == -1) {
                c.skill2GlobalIdx == -1
            } else {
                c.skill2GlobalIdx == skill2GlobalIdx && c.skill2Pts == ePts2
            }
            && (slot < 0 || slot in c.possibleSlots)
        }
    }

    /**
     * 全 kindIdx に対して findExact を実行し、いずれかの種類に存在する組合せを返す。
     * OCR が種類を読み取れなかった場合に使う。
     */
    fun findExactAllKinds(
        skill1GlobalIdx: Int,
        skill1Pts: Int,
        skill2GlobalIdx: Int = -1,
        skill2Pts: Int = 0,
        slot: Int = -1
    ): List<ValidCombo> = (0..3).flatMap { k ->
        findExact(k, skill1GlobalIdx, skill1Pts, skill2GlobalIdx, skill2Pts, slot)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 最近傍補正検索 API (OCR誤読吸収)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * OCR が読み取ったポイントが有効範囲外 (±[ptsTolerance] 以内) の場合に
     * 最も近い有効組合せを返す。
     *
     * @param ptsTolerance 許容誤差（デフォルト 2）
     */
    fun findNearest(
        kindIdx: Int,
        skill1GlobalIdx: Int,
        skill1PtsOcr: Int,
        skill2GlobalIdx: Int = -1,
        skill2PtsOcr: Int = 0,
        slotOcr: Int = -1,
        ptsTolerance: Int = 2
    ): List<ValidCombo> {
        val key = kindIdx.toLong() * 1000L + skill1GlobalIdx.toLong()
        val candidates = byKindSkill1[key] ?: return emptyList()

        return candidates
            .filter { c ->
                if (kotlin.math.abs(c.skill1Pts - skill1PtsOcr) > ptsTolerance) return@filter false
                if (skill2GlobalIdx == -1) {
                    c.skill2GlobalIdx == -1
                } else {
                    c.skill2GlobalIdx == skill2GlobalIdx &&
                        kotlin.math.abs(c.skill2Pts - skill2PtsOcr) <= ptsTolerance
                }
                && (slotOcr < 0 || slotOcr in c.possibleSlots)
            }
            .sortedWith(compareBy(
                { kotlin.math.abs(it.skill1Pts - skill1PtsOcr) },
                { if (skill2GlobalIdx >= 0) kotlin.math.abs(it.skill2Pts - skill2PtsOcr) else 0 }
            ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ユーティリティ API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * スキル1の有効ポイント範囲を返す（OCR 結果の妥当性確認に使用）。
     * @return IntRange または null（スキルがこの種類に存在しない場合）
     */
    fun skill1PtsRange(kindIdx: Int, skill1GlobalIdx: Int): IntRange? {
        val tbl = KIND_TABLES[kindIdx] ?: return null
        val s1Local = tbl.skill1.indexOf(skill1GlobalIdx)
        if (s1Local < 0) return null
        val sp1 = tbl.sp1[s1Local]
        return minOf(sp1[0], sp1[1])..maxOf(sp1[0], sp1[1])
    }

    /**
     * スキル2の有効ポイント最大値を返す（OCR 結果の妥当性確認に使用）。
     * @return max pts または -1（スキルがこの種類に存在しない場合）
     */
    fun skill2PtsMax(kindIdx: Int, skill2GlobalIdx: Int): Int {
        val tbl = KIND_TABLES[kindIdx] ?: return -1
        val s2Local = tbl.skill2.indexOf(skill2GlobalIdx)
        if (s2Local < 0) return -1
        return tbl.sp2[s2Local][1]
    }

    /**
     * fill 値から対応するスロット確率を返す（デバッグ・UI 表示用）。
     * @return Map<Int, Int> slot → 確率(%) 例: {0:3, 1:50, 2:35, 3:12}
     */
    fun slotProbabilities(kindIdx: Int, fill: Int): Map<Int, Int> {
        val tbl = KIND_TABLES[kindIdx] ?: return emptyMap()
        val f = fill.coerceIn(1, 20)
        val sv = tbl.slotvalue[f - 1]
        val result = mutableMapOf<Int, Int>()
        if (sv[0] > 0) result[0] = sv[0]
        if (sv[1] > sv[0]) result[1] = sv[1] - sv[0]
        if (sv[2] > sv[1]) result[2] = sv[2] - sv[1]
        if (sv[2] < 100) result[3] = 100 - sv[2]
        return result
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部: DB 構築
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildAllCombos(): List<ValidCombo> {
        val result = mutableListOf<ValidCombo>()

        for ((kindIdx, tbl) in KIND_TABLES) {

            // ── スキル1 ループ ────────────────────────────────────────────────
            for (s1Local in tbl.skill1.indices) {
                val s1g    = tbl.skill1[s1Local]
                val s1Name = SKILL_NAMES.getOrElse(s1g) { "?" }.replace("　", "").trim()
                val sp1    = tbl.sp1[s1Local]
                val sp1lo  = minOf(sp1[0], sp1[1])
                val sp1hi  = maxOf(sp1[0], sp1[1])
                val sp1hiV = sp1[1]     // fill 計算用（常に [1]）

                for (s1pts in sp1lo..sp1hi) {

                    // ──── スキル2なし ─────────────────────────────────────────
                    // fill = s1pts * 10 / sp1hiV  (sp2hi が約分されて消える)
                    val fillNo = (s1pts * 10) / sp1hiV
                    result += ValidCombo(
                        kindIdx      = kindIdx,
                        kindLabel    = tbl.label,
                        skill1GlobalIdx = s1g,
                        skill1Name   = s1Name,
                        skill1Pts    = s1pts,
                        skill2GlobalIdx = -1,
                        skill2Name   = null,
                        skill2Pts    = 0,
                        fill         = fillNo.coerceIn(1, 20),
                        possibleSlots = possibleSlotsFor(tbl, fillNo)
                    )

                    // ──── スキル2あり ─────────────────────────────────────────
                    // なぞのお守り (th=100) はスキル2が出現しない
                    if (tbl.th >= 100) continue

                    for (s2Local in tbl.skill2.indices) {
                        val s2g = tbl.skill2[s2Local]
                        if (s2g == s1g) continue   // 衝突スキップ

                        val s2Name = SKILL_NAMES.getOrElse(s2g) { "?" }.replace("　", "").trim()
                        val sp2hiV = tbl.sp2[s2Local][1]  // fill 計算用（常に [1]）

                        // 正ポイント範囲: 1 〜 sp2hiV
                        for (s2pts in 1..sp2hiV) {
                            val fill = computeFill(sp1hiV, sp2hiV, s1pts, s2pts)
                            result += ValidCombo(
                                kindIdx      = kindIdx,
                                kindLabel    = tbl.label,
                                skill1GlobalIdx = s1g,
                                skill1Name   = s1Name,
                                skill1Pts    = s1pts,
                                skill2GlobalIdx = s2g,
                                skill2Name   = s2Name,
                                skill2Pts    = s2pts,
                                fill         = fill.coerceIn(1, 20),
                                possibleSlots = possibleSlotsFor(tbl, fill)
                            )
                        }
                    }
                }
            }
        }

        return result
    }
}
