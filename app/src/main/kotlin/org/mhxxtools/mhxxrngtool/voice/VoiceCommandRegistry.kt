package org.mhxxtools.mhxxrngtool.voice

/**
 * 1件の音声コマンド定義。[phrases] のいずれかが認識結果に含まれていれば一致とみなす。
 */
private data class VoiceCommand(
    val phrases: List<String>,
    val description: String,
    val action: () -> Unit
)

/**
 * アプリの機能を音声で呼び出すためのコマンド表。
 *
 * [register] で「説明」「呼びかけフレーズ（複数可）」「実行する処理」を登録し、
 * [dispatch] で音声認識結果の文字列から最も一致するコマンドを実行する。
 *
 * マッチングは部分一致（認識結果の中にフレーズが含まれるか）で行い、
 * 複数のフレーズが一致した場合はより長い＝より具体的なフレーズを優先する。
 * これにより「タイマー」（タブ移動のみ）よりも「タイマー開始」（移動して開始）を
 * 自然に優先させることができる。
 */
class VoiceCommandRegistry {
    private val commands = mutableListOf<VoiceCommand>()

    /** @param phrases このコマンドを呼び出すための語句（最低1つ必要）。 */
    fun register(description: String, vararg phrases: String, action: () -> Unit) {
        require(phrases.isNotEmpty()) { "phrases must not be empty" }
        commands += VoiceCommand(phrases.toList(), description, action)
    }

    /**
     * 認識結果 [heard] に対応するコマンドを実行する。
     * @return 実行したコマンドの説明文。一致するコマンドがなければ null。
     */
    fun dispatch(heard: String): String? {
        val normalizedHeard = normalize(heard)
        if (normalizedHeard.isEmpty()) return null

        val matched = commands
            .flatMap { cmd -> cmd.phrases.map { phrase -> phrase to cmd } }
            .filter { (phrase, _) -> normalizedHeard.contains(normalize(phrase)) }
            .maxByOrNull { (phrase, _) -> phrase.length }
            ?.second

        matched?.action?.invoke()
        return matched?.description
    }

    /** 空白・句読点の違いや全角/半角・大文字小文字を吸収するための正規化。 */
    private fun normalize(s: String): String =
        s.trim().lowercase().replace(Regex("[\\s　、。!！?？,.・ー]"), "")
}
