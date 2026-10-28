package org.mhxxtools.mhxxrngtool.switchtransfer

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GalleryKind { IMAGE, VIDEO }

data class GalleryItem(
    val id: String,
    val file: File,
    val kind: GalleryKind,
    val createdAt: Long,
    val name: String,
    val sizeBytes: Long = file.length()
)

object GalleryStore {
    private const val DIR = "switch_gallery"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    fun list(context: Context): List<GalleryItem> {
        val d = dir(context)
        return d.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.filter {
                val e = it.extension.lowercase()
                e in listOf("jpg", "jpeg", "png", "webp", "mp4", "mov") || detectKind(it) != null
            }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val kind = when (f.extension.lowercase()) {
                    "mp4", "mov" -> GalleryKind.VIDEO
                    "jpg", "jpeg", "png", "webp" -> GalleryKind.IMAGE
                    else -> detectKind(f) ?: GalleryKind.IMAGE
                }
                GalleryItem(f.name, f, kind, f.lastModified(), f.name, f.length())
            }
            ?: emptyList()
    }

    /** マジックバイトで種別判定 */
    fun detectKind(file: File): GalleryKind? {
        if (!file.exists() || file.length() < 12) return null
        val head = ByteArray(12)
        file.inputStream().use { it.read(head) }
        return detectKindFromBytes(head)
    }

    fun detectKindFromBytes(head: ByteArray): GalleryKind? {
        if (head.size < 3) return null
        // JPEG
        if (head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) return GalleryKind.IMAGE
        // PNG
        if (head[0] == 0x89.toByte() && head[1] == 0x50.toByte() && head[2] == 0x4E.toByte()) return GalleryKind.IMAGE
        // WebP: RIFF....WEBP
        if (head.size >= 12 &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte()
        ) return GalleryKind.IMAGE
        // MP4/MOV: ....ftyp
        if (head.size >= 8 &&
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) return GalleryKind.VIDEO
        return null
    }

    fun extFor(kind: GalleryKind, suggestedName: String?, head: ByteArray?): String {
        val fromName = suggestedName?.substringAfterLast('.', "")?.lowercase()
        if (fromName in listOf("jpg", "jpeg", "png", "webp", "mp4", "mov")) return fromName
        when (head?.let { detectKindFromBytes(it) }) {
            GalleryKind.VIDEO -> return "mp4"
            GalleryKind.IMAGE -> {
                if (head.size >= 3 && head[0] == 0x89.toByte()) return "png"
                return "jpg"
            }
            null -> {}
        }
        return if (kind == GalleryKind.VIDEO) "mp4" else "jpg"
    }

    fun saveBytes(context: Context, bytes: ByteArray, suggestedName: String?): GalleryItem? {
        if (bytes.size < 64) return null
        val head = bytes.copyOf(minOf(12, bytes.size))
        val kind = detectKindFromBytes(head)
            ?: when {
                suggestedName?.endsWith(".mp4", true) == true ||
                    suggestedName?.endsWith(".mov", true) == true -> GalleryKind.VIDEO
                else -> GalleryKind.IMAGE
            }
        val ext = extFor(kind, suggestedName, head)
        // 元ファイル名を可能な限り残す
        val base = suggestedName
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(80)
            ?.ifBlank { null }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = if (base != null) "${base}_$stamp.$ext" else "sw_$stamp.$ext"
        val out = File(dir(context), name)
        FileOutputStream(out).use { it.write(bytes) }
        return GalleryItem(name, out, kind, out.lastModified(), name, out.length())
    }

    /** ストリーム保存（動画向け） */
    fun saveFromStream(
        context: Context,
        input: java.io.InputStream,
        suggestedName: String?,
        expectedSize: Long = -1
    ): GalleryItem? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val tmp = File(dir(context), "tmp_$stamp.bin")
        var written = 0L
        val headBuf = ByteArray(12)
        var headLen = 0
        FileOutputStream(tmp).use { out ->
            val buf = ByteArray(64 * 1024)
            var first = true
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                if (first) {
                    headLen = minOf(12, n)
                    System.arraycopy(buf, 0, headBuf, 0, headLen)
                    first = false
                }
                out.write(buf, 0, n)
                written += n
            }
        }
        if (written < 64) {
            tmp.delete()
            return null
        }
        if (expectedSize > 0 && written < expectedSize * 0.95) {
            // 不完全ダウンロード
            tmp.delete()
            return null
        }
        val kind = detectKindFromBytes(headBuf.copyOf(headLen))
            ?: when {
                suggestedName?.endsWith(".mp4", true) == true -> GalleryKind.VIDEO
                else -> GalleryKind.IMAGE
            }
        val ext = extFor(kind, suggestedName, headBuf)
        val base = suggestedName
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
            ?.take(80)
            ?.ifBlank { null }
        val name = if (base != null) "${base}_$stamp.$ext" else "sw_$stamp.$ext"
        val out = File(dir(context), name)
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
        return GalleryItem(name, out, kind, out.lastModified(), name, out.length())
    }

    fun saveFromUri(context: Context, uri: Uri): GalleryItem? {
        val cr = context.contentResolver
        val type = cr.getType(uri) ?: ""
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val tmpName = "imp_$stamp.bin"
        val tmp = File(dir(context), tmpName)
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        } ?: return null
        val kind = detectKind(tmp) ?: when {
            type.contains("video") -> GalleryKind.VIDEO
            else -> GalleryKind.IMAGE
        }
        val ext = extFor(kind, null, null)
        val name = "imp_$stamp.$ext"
        val out = File(dir(context), name)
        tmp.renameTo(out)
        return GalleryItem(name, out, kind, out.lastModified(), name, out.length())
    }

    fun delete(context: Context, id: String): Boolean {
        val f = File(dir(context), id)
        return f.exists() && f.delete()
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
