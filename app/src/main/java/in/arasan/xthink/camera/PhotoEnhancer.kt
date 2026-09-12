package `in`.arasan.xthink.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import `in`.arasan.xthink.guidance.BodyPose
import `in`.arasan.xthink.guidance.CropProposal
import `in`.arasan.xthink.guidance.CropRect
import `in`.arasan.xthink.guidance.Cut
import `in`.arasan.xthink.guidance.HeadroomExtension
import `in`.arasan.xthink.guidance.PhotographerCrop
import `in`.arasan.xthink.guidance.SubjectBox
import `in`.arasan.xthink.ui.Looks
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * After the shutter: find the person in the saved photo, ask
 * [PhotographerCrop] what a photographer would do, and hold the answer
 * until the photographer says yes.
 *
 * Detection runs on a ~1000px decode - plenty for a body, and fast. The
 * crop itself is cut from the full-resolution JPEG only on [save], so a
 * dismissed proposal costs nothing but the small decode. Every result is
 * a fraction of the photo, which is what lets the two sizes agree.
 */
class PhotoEnhancer(private val context: Context) {

    /** One proposal: what the card shows, and what [save] needs. */
    class Proposal(
        val sourceUri: Uri,
        val before: Bitmap,
        val after: Bitmap,
        val proposal: CropProposal,
        val sourceName: String,
        /** Fraction of the photo's height added above it before cropping; 0 for none. */
        val extraTop: Float,
    )

