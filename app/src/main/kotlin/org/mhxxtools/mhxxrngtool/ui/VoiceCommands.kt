package org.mhxxtools.mhxxrngtool.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * アプリ内の音声操作。
 * 日本語の誤認識は候補全体と言い換え辞書でコマンドに寄せる。
 */
object VoiceCommands {

    data class Action(
        val tab: Int? = null,
        val kind: Int? = null,
        val run: String? = null,
        val label: String
    )

    private val FIXES = listOf(
        "ふうか" to "風化", "風火" to "風化", "風花" to "風化", "ふうかした" to "風化",
        "ふるび" to "古び", "古美" to "古び", "ふるびた" to "古び",
        "ひかる" to "光る", "光るお守り" to "光る",
        "なぞの" to "なぞ", "謎の" to "なぞ", "謎" to "なぞ",
        "けんさく" to "検索", "検査" to "検索", "検索タブ" to "検索",
        "しゅうへん" to "周辺", "周辺タブ" to "周辺",
        "ちょうごう" to "調合", "調合タブ" to "調合", "調号" to "調合",
        "いち" to "位置", "位置タブ" to "位置",
        "ねらいめ" to "狙い目", "狙い" to "狙い目", "ねらい" to "狙い目",
        "かんてい" to "鑑定", "鑑定タブ" to "鑑定", "官邸" to "鑑定",
        "たいまー" to "タイマー", "タイマ" to "タイマー",
        "かいせき" to "解析", "開始" to "開始", "じっこう" to "実行"
    )

    fun normalize(raw: String): String {
        var t = raw.replace(" ", "").replace("　", "")
        FIXES.forEach { (from, to) -> t = t.replace(from, to) }
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
            t.contains("タイマー") -> tab = 6
            t.contains("鑑定") -> tab = 5
            t.contains("狙い") -> tab = 4
            t.contains("位置") -> tab = 3
            t.contains("調合") -> tab = 2
            t.contains("周辺") -> tab = 1
            t.contains("検索") -> tab = 0
        }
        when {
            t.contains("解析") -> run = "analyze"
            t.contains("検索して") || t.contains("検索開始") || t.contains("探して") -> run = "search"
            t.contains("スタート") || t.contains("開始") || t.contains("実行") -> run = "start"
        }
        if (kind == null && tab == null && run == null) return null
        return Action(tab, kind, run, t)
    }

    /** 候補の中でコマンドに落ちるものを優先する */
    fun best(candidates: List<String>): String? {
        val parsed = candidates.mapNotNull { c -> parse(c)?.let { c to it } }
        return parsed.firstOrNull()?.first ?: candidates.firstOrNull()
    }

    fun listen(context: android.content.Context, onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("音声認識が使えません")
            return
        }
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "風化、検索、調合、鑑定")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                rec.destroy()
                onError("聞き取れませんでした")
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                rec.destroy()
                val text = best(list)
                if (text.isNullOrBlank()) onError("聞き取れませんでした") else onText(normalize(text))
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent)
    }
}
