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
    val name: String
)

object GalleryStore {
    private const val DIR = "switch_gallery"

    fun dir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    fun list(context: Context): List<GalleryItem> {
        val d = dir(context)
        return d.listFiles()
            ?.filter { it.isFile && (it.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "mp4", "mov")) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                val kind = if (f.extension.lowercase() in listOf("mp4", "mov")) GalleryKind.VIDEO else GalleryKind.IMAGE
                GalleryItem(f.name, f, kind, f.lastModified(), f.name)
            }
            ?: emptyList()
    }

    fun saveBytes(context: Context, bytes: ByteArray, suggestedName: String?): GalleryItem {
        val ext = when {
            suggestedName?.endsWith(".mp4", true) == true -> "mp4"
            suggestedName?.endsWith(".png", true) == true -> "png"
            suggestedName?.endsWith(".webp", true) == true -> "webp"
            else -> "jpg"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "sw_${stamp}.$ext"
        val out = File(dir(context), name)
        FileOutputStream(out).use { it.write(bytes) }
        val kind = if (ext == "mp4") GalleryKind.VIDEO else GalleryKind.IMAGE
        return GalleryItem(name, out, kind, out.lastModified(), name)
    }

    fun saveFromUri(context: Context, uri: Uri): GalleryItem? {
        val cr = context.contentResolver
        val type = cr.getType(uri) ?: ""
        val ext = when {
            type.contains("video") || type.contains("mp4") -> "mp4"
            type.contains("png") -> "png"
            else -> "jpg"
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = "imp_${stamp}.$ext"
        val out = File(dir(context), name)
        cr.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: return null
        val kind = if (ext == "mp4") GalleryKind.VIDEO else GalleryKind.IMAGE
        return GalleryItem(name, out, kind, out.lastModified(), name)
    }

    fun delete(context: Context, id: String): Boolean {
        val f = File(dir(context), id)
        return f.exists() && f.delete()
    }

    fun clearAll(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