    private val faces = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .build(),
    )

    private val pose = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build(),
    )

    private val worker: Executor = Executors.newSingleThreadExecutor()

    /**
     * Look at the photo at [uri]. [onResult] is called on [callbackExecutor]
     * with a proposal, or null when the photo is already framed or has no
     * person in it.
     */
    /**
     * The look at a photo: the small decode for the review, a crop if one is
     * worth it, and whether the photo is soft (missed focus or motion).
     */
    class Result(
        val small: Bitmap?,
        val proposal: Proposal?,
        val soft: Boolean = false,
        /** Index into Looks.ALL the coach suggested, or null. */
        val suggestedLook: Int? = null,
    )

    /**
     * Someone who names the cut - the on-device model. Called with the small
     * photo; must answer exactly once, on any thread, with the coach's words
     * or null. Absent, the rules decide alone.
     */
    fun interface CutAdvisor {
        fun advise(photo: Bitmap, answer: (String?) -> Unit)
    }

    fun analyse(
        uri: Uri,
        callbackExecutor: Executor,
        advisor: CutAdvisor? = null,
        onResult: (Result) -> Unit,
    ) {
        worker.execute {
            val started = System.currentTimeMillis()
            val small = runCatching { decode(uri, ANALYSIS_LONG_EDGE) }.getOrElse {
                Log.w(TAG, "enhance: decode failed", it)
                callbackExecutor.execute { onResult(Result(null, null)) }
                return@execute
            }
            val image = InputImage.fromBitmap(small, 0)
            val soft = isSoft(small)
            faces.process(image).addOnSuccessListener(worker) { found ->
                val face = found.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
                if (face == null) {
                    Log.i(TAG, "enhance: no face, nothing to crop soft=%s (%d ms)".format(soft, System.currentTimeMillis() - started))
                    callbackExecutor.execute { onResult(Result(small, null, soft)) }
                    return@addOnSuccessListener
                }
                pose.process(image).addOnCompleteListener(worker) { task ->
                    val body = task.result?.takeIf { task.isSuccessful }?.let { toBodyPose(it, small.height) }
                    // The cut: the coach's word if there is a coach, else the rules'.
                    if (advisor == null) {
                        finish(uri, small, face, body, null, null, soft, started, callbackExecutor, onResult)
                    } else {
                        var answered = false
                        advisor.advise(small) { words ->
                            worker.execute {
                                if (answered) return@execute
                                answered = true
                                val cut = PhotographerCrop.parseCut(words)
                                val look = words?.let { w ->
                                    Looks.ALL.indexOfFirst { w.contains(it.name, ignoreCase = true) }.takeIf { it >= 0 }
                                }
                                Log.i(TAG, "enhance: coach says '${words?.trim()?.take(40)}' -> cut=$cut look=${look?.let { Looks.ALL[it].name }}")
                                finish(uri, small, face, body, cut, look, soft, started, callbackExecutor, onResult)
                            }
                        }
                    }
                }
            }.addOnFailureListener(worker) {
                Log.w(TAG, "enhance: face detection failed", it)
                callbackExecutor.execute { onResult(Result(small, null, soft)) }
            }
        }
    }

    /** The geometry, once the face, the body and (maybe) the coach's cut are known. */
    private fun finish(
        uri: Uri,
        small: Bitmap,
        face: com.google.mlkit.vision.face.Face,
        body: BodyPose?,
        preferredCut: Cut?,
        suggestedLook: Int?,
        soft: Boolean,
        started: Long,
        callbackExecutor: Executor,
        onResult: (Result) -> Unit,
    ) {
        run {
                    val w = small.width.toFloat()
                    val h = small.height.toFloat()
                    val box = SubjectBox(
                        cx = face.boundingBox.exactCenterX() / w,
                        cy = face.boundingBox.exactCenterY() / h,
                        w = face.boundingBox.width() / w,
                        h = face.boundingBox.height() / h,
                    )
                    val eyeL = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.y
                    val eyeR = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.y
                    var eyesY = if (eyeL != null && eyeR != null) (eyeL + eyeR) / 2f / h else null

                    // A head against a plain top edge: add the missing room
                    // above it first, then crop the taller picture.
                    val extra = HeadroomExtension.extraTop(box, topStripStdDev(small, box))
                    var canvas = small
                    var subject = box
                    var pose = body
                    if (extra > 0f) {
                        canvas = extendTop(small, extra)
                        subject = HeadroomExtension.shift(box, extra)
                        pose = HeadroomExtension.shift(body, extra)
                        eyesY = eyesY?.let { HeadroomExtension.shiftY(it, extra) }
                    }
                    val cw = canvas.width.toFloat()
                    val ch = canvas.height.toFloat()
                    var crop = PhotographerCrop.propose(subject, eyesY, pose, cw / ch, preferredCut = preferredCut)
                    if (crop == null && extra > 0f) {
                        // Nothing to crop, but the room above was worth adding on its own.
                        crop = CropProposal(CropRect(0f, 0f, 1f, 1f), emptyList())
                    }
                    val elapsed = System.currentTimeMillis() - started
                    if (crop == null) {
                        Log.i(TAG, "enhance: already framed face=%.2f body=%s (%d ms)".format(box.h, body != null, elapsed))
                        callbackExecutor.execute { onResult(Result(small, null, soft, suggestedLook)) }
                        return
                    }
                    val reasons = if (extra > 0f) listOf("Added space above the head") + crop.rationale else crop.rationale
                    val px = PhotographerCrop.toPixels(crop.crop, canvas.width, canvas.height)
                    val after = Bitmap.createBitmap(canvas, px[0], px[1], px[2], px[3])
                    Log.i(
                        TAG,
                        "enhance: crop %s extraTop=%.2f body=%s reasons=%s (%d ms)".format(
                            crop.crop, extra, body != null, reasons, elapsed,
                        ),
                    )
                    val name = uri.lastPathSegment ?: "xthink"
                    callbackExecutor.execute {
                        onResult(Result(small, Proposal(uri, small, after, CropProposal(crop.crop, reasons), name, extra), soft, suggestedLook))
                    }
        }
    }

    /**
     * Soft or not: the same Laplacian measure the live gate uses, on the
     * 1024px decode, against a fixed floor. A sharp phone photo scores in
     * the hundreds; a missed focus or a shaken frame in the tens.
     */
    private fun isSoft(bmp: Bitmap): Boolean {
        val step = 3
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        val w = bmp.width
        val h = bmp.height
        val row = IntArray(w)
        val prev = IntArray(w)
        val next = IntArray(w)
        var y = step
        while (y < h - step) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            bmp.getPixels(prev, 0, w, 0, y - step, w, 1)
            bmp.getPixels(next, 0, w, 0, y + step, w, 1)
            var x = step
            while (x < w - step) {
                val l = 4 * lum(row[x]) - lum(row[x - step]) - lum(row[x + step]) - lum(prev[x]) - lum(next[x])
                sum += l
                sumSq += l.toDouble() * l
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return false
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return variance < SOFT_FLOOR
    }

    private fun lum(c: Int): Int = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    /**
     * Write what the photographer chose: the crop (if [useCrop]) and the
     * look (if [look] is a colour matrix), from the full-resolution photo,
     * saved next to the original as a new file. The original is never
     * touched. Nothing is written - and null is returned - when the choice
     * is the photo exactly as shot.
     */
    fun saveFinal(
        source: Uri,
        crop: Proposal?,
        useCrop: Boolean,
        look: FloatArray?,
        callbackExecutor: Executor,
        onSaved: (Uri?) -> Unit,
    ) {
        val cropping = useCrop && crop != null
        if (!cropping && look == null) {
            callbackExecutor.execute { onSaved(null) }
            return
        }
        worker.execute {
            val result = runCatching {
                var full = decode(source, 0)
                if (cropping) {
                    if (crop!!.extraTop > 0f) full = extendTop(full, crop.extraTop)
                    val px = PhotographerCrop.toPixels(crop.proposal.crop, full.width, full.height)
                    full = Bitmap.createBitmap(full, px[0], px[1], px[2], px[3])
                }
                if (look != null) {
                    val out = Bitmap.createBitmap(full.width, full.height, Bitmap.Config.ARGB_8888)
                    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(look) }
                    Canvas(out).drawBitmap(full, 0f, 0f, paint)
                    full = out
                }
                val display = "xthink_" + System.currentTimeMillis() + "_enhanced.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, display)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/xThink")
                }
                val resolver = context.contentResolver
                val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore refused the insert")
                resolver.openOutputStream(out)!!.use { full.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                Log.i(TAG, "enhance: saved ${full.width}x${full.height} crop=$cropping look=${look != null} -> $out")
                out
            }.onFailure { Log.e(TAG, "enhance: save failed", it) }.getOrNull()
            callbackExecutor.execute { onSaved(result) }
        }
    }

    /**
     * Decode with EXIF orientation applied, so a portrait photo is upright
     * and "top" means top. [longEdge] 0 means full size.
     */
    private fun decode(uri: Uri, longEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            if (longEdge > 0) {
                val longest = maxOf(info.size.width, info.size.height)
                var sample = 1
                while (longest / (sample * 2) >= longEdge) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
        }
    }

    /**
     * Rows of the body as fractions of the photo's height. A row is the
     * mean of its left and right landmark when both are confidently in the
     * frame; a landmark that ML Kit is guessing about (low likelihood) is
     * left out so the crop rule falls back rather than cutting on a guess.
     */
    private fun toBodyPose(pose: Pose, height: Int): BodyPose? {
        if (pose.allPoseLandmarks.isEmpty()) return null
        fun row(left: Int, right: Int): Float? {
            val l = pose.getPoseLandmark(left)?.takeIf { it.inFrameLikelihood >= IN_FRAME }
            val r = pose.getPoseLandmark(right)?.takeIf { it.inFrameLikelihood >= IN_FRAME }
            val ys = listOfNotNull(l?.position?.y, r?.position?.y)
            if (ys.isEmpty()) return null
            val y = ys.average().toFloat() / height
            return if (y in 0f..1f) y else null
        }
        return BodyPose(
            shoulderY = row(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER),
            hipY = row(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP),
            kneeY = row(PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE),
            ankleY = row(PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE),
        )
    }

    /**
     * Luminance spread of the rows above the face, across the face's own
     * columns widened a little - the part an extension would have to
     * blend with. Sampled on a grid; exactness is not the point.
     */
    private fun topStripStdDev(bmp: Bitmap, face: SubjectBox): Float {
        val faceTop = ((face.cy - face.h / 2f) * bmp.height).toInt().coerceIn(1, bmp.height)
        val rows = maxOf(faceTop, (bmp.height * 0.06f).toInt()).coerceAtMost(bmp.height)
        val x0 = ((face.cx - face.w) * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val x1 = ((face.cx + face.w) * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val stepX = maxOf(1, (x1 - x0) / 24)
        val stepY = maxOf(1, rows / 12)
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var y = 0
        while (y < rows) {
            var x = x0
            while (x < x1) {
                val c = bmp.getPixel(x, y)
                val l = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                sum += l
                sumSq += l * l
                n++
                x += stepX
            }
            y += stepY
        }
        if (n < 4) return Float.MAX_VALUE
        val mean = sum / n
        return kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    /**
     * A taller picture with [extra] of its height added on top: the top
     * strip reflected, then softened by a scale down and up so the seam
     * and any texture disappear. Only ever used on a plain strip.
     */
    private fun extendTop(src: Bitmap, extra: Float): Bitmap {
        val add = (src.height * extra).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(src.width, src.height + add, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, 0f, add.toFloat(), null)
        val stripH = add.coerceAtMost(src.height)
        val strip = Bitmap.createBitmap(src, 0, 0, src.width, stripH)
        val soft = Bitmap.createScaledBitmap(
            Bitmap.createScaledBitmap(strip, maxOf(1, src.width / 24), maxOf(1, stripH / 24), true),
            src.width, add, true,
        )
        val flip = Matrix().apply { preScale(1f, -1f) }
        val mirrored = Bitmap.createBitmap(soft, 0, 0, soft.width, soft.height, flip, true)
        canvas.drawBitmap(mirrored, 0f, 0f, null)
        return out
    }

    fun close() {
        faces.close()
        pose.close()
    }

    private companion object {
        const val TAG = "xThink"
        const val ANALYSIS_LONG_EDGE = 1024
        const val JPEG_QUALITY = 95
        /** Laplacian variance below which a 1024px decode is called soft. */
        const val SOFT_FLOOR = 60.0
        /** Below this ML Kit is extrapolating a landmark it cannot see. */
        const val IN_FRAME = 0.6f
    }
}
