package `in`.arasan.xthink.camera

import android.graphics.Bitmap
import `in`.arasan.xthink.guidance.CropRect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

/**
 * Reads the text the camera is looking at - a whiteboard, a page, a badge,
 * a screen - with ML Kit's on-device recognizer. Lines come back in
 * reading order, top to bottom, one per line, ready to be typed.
 */
class ScreenReader {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    var busy: Boolean = false
        private set

    /** [onText] on [executor] with the text, empty when nothing was read. */
    fun read(bitmap: Bitmap, executor: Executor, onText: (String) -> Unit): Boolean =
        readWithBounds(bitmap, executor) { text, _ -> onText(text) }

    /**
     * The text, and the box all of it filled as fractions of [bitmap] -
     * null when nothing was read - so the lens can be steered at it.
     */
    fun readWithBounds(bitmap: Bitmap, executor: Executor, onResult: (String, CropRect?) -> Unit): Boolean {
        if (busy) return false
        busy = true
        val started = System.currentTimeMillis()
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                val text = lines.joinToString("\n")
                val boxes = result.textBlocks.mapNotNull { it.boundingBox }
                val box = if (boxes.isEmpty()) null else CropRect(
                    boxes.minOf { it.left } / bitmap.width.toFloat(), boxes.minOf { it.top } / bitmap.height.toFloat(),
                    boxes.maxOf { it.right } / bitmap.width.toFloat(), boxes.maxOf { it.bottom } / bitmap.height.toFloat(),
                )
                Log.i(TAG, "ocr: ${lines.size} lines, ${text.length} chars in ${System.currentTimeMillis() - started} ms" + (box?.let { " box %.2f,%.2f-%.2f,%.2f".format(it.left, it.top, it.right, it.bottom) } ?: ""))
                executor.execute { onResult(text, box) }
            }
            .addOnFailureListener {
                Log.w(TAG, "ocr failed", it)
                executor.execute { onResult("", null) }
            }
            .addOnCompleteListener { busy = false }
        return true
    }

    fun close() = recognizer.close()

    private companion object {
        const val TAG = "xThink"
    }
}
