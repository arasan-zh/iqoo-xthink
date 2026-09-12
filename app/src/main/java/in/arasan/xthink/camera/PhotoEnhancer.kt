package `in`.arasan.xthink.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
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
import `in`.arasan.xthink.guidance.PhotographerCrop
import `in`.arasan.xthink.guidance.SubjectBox
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
    fun analyse(uri: Uri, callbackExecutor: Executor, onResult: (Proposal?) -> Unit) {
        worker.execute {
            val started = System.currentTimeMillis()
            val small = runCatching { decode(uri, ANALYSIS_LONG_EDGE) }.getOrElse {
                Log.w(TAG, "enhance: decode failed", it)
                callbackExecutor.execute { onResult(null) }
                return@execute
            }
            val image = InputImage.fromBitmap(small, 0)
            faces.process(image).addOnSuccessListener(worker) { found ->
                val face = found.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() }
                if (face == null) {
                    Log.i(TAG, "enhance: no face, nothing to crop (%d ms)".format(System.currentTimeMillis() - started))
                    callbackExecutor.execute { onResult(null) }
                    return@addOnSuccessListener
                }
                pose.process(image).addOnCompleteListener(worker) { task ->
                    val body = task.result?.takeIf { task.isSuccessful }?.let { toBodyPose(it, small.height) }
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
                    val crop = PhotographerCrop.propose(box, eyesY, body, w / h)
                    val elapsed = System.currentTimeMillis() - started
                    if (crop == null) {
                        Log.i(TAG, "enhance: already framed face=%.2f body=%s (%d ms)".format(box.h, body != null, elapsed))
                        callbackExecutor.execute { onResult(null) }
                        return@addOnCompleteListener
                    }
                    val px = PhotographerCrop.toPixels(crop.crop, small.width, small.height)
                    val after = Bitmap.createBitmap(small, px[0], px[1], px[2], px[3])
                    Log.i(
                        TAG,
                        "enhance: crop %s body=%s reasons=%s (%d ms)".format(
                            crop.crop, body != null, crop.rationale, elapsed,
                        ),
                    )
                    val name = uri.lastPathSegment ?: "xthink"
                    callbackExecutor.execute { onResult(Proposal(uri, small, after, crop, name)) }
                }
            }.addOnFailureListener(worker) {
                Log.w(TAG, "enhance: face detection failed", it)
                callbackExecutor.execute { onResult(null) }
            }
        }
    }

    /**
     * Cut the crop from the full-resolution photo and save it next to the
     * original as `<name>_xthink.jpg`. Both files stay: the before and the
     * after. Returns the new Uri, on [callbackExecutor].
     */
    fun save(p: Proposal, callbackExecutor: Executor, onSaved: (Uri?) -> Unit) {
        worker.execute {
            val result = runCatching {
                val full = decode(p.sourceUri, 0)
                val px = PhotographerCrop.toPixels(p.proposal.crop, full.width, full.height)
                val cropped = Bitmap.createBitmap(full, px[0], px[1], px[2], px[3])
                val display = "xthink_" + System.currentTimeMillis() + "_enhanced.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, display)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/xThink")
                }
                val resolver = context.contentResolver
                val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore refused the insert")
                resolver.openOutputStream(out)!!.use { cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                Log.i(TAG, "enhance: saved ${cropped.width}x${cropped.height} -> $out")
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

    fun close() {
        faces.close()
        pose.close()
    }

    private companion object {
        const val TAG = "xThink"
        const val ANALYSIS_LONG_EDGE = 1024
        const val JPEG_QUALITY = 95
        /** Below this ML Kit is extrapolating a landmark it cannot see. */
        const val IN_FRAME = 0.6f
    }
}
