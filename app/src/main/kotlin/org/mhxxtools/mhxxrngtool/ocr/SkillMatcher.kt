package org.mhxxtools.mhxxrngtool.ocr

import org.mhxxtools.mhxxrngtool.rng.SKILL_NAMES

// ======================================================================
// OCR誤読を含むすべての表記 → SKILL_NAMES グローバルインデックス
// HTMLツール snipe_integrated.html の SKILL_ALIASES を移植・拡張
//
// MHXX NS の鑑定結果画面でよく起こる誤読パターン:
//   撃 → 擊 / 击  (旧字体・簡体字混在)
//   固有スキル1/2 ラベル → スキップ対象
//   スロット ○○○ → ● or O 等で誤読されるが extractSlotCandidates が吸収
// ======================================================================
val SKILL_ALIASES: Map<String, Int> = mapOf(
    "毒" to 0, "麻痺" to 1, "睡眠" to 2, "気絶" to 3,
    "聴覚" to 4, "聴覚保護" to 4,
    "風圧" to 5, "耐震" to 6,
    "だる" to 7, "だるま" to 7,
    "耐暑" to 8, "耐寒" to 9,
    "寒冷" to 10, "炎熱" to 11,
    "盗み" to 12, "対防" to 13,
    "狂撃" to 14, "狂擊" to 14, "狂击" to 14,
    "細菌" to 15, "裂傷" to 16,
    "攻撃" to 17, "攻擊" to 17, "攻击" to 17,
    "防御" to 18, "体力" to 19,
    "火耐" to 20, "水耐" to 21, "雷耐" to 22, "氷耐" to 23, "龍耐" to 24, "属耐" to 25,
    "火攻" to 26, "水攻" to 27, "雷攻" to 28, "氷攻" to 29, "龍攻" to 30, "属攻" to 31,
    "特攻" to 32, "特殊攻撃" to 32,
    "研師" to 33, "研ぎ師" to 33,
    "匠" to 34,
    "斬味" to 35, "斬れ味" to 35,
    "剣術" to 36,
    "研磨" to 37, "研磨術" to 37,
    "鈍器" to 38,
    "抜会" to 39, "抜刀会心" to 39,
    "抜減" to 40, "抜刀減気" to 40, "抜刀減氣" to 40, "抜刀 減気" to 40, "抜 刀減気" to 40,
    "納刀" to 41, "納 刀" to 41, "納刀 " to 41, "納研" to 42, "納刀研磨" to 42,
    "刃鱗" to 43, "刃鳞" to 43,
    "装速" to 44, "装填速度" to 44,
    "反動" to 45,
    "精密" to 46, "精密射撃" to 46,
    "通強" to 47, "通常弾強化" to 47,
    "貫強" to 48, "貫通弾強化" to 48,
    "散強" to 49, "散弾強化" to 49,
    "重強" to 50, "重撃弾強化" to 50,
    "通追" to 51, "貫追" to 52, "散追" to 53, "榴追" to 54, "拡追" to 55,
    "毒追" to 56, "麻追" to 57, "睡追" to 58, "強追" to 59,
    "属追" to 60, "接追" to 61, "減追" to 62, "爆追" to 63,
    "速射" to 64, "射法" to 65,
    "装数" to 66, "装填数" to 66,
    "変則" to 67, "変則射撃" to 67,
    "弾節" to 68, "弹節" to 68, "弾薬節約" to 68,
    "達人" to 69,
    "痛撃" to 70, "痛擊" to 70, "痛击" to 70,
    "連撃" to 71, "连撃" to 71,
    "特会" to 72, "特殊会心" to 72,
    "属会" to 73, "属性会心" to 73,
    "会心" to 74, "会心強化" to 74,
    "裏会" to 75, "裏会心" to 75,
    "溜短" to 76, "溜め短縮" to 76,
    "スタ" to 77, "スタミナ" to 77,
    "体術" to 78,
    "気力" to 79, "気力回復" to 79,
    "走行" to 80, "走行継続" to 80,
    "回性" to 81, "回避性能" to 81,
    "回距" to 82, "回避距離" to 82,
    "泡沫" to 83,
    "ガ性" to 84, "ガード性能" to 84,
    "ガ強" to 85, "ガード強化" to 85,
    "KO" to 86, "ＫＯ" to 86,
    "減攻" to 87, "減気攻撃" to 87,
    "笛" to 88, "砲術" to 89,
    "重撃" to 90, "重擊" to 90,
    "爆弾" to 91, "爆弹" to 91, "爆弾強化" to 91,
    "本気" to 92, "闘魂" to 93, "無傷" to 94,
    "チャ" to 95, "チャンス" to 95,
    "龍気" to 96, "底力" to 97, "逆境" to 98, "逆上" to 99,
    "窮地" to 100, "根性" to 101, "気配" to 102, "采配" to 103, "号令" to 104,
    "乗り" to 105, "跳躍" to 106, "無心" to 107, "我慢" to 108,
    "SP" to 109, "ＳＰ" to 109, "SP延長" to 109,
    "千里" to 110, "千里眼" to 110,
    "観察" to 111, "観察眼" to 111,
    "狩人" to 112, "運搬" to 113, "加護" to 114,
    "英雄" to 115, "英雄の盾" to 115,
    "回量" to 116, "回復量" to 116,
    "回速" to 117, "回復速度" to 117,
    "効果" to 118, "効果持続" to 118,
    "広域" to 119,
    "腹減" to 120, "腹減り" to 120,
    "食い" to 121, "食いしん坊" to 121,
    "食事" to 122, "節食" to 123, "肉食" to 124, "茸食" to 125,
    "野草" to 126, "野草知識" to 126,
    "調成" to 127, "調合成功率" to 127,
    "調数" to 128, "調合数" to 128,
    "高速" to 129, "高速設置" to 129,
    "採取" to 130,
    "ハチ" to 131, "ハチミツ" to 131,
    "護石王" to 132,
    "気ま" to 133, "気まぐれ" to 133,
    "運気" to 134,
    "剥取" to 135, "剥ぎ取り" to 135,
    "捕獲" to 136,
    "ベル" to 137, "ベルナ" to 137,
    "ここ" to 138, "ここっと" to 138,
    "ポッ" to 139, "ポッケ" to 139,
    "ユク" to 140, "ユクモ" to 140,
    "龍識" to 141, "龍識船" to 141,
    "飛行" to 142, "飛行酒場" to 142,
    "紅兜" to 143,
    "大雪" to 144, "大雪主" to 144,
    "矛砕" to 145, "岩穿" to 146,
    "紫毒" to 147, "紫毒姫" to 147,
    "宝纏" to 148,
    "白疾" to 149, "白疾風" to 149,
    "隻眼" to 150,
    "黒炎" to 151, "黑炎" to 151, "黒炎王" to 151,
    "金雷" to 152, "金雷公" to 152,
    "荒鉤" to 153, "荒鉤爪" to 153,
    "燼滅" to 154, "燼滅刃" to 154,
    "朧隠" to 155, "鎧裂" to 156, "天眼" to 157,
    "青電" to 158, "青電主" to 158,
    "銀嶺" to 159, "鏖魔" to 160,
    "真紅" to 161, "真・紅兜" to 161,
    "真大" to 162, "真・大雪主" to 162,
    "真矛" to 163, "真・矛砕" to 163,
    "真岩" to 164, "真・岩穿" to 164,
    "真紫" to 165, "真・紫毒姫" to 165,
    "真宝" to 166, "真・宝纏" to 166,
    "真白" to 167, "真・白疾風" to 167,
    "真隻" to 168, "真・隻眼" to 168,
    "真黒" to 169, "真黑" to 169, "真・黒炎王" to 169,
    "真金" to 170, "真・金雷公" to 170,
    "真荒" to 171, "真・荒鉤爪" to 171,
    "真燼" to 172, "真・燼滅刃" to 172,
    "真朧" to 173, "真膽" to 173, "真・朧隠" to 173,
    "真鎧" to 174, "真・鎧裂" to 174,
    "真天" to 175, "真・天眼" to 175,
    "真青" to 176, "真・青電主" to 176,
    "真銀" to 177, "真银" to 177, "真・銀嶺" to 177,
    "真鏖" to 178, "真座" to 178, "真・鏖魔" to 178,
    "北辰" to 179, "北辰納豆流" to 179,
    "斬術" to 180, "食欲" to 181, "職工" to 182, "剛腕" to 183, "祈願" to 184,
    "裏稼" to 185, "裏稼業" to 185,
    "刀匠" to 186, "射手" to 187,
    "状態" to 188, "状態耐性" to 188,
    "怒" to 189, "怒り" to 189,
    "回術" to 190, "回避術" to 190,
    "居合" to 191, "頑強" to 192,
    "剛撃" to 193, "刚擎" to 193, "刚撃" to 193, "剛擎" to 193,
    "盾持" to 194,
    "潔癖" to 195,
    "増幅" to 196, "增幅" to 196,
    "護収" to 197, "護石収集" to 197,
    "強欲" to 198,
    "対鋼" to 199, "対銅" to 199, "対鋼龍" to 199,
    "対霞" to 200, "対霞龍" to 200,
    "対炎" to 201, "対炎龍" to 201,
    "胴倍" to 202, "酮倍" to 202, "胴系統倍加" to 202, "胴系" to 202,
    "秘術" to 203,
    "護強" to 204, "護石強化" to 204
)

