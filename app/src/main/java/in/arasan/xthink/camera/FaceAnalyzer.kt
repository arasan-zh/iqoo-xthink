package `in`.arasan.xthink.camera

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import `in`.arasan.xthink.guidance.EyeLine
import `in`.arasan.xthink.guidance.FrameMapping
import `in`.arasan.xthink.guidance.FrameRect
import `in`.arasan.xthink.guidance.ShotType
import `in`.arasan.xthink.guidance.ShotTypeSelector
import `in`.arasan.xthink.guidance.SubjectBox

private const val TAG = "xThink"

/** What one analysed frame tells us about the subject. */
data class FaceResult(
    val subject: SubjectBox?,
    val eyes: EyeLine?,
    val faceCount: Int,
    val shotType: ShotType,
    val detectMs: Long,
    val dtMs: Long,
)

/**
 * ML Kit face detection, wired to the settings CLAUDE.md fixes:
 * PERFORMANCE_MODE_FAST, landmarks ON, classification OFF.
 *
 * Everything geometric is delegated to [FrameMapping] in :guidance, which is
 * unit tested. This class handles the Android plumbing and one policy decision
 * that needs a detector to make sense of:
 *
 *  - **Which face is the subject.** Portrait-only build: the composition
 *    always targets ONE face, the largest, however many are in frame. Face
 *    size is the most stable signal ML Kit gives - it does not flicker between
 *    candidates as the phone moves the way "most central" would - and the
 *    nearest person is almost always who the photographer means to shoot.
 *    Group framing (a box spanning everybody) needs a mode tab to reach
 *    deliberately, so it is not attempted here.
 */
class FaceAnalyzer(
    private val onResult: (FaceResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .build()
    )

    private var lastAnalysisMs = 0L
    private var busy = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val now = SystemClock.uptimeMillis()

        // Throttled, per CLAUDE.md. STRATEGY_KEEP_ONLY_LATEST already drops
        // stale frames; this caps the rate we ask the NPU to work at, which is
        // thermal budget the v0.5-cool governor will want back.
        if (busy || now - lastAnalysisMs < MIN_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val dtMs = if (lastAnalysisMs == 0L) 0L else (now - lastAnalysisMs).coerceIn(0L, MAX_DT_MS)
        lastAnalysisMs = now
        busy = true

        val rotation = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotation)

        // ML Kit reports faces against the ROTATED image; CameraX reports the
        // crop rect against the UNROTATED buffer. Put them in the same space
        // before comparing them.
        val crop = FrameMapping.rotate(
            FrameRect(
                imageProxy.cropRect.left.toFloat(),
                imageProxy.cropRect.top.toFloat(),
                imageProxy.cropRect.right.toFloat(),
                imageProxy.cropRect.bottom.toFloat(),
            ),
            imageProxy.width,
            imageProxy.height,
            rotation,
        )

        val started = SystemClock.uptimeMillis()
        detector.process(image)
            .addOnSuccessListener { faces ->
                onResult(interpret(faces, crop, SystemClock.uptimeMillis() - started, dtMs))
            }
            .addOnFailureListener { Log.w(TAG, "face detection failed", it) }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }

    private fun interpret(
        faces: List<Face>,
        crop: FrameRect,
        detectMs: Long,
        dtMs: Long,
    ): FaceResult {
        if (faces.isEmpty()) {
            return FaceResult(null, null, 0, ShotTypeSelector.shotTypeFor(0), detectMs, dtMs)
        }

        // Largest face wins, always - portrait-only, so a crowd in the
        // background never pulls the composition into a group shot it cannot
        // reach in this build. See the class doc for why size, not centrality.
        val target = faces.maxBy { it.boundingBox.width().toLong() * it.boundingBox.height() }
        val rect = target.boundingBox.toFrameRect()

        return FaceResult(
            subject = FrameMapping.normalize(rect, crop),
            eyes = eyeLineOf(target, rect, crop),
            faceCount = faces.size,
            shotType = ShotTypeSelector.shotTypeFor(faces.size),
            detectMs = detectMs,
            dtMs = dtMs,
        )
    }

    private fun eyeLineOf(face: Face, rect: FrameRect, crop: FrameRect) = EyeLine(
        y = FrameMapping.normalizeY(eyeYOf(face, rect), crop),
        gazeDx = FrameMapping.gazeFromHeadYaw(face.headEulerAngleY),
    )

    /**
     * Eye height in rotated-image pixels. Landmarks when we have them; a face
     * turned far enough in profile loses one or both, and the box proportion
     * is a better answer than dropping the eye line entirely.
     */
    private fun eyeYOf(face: Face, rect: FrameRect): Float {
        val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        return when {
            left != null && right != null -> (left.y + right.y) / 2f
            left != null -> left.y
            right != null -> right.y
            else -> rect.top + rect.height * EYE_LINE_FRACTION_OF_FACE
        }
    }

    /** Release the detector's native resources. */
    fun close() {
        runCatching { detector.close() }
    }

    private fun android.graphics.Rect.toFrameRect() =
        FrameRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        /** ~30 Hz. Faster buys nothing the EMA would not smooth away. */
        const val MIN_INTERVAL_MS = 33L

        /** A backgrounded app must not return with a dt that instantly locks. */
        const val MAX_DT_MS = 250L

        /** Ignore faces smaller than this fraction of the frame. */
        const val MIN_FACE_SIZE = 0.1f

        /** Eyes sit roughly this far down an ML Kit face box. */
        const val EYE_LINE_FRACTION_OF_FACE = 0.4f
    }
}
