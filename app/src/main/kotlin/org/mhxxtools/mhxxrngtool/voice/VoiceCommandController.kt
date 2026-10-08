package org.mhxxtools.mhxxrngtool.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** マイクボタンの表示状態。 */
enum class VoiceState { IDLE, LISTENING, PROCESSING }

/**
 * 端末標準の音声認識サービス（Android フレームワークの [SpeechRecognizer] API）を
 * 使った音声コマンド入力のコントローラー。
 *
 * [SpeechRecognizer.createSpeechRecognizer] はコンポーネントを明示しない限り、
 * 端末にインストールされている既定の音声認識サービスにバインドされる。
 * Google Play 済みの Android 端末（本アプリの対象機種）では既定サービスは
 * 「Google 音声入力 / Speech Services by Google」であり、追加設定なしで
 * Google の音声認識と連携する。
 *
 * 使い方（Composable 側）:
 * ```
 * val controller = remember { VoiceCommandController(context) { heard -> ... } }
 * DisposableEffect(Unit) { onDispose { controller.destroy() } }
 * ```
 *
 * @param onRecognized 認識されたテキストを受け取り、ステータス表示用の文言を返す。
 *                      実際のコマンド解釈・実行は呼び出し側（[VoiceCommandRegistry] 等）が行う。
 */
class VoiceCommandController(
    private val context: Context,
    private val onRecognized: (spokenText: String) -> String
) {
    var state by mutableStateOf(VoiceState.IDLE)
        private set

    /** マイクボタン付近に出す短いステータス文言（聞き取り中 / 実行結果 / エラー）。 */
    var statusMessage by mutableStateOf("")
        private set

    private var recognizer: SpeechRecognizer? = null

    /** この端末で音声認識サービスが利用可能か。 */
    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state = VoiceState.LISTENING
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            state = VoiceState.PROCESSING
            statusMessage = "認識中…"
        }

        override fun onError(error: Int) {
            state = VoiceState.IDLE
            statusMessage = errorMessage(error)
        }

        override fun onResults(results: Bundle?) {
            state = VoiceState.IDLE
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            statusMessage = if (text.isNotBlank()) onRecognized(text) else "聞き取れませんでした"
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** RECORD_AUDIO 許可後（または既に許可済みの状態で）マイクボタンから呼ぶ。 */
    fun start() {
        if (state != VoiceState.IDLE) return
        if (!isAvailable) {
            statusMessage = "この端末では音声認識が利用できません"
            return
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        statusMessage = "聞き取り中…"
        state = VoiceState.LISTENING
        r.startListening(buildRecognizerIntent())
    }

    /** 聞き取り中にマイクボタンを再タップした場合などに呼ぶ。 */
    fun cancel() {
        recognizer?.cancel()
        state = VoiceState.IDLE
        statusMessage = ""
    }

    /** RECORD_AUDIO が拒否された場合に呼ぶ。 */
    fun onPermissionDenied() {
        statusMessage = "音声コマンドを使うにはマイクの許可が必要です"
    }

    /** ステータス表示を消す（数秒後の自動クリアなど）。 */
    fun clearStatus() {
        statusMessage = ""
    }

    /** Composable が破棄される際に呼ぶ（SpeechRecognizer のリーク防止）。 */
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun buildRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "聞き取れませんでした"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイクの権限が必要です"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "通信エラーが発生しました"
        SpeechRecognizer.ERROR_AUDIO -> "録音エラーが発生しました"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識サービスが混み合っています"
        SpeechRecognizer.ERROR_CLIENT -> "認識を中止しました"
        else -> "音声認識エラーが発生しました"
    }
}