data class SkillCandidate(
    val localIdx: Int,
    val name: String,
    val score: Float,
    val position: Int,
    val points: Int?,
    val pointsInRange: Boolean?
)

data class ScanResult(
    val rawText: String,
    val normalizedText: String,
    val skill1Candidates: List<SkillCandidate>,
    val skill2Candidates: List<SkillCandidate>,
    val slotCandidates: List<Int>
) {
    val bestSkill1: SkillCandidate? get() = skill1Candidates.firstOrNull()
    val bestSkill2: SkillCandidate? get() = skill2Candidates.firstOrNull()
    val bestSlot: Int? get() = slotCandidates.firstOrNull()
}

object SkillMatcher {

    private val ZEN2HAN = mapOf(
        '０' to '0','１' to '1','２' to '2','３' to '3','４' to '4',
        '５' to '5','６' to '6','７' to '7','８' to '8','９' to '9',
        '＋' to '+'
    )
    private val NOISE_RE = Regex("[|｜。、・:：;；]")

    // ── テキスト正規化 ────────────────────────────────────────────────────────
    fun normalizeText(text: String): String {
        var s = text.map { ZEN2HAN[it] ?: it }.joinToString("")
        s = NOISE_RE.replace(s, " ")
        s = s.replace(Regex("[\r\n]+"), " ")
        s = s.replace(Regex("[ \t　]+"), " ")   // 全角スペース(U+3000)も半角に統一
        return s.trim()
    }

    // ── スキル名の正規化 (全角スペース除去 + trim) ───────────────────────────
    // SKILL_NAMES の "匠　" "毒　" 等に含まれる全角スペースを除去する
    internal fun normalizeNameForSearch(name: String): String =
        name.replace("　", "").trim()

    // ── IntRange 重複チェック ─────────────────────────────────────────────────
    private fun IntRange.overlaps(other: IntRange) =
        first <= other.last && last >= other.first

    // ======================================================================
    // グローバルスキルインデックス検索 (HTMLツールの findSkillIdxByOcr を完全移植)
    //
    // OCR で読み取った生テキストから SKILL_NAMES のグローバルインデックスを返す。
    // snipe_integrated.html の findSkillIdxByOcr() と同じ 5ステップ方式:
    //   Step 1. SKILL_NAMES 完全一致
    //   Step 2. SKILL_ALIASES 完全一致 (OCR誤読バリアント含む)
    //   Step 3. スキル名が OCRテキストを含む  例: "斬れ" → "斬れ味", "溜め" → "溜め短縮"
    //   Step 4. OCRテキストがスキル名を含む  例: 長めのOCR誤読 → 正規スキル名
    //   Step 5. SKILL_ALIASES 両方向包含チェック
    // ======================================================================
    internal fun findSkillGlobalIdx(rawName: String): Int {
        val n = normalizeNameForSearch(rawName)
        if (n.isEmpty()) return -1

        // Step 1: SKILL_NAMES 完全一致 (HTML Step1 互換)
        SKILL_NAMES.forEachIndexed { i, sn ->
            if (normalizeNameForSearch(sn) == n) return i
        }

        // Step 2: SKILL_ALIASES 完全一致 (HTML Step2 互換 / OCR誤読バリアント含む)
        SKILL_ALIASES[n]?.let { return it }

        // Step 3: スキル名が OCRテキストを含む (HTML Step3 互換)
        // 例: n="斬れ" → norm="斬れ味" → norm.contains("斬れ") = true
        // 例: n="溜め" → norm="溜め短縮" → norm.contains("溜め") = true
        SKILL_NAMES.forEachIndexed { i, sn ->
            val norm = normalizeNameForSearch(sn)
            if (norm.length >= 2 && norm.contains(n)) return i
        }

        // Step 4: OCRテキストがスキル名を含む (HTML Step4 互換)
        // 例: n="斬れ味スキル" → norm="斬れ味" → n.contains("斬れ味") = true
        if (n.length >= 2) {
            SKILL_NAMES.forEachIndexed { i, sn ->
                val norm = normalizeNameForSearch(sn)
                if (norm.length >= 2 && n.contains(norm)) return i
            }
        }

        // Step 5: SKILL_ALIASES 両方向包含チェック (HTML Step5 互換)
        // 第1節: OCRテキストがエイリアスを含む  例: n="痛撃123" → alias="痛撃"
        // 第2節: エイリアスが OCRテキストを含む  例: n="痛" → alias="痛撃" (HTML互換追加)
        if (n.length >= 2) {
            for ((alias, si) in SKILL_ALIASES) {
                if (alias.length >= 2 && n.contains(alias)) return si
                if (alias.length >= 2 && alias.contains(n)) return si
            }
        }

        return -1
    }

