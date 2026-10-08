package org.mhxxtools.mhxxrngtool.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Google 音声認識連携。短い日本語コマンド向けに
 * 「候補を語彙スコアで選ぶ」方式にして誤認識を減らす。
 */
object VoiceCommands {

    data class Action(
        val tab: Int? = null,
        val kind: Int? = null,
        val run: String? = null,
        val label: String
    )

    /** 認識結果に出やすい誤変換 → 正しい語 */
    private val FIXES = listOf(
        "ふうか" to "風化", "風火" to "風化", "風花" to "風化", "風化た" to "風化",
        "ふーか" to "風化", "フウカ" to "風化", "ふう化した" to "風化",
        "ふるび" to "古び", "古美" to "古び", "ふるびた" to "古び", "古びた" to "古び",
        "ふーび" to "古び", "フルビ" to "古び",
        "ひかる" to "光る", "光るお守り" to "光る", "ヒカル" to "光る",
        "なぞの" to "なぞ", "謎の" to "なぞ", "謎" to "なぞ", "ナゾ" to "なぞ",
        "けんさく" to "検索", "検査" to "検索", "検索タブ" to "検索", "ケンサク" to "検索",
        "しゅうへん" to "周辺", "周辺タブ" to "周辺", "シュウヘン" to "周辺",
        "ちょうごう" to "調合", "調合タブ" to "調合", "調号" to "調合", "チョウゴウ" to "調合",
        "いち" to "位置", "位置タブ" to "位置", "イチ" to "位置",
        "ねらいめ" to "狙い目", "狙い" to "狙い目", "ねらい" to "狙い目", "ネライメ" to "狙い目",
        "かんてい" to "鑑定", "鑑定タブ" to "鑑定", "官邸" to "鑑定", "カンテイ" to "鑑定",
        "たいまー" to "タイマー", "タイマ" to "タイマー", "タイマー" to "タイマー",
        "あるでゅいーの" to "Arduino", "アルデュイノ" to "Arduino", "アルディーノ" to "Arduino",
        "あるでぃーの" to "Arduino", "アルデューノ" to "Arduino", "あーでゅいの" to "Arduino",
        "かいせき" to "解析", "カイセキ" to "解析",
        "じっこう" to "実行", "スタート" to "開始", "はじめ" to "開始",
        "さがして" to "探して", "探して" to "探して"
    )

    /** アプリで受け付ける語彙（短いほど優先しやすい） */
    private val VOCAB = listOf(
        "風化", "古び", "光る", "なぞ",
        "検索", "周辺", "調合", "位置", "狙い目", "鑑定", "タイマー", "Arduino",
        "解析", "検索して", "検索開始", "探して", "開始", "実行", "スタート"
    )

    fun normalize(raw: String): String {
        var t = raw.lowercase(Locale.JAPAN)
            .replace(" ", "")
            .replace("　", "")
            .replace("ー", "ー")
        // 全角英数はそのまま、カタカナは一部ひらがな寄りにしない
        FIXES.forEach { (from, to) ->
            if (t.contains(from)) t = t.replace(from, to)
        }
        return t
    }

    fun parse(raw: String): Action? {
        val t = normalize(raw)
        if (t.isBlank()) return null
        var kind: Int? = null
        var tab: Int? = null
        var run: String? = null
        when {
            t.contains("風化") -> kind = 0
            t.contains("古び") -> kind = 1
            t.contains("光る") -> kind = 2
            t.contains("なぞ") -> kind = 3
        }
        when {
            t.contains("Arduino") || t.contains("アルディーノ") || t.contains("アルデュイノ") -> tab = 7
            t.contains("タイマー") -> tab = 6
            t.contains("鑑定") -> tab = 5
            t.contains("狙い") -> tab = 4
            t.contains("位置") -> tab = 3
            t.contains("調合") -> tab = 2
            t.contains("周辺") -> tab = 1
            t.contains("検索") && !t.contains("検索して") && !t.contains("検索開始") -> tab = 0
        }
        when {
            t.contains("解析") -> run = "analyze"
            t.contains("検索して") || t.contains("検索開始") || t.contains("探して") -> {
                run = "search"
                if (tab == null) tab = 0
            }
            t.contains("スタート") || t.contains("開始") || t.contains("実行") -> run = "start"
        }
        if (kind == null && tab == null && run == null) return null
        return Action(tab, kind, run, t)
    }

    /**
     * Google が返す複数候補から、アプリ語彙に一番近いものを選ぶ。
     * 先頭候補をそのまま信じない。
     */
    fun best(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        var bestText: String? = null
        var bestScore = Int.MIN_VALUE
        for (c in candidates) {
            val n = normalize(c)
            val score = scoreText(n)
            if (score > bestScore) {
                bestScore = score
                bestText = n
            }
        }
        // どの候補も語彙に寄りがつかないときは先頭を正規化して返す
        if (bestScore < 10) {
            return normalize(candidates.first())
        }
        return bestText
    }

    private fun scoreText(t: String): Int {
        var score = 0
        for (v in VOCAB) {
            when {
                t == v -> score += 100
                t.contains(v) -> score += 40 + v.length
                // 1文字違い（編集距離1）
                editDistance(t, v) == 1 -> score += 25
                editDistance(t, v) == 2 && v.length >= 2 -> score += 12
            }
        }
        // 複合コマンド
        if (t.contains("検索") && (t.contains("して") || t.contains("開始"))) score += 20
        if (t.contains("調合") && t.contains("解析")) score += 20
        return score
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > 2) return 99
        val m = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) m[i][0] = i
        for (j in 0..b.length) m[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                m[i][j] = minOf(
                    m[i - 1][j] + 1,
                    m[i][j - 1] + 1,
                    m[i - 1][j - 1] + cost
                )
            }
        }
        return m[a.length][b.length]
    }

    fun listen(context: android.content.Context, onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Google音声認識が使えません。Googleアプリを入れてください")
            return
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 8)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "例: 風化、検索、調合を解析")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // 短く話す想定。無音で切りすぎないよう少し長め
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                rec.destroy()
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "ネットワークエラー。Google音声認識に接続できません"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        "マイクの許可が必要です"
                    SpeechRecognizer.ERROR_NO_MATCH ->
                        "聞き取れませんでした。短くはっきり話してください"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "声が聞こえませんでした"
                    else -> "聞き取れませんでした ($error)"
                }
                onError(msg)
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                rec.destroy()
                val text = best(list)
                if (text.isNullOrBlank()) {
                    onError("聞き取れませんでした。候補: ${list.take(3).joinToString(" / ")}")
                } else {
                    onText(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent)
    }
}
