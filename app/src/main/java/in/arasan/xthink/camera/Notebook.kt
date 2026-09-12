package `in`.arasan.xthink.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Things worth remembering, kept where the photographer can find them:
 * `Documents/xThink/xthink-notes.md`, one dated entry per save, Markdown so
 * any app can read it. Search is the Files app's, not ours - honest for
 * the time there was.
 */
class Notebook(private val context: Context) {

    fun append(kind: String, prompt: String, body: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri("external")
        val existing: Uri? = resolver.query(
            collection,
            arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
            arrayOf("Documents/xThink/", FILE),
            null,
        )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null }
        val uri = existing ?: resolver.insert(
            collection,
            ContentValues().apply {
                put(MediaStore.Files.FileColumns.DISPLAY_NAME, FILE)
                put(MediaStore.Files.FileColumns.MIME_TYPE, "text/markdown")
                put(MediaStore.Files.FileColumns.RELATIVE_PATH, "Documents/xThink")
            },
        ) ?: return false
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val entry = buildString {
            if (existing == null) append("# xThink notes\n\n")
            append("## $stamp · $kind\n\n")
            if (prompt.isNotBlank()) append("> $prompt\n\n")
            append(body.trim()).append("\n\n")
        }
        return runCatching {
            resolver.openOutputStream(uri, "wa")!!.use { it.write(entry.toByteArray()) }
            Log.i(TAG, "notebook: saved ${body.length} chars -> $uri")
            true
        }.onFailure { Log.e(TAG, "notebook: save failed", it) }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "xThink"
        const val FILE = "xthink-notes.md"
    }
}