    // ======================================================================
    // 検索ワードマップの構築 (HTMLツールの方式を移植)
    //
    // skill1Names/skill2Names それぞれのスキル名に加え、
    // SKILL_ALIASES で同じグローバルインデックスを持つすべての表記を
    // 検索対象として追加する。
    // これにより OCR が「痛擊」と誤読しても「痛撃」として正しく認識できる。
    //
    // key: 正規化済み検索文字列
    // value: (skill1LocalIdx?, skill2LocalIdx?)
    // ======================================================================
    private data class LocalIdxPair(val s1: Int?, val s2: Int?)

    private fun buildSearchTermMap(
        skill1Names: List<String>,
        skill2Names: List<String>
    ): Map<String, LocalIdxPair> {
        val map = mutableMapOf<String, LocalIdxPair>()

        // ── 基本名 (種類テーブルのスキル名) を登録 ──
        skill1Names.forEachIndexed { localIdx, name ->
            val k = normalizeNameForSearch(name)
            if (k.isNotEmpty()) map[k] = LocalIdxPair(localIdx, map[k]?.s2)
        }
        skill2Names.forEachIndexed { localIdx, name ->
            val k = normalizeNameForSearch(name)
            if (k.isNotEmpty()) map[k] = LocalIdxPair(map[k]?.s1, localIdx)
        }

        // ── SKILL_ALIASES の誤読バリアントを追加 ──
        // 各スキル名のグローバルインデックスを求め、同じグローバルインデックスを
        // 持つすべてのエイリアスも検索対象に追加する
        skill1Names.forEachIndexed { localIdx, name ->
            val globalIdx = findSkillGlobalIdx(name)
            if (globalIdx < 0) return@forEachIndexed
            for ((alias, aliasGlobal) in SKILL_ALIASES) {
                if (aliasGlobal != globalIdx) continue
                val k = normalizeNameForSearch(alias)
                if (k.isEmpty()) continue
                val ex = map[k]
                if (ex == null || ex.s1 == null) {
                    map[k] = LocalIdxPair(localIdx, ex?.s2)
                }
            }
        }
        skill2Names.forEachIndexed { localIdx, name ->
            val globalIdx = findSkillGlobalIdx(name)
            if (globalIdx < 0) return@forEachIndexed
            for ((alias, aliasGlobal) in SKILL_ALIASES) {
                if (aliasGlobal != globalIdx) continue
                val k = normalizeNameForSearch(alias)
                if (k.isEmpty()) continue
                val ex = map[k]
                if (ex == null || ex.s2 == null) {
                    map[k] = LocalIdxPair(ex?.s1, localIdx)
                }
            }
        }

        return map
    }

    // ── 近傍の SP (スキルポイント) を抽出 ────────────────────────────────────
    fun extractNumberNear(text: String, start: Int, window: Int = 30): Int? {
        if (start >= text.length) return null
        val snippet = text.substring(start, minOf(start + window, text.length))
        // "+N" 優先 (OCR が「ト6」のように誤読しても fallback で数字だけ拾う)
        return Regex("\\+\\s*(\\d{1,2})").find(snippet)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("(?<![0-9])(\\d{1,2})(?![0-9])").find(snippet)?.groupValues?.get(1)?.toIntOrNull()
    }

    // ── SkillCandidate 生成ヘルパー ───────────────────────────────────────────
    private fun makeCandidate(
        localIdx: Int, name: String, spTable: Array<IntArray>,
        pos: Int, sp: Int?, score: Float = 1.0f
    ): SkillCandidate {
        val spRow = spTable[localIdx]
        val lo = minOf(spRow[0], spRow[1]); val hi = maxOf(spRow[0], spRow[1])
        return SkillCandidate(
            localIdx = localIdx, name = name,
            score = score, position = pos, points = sp,
            pointsInRange = sp?.let { it in lo..hi }
        )
    }

    // ======================================================================
    // Step 0: 既知スキル名 + SKILL_ALIASES によるサブストリング直接検索
    //
    // HTMLツールの findSkillIdxByOcr を Android / Kotlin に移植した方式。
    // buildSearchTermMap() で「正規化スキル名 + OCR誤読エイリアス」を
    // 全て列挙し、OCR正規化テキストを直接サーチする。
    //   - 完全一致で見つかる → score=1.0 (最高精度)
    //   - 1文字スキル (匠・毒 等) も正しく検出可能
    //   - OCR誤読バリアント (痛擊→痛撃 等) も自動吸収
    // ======================================================================
    private data class NamePos(
        val pos: Int, val normName: String, val sp: Int?,
        val s1Idx: Int?, val s2Idx: Int?,
        val displayName: String   // skill1Names/skill2Names の元の表記
    )

    private fun directNameSearch(
        normalized: String,
        skill1Names: List<String>, skill1Sp: Array<IntArray>,
        skill2Names: List<String>, skill2Sp: Array<IntArray>
    ): Pair<List<SkillCandidate>, List<SkillCandidate>> {

        val termMap = buildSearchTermMap(skill1Names, skill2Names)

        // 長い検索ワードを優先 (短い名前の誤前置詞マッチを防ぐ)
        val found = mutableListOf<NamePos>()
        val usedRanges = mutableListOf<IntRange>()

        for ((k, pair) in termMap.entries.sortedByDescending { it.key.length }) {
            if (k.isEmpty()) continue
            var searchFrom = 0
            while (searchFrom <= normalized.length - k.length) {
                val pos = normalized.indexOf(k, searchFrom)
                if (pos < 0) break
                val range = pos until pos + k.length

                // 単語境界チェック: マッチ直後が空白 / '+' / 数字 / 末尾
                // → 「毒追+5」中の「毒」を誤検出しない
                val afterPos = pos + k.length
                val afterChar = if (afterPos < normalized.length) normalized[afterPos] else ' '
                val validBoundary = afterChar == ' ' || afterChar == '+' ||
                    afterChar.isDigit() || afterPos >= normalized.length
                if (!validBoundary) { searchFrom = pos + 1; continue }

                // 既存マッチと重複しない場合のみ登録
                if (usedRanges.none { it.overlaps(range) }) {
                    val sp = extractNumberNear(normalized, afterPos)
                    // displayName: s1またはs2の元名を取得
                    val dispName = when {
                        pair.s1 != null -> skill1Names[pair.s1]
                        pair.s2 != null -> skill2Names[pair.s2]
                        else -> k
                    }
                    found.add(NamePos(pos, k, sp, pair.s1, pair.s2, dispName))
                    usedRanges.add(range)
                }
                searchFrom = pos + 1
            }
        }

        // テキスト中の出現位置順にソート (画面上の上から順 = skill1が先)
        found.sortBy { it.pos }

        val s1Cands = mutableListOf<SkillCandidate>()
        val s2Cands = mutableListOf<SkillCandidate>()

        for ((i, nm) in found.withIndex()) {
            when {
                i == 0 && nm.s1Idx != null -> {
                    // 最初に見つかったスキル → skill1候補
                    s1Cands.add(makeCandidate(nm.s1Idx, skill1Names[nm.s1Idx], skill1Sp, nm.pos, nm.sp))
                }
                i == 0 && nm.s2Idx != null -> {
                    // skill1には無いがskill2にある → skill2候補に登録
                    s2Cands.add(makeCandidate(nm.s2Idx, skill2Names[nm.s2Idx], skill2Sp, nm.pos, nm.sp))
                }
                i == 1 && nm.s2Idx != null -> {
                    // 2番目に見つかったスキル → skill2候補
                    s2Cands.add(makeCandidate(nm.s2Idx, skill2Names[nm.s2Idx], skill2Sp, nm.pos, nm.sp))
                }
                i == 1 && nm.s2Idx == null && nm.s1Idx != null && s1Cands.isEmpty() -> {
                    s1Cands.add(makeCandidate(nm.s1Idx, skill1Names[nm.s1Idx], skill1Sp, nm.pos, nm.sp))
                }
                i >= 2 -> break
            }
        }

        return s1Cands to s2Cands
    }

