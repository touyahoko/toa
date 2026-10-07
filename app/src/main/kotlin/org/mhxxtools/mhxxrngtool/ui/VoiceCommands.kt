package org.mhxxtools.mhxxrngtool.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * アプリ内の音声操作。
 * 「風化」「鑑定」「検索して」「調合を解析」などを受けてタブ・種類・開始を切り替える。
 */
object VoiceCommands {

    data class Action(
        val tab: Int? = null,
        val kind: Int? = null,
        val run: String? = null,
        val label: String
    )

    fun parse(raw: String): Action? {
        val t = raw.replace(" ", "").replace("　", "")
        if (t.isBlank()) return null
        var kind: Int? = null
        var tab: Int? = null
        var run: String? = null
        when {
            t.contains("風化") -> kind = 0
            t.contains("古び") || t.contains("古びた") -> kind = 1
            t.contains("光る") -> kind = 2
            t.contains("なぞ") || t.contains("謎") -> kind = 3
        }
        when {
            t.contains("タイマー") || t.contains("たいまー") -> tab = 6
            t.contains("鑑定") || t.contains("OCR") || t.contains("おーしーあーる") -> tab = 5
            t.contains("狙い") || t.contains("ねらい") -> tab = 4
            t.contains("位置") || t.contains("いち") -> tab = 3
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
        return Action(tab, kind, run, raw)
    }

    fun listen(context: android.content.Context, onText: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("音声認識が使えません")
            return
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                rec.destroy()
                val text = list?.firstOrNull().orEmpty()
                if (text.isBlank()) onError("聞き取れませんでした") else onText(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent)
    }
}
