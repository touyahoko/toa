package org.mhxxtools.mhxxrngtool.ui.ocr

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.mhxxtools.mhxxrngtool.ocr.AndroidOcr
import org.mhxxtools.mhxxrngtool.ocr.HybridCharmReader
import org.mhxxtools.mhxxrngtool.ocr.CharmLearningModel
import org.mhxxtools.mhxxrngtool.ocr.OcrCharm
import org.mhxxtools.mhxxrngtool.ocr.inferKindFromSkills

import org.mhxxtools.mhxxrngtool.rng.*

data class OcrUiState(
    val isProcessing: Boolean = false,
    val ocrStatus: String = "",
    val hasError: Boolean = false,
    val detectedKind: String = "",
    val detectedSkill1: String = "",
    val detectedSkill2: String = "",
    val detectedSlot: String = "",
    val isSearching: Boolean = false,
    val searchProgress: Float = 0f,
    val searchStatus: String = "",
    val frameResults: List<CharmResult> = emptyList(),
    val totalFound: Int = 0,
    val autoApplyReady: ApplyData? = null
)

data class ApplyData(
    val kind: Int,
    val skill1Name: String?,
    val skill1Pts: Int?,
    val skill2Name: String?,
    val skill2Pts: Int?,
    val slot: Int?
)

/**
 * 鑑定画像1枚 → OCR → そのお守りと完全一致するフレームを
 * **検索範囲内すべて** 洗い出して表示する。
 *
 * 「数件で打ち切り」はしない。ヒットはすべて一覧に載せる。
 */
class OcrViewModel : ViewModel() {
    private val _state = MutableStateFlow(OcrUiState())
    val state: StateFlow<OcrUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    /**
     * 検索幅（フレーム数）。
     * 30fps で約 38.6 日分。ゲーム開始からの実用範囲をカバー。
     */
    private val SEARCH_STEP = 100_000_000L

    /** 表示上限（メモリ保護）。この件数を超えた分は totalFound にのみ反映 */
    private val DISPLAY_CAP = 500