    // ======================================================================
    // Step 1: "スキル名 +ポイント" ペアを正規表現で抽出して SKILL_ALIASES でマッチ
    //
    // HTMLツールの Pattern1/Pattern2 + findSkillIdxByOcr をベースにした方式。
    // Step 0 が失敗した場合 (既知名がそのままでは見つからない) のフォールバック。
    // ======================================================================
    private fun extractSkillPointPairs(text: String): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        // HTMLツールの re1 相当: スキル名[+]数値 が連続 (1〜6文字、1文字スキルも対応)
        val re1 = Regex("""([^\d\s+]{1,6})[+＋]?(\d{1,2})""")
        re1.findAll(text).forEach { m ->
            val name = m.groupValues[1].trim()
            val pts  = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (name.isNotBlank() && pts in 1..20) result.add(name to pts)
        }
        // HTMLツールの re2 相当: スキル名 [空白] 数値
        val re2 = Regex("""([^\d\s+]{1,6})\s+(\d{1,2})""")
        re2.findAll(text).forEach { m ->
            val name = m.groupValues[1].trim()
            val pts  = m.groupValues[2].toIntOrNull() ?: return@forEach
            if (name.isBlank() || pts !in 1..20) return@forEach
            if (result.none { it.first == name }) result.add(name to pts)
        }
        return result
    }

    // SKILL_ALIASES を活用したペアマッチング (HTML findSkillIdxByOcr + 種類テーブル変換)
    private fun matchPairByAlias(
        pairName: String, pairPts: Int,
        names: List<String>, spTable: Array<IntArray>
    ): List<SkillCandidate> {
        val n = normalizeNameForSearch(pairName)

        // グローバルインデックスを求める
        val globalIdx = findSkillGlobalIdx(n)

        // グローバルインデックスが得られた場合: names リストの中から対応するローカルインデックスを探す
        if (globalIdx >= 0) {
            val globalNorm = normalizeNameForSearch(SKILL_NAMES.getOrElse(globalIdx) { "" })
            names.forEachIndexed { localIdx, name ->
                if (normalizeNameForSearch(name) == globalNorm) {
                    val spRow = spTable[localIdx]
                    val lo = minOf(spRow[0], spRow[1]); val hi = maxOf(spRow[0], spRow[1])
                    return listOf(SkillCandidate(
                        localIdx = localIdx, name = names[localIdx],
                        score = 0.95f, position = 0, points = pairPts,
                        pointsInRange = pairPts in lo..hi
                    ))
                }
            }
        }

        // グローバルインデックスが得られない場合: 従来のファジーマッチ (閾値高め)
        return names.indices.mapNotNull { idx ->
            val norm = normalizeNameForSearch(names[idx])
            if (norm.isEmpty()) return@mapNotNull null
            val score = when {
                n == norm                          -> 1.0f
                n.contains(norm) && norm.length >= 2 -> 0.85f
                n.contains(norm) && norm.length >= 2 -> 0.80f
                else -> {
                    val sim = similarity(n, norm)
                    if (sim < 0.75f) return@mapNotNull null
                    sim
                }
            }
            makeCandidate(idx, names[idx], spTable, 0, pairPts, score)
        }.sortedByDescending { it.score }
    }

    // ── 類似度計算 (LCS比率) ─────────────────────────────────────────────────
    private fun similarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (a.isEmpty() || b.isEmpty()) return 0.0f
        var matches = 0
        val used = BooleanArray(b.length)
        for (ca in a) {
            val idx = b.indices.firstOrNull { !used[it] && b[it] == ca }
            if (idx != null) { matches++; used[idx] = true }
        }
        return 2.0f * matches / (a.length + b.length)
    }

    // ======================================================================
    // Step 2: ウィンドウベースマッチング (最終フォールバック)
    // ======================================================================
    private fun bestWindowScore(name: String, text: String): Pair<Float, Int> {
        var bestScore = 0.0f; var bestPos = -1
        for (wlen in intArrayOf(name.length, name.length - 1, name.length + 1)) {
            if (wlen <= 0) continue
            for (i in 0..maxOf(0, text.length - wlen)) {
                val window = text.substring(i, minOf(i + wlen, text.length))
                if (window.isEmpty()) continue
                val score = similarity(name, window)
                if (score > bestScore) { bestScore = score; bestPos = i }
            }
        }
        return bestScore to bestPos
    }

    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun findSingleBest(text: String, allNames: List<String>, threshold: Float = 0.72f)
        : Quadruple<Float, Int, Int, String>? {
        var best: Quadruple<Float, Int, Int, String>? = null
        for (name in allNames) {
            val n = normalizeNameForSearch(name); if (n.isEmpty()) continue
            val exactPos = text.indexOf(n)
            val (score, pos) = if (exactPos >= 0) 1.0f to exactPos else bestWindowScore(n, text)
            if (pos < 0 || score < threshold) continue
            if (best == null || score > best.first || (score == best.first && n.length > best.fourth.length))
                best = Quadruple(score, pos, pos + n.length, n)
        }
        return best
    }

    private fun locateCandidateSpans(text: String, allNames: List<String>, maxSpans: Int = 2)
        : List<Triple<Int, Int, Float>> {
        val spans = mutableListOf<Triple<Int, Int, Float>>()
        var remaining = text
        repeat(maxSpans) {
            val best = findSingleBest(remaining, allNames) ?: return@repeat
            spans.add(Triple(best.second, best.third, best.first))
            remaining = remaining.substring(0, best.second) +
                " ".repeat(best.third - best.second) + remaining.substring(best.third)
        }
        return spans.sortedBy { it.first }
    }

    private fun classifySpan(spanText: String, names: List<String>, threshold: Float = 0.68f)
        : List<Pair<Int, Float>> {
        return names.indices
            .map { idx ->
                val n = normalizeNameForSearch(names[idx])
                val score = when {
                    n == spanText -> 1.0f
                    findSkillGlobalIdx(spanText) >= 0 &&
                        findSkillGlobalIdx(spanText) == findSkillGlobalIdx(n) -> 0.95f
                    else -> similarity(n, spanText)
                }
                idx to score
            }
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
    }

    private fun candidatesForSpan(
        spanStart: Int, spanEnd: Int, fullText: String,
        names: List<String>, spTable: Array<IntArray>, topN: Int = 3
    ): List<SkillCandidate> {
        val spanText = normalizeNameForSearch(fullText.substring(spanStart, spanEnd))
        val pts = extractNumberNear(fullText, spanEnd)
        return classifySpan(spanText, names).take(topN).map { (idx, score) ->
            makeCandidate(idx, names[idx], spTable, spanStart, pts, score)
        }
    }

    // ── スロット候補を抽出 ────────────────────────────────────────────────────
    fun extractSlotCandidates(text: String): List<Int> {
        val unified = text.replace(Regex("[〇○◯ＯOｏo◎●◉◯０Qq]"), "◯")
        // スロット行だけ（全文の装飾○は使わない）
        val slotLine = Regex("スロ(?:ット)?[^\\n]{0,60}").find(unified)?.value ?: ""
        if (slotLine.isEmpty()) return emptyList()

        Regex("([0]{2,3})(?![0-9])").find(slotLine)?.let {
            return listOf(it.groupValues[1].length.coerceIn(2, 3))
        }

        val candidates = mutableListOf<Int>()
        Regex("◯{1,3}").findAll(slotLine).forEach {
            val v = it.value.length.coerceIn(1, 3)
            if (v !in candidates) candidates.add(v)
        }
        candidates.sortDescending()
        if (candidates.isNotEmpty()) return candidates

        if (Regex("(?:[-－─—–―_]\\s*){2,}").containsMatchIn(slotLine)) return listOf(0)
        if (Regex("なし|無").containsMatchIn(slotLine)) return listOf(0)

        Regex("スロット\\s*[:：.]?\\s*([0-3])(?![0-9])").find(slotLine)?.let {
            return listOf(it.groupValues[1].toInt())
        }
        return candidates
    }

    // ======================================================================
    // 「固有スキル1/2」ラベルをアンカーにしてスキルを抽出 (Step -1)
    //
    // MHXX NS 鑑定画面には必ず「固有スキル1」「固有スキル2」ラベルがある。
    // これを起点にすることで、OCR が列型・行型どちらで読んでも対応できる。
    // 「固」→「国」誤読にも対応。
    // ======================================================================
    private fun extractBySkillLabels(
        normalized: String,
        skill1Names: List<String>, skill1Sp: Array<IntArray>,
        skill2Names: List<String>, skill2Sp: Array<IntArray>
    ): Pair<List<SkillCandidate>, List<SkillCandidate>> {

        val re = Regex("(?:固|国)有スキル\\s*([12１２])")
        val labels = re.findAll(normalized).associate {
            (if (it.groupValues[1] in listOf("1","１")) 1 else 2) to it.range.last
        }
        val l1 = labels[1] ?: return emptyList<SkillCandidate>() to emptyList()
        val l2 = labels[2]
        val slotPos = Regex("スロ(?:ット)?").find(normalized)?.range?.first ?: normalized.length
        val afterAll = minOf(slotPos + 60, normalized.length)

        fun findInSeg(
            from: Int, to: Int,
            names: List<String>, spTable: Array<IntArray>
        ): SkillCandidate? {
            if (from >= to) return null
            val seg = normalized.substring(from, minOf(to, normalized.length))
            for (i in names.indices) {
                val n = normalizeNameForSearch(names[i]); if (n.isEmpty()) continue
                val pos = seg.indexOf(n)
                if (pos >= 0) {
                    val sp = extractNumberNear(seg, pos + n.length)
                    val sr = spTable[i]; val lo = minOf(sr[0],sr[1]); val hi = maxOf(sr[0],sr[1])
                    return SkillCandidate(i, names[i], 1.0f, from + pos, sp, sp?.let { it in lo..hi })
                }
                val gIdx = findSkillGlobalIdx(n); if (gIdx < 0) continue
                for ((alias, ag) in SKILL_ALIASES) {
                    if (ag != gIdx) continue
                    val an = normalizeNameForSearch(alias); val ap = seg.indexOf(an)
                    if (ap >= 0) {
                        val sp = extractNumberNear(seg, ap + an.length)
                        val sr = spTable[i]; val lo = minOf(sr[0],sr[1]); val hi = maxOf(sr[0],sr[1])
                        return SkillCandidate(i, names[i], 0.95f, from + ap, sp, sp?.let { it in lo..hi })
                    }
                }
            }
            return null
        }

        val s1End = (l2 ?: slotPos).coerceAtMost(normalized.length)
        // ラベル直後〜ラベル2の狭い範囲のみ検索する (行型OCR対応)
        // 列型OCR ("固有スキル1 固有スキル2 痛撃 達人 ト6 +10") の場合は
        // ここでは見つからず Step 0 (assignSpByOrder) に委ねる
        val s1 = findInSeg(l1 + 1, s1End, skill1Names, skill1Sp)
        val s2 = if (l2 != null) findInSeg(l2 + 1, afterAll, skill2Names, skill2Sp) else null

        return (if (s1 != null) listOf(s1) else emptyList()) to
               (if (s2 != null) listOf(s2) else emptyList())
    }

    // ======================================================================
    // SP を出現順でスキルに割り当てる (列型OCR対策)
    //
    // 「痛撃 達人 ト6 +10」のように SP がスキル名の後ろにまとめて来る場合、
    // テキスト上の出現順で 1番目スキル→1番目SP、2番目→2番目SP と割り当てる。
    // 「ト」は「+」の OCR 誤読として扱う。
    // ======================================================================
    private fun assignSpByOrder(
        found: List<NamePos>, normalized: String,
        skill1Names: List<String>, skill1Sp: Array<IntArray>,
        skill2Names: List<String>, skill2Sp: Array<IntArray>
    ): Pair<List<SkillCandidate>, List<SkillCandidate>> {

        // 「+N」または「トN」を全て位置順に収集 (SP範囲 1〜20)
        val spList = Regex("[+＋ト]\\s*(\\d{1,2})")
            .findAll(normalized)
            .mapNotNull { m -> m.groupValues[1].toIntOrNull()?.let { sp ->
                if (sp in 1..20) m.range.first to sp else null
            }}
            .sortedBy { it.first }
            .toList()

        fun make(nm: NamePos, spOv: Int?, isS1: Boolean): SkillCandidate? {
            val idx   = if (isS1) nm.s1Idx else nm.s2Idx ?: return null
            val names = if (isS1) skill1Names else skill2Names
            val spt   = if (isS1) skill1Sp    else skill2Sp
            if (idx == null) return null
            val sp    = spOv ?: nm.sp
            val sr    = spt[idx]; val lo = minOf(sr[0],sr[1]); val hi = maxOf(sr[0],sr[1])
            return SkillCandidate(idx, names[idx], 1.0f, nm.pos, sp, sp?.let { it in lo..hi })
        }

        val s1 = mutableListOf<SkillCandidate>()
        val s2 = mutableListOf<SkillCandidate>()
        for ((i, nm) in found.withIndex()) {
            val ordSp = spList.getOrNull(i)?.second
            when {
                i == 0 && nm.s1Idx != null -> make(nm, ordSp, true)?.let  { s1.add(it) }
                i == 0 && nm.s2Idx != null -> make(nm, ordSp, false)?.let { s2.add(it) }
                i == 1 && nm.s2Idx != null -> make(nm, ordSp, false)?.let { s2.add(it) }
                i == 1 && nm.s2Idx == null && nm.s1Idx != null && s1.isEmpty()
                                           -> make(nm, ordSp, true)?.let  { s1.add(it) }
                i >= 2 -> break
            }
        }
        return s1 to s2
    }

    // ======================================================================
    // メインスキャン
    //
    // 優先順位:
    //   Step-1: 「固有スキル1/2」ラベル検出 (最優先・最も確実)
    //   Step 0: SKILL_ALIASES サブストリング検索 + SP出現順割り当て
    //   Step 1: "+N" 抽出 + SKILL_ALIASES グローバルインデックス変換
    //   Step 2: ウィンドウ近似マッチ (最終フォールバック)
    // ======================================================================
    fun scan(
        rawText: String,
        skill1Names: List<String>, skill1Sp: Array<IntArray>,
        skill2Names: List<String>, skill2Sp: Array<IntArray>
    ): ScanResult {
        val normalized = normalizeText(rawText)
        val slots = extractSlotCandidates(normalized)

        // ── Step -1: 「固有スキル1/2」ラベルをアンカーにした抽出 ─────────────
        val (s1Label, s2Label) = extractBySkillLabels(
            normalized, skill1Names, skill1Sp, skill2Names, skill2Sp
        )
        if (s1Label.isNotEmpty()) {
            return ScanResult(rawText, normalized, s1Label, s2Label, slots)
        }

        // ── Step 0: SKILL_ALIASES サブストリング検索 + SP出現順割り当て ────────
        val termMap = buildSearchTermMap(skill1Names, skill2Names)
        val foundList = mutableListOf<NamePos>()
        val usedRanges = mutableListOf<IntRange>()
        for ((k, pair) in termMap.entries.sortedByDescending { it.key.length }) {
            if (k.isEmpty()) continue
            var sf = 0
            while (sf <= normalized.length - k.length) {
                val pos = normalized.indexOf(k, sf); if (pos < 0) break
                val range = pos until pos + k.length
                val afterPos = pos + k.length
                val afterChar = if (afterPos < normalized.length) normalized[afterPos] else ' '
                val ok = afterChar == ' ' || afterChar == '+' || afterChar.isDigit() || afterPos >= normalized.length
                if (!ok) { sf = pos + 1; continue }
                if (usedRanges.none { it.overlaps(range) }) {
                    val sp = extractNumberNear(normalized, afterPos)
                    val disp = when { pair.s1 != null -> skill1Names[pair.s1]; pair.s2 != null -> skill2Names[pair.s2]; else -> k }
                    foundList.add(NamePos(pos, k, sp, pair.s1, pair.s2, disp))
                    usedRanges.add(range)
                }
                sf = pos + 1
            }
        }
        foundList.sortBy { it.pos }
        if (foundList.isNotEmpty()) {
            val (s1D, s2D) = assignSpByOrder(
                foundList, normalized, skill1Names, skill1Sp, skill2Names, skill2Sp
            )
            if (s1D.isNotEmpty() || s2D.isNotEmpty()) {
                return ScanResult(rawText, normalized, s1D, s2D, slots)
            }
        }

        // ── Step 1: ペア抽出 + SKILL_ALIASES マッチング ──────────────────────
        val pairs = extractSkillPointPairs(normalized)
        if (pairs.isNotEmpty()) {
            val s1 = matchPairByAlias(pairs[0].first, pairs[0].second, skill1Names, skill1Sp)
            val s2 = if (pairs.size >= 2) {
                val usedPairIdx = s1.firstOrNull()?.let { cand ->
                    pairs.indexOfFirst { p ->
                        matchPairByAlias(p.first, p.second, skill1Names, skill1Sp)
                            .firstOrNull()?.localIdx == cand.localIdx
                    }
                } ?: 0
                val remaining = pairs.filterIndexed { i, _ -> i != usedPairIdx }
                if (remaining.isNotEmpty())
                    matchPairByAlias(remaining[0].first, remaining[0].second, skill2Names, skill2Sp)
                else emptyList()
            } else emptyList()

            if (s1.isNotEmpty()) return ScanResult(rawText, normalized, s1, s2, slots)
        }

        // ── Step 2: ウィンドウベースフォールバック ────────────────────────────
        val allNames = (skill1Names + skill2Names).distinct()
        val spans = locateCandidateSpans(normalized, allNames)
        val s1 = if (spans.isNotEmpty())
            candidatesForSpan(spans[0].first, spans[0].second, normalized, skill1Names, skill1Sp)
        else emptyList()
        val s2 = if (spans.size >= 2)
            candidatesForSpan(spans[1].first, spans[1].second, normalized, skill2Names, skill2Sp)
        else emptyList()

        return ScanResult(rawText, normalized, s1, s2, slots)
    }
}

