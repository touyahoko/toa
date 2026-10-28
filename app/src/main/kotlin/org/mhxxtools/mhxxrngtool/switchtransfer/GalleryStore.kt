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
        if (fromName != null && fromName in listOf("jpg", "jpeg", "png", "webp", "mp4", "mov")) return fromName
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
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeBase = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val name = "$safeBase.$ext"
        val out = File(dir(context), name)
        FileOutputStream(out).use { it.write(bytes) }
        return GalleryItem(out.name, out, kind, out.lastModified(), out.name, out.length())
    }

    fun delete(context: Context, item: GalleryItem): Boolean {
        return item.file.delete()
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