    fun recognizeFromUri(
        context: Context,
        uri: Uri,
        userKind: Int = -1
    ) {
        searchJob?.cancel()
        _state.update { OcrUiState(isProcessing = true, ocrStatus = "OCR+テンプレ照合中…") }

        viewModelScope.launch {
            // ハイブリッド: ML Kit OCR（スキル名）+ テンプレ（ポイント/スロット/検証）
            val bmp = AndroidOcr.loadBitmap(context, uri).getOrElse { e ->
                setError("画像読込エラー: ${e.message}"); return@launch
            }
            val hybrid = HybridCharmReader.read(context, bmp)
            bmp.recycle()
            val r = hybrid.getOrElse { e ->
                setError("認識エラー: ${e.message}"); return@launch
            }
            val charm = r.charm
            val slots = r.charm.slots
            val debugNote = r.note
            if (charm.skills.isEmpty()) {
                setError(
                    "スキルを認識できませんでした。\n" +
                        "鑑定パネル全体が写るように撮影してください。\n" +
                        "($debugNote)"
                )
                return@launch
            }

            val skill1 = charm.skills[0]
            val skill2 = charm.skills.getOrNull(1)

            val kindNames = listOf("風化したお守り", "古びたお守り", "光るお守り", "なぞのお守り")
            val inferred = when {
                userKind in 0..3 -> userKind
                charm.kind >= 0 -> charm.kind
                else -> inferKindFromSkills(charm)
            }

            // この組み合わせが理論上出る種類だけ（全種類を無駄に回さない）
            val viableKinds = (0..3).filter { k ->
                val tbl = KIND_TABLES[k] ?: return@filter false
                val s1 = tbl.skill1.indexOf(skill1.globalIdx)
                if (s1 < 0) return@filter false
                val sp1 = tbl.sp1[s1]
                if (skill1.pts !in minOf(sp1[0], sp1[1])..maxOf(sp1[0], sp1[1])) return@filter false
                if (skill2 != null) {
                    val s2 = tbl.skill2.indexOf(skill2.globalIdx)
                    if (s2 < 0) return@filter false
                    val sp2 = tbl.sp2[s2]
                    val lo = minOf(sp2[0], sp2[1]); val hi = maxOf(sp2[0], sp2[1])
                    if (skill2.pts > 0 && skill2.pts !in lo..hi) return@filter false
                }
                true
            }.ifEmpty { listOf(0, 1, 2, 3) }
                .let { list ->
                    if (inferred in list) listOf(inferred) + list.filter { it != inferred }
                    else list
                }

            _state.update {
                it.copy(
                    isProcessing = false,
                    ocrStatus = "✓ 認識完了 → 完全一致フレームを全件検索中…",
                    hasError = false,
                    detectedKind = if (inferred >= 0) kindNames[inferred] else "自動",
                    detectedSkill1 = "${skill1.name} +${skill1.pts}",
                    detectedSkill2 = skill2?.let { s -> "${s.name} +${s.pts}" } ?: "（なし）",
                    detectedSlot = when {
                        slots < 0 -> "不明"
                        slots == 0 -> "なし"
                        else -> "○".repeat(slots.coerceAtMost(3))
                    },
                    isSearching = true,
                    searchStatus = "全件検索開始…",
                    frameResults = emptyList(),
                    totalFound = 0
                )
            }

            searchJob = launch(Dispatchers.Default) {
                // frame -> result（全ヒット保持）
                val hits = sortedMapOf<Long, CharmResult>()
                var firstApply: ApplyData? = null
                val originNames = listOf("マカ", "炭鉱")
                val combos = viableKinds.flatMap { k -> listOf(k to 0, k to 1) }
                val totalCombos = combos.size.coerceAtLeast(1).toFloat()

                fun publish(status: String, progress: Float) {
                    val snap = hits.values.take(DISPLAY_CAP)
                    val count = hits.size
                    launch(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                frameResults = snap,
                                totalFound = count,
                                searchProgress = progress.coerceIn(0f, 1f),
                                searchStatus = status,
                                ocrStatus = if (count > 0)
                                    "✓ 完全一致 ${count}件（検索継続中…）"
                                else
                                    "✓ 認識完了 → 完全一致を全件検索中…"
                            )
                        }
                    }
                }

                for ((ci, combo) in combos.withIndex()) {
                    if (!isActive) break
                    val (kindIdx, origin) = combo
                    val tbl = KIND_TABLES[kindIdx] ?: continue

                    val s1Local = tbl.skill1.indexOf(skill1.globalIdx)
                    if (s1Local < 0) continue

                    var s2Local: Int? = null
                    if (skill2 != null) {
                        val li = tbl.skill2.indexOf(skill2.globalIdx)
                        if (li < 0) continue
                        s2Local = li
                    }

                    if (firstApply == null) {
                        val s1Names = tbl.skill1.map { SKILL_NAMES[it].replace("　", "").trim() }
                        val s2Names = tbl.skill2.map { SKILL_NAMES[it].replace("　", "").trim() }
                        firstApply = ApplyData(
                            kind = kindIdx,
                            skill1Name = s1Names.getOrNull(s1Local),
                            skill1Pts = skill1.pts,
                            skill2Name = s2Local?.let { s2Names.getOrNull(it) },
                            skill2Pts = skill2?.pts,
                            slot = slots.takeIf { it >= 0 }
                        )
                    }

                    val target = SearchTarget(
                        skill1Idx = s1Local,
                        skill1Pts = skill1.pts,
                        skill2Idx = s2Local,
                        skill2Pts = skill2?.pts ?: 0,
                        // 不明(-1)のときはスロット条件なしで検索
                        slot = if (slots >= 0) slots else -1,
                        origin = origin
                    )

                    val engine = MHXXEngine(kindIdx)
                    var lastPub = 0L

                    // 全件走査（打ち切らない）
                    engine.search(
                        start = 0L,
                        step = SEARCH_STEP,
                        target = target,
                        shouldStop = { !isActive },
                        onProgress = { done, total ->
                            if (done - lastPub >= 200_000L) {
                                lastPub = done
                                val overall = (ci + done.toFloat() / total.coerceAtLeast(1)) / totalCombos
                                publish(
                                    "全件検索 ${"%.0f".format(overall * 100)}% " +
                                        "[${kindNames[kindIdx]}/${originNames[origin]}] " +
                                        "hit=${hits.size}",
                                    overall
                                )
                            }
                        }
                    ).forEach { r ->
                        if (!isActive) return@forEach
                        val isNew = hits.putIfAbsent(r.frame, r) == null
                        if (isNew && hits.size % 5 == 1) {
                            // 数件ごとにUI更新（全件は重すぎるので間引き）
                            publish(
                                "ヒット ${hits.size}件… F${r.frame} " +
                                    "[${kindNames[kindIdx]}/${originNames[origin]}]",
                                (ci + 0.5f) / totalCombos
                            )
                        }
                    }
                }

                val finalList = hits.values.take(DISPLAY_CAP)
                val total = hits.size
                // 完全一致が出たら学習モデルに成功例を記録
                if (total > 0) {
                    CharmLearningModel.recordSuccess(
                        context,
                        OcrCharm(
                            kind = inferred,
                            slots = slots,
                            skills = listOfNotNull(skill1, skill2)
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            isSearching = false,
                            searchProgress = 1f,
                            frameResults = finalList,
                            totalFound = total,
                            ocrStatus = when {
                                total == 0 ->
                                    "⚠ 完全一致フレームなし\n" +
                                        "${skill1.name}+${skill1.pts} / " +
                                        (skill2?.let { s -> "${s.name}+${s.pts}" } ?: "スキル2なし") +
                                        " / スロ$slots"
                                total > DISPLAY_CAP ->
                                    "✓ 完全一致 ${total}件（表示は先頭${DISPLAY_CAP}件）"
                                else ->
                                    "✓ 完全一致 ${total}件のフレーム"
                            },
                            searchStatus = if (total > 0) "完了" else "0件",
                            autoApplyReady = firstApply
                        )
                    }
                }
            }
        }
    }

    private fun setError(msg: String) {
        _state.update {
            it.copy(isProcessing = false, isSearching = false, ocrStatus = msg, hasError = true)
        }
    }

    fun cancelSearch() {
        searchJob?.cancel()
        _state.update { it.copy(isSearching = false, searchProgress = 0f) }
    }

    fun clearAutoApply() = _state.update { it.copy(autoApplyReady = null) }
}