// ======================================================================
// MHXX NS 鑑定結果画面 — 切り抜き領域の相対座標定数
//
// 基準スクリーン: 1024×576 (16:9 の Switch キャプチャ)
// 解像度非依存 (0.0〜1.0 の相対座標)
//
//  天の護石 ─────────────────────────────
//  固有スキル1  [スキル名]  [+SP]     ← CROP_SKILL1
//  固有スキル2  [スキル名]  [+SP]     ← CROP_SKILL2
//  スロット   ○○○                    ← CROP_SLOT
// ======================================================================
object CropRegion {
    /**
     * 基準: 1280×670〜720 の鑑定結果画面（提供画像 1162.jpg 含む）
     * 相対座標は解像度非依存。スマホ撮影で少しずれてもラベルアンカーが主、
     * ここはフォールバック切り出し用。
     */
    /** 固有スキル1 行 (left, top, right, bottom) */
    val SKILL1 = floatArrayOf(0.38f, 0.20f, 0.68f, 0.28f)
    /** 固有スキル2 行 */
    val SKILL2 = floatArrayOf(0.38f, 0.26f, 0.68f, 0.34f)
    /** スロット行（やや広め。丸3つ＋余白を確実に含む） */
    val SLOT   = floatArrayOf(0.35f, 0.31f, 0.65f, 0.42f)
}

