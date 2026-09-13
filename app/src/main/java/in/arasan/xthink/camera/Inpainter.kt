package `in`.arasan.xthink.camera

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import `in`.arasan.xthink.guidance.CropRect
import `in`.arasan.xthink.guidance.Retouch
import java.io.File
import java.nio.FloatBuffer

/**
 * LaMa, on the phone. Fills holes in a photo with what was probably
 * behind them: big-lama exported to ONNX at a fixed 512 x 512, run with
 * ONNX Runtime on the CPU.
 *
 * The model never sees the whole photo. [Retouch.window] picks the
 * smallest square around the holes (with context), that square goes
 * through the net at 512, and only the filled pixels - feathered at the
 * edge - are put back at the photo's own resolution. A stray cup in a
 * 12-megapixel frame keeps the frame's sharpness everywhere else.
 *
 * The holes are drawn with a margin (the desk test showed it: a hole that
 * hugs the object leaves a ghost of it; ten pixels of margin at 512
 * removes it cleanly).
 *
 * Like the coach's model, the network file is not in the APK. It is
 * looked for in the same places; absent, [available] is false and the
 * review simply offers no retouch.
 */
class Inpainter(private val context: Context) {

    private var session: OrtSession? = null
    private var loadFailed = false

    fun modelFile(): File? {
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { File(it, "models") },
            File("/data/local/tmp/llm"),
        )
        return candidates.asSequence()
            .flatMap { dir -> dir.listFiles { f -> f.name.endsWith(".onnx") && f.name.contains("lama") }?.asSequence() ?: emptySequence() }
            .sortedByDescending { it.length() }
            .firstOrNull()
    }

    val available: Boolean get() = !loadFailed && modelFile() != null

    /** For the chip: the graph is being loaded / a fill is running. */
    @Volatile var loading: Boolean = false
        private set
    @Volatile var painting: Boolean = false
        private set

    /** Seconds, once, on whichever thread calls; the ONNX session is kept. */
    @Synchronized
    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (loadFailed) return null
        val file = modelFile() ?: return null
        val started = SystemClock.uptimeMillis()
        loading = true
        val env = OrtEnvironment.getEnvironment()
        // Optimising the graph is most of the first load (tens of seconds).
        // The optimised graph is written once to the cache and read back
        // from then on, un-optimised again, in a few seconds.
        val cache = File(context.cacheDir, "lama-opt-${file.length()}.onnx")
        if (cache.exists() && cache.length() > 0) {
            val fast = runCatching {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                }
                env.createSession(cache.absolutePath, opts)
            }.onFailure { Log.w(TAG, "inpaint: cached graph unusable, rebuilding", it); cache.delete() }.getOrNull()
            if (fast != null) {
                session = fast
                Log.i(TAG, "inpaint: loaded the cached graph in ${SystemClock.uptimeMillis() - started} ms")
                return fast
            }
        }
        return runCatching {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(THREADS)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setOptimizedModelFilePath(cache.absolutePath)
            }
            env.createSession(file.absolutePath, opts).also {
                session = it
                loading = false
                Log.i(TAG, "inpaint: loaded ${file.name} (${file.length() / 1_000_000} MB) in ${SystemClock.uptimeMillis() - started} ms; graph cached ${cache.length() / 1_000_000} MB")
            }
        }.onFailure {
            loadFailed = true
            loading = false
            Log.e(TAG, "inpaint: could not load ${file.name}", it)
        }.getOrNull()
    }

    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Load the session now, in the background, so the first review does not wait for it. */
    fun warmUp() {
        if (session != null || loadFailed || modelFile() == null) return
        loader.execute { ensureSession() }
    }

    /**
     * [src] with [holes] (fractions of the frame) filled. Synchronous and
     * slow - seconds - so call it off the main thread. Returns null when
     * the network is not on the phone or failed.
     *
     * The net works inside [where] - by default the square Retouch.window
     * picks around the holes. A strip painted in along an edge passes its
     * own band instead: not square, squashed to the net's square and
     * stretched back, which on a plain edge costs nothing visible.
     */
    fun inpaint(src: Bitmap, holes: List<CropRect>, where: CropRect? = null): Bitmap? {
        if (holes.isEmpty()) return null
        val s = ensureSession() ?: return null
        val started = SystemClock.uptimeMillis()
        painting = true
        return runCatching {
            val aspect = src.width.toFloat() / src.height
            val window = where ?: Retouch.window(holes, aspect)
            val wp = Retouch.toPixels(window, src.width, src.height)
            val square = Bitmap.createBitmap(src, wp[0], wp[1], wp[2], wp[3])
            val net = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)

            // The mask at 512: the holes inside the window, plus the margin.
            val mask = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ALPHA_8)
            val mc = Canvas(mask)
            val mp = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
            val local = holes.map { Retouch.within(it, window) }
            for (h in local) {
                val r = RectF(h.left * SIDE - MARGIN_PX, h.top * SIDE - MARGIN_PX, h.right * SIDE + MARGIN_PX, h.bottom * SIDE + MARGIN_PX)
                mc.drawRect(r, mp)
            }

            val image = FloatArray(3 * SIDE * SIDE)
            val maskF = FloatArray(SIDE * SIDE)
            val px = IntArray(SIDE * SIDE)
            net.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE)
            val mpx = ByteArray(SIDE * SIDE)
            java.nio.ByteBuffer.wrap(mpx).let { mask.copyPixelsToBuffer(it) }
            val plane = SIDE * SIDE
            for (i in 0 until plane) {
                val c = px[i]
                image[i] = ((c shr 16) and 0xFF) / 255f
                image[plane + i] = ((c shr 8) and 0xFF) / 255f
                image[2 * plane + i] = (c and 0xFF) / 255f
                maskF[i] = if (mpx[i].toInt() != 0) 1f else 0f
            }

            val env = OrtEnvironment.getEnvironment()
            val out: FloatArray
            OnnxTensor.createTensor(env, FloatBuffer.wrap(image), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { ti ->
                OnnxTensor.createTensor(env, FloatBuffer.wrap(maskF), longArrayOf(1, 1, SIDE.toLong(), SIDE.toLong())).use { tm ->
                    s.run(mapOf("image" to ti, "mask" to tm)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val arr = result[0].value as Array<Array<Array<FloatArray>>>
                        out = FloatArray(3 * plane)
                        for (ch in 0 until 3) for (y in 0 until SIDE) System.arraycopy(arr[0][ch][y], 0, out, ch * plane + y * SIDE, SIDE)
                    }
                }
            }

            // The filled square, 0..255 per channel, back to the window's size.
            val filledPx = IntArray(plane)
            for (i in 0 until plane) {
                val r = out[i].toInt().coerceIn(0, 255)
                val g = out[plane + i].toInt().coerceIn(0, 255)
                val b = out[2 * plane + i].toInt().coerceIn(0, 255)
                filledPx[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val filled = Bitmap.createBitmap(filledPx, SIDE, SIDE, Bitmap.Config.ARGB_8888)
            val filledUp = Bitmap.createScaledBitmap(filled, wp[2], wp[3], true)

            // Feathered mask: the same holes a touch smaller than the
            // margin, blurred by a scale down and up, so the fill blends
            // into the untouched pixels around it.
            val soft = Bitmap.createBitmap(SIDE / 4, SIDE / 4, Bitmap.Config.ALPHA_8)
            val sc = Canvas(soft)
            for (h in local) {
                val r = RectF(
                    (h.left * SIDE - MARGIN_PX * 0.5f) / 4f, (h.top * SIDE - MARGIN_PX * 0.5f) / 4f,
                    (h.right * SIDE + MARGIN_PX * 0.5f) / 4f, (h.bottom * SIDE + MARGIN_PX * 0.5f) / 4f,
                )
                sc.drawRect(r, mp)
            }
            val featherUp = Bitmap.createScaledBitmap(soft, wp[2], wp[3], true)

            val result = src.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(result)
            val layer = canvas.saveLayer(RectF(wp[0].toFloat(), wp[1].toFloat(), (wp[0] + wp[2]).toFloat(), (wp[1] + wp[3]).toFloat()), null)
            canvas.drawBitmap(filledUp, wp[0].toFloat(), wp[1].toFloat(), null)
            val keep = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            canvas.drawBitmap(featherUp, wp[0].toFloat(), wp[1].toFloat(), keep)
            canvas.restoreToCount(layer)
            Log.i(TAG, "inpaint: ${holes.size} hole(s) in a ${wp[2]} px window of ${src.width}x${src.height} in ${SystemClock.uptimeMillis() - started} ms")
            result
        }.onFailure { Log.e(TAG, "inpaint failed", it) }.getOrNull().also { painting = false }
    }

    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    private companion object {
        const val TAG = "xThink"
        const val SIDE = 512
        /** Margin around each hole at 512, in pixels. Ten was the desk-tested sweet spot. */
        const val MARGIN_PX = 10f
        const val THREADS = 4
    }
}
