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
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import `in`.arasan.xthink.guidance.Retouch
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import `in`.arasan.xthink.guidance.BodyPose
import `in`.arasan.xthink.guidance.CropProposal
import `in`.arasan.xthink.guidance.CropRect
import `in`.arasan.xthink.guidance.Cut
import `in`.arasan.xthink.guidance.Extension
import `in`.arasan.xthink.guidance.Finishing
import `in`.arasan.xthink.guidance.Side
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

    /** One proposal: what the card shows, and what [saveFinal] needs. */
    class Proposal(
        val sourceUri: Uri,
        val before: Bitmap,
        val after: Bitmap,
        val proposal: CropProposal,
        val sourceName: String,
        /** Room painted in around the photo before the crop; empty for none. */
        val extensions: List<Extension>,
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

    /** For the retouch: everything in the photo, with a guess at what it is. */
    private val objects = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build(),
    )

    private val worker: Executor = Executors.newSingleThreadExecutor()

    /**
     * The look at a photo: the small decode for the review, a crop if one is
     * worth it, whether the photo is soft (missed focus or motion), and what
     * LaMa is to do once the review is up.
     */
    class Result(
        val small: Bitmap?,
        val proposal: Proposal?,
        val soft: Boolean = false,
        /** Index into Looks.ALL the coach suggested, or null. */
        val suggestedLook: Int? = null,
        /** The face, as a fraction of [small]. */
        val subject: SubjectBox? = null,
        /** The inpainter's instructions: holes to fill on the photo as shot, room to paint in. */
        val job: Finishing.LamaJob = Finishing.LamaJob.NONE,
        /** The coach's reason for its plan, in its words. */
        val why: String = "",
    )

    /**
     * The retouch, done: what LaMa did and why, the small photo with the
     * holes filled, and the same with the room painted in and the crop
     * applied when there is one.
     */
    class Clean(
        val job: Finishing.LamaJob,
        val why: String,
        val before: Bitmap,
        val after: Bitmap?,
    )

    /**
     * Someone who decides the finish - the on-device model, looking at the
     * photo. Called with the small photo, the detectors' facts in words and
     * the numbered list of what could be painted out; must answer exactly
     * once, on any thread, in the shape Finishing.parse reads, or null.
     * Absent, the rules decide alone.
     */
    fun interface FinishAdvisor {
        fun advise(photo: Bitmap, facts: String, candidates: String, answer: (String?) -> Unit)
    }

    /**
     * Look at the photo at [uri]: the face, the body, and - with an
     * [advisor] - everything in the frame, put to the coach as one
     * question. [onResult] on [callbackExecutor] with what to offer.
     */
    fun analyse(
        uri: Uri,
        callbackExecutor: Executor,
        advisor: FinishAdvisor? = null,
        paint: Boolean = true,
        onSmall: ((Bitmap) -> Unit)? = null,
        onResult: (Result) -> Unit,
    ) {
        worker.execute {
            val started = System.currentTimeMillis()
            val small = runCatching { decode(uri, ANALYSIS_LONG_EDGE) }.getOrElse {
                Log.w(TAG, "enhance: decode failed", it)
                callbackExecutor.execute { onResult(Result(null, null)) }
                return@execute
            }
            // The review opens on this at once; the rest arrives as it is worked out.
            if (onSmall != null) callbackExecutor.execute { onSmall(small) }
            val image = InputImage.fromBitmap(small, 0)
            val soft = isSoft(small)
            faces.process(image).addOnSuccessListener(worker) { found ->
                val face = found.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
                if (face == null) {
                    Log.i(TAG, "enhance: no face, nothing to crop soft=%s (%d ms)".format(soft, System.currentTimeMillis() - started))
                    callbackExecutor.execute { onResult(Result(small, null, soft)) }
                    return@addOnSuccessListener
                }
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
                val eyesY = if (eyeL != null && eyeR != null) (eyeL + eyeR) / 2f / h else null
                pose.process(image).addOnCompleteListener(worker) { task ->
                    val body = task.result?.takeIf { task.isSuccessful }?.let { toBodyPose(it, small.height) }
                    if (advisor == null) {
                        // The rules alone: the ladder's cut, reflected headroom, nothing painted out.
                        finish(uri, small, box, eyesY, body, emptyList(), Finishing.Plan.NONE, paint, soft, started, callbackExecutor, onResult)
                        return@addOnCompleteListener
                    }
                    // With a coach: everything the detectors know goes into one question.
                    objects.process(image).addOnCompleteListener(worker) { t ->
                        val things = t.result?.takeIf { t.isSuccessful } ?: emptyList()
                        val boxes = things.map { CropRect(it.boundingBox.left / w, it.boundingBox.top / h, it.boundingBox.right / w, it.boundingBox.bottom / h) }
                        val labels = things.map { o -> o.labels.maxByOrNull { it.confidence }?.text }
                        val candidates = Retouch.eligible(boxes, box, labels)
                        val facts = Finishing.facts(box, body, edgeSpread(small, box))
                        val list = Retouch.describe(candidates)
                        Log.i(TAG, "enhance: $facts ${things.size} found, ${candidates.size} offered:\n$list")
                        var answered = false
                        advisor.advise(small, facts, list) { words ->
                            worker.execute {
                                if (answered) return@execute
                                answered = true
                                val plan = Finishing.parse(words, candidates.size)
                                Log.i(TAG, "enhance: coach says '${words?.trim()?.replace('\n', '/')?.take(140)}' -> $plan")
                                finish(uri, small, box, eyesY, body, candidates, plan, paint, soft, started, callbackExecutor, onResult)
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

    /** The geometry, once the face, the body and (maybe) the coach's plan are known. */
    private fun finish(
        uri: Uri,
        small: Bitmap,
        box: SubjectBox,
        eyes: Float?,
        body: BodyPose?,
        candidates: List<Retouch.Candidate>,
        plan: Finishing.Plan,
        paint: Boolean,
        soft: Boolean,
        started: Long,
        callbackExecutor: Executor,
        onResult: (Result) -> Unit,
    ) {
        val suggestedLook = plan.look?.let { name -> Looks.ALL.indexOfFirst { it.name.equals(name, ignoreCase = true) }.takeIf { it >= 0 } }
        // Room painted in first - the plan's, as far as the rules allow -
        // then the crop of the bigger picture. The preview gets a reflected
        // strip; LaMa paints the real one in the retouch.
        // With the retouch off nothing is painted: no strips, no holes, whatever the plan said.
        val extensions = if (paint) Finishing.extensions(plan, box, body, edgeSpread(small, box)) else emptyList()
        val canvas = extend(small, extensions, null)
        val subject = Finishing.shift(box, extensions)
        val pose = Finishing.shift(body, extensions)
        val eyesY = eyes?.let { Finishing.shiftY(it, extensions) }
        val cw = canvas.width.toFloat()
        val ch = canvas.height.toFloat()
        var crop = if (plan.keepFrame && !plan.trimHeadroom) null else PhotographerCrop.propose(subject, eyesY, pose, cw / ch, preferredCut = plan.cut)
        if (crop == null && extensions.isNotEmpty()) {
            // Nothing to crop, but the room painted in is worth having on its own.
            crop = CropProposal(CropRect(0f, 0f, 1f, 1f), emptyList())
        }
        val job = if (paint) Finishing.job(plan, candidates, extensions) else Finishing.LamaJob.NONE
        val elapsed = System.currentTimeMillis() - started
        if (crop == null) {
            Log.i(TAG, "enhance: already framed face=%.2f body=%s remove=%d (%d ms)".format(box.h, body != null, job.remove.size, elapsed))
            callbackExecutor.execute { onResult(Result(small, null, soft, suggestedLook, box, job, plan.why)) }
            return
        }
        val reasons = Finishing.describe(Finishing.LamaJob(emptyList(), extensions)) + crop.rationale
        val px = PhotographerCrop.toPixels(crop.crop, canvas.width, canvas.height)
        val after = Bitmap.createBitmap(canvas, px[0], px[1], px[2], px[3])
        Log.i(TAG, "enhance: crop %s extend=%s remove=%d body=%s reasons=%s (%d ms)".format(crop.crop, extensions, job.remove.size, body != null, reasons, elapsed))
        val name = uri.lastPathSegment ?: "xthink"
        callbackExecutor.execute {
            onResult(Result(small, Proposal(uri, small, after, CropProposal(crop.crop, reasons), name, extensions), soft, suggestedLook, box, job, plan.why))
        }
    }

    /**
     * Once the review is up: LaMa does what the plan says - fills the holes
     * on the photo as shot, then paints the room in and cuts the crop.
     * [onResult] on [callbackExecutor] with the retouch, or null when the
     * job is empty, the net is not on the phone, or it failed.
     */
    fun retouch(result: Result, inpainter: Inpainter, callbackExecutor: Executor, onResult: (Clean?) -> Unit) {
        val small = result.small
        val job = result.job
        if (small == null || job.isEmpty || !inpainter.available) {
            callbackExecutor.execute { onResult(null) }
            return
        }
        worker.execute {
            val started = System.currentTimeMillis()
            var cleaned = small
            if (job.remove.isNotEmpty()) {
                val filled = inpainter.inpaint(small, job.remove)
                if (filled == null) {
                    Log.w(TAG, "retouch: inpainting failed")
                    callbackExecutor.execute { onResult(null) }
                    return@execute
                }
                cleaned = filled
            }
            val after = result.proposal?.let { cropOf(cleaned, it, inpainter) }
            Log.i(TAG, "retouch: ${job.remove.size} hole(s) filled, ${job.extend.size} strip(s) painted (%d ms)".format(System.currentTimeMillis() - started))
            callbackExecutor.execute { onResult(Clean(job, result.why, cleaned, after)) }
        }
    }

    /** The proposal's crop, cut from another rendering of the same photo; the room painted in by [inpainter] when given. */
    private fun cropOf(src: Bitmap, proposal: Proposal, inpainter: Inpainter?): Bitmap {
        val canvas = extend(src, proposal.extensions, inpainter)
        val px = PhotographerCrop.toPixels(proposal.proposal.crop, canvas.width, canvas.height)
        return Bitmap.createBitmap(canvas, px[0], px[1], px[2], px[3])
    }

    /**
     * The photo with room around it. Each strip is first filled with the
     * edge beside it, reflected and softened - a fair guess on the plain
     * edges the rules allow, and the preview until LaMa is done - then,
     * given an [inpainter], painted by LaMa in a band along that edge.
     */
    private fun extend(src: Bitmap, extensions: List<Extension>, inpainter: Inpainter?): Bitmap {
        if (extensions.isEmpty()) return src
        val c = Finishing.canvas(src.width, src.height, extensions)
        val out = Bitmap.createBitmap(c[0], c[1], Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, c[2].toFloat(), c[3].toFloat(), null)
        for (e in extensions) {
            val hp = Retouch.toPixels(Finishing.hole(e, extensions), out.width, out.height)
            val vertical = e.side == Side.TOP || e.side == Side.BOTTOM
            val depth = if (vertical) hp[3].coerceIn(1, src.height) else hp[2].coerceIn(1, src.width)
            val strip = when (e.side) {
                Side.TOP -> Bitmap.createBitmap(src, 0, 0, src.width, depth)
                Side.BOTTOM -> Bitmap.createBitmap(src, 0, src.height - depth, src.width, depth)
                Side.LEFT -> Bitmap.createBitmap(src, 0, 0, depth, src.height)
                Side.RIGHT -> Bitmap.createBitmap(src, src.width - depth, 0, depth, src.height)
            }
            val soft = Bitmap.createScaledBitmap(
                Bitmap.createScaledBitmap(strip, maxOf(1, strip.width / 24), maxOf(1, strip.height / 24), true),
                hp[2], hp[3], true,
            )
            val flip = Matrix().apply { if (vertical) preScale(1f, -1f) else preScale(-1f, 1f) }
            val mirrored = Bitmap.createBitmap(soft, 0, 0, soft.width, soft.height, flip, true)
            canvas.drawBitmap(mirrored, hp[0].toFloat(), hp[1].toFloat(), null)
        }
        if (inpainter == null) return out
        var painted = out
        for (e in extensions) {
            painted = inpainter.inpaint(painted, listOf(Finishing.hole(e, extensions)), Finishing.band(e, extensions)) ?: painted
        }
        return painted
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
     * Write what the photographer chose: LaMa's work (if [job], with an
     * [inpainter]), the crop (if [useCrop]) and the look (if [look] is a
     * colour matrix), from the full-resolution photo, saved next to the
     * original as a new file. The original is never touched. Nothing is
     * written - and null is returned - when the choice is the photo
     * exactly as shot.
     */
    fun saveFinal(
        source: Uri,
        crop: Proposal?,
        useCrop: Boolean,
        look: FloatArray?,
        callbackExecutor: Executor,
        job: Finishing.LamaJob? = null,
        inpainter: Inpainter? = null,
        onSaved: (Uri?) -> Unit,
    ) {
        val cropping = useCrop && crop != null
        val painting = job != null && !job.isEmpty && inpainter != null && inpainter.available
        if (!cropping && look == null && !painting) {
            callbackExecutor.execute { onSaved(null) }
            return
        }
        worker.execute {
            val result = runCatching {
                var full = decode(source, 0)
                if (painting && job!!.remove.isNotEmpty()) {
                    // The same holes, on the full photo; LaMa works in a
                    // window around them, so the rest keeps its pixels.
                    full = inpainter!!.inpaint(full, job.remove) ?: full
                }
                if (cropping) {
                    // The room painted in - by LaMa when the retouch is on,
                    // else the reflected guess - then the crop.
                    full = cropOf(full, crop!!, if (painting) inpainter else null)
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
                Log.i(TAG, "enhance: saved ${full.width}x${full.height} crop=$cropping look=${look != null} painted=$painting -> $out")
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
     * Luminance spread along each edge - what a strip painted there would
     * have to blend with. Above the head it is the face's own columns,
     * widened a little; the other edges are a band the full length of the
     * edge. Sampled on a grid; exactness is not the point.
     */
    private fun edgeSpread(bmp: Bitmap, face: SubjectBox): Map<Side, Float> {
        val w = bmp.width
        val h = bmp.height
        val faceTop = ((face.cy - face.h / 2f) * h).toInt().coerceIn(1, h)
        val rows = maxOf(faceTop, (h * 0.06f).toInt()).coerceAtMost(h)
        val x0 = ((face.cx - face.w) * w).toInt().coerceIn(0, w - 1)
        val x1 = ((face.cx + face.w) * w).toInt().coerceIn(x0 + 1, w)
        val band = (minOf(w, h) * EDGE_BAND).toInt().coerceAtLeast(2)
        return mapOf(
            Side.TOP to spread(bmp, x0, 0, x1, rows),
            Side.LEFT to spread(bmp, 0, 0, band, h),
            Side.RIGHT to spread(bmp, w - band, 0, w, h),
            Side.BOTTOM to spread(bmp, 0, h - band, w, h),
        )
    }

    private fun spread(bmp: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val stepX = maxOf(1, (x1 - x0) / 24)
        val stepY = maxOf(1, (y1 - y0) / 24)
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var y = y0
        while (y < y1) {
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

    fun close() {
        faces.close()
        pose.close()
        objects.close()
    }

    private companion object {
        const val TAG = "xThink"
        const val ANALYSIS_LONG_EDGE = 1024
        const val JPEG_QUALITY = 95
        /** Laplacian variance below which a 1024px decode is called soft. */
        const val SOFT_FLOOR = 60.0
        /** Below this ML Kit is extrapolating a landmark it cannot see. */
        const val IN_FRAME = 0.6f
        /** The band along a side or bottom edge whose texture is measured, as a fraction of the short side. */
        const val EDGE_BAND = 0.08f
    }
}