// ======================================================================
// 切り抜き専用パーサー
//
// フル画像OCRと違い、対象行だけの短いテキストを受け取るため
// 高精度で完全一致が可能。
// ======================================================================

/**
 * スキル行切り抜きの OCR テキストを解析してスキル候補を返す。
 *
 * 切り抜きテキスト例: "固有スキル1 痛撃 +6" / "達人 +10" / "痛擊 6"
 * → スキル名は SKILL_ALIASES で誤読補正、SP は近傍の数字を採用
 *
 * @param rawText  切り抜き領域の OCR 生テキスト
 * @param names    種類テーブルのスキル名リスト (全角スペース除去済み)
 * @param spTable  種類テーブルの SP 範囲
 * @return 最高スコアの候補、何も見つからなければ null
 */
fun parseSkillCrop(
    rawText: String,
    names: List<String>,
    spTable: Array<IntArray>
): SkillCandidate? {
    if (rawText.isBlank()) return null
    val normalized = SkillMatcher.normalizeText(rawText)

    // ── Approach 1: 既知スキル名のサブストリング直接検索 ─────────────────
    // ダミーの skill2 を空リストにして scan() を呼ぶ
    // → directNameSearch が skill1 候補を高精度で返す
    val result = SkillMatcher.scan(
        rawText,
        names, spTable,
        emptyList(), emptyArray()
    )
    result.bestSkill1?.let { return it }

    // ── Approach 2: テキスト全体をスキャンして最初に見つかるスキル名を採用
    // (OCR が "固有スキル1" ラベルのみを読んだ場合など Approach 1 が失敗するケース)
    for (i in names.indices) {
        val normName = SkillMatcher.normalizeNameForSearch(names[i])
        if (normName.isEmpty()) continue
        val pos = normalized.indexOf(normName)
        if (pos >= 0) {
            val sp = SkillMatcher.extractNumberNear(normalized, pos + normName.length)
            val spRow = spTable[i]
            val lo = minOf(spRow[0], spRow[1]); val hi = maxOf(spRow[0], spRow[1])
            return SkillCandidate(
                localIdx = i, name = names[i],
                score = 1.0f, position = pos, points = sp,
                pointsInRange = sp?.let { it in lo..hi }
            )
        }
        // エイリアスでも探す
        val globalIdx = SkillMatcher.findSkillGlobalIdx(normName)
        if (globalIdx >= 0) {
            for ((alias, aliasGlobal) in SKILL_ALIASES) {
                if (aliasGlobal != globalIdx) continue
                val aliasNorm = SkillMatcher.normalizeNameForSearch(alias)
                val apos = normalized.indexOf(aliasNorm)
                if (apos >= 0) {
                    val sp = SkillMatcher.extractNumberNear(normalized, apos + aliasNorm.length)
                    val spRow = spTable[i]
                    val lo = minOf(spRow[0], spRow[1]); val hi = maxOf(spRow[0], spRow[1])
                    return SkillCandidate(
                        localIdx = i, name = names[i],
                        score = 0.95f, position = apos, points = sp,
                        pointsInRange = sp?.let { it in lo..hi }
                    )
                }
            }
        }
    }
    return null
}

/**
 * スロット行切り抜きの OCR テキストからスロット数 (0〜3) を返す。
 *
 * 対応パターン:
 *   "スロット ○○○"  → 3  (MHXX NS 標準: 空スロット = ○)
 *   "スロット OOO"   → 3  (OCR が ○ を O と誤読)
 *   "スロット 3"     → 3  (数字で表示)
 *   "スロット"       → 0  (スロットなし)
 */
fun parseSlotCrop(rawText: String): Int? {
    if (rawText.isBlank()) return null
    val normalized = SkillMatcher.normalizeText(rawText)
        .replace(Regex("[〇○◯ＯOｏo◎●◉◯０Qq]"), "◯")

    if (Regex("[-－─—–―_ー]{2,}").containsMatchIn(normalized)) return 0
    if (Regex("なし|無").containsMatchIn(normalized)) return 0

    val runs = Regex("◯{1,3}").findAll(normalized).map { it.value.length }.toList()
    if (runs.isNotEmpty()) return runs.maxOrNull()!!.coerceIn(1, 3)

    Regex("スロット\\s*[:：.]?\\s*([0-3])").find(normalized)?.let {
        return it.groupValues[1].toIntOrNull()
    }
    Regex("\\b([1-3])\\b").find(normalized)?.let {
        return it.groupValues[1].toIntOrNull()
    }

    // ラベルのみ → 不明（0 に落とさない。○ が OCR で落ちただけかもしれない）
    return null
}

// ======================================================================
// HTMLツール snipe_integrated.html の parseOcrCharm / inferKindFromSkills
// を Kotlin に移植した実装
//
// 従来の SkillMatcher.scan() は「種類テーブルを先に決めてからマッチ」だったが、
// このアプローチは「グローバルインデックスで認識 → あとで種類を推定」する。
// どんな組み合わせのお守りでも認識できる理由がここにある。
// ======================================================================

/** OCRで認識した1スキル */
data class OcrSkill(
    val globalIdx: Int,   // SKILL_NAMES 上のグローバルインデックス
    val name: String,     // 正規化済みスキル名
    val pts: Int          // スキルポイント (+1〜+20)
)

/** OCRで認識したお守り情報（種類は不明の場合 -1） */
data class OcrCharm(
    val kind: Int,             // 0=風化 1=古びた 2=光る 3=なぞ  -1=不明
    val slots: Int,            // 0〜3  -1=不明
    val skills: List<OcrSkill> // 最大2スキル（gIdx の昇順ではなく出現順）
)

// UIラベル行に含まれるノイズワード（スキル名検索時にスキップ）
private val LABEL_NOISE = Regex("固有スキル|国有スキル|スロット|護石|スナイプ|鑑定|ボック")

/**
 * ML Kit OCR の生テキストからお守り情報を抽出する。
 * 全スキル組み合わせ（1文字スキル〜長い正式名、全4種）の鑑定画像に対応。
 */
fun parseOcrCharm(rawText: String): OcrCharm {
    // 正規化（全角空白もスペース化、句読点除去）
    val norm  = rawText
        .replace(Regex("[　\\s]+"), " ")
        .replace(Regex("[！？。、・]"), "")
        .trim()
    val lines = rawText.split(Regex("[\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }

    // ── 種類検出 ──────────────────────────────────────────────────────────
    // 「天の護石」は RARE 8〜10 帯の表示名。風化テーブル (kind=0) に属することが多い。
    // ただし RARE 数字が取れればそちらを優先し、取れない場合のみ天の護石で 0 を仮置きする。
    var kind = when {
        norm.contains("風化") -> 0
        norm.contains("古び") -> 1
        norm.contains("光る") -> 2
        norm.contains("なぞ") || norm.contains("謎") -> 3
        else -> -1
    }
    if (kind < 0) {
        val rareM = Regex("RARE[\\s*＊]?(\\d+)", RegexOption.IGNORE_CASE).find(norm)
            ?: Regex("レア[\\s*＊]?(\\d+)").find(norm)
        rareM?.groupValues?.get(1)?.toIntOrNull()?.let { r ->
            // notebook rare(): 風化8-10 / 古び5-7 / 光る3-4 / なぞ1-2
            kind = when {
                r >= 8 -> 0
                r >= 5 -> 1
                r >= 3 -> 2
                else -> 3
            }
        }
    }
    // 天の護石フォールバック（RARE が読めなかったスマホ写真向け）
    if (kind < 0 && (norm.contains("天の護石") || norm.contains("天の護"))) {
        kind = 0
    }

    // スロット: 「スロット」行だけを見る（全文の装飾○は無視）
    //   ○ / ○○ / ○○○ → 1〜3
    //   --- / - - -     → 0
    var slots = -1
    run {
        // スマホ写真でよく出る丸・ゼロ誤読をすべて ◯ に正規化
        var unified = norm.replace(Regex("[〇○◯ＯOｏo◎●◉◯０Qq]"), "◯")
        unified = unified.replace(Regex("(?:◯\\s*){1,3}◯")) { mr ->
            "◯".repeat(mr.value.count { it == '◯' })
        }

        // スロット行（ラベル〜その右〜最大60文字）。全文は使わない。
        val slotArea = Regex("スロ(?:ット)?[^\\n]{0,60}").find(unified)?.value
            ?: Regex("スロ(?:ット)?.{0,40}").find(unified)?.value
            ?: ""

        if (slotArea.isEmpty()) return@run

        // 1) 行内の ◯ → 1〜3（この行に丸があれば最優先）
        val runs = Regex("◯{1,3}").findAll(slotArea).map { it.value.length }.toList()
        if (runs.isNotEmpty()) {
            slots = runs.maxOrNull()!!.coerceIn(1, 3)
            return@run
        }

        // 2) 行内の "000"/"00" → ○ 誤読
        Regex("([0]{2,3})(?![0-9])").find(slotArea)?.let {
            slots = it.groupValues[1].length.coerceIn(2, 3)
            return@run
        }

        // 3) 行内の横線 → 0
        if (Regex("(?:[-－─—–―_]\\s*){2,}").containsMatchIn(slotArea)) {
            slots = 0
            return@run
        }
        if (Regex("なし|無").containsMatchIn(slotArea)) {
            slots = 0
            return@run
        }

        // 4) 「スロット N」数字
        Regex("スロット\\s*[:：.]?\\s*([0-3])(?![0-9])").find(slotArea)?.let {
            slots = it.groupValues[1].toInt()
            return@run
        }
        Regex("([1-3])\\s*スロ").find(slotArea)?.let {
            slots = it.groupValues[1].toInt()
        }
    }

    val skillMatches = mutableListOf<OcrSkill>()

    fun tryAdd(rawName: String, pts: Int) {
        if (pts !in 1..20) return
        if (LABEL_NOISE.containsMatchIn(rawName)) return
        val gIdx = SkillMatcher.findSkillGlobalIdx(rawName)
        if (gIdx >= 0 && skillMatches.none { it.globalIdx == gIdx }) {
            val disp = SkillMatcher.normalizeNameForSearch(
                SKILL_NAMES.getOrElse(gIdx) { rawName }
            )
            skillMatches.add(OcrSkill(gIdx, disp, pts))
        }
    }

    // 1文字スキル（毒/匠/笛/怒）〜長い正式名まで。最大8文字。
    val nameCls = """[ぁ-んァ-ン一-龥々〆〇ーA-Za-z]{1,8}"""

    // Strategy 1: スキル名+N / スキル名＋N
    Regex("($nameCls)[+＋](\\d{1,2})").findAll(norm).forEach { m ->
        tryAdd(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: return@forEach)
    }

    // Strategy 1b: スキル名 空白 N
    Regex("($nameCls)\\s+(\\d{1,2})").findAll(norm).forEach { m ->
        tryAdd(m.groupValues[1], m.groupValues[2].toIntOrNull() ?: return@forEach)
    }

    // Strategy 2: 行またぎ（名前行と数値行を出現順に対応）
    if (skillMatches.size < 2) {
        val foundNames = mutableListOf<Pair<Int, String>>()
        val foundPts = mutableListOf<Int>()
        for (line in lines) {
            if (LABEL_NOISE.containsMatchIn(line)) continue
            val pure = Regex("^($nameCls)$").find(line.trim())
            if (pure != null) {
                val gIdx = SkillMatcher.findSkillGlobalIdx(pure.groupValues[1])
                if (gIdx >= 0) foundNames.add(gIdx to pure.groupValues[1])
                continue
            }
            val inline = Regex("($nameCls)\\s*[+＋]?(\\d{1,2})").find(line)
            if (inline != null) {
                tryAdd(inline.groupValues[1], inline.groupValues[2].toIntOrNull() ?: 0)
                continue
            }
            Regex("^[+＋]?(\\d{1,2})$").find(line.trim())?.groupValues?.get(1)
                ?.toIntOrNull()?.let { if (it in 1..20) foundPts.add(it) }
        }
        val n = minOf(foundNames.size, foundPts.size)
        for (i in 0 until n) {
            val (gIdx, rawName) = foundNames[i]
            if (skillMatches.none { it.globalIdx == gIdx }) {
                skillMatches.add(
                    OcrSkill(
                        gIdx,
                        SkillMatcher.normalizeNameForSearch(SKILL_NAMES.getOrElse(gIdx) { rawName }),
                        foundPts[i]
                    )
                )
            }
        }
    }

    // 最大2スキル
    return OcrCharm(kind, slots, skillMatches.take(2))
}

/**
 * OCR 用の種類推定は rng.KIND_TABLES（notebook 準拠の本物）を使う。
 * 以前の誤った「スキル帯域で種類を決める」テーブルは削除済み。
 */
fun inferKindFromSkills(charm: OcrCharm): Int {
    if (charm.kind >= 0 || charm.skills.isEmpty()) return charm.kind
    val s1gIdx = charm.skills[0].globalIdx
    val s2gIdx = charm.skills.getOrNull(1)?.globalIdx ?: -1
    // skill1 に s1 があり、skill2 に s2 がある種類を優先
    for (k in 0..3) {
        val tbl = org.mhxxtools.mhxxrngtool.rng.KIND_TABLES[k] ?: continue
        val s1ok = tbl.skill1.contains(s1gIdx)
        if (!s1ok) continue
        if (s2gIdx < 0) return k
        if (tbl.skill2.contains(s2gIdx)) return k
    }
    // skill2 に無い場合でも skill1 に両方ある種類を許容
    for (k in 0..3) {
        val tbl = org.mhxxtools.mhxxrngtool.rng.KIND_TABLES[k] ?: continue
        if (tbl.skill1.contains(s1gIdx) &&
            (s2gIdx < 0 || tbl.skill1.contains(s2gIdx) || tbl.skill2.contains(s2gIdx))
        ) return k
    }
    return -1
}
