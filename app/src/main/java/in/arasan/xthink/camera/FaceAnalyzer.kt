package `in`.arasan.xthink.camera

import android.annotation.SuppressLint
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import `in`.arasan.xthink.guidance.Exercise
import `in`.arasan.xthink.guidance.Joint
import `in`.arasan.xthink.guidance.JointAngles
import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import `in`.arasan.xthink.guidance.CoachMode
import `in`.arasan.xthink.guidance.EyeLine
import `in`.arasan.xthink.guidance.FrameMapping
import `in`.arasan.xthink.guidance.FrameRect
import `in`.arasan.xthink.guidance.GuidanceConstants
import `in`.arasan.xthink.guidance.SubjectBox

private const val TAG = "xThink"

/** What one analysed frame tells us about the subject. */
data class FaceResult(
    val subject: SubjectBox?,
    val eyes: EyeLine?,
    val faceCount: Int,
    val detectMs: Long,
    val dtMs: Long,
    /**
     * Focus measure of the frame: variance of a Laplacian over the luma
     * plane, sampled on a grid. Scene-relative - only meaningful against
     * recent frames of the same scene - so the caller keeps a running
     * reference. 0 when it could not be measured.
     */
    val sharpness: Float = 0f,
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
 *    always targets ONE face, the largest, however many are in frame, and
 *    frames it as HALF_BODY - head and shoulders, not a face-filling crop. Face
 *    size is the most stable signal ML Kit gives - it does not flicker between
 *    candidates as the phone moves the way "most central" would - and the
 *    nearest person is almost always who the photographer means to shoot.
 *    Group framing (a box spanning everybody) needs a mode tab to reach
 *    deliberately, so it is not attempted here.
 */
class FaceAnalyzer(
    private val context: Context,
    private val onResult: (FaceResult) -> Unit,
) : ImageAnalysis.Analyzer {

    /**
     * Set from the UI thread, read on the analysis thread. PORTRAIT frames
     * the largest face; WIDE frames every face as one subject.
     */
    @Volatile
    var mode: CoachMode = CoachMode.PORTRAIT

    /**
     * Minimum gap between analysed frames. The thermal governor raises this
     * as the phone warms; the detector is the largest heat source we control.
     * Set from the UI thread, read on the analysis thread.
     */
    @Volatile
    var minIntervalMs: Long = MIN_INTERVAL_MS

    /**
     * True on the front camera. Its preview is a mirror but the analysis
     * frame is not, so results are flipped into preview space before the
     * engine and the overlay see them. See FrameMapping.mirrorX.
     */
    @Volatile
    var mirrored: Boolean = false

    /**
     * Where the photographer's attention is, in preview space (0..1). The
     * centre until they tap. OBJECT mode frames the thing here - the biggest
     * object in view is usually the table, not the cup on it.
     */
    @Volatile
    var focusX: Float = 0.5f

    @Volatile
    var focusY: Float = 0.5f

    /** Uptime of the last tap, so a fresh tap picks the face under it. */
    @Volatile
    var focusTapMs: Long = 0L

    /** Where the chosen face was last frame, analysis space; keeps the same person between frames. */
    private var lastFace: SubjectBox? = null

    /**
     * Variance of the 4-neighbour Laplacian on the Y plane, every 4th pixel
     * each way: ~11k samples of a 480x360 frame, well under a millisecond.
     * Blur flattens the Laplacian; the number collapses.
     */
    private fun lumaSharpness(proxy: ImageProxy): Float {
        val plane = proxy.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val w = proxy.width
        val h = proxy.height
        val step = SHARPNESS_STEP
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        fun y(x: Int, yy: Int): Int = buf.get(yy * rs + x * ps).toInt() and 0xFF
        var yy = step
        while (yy < h - step) {
            var x = step
            while (x < w - step) {
                val l = 4 * y(x, yy) - y(x - step, yy) - y(x + step, yy) - y(x, yy - step) - y(x, yy + step)
                sum += l
                sumSq += l.toDouble() * l
                n++
                x += step
            }
            yy += step
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    // ---- FIT: a body's joints and a hand's sign, from the same frames ----

    /** Set while the FIT tab is up: the exercise whose joint to watch, or null. */
    @Volatile
    var fitExercise: Exercise? = null

    /** Set while FIT watches for hand signs. */
    @Volatile
    var fitGestures: Boolean = false

    /** Where FIT frames go: the joint angle (or null), the hand sign (or null), and dt. */
    @Volatile
    var onFit: ((angleDeg: Float?, gesture: String?, dtMs: Long) -> Unit)? = null

    private val poseStream by lazy {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build(),
        )
    }
    private var gestureRecognizer: GestureRecognizer? = null
    private var gestureFrame = 0
    private var lastGesture: String? = null
    private var lastGestureMs = 0L

    private fun gestures(): GestureRecognizer? {
        gestureRecognizer?.let { return it }
        return runCatching {
            GestureRecognizer.createFromOptions(
                context,
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumHands(1)
                    .build(),
            ).also { gestureRecognizer = it }
        }.onFailure { Log.e(TAG, "gesture recognizer failed to load", it) }.getOrNull()
    }

    /** The FIT path: joints, then (every third frame) the hand. Runs instead of faces. */
    private fun analyzeFit(imageProxy: ImageProxy, image: InputImage, exercise: Exercise?, dtMs: Long) {
        // The hand is read every third frame; a sign holds for a moment so the
        // frames in between do not blink it away.
        var gesture: String? = null
        val nowMs = SystemClock.uptimeMillis()
        if (fitGestures && ++gestureFrame % 3 == 0) {
            gesture = runCatching {
                val bmp = imageProxy.toBitmap()
                val rot = imageProxy.imageInfo.rotationDegrees
                val upright = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                val result = gestures()?.recognize(BitmapImageBuilder(upright).build())
                result?.gestures()?.firstOrNull()?.firstOrNull()?.let { if (it.score() >= 0.55f) it.categoryName() else null }
            }.getOrNull()
            if (gesture != null && gesture != "None") { lastGesture = gesture; lastGestureMs = nowMs }
        }
        if (fitGestures) gesture = if (nowMs - lastGestureMs <= GESTURE_HOLD_MS) lastGesture else null
        if (exercise == null) {
            onFit?.invoke(null, gesture, dtMs)
            busy = false
            imageProxy.close()
            return
        }
        poseStream.process(image)
            .addOnSuccessListener { pose ->
                fun j(t: Int): Joint? = pose.getPoseLandmark(t)?.takeIf { it.inFrameLikelihood >= 0.5f }?.let { Joint(it.position.x, it.position.y) }
                val angle: Float? = when (exercise) {
                    Exercise.SQUAT -> bestAngle(exercise, j(PoseLandmark.LEFT_HIP), j(PoseLandmark.LEFT_KNEE), j(PoseLandmark.LEFT_ANKLE),
                        j(PoseLandmark.RIGHT_HIP), j(PoseLandmark.RIGHT_KNEE), j(PoseLandmark.RIGHT_ANKLE))
                    Exercise.JUMPING_JACK -> bestAngle(exercise, j(PoseLandmark.LEFT_HIP), j(PoseLandmark.LEFT_SHOULDER), j(PoseLandmark.LEFT_WRIST),
                        j(PoseLandmark.RIGHT_HIP), j(PoseLandmark.RIGHT_SHOULDER), j(PoseLandmark.RIGHT_WRIST))
                    Exercise.KNEE_RAISE -> bestAngle(exercise, j(PoseLandmark.LEFT_SHOULDER), j(PoseLandmark.LEFT_HIP), j(PoseLandmark.LEFT_KNEE),
                        j(PoseLandmark.RIGHT_SHOULDER), j(PoseLandmark.RIGHT_HIP), j(PoseLandmark.RIGHT_KNEE))
                }
                onFit?.invoke(angle, gesture, dtMs)
            }
            .addOnFailureListener { Log.w(TAG, "pose stream failed", it) }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }

    /** The angle from whichever side is fully in frame; with both in, the exercise says which counts. */
    private fun bestAngle(exercise: Exercise, a1: Joint?, b1: Joint?, c1: Joint?, a2: Joint?, b2: Joint?, c2: Joint?): Float? {
        val left = if (a1 != null && b1 != null && c1 != null) JointAngles.angle(a1, b1, c1) else null
        val right = if (a2 != null && b2 != null && c2 != null) JointAngles.angle(a2, b2, c2) else null
        return JointAngles.pick(left, right, exercise.side)
    }

    private fun deliver(result: FaceResult) {
        if (!mirrored) { onResult(result); return }
        onResult(
            result.copy(
                subject = result.subject?.let { FrameMapping.mirrorX(it) },
                eyes = result.eyes?.let { FrameMapping.mirrorX(it) },
            )
        )
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(MIN_FACE_SIZE)
            .build()
    )

    /**
     * OBJECT mode's detector. Stream mode for latency and tracking; a single
     * object, the most prominent, because a still life has one subject; no
     * classification, because we need a box, not a label. Only one of the
     * two detectors runs per frame - the mode picks - which is what keeps
     * OBJECT mode from doubling the heat.
     */
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .build()
    )

    private var lastAnalysisMs = 0L
    private var busy = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        // CREATIVE: no assistance means no detector. Close the frame before
        // any work - zero ML, zero heat, and the engine never ticks.
        if (mode == CoachMode.CREATIVE) {
            imageProxy.close()
            return
        }

        val now = SystemClock.uptimeMillis()

        // Throttled, per CLAUDE.md. STRATEGY_KEEP_ONLY_LATEST already drops
        // stale frames; this caps the rate we ask the NPU to work at, which is
        // thermal budget the v0.5-cool governor will want back.
        if (busy || now - lastAnalysisMs < minIntervalMs) {
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
        val sharpness = runCatching { lumaSharpness(imageProxy) }.getOrDefault(0f)
        if (fitExercise != null || fitGestures) {
            analyzeFit(imageProxy, image, fitExercise, dtMs)
            return
        }

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
        if (mode == CoachMode.OBJECT) {
            objectDetector.process(image)
                .addOnSuccessListener { objects ->
                    deliver(interpretObjects(objects, crop, SystemClock.uptimeMillis() - started, dtMs).copy(sharpness = sharpness))
                }
                .addOnFailureListener { Log.w(TAG, "object detection failed", it) }
                .addOnCompleteListener {
                    busy = false
                    imageProxy.close()
                }
            return
        }
        detector.process(image)
            .addOnSuccessListener { faces ->
                deliver(interpret(faces, crop, SystemClock.uptimeMillis() - started, dtMs).copy(sharpness = sharpness))
            }
            .addOnFailureListener { Log.w(TAG, "face detection failed", it) }
            .addOnCompleteListener {
                busy = false
                imageProxy.close()
            }
    }

    /**
     * The object under the focus point is the subject: the smallest box that
     * contains it, or failing that the nearest box centre. No eye line - the
     * engine composes the box centre, and the OBJECT profile puts it at 0.50.
     * faceCount carries the subject count so the selector treats "found one"
     * the same way it treats a face.
     */
    private fun interpretObjects(
        objects: List<DetectedObject>,
        crop: FrameRect,
        detectMs: Long,
        dtMs: Long,
    ): FaceResult {
        if (objects.isEmpty()) return FaceResult(null, null, 0, detectMs, dtMs)
        // Focus point into analysis space: the preview is a mirror on the front camera.
        val fx = if (mirrored) 1f - focusX else focusX
        val fy = focusY
        val boxes = objects.map { FrameMapping.normalize(it.boundingBox.toFrameRect(), crop) }
        val containing = boxes.filter {
            fx >= it.cx - it.w / 2f && fx <= it.cx + it.w / 2f && fy >= it.cy - it.h / 2f && fy <= it.cy + it.h / 2f
        }
        val subject = containing.minByOrNull { it.w * it.h }
            ?: boxes.minByOrNull { (it.cx - fx) * (it.cx - fx) + (it.cy - fy) * (it.cy - fy) }!!
        return FaceResult(
            subject = subject,
            eyes = null,
            faceCount = 1,
            detectMs = detectMs,
            dtMs = dtMs,
        )
    }

    private fun interpret(
        faces: List<Face>,
        crop: FrameRect,
        detectMs: Long,
        dtMs: Long,
    ): FaceResult {
        if (faces.isEmpty()) {
            lastFace = null
            return FaceResult(null, null, 0, detectMs, dtMs)
        }

        val rects = faces.map { it.boundingBox.toFrameRect() }

        if (mode == CoachMode.WIDE && faces.size > 1) {
            // Everyone is the subject, framed as one box spanning all faces.
            // Averaging the eye lines keeps the horizon honest when heads are
            // at different heights; averaging the yaws means a group all
            // facing one way gets lead room, while a group looking every
            // which way averages to roughly zero and stays centred.
            val union = FrameMapping.union(rects)!!
            val eyeY = faces.indices.map { eyeYOf(faces[it], rects[it]) }.average().toFloat()
            val yaw = faces.map { it.headEulerAngleY }.average().toFloat()
            return FaceResult(
                subject = FrameMapping.normalize(union, crop),
                eyes = EyeLine(
                    y = FrameMapping.normalizeY(eyeY, crop),
                    gazeDx = FrameMapping.gazeFromHeadYaw(yaw),
                ),
                faceCount = faces.size,
                detectMs = detectMs,
                dtMs = dtMs,
            )
        }

        // PORTRAIT (or a group of one). A fresh tap picks the face under the
        // finger; otherwise the face nearest where the chosen one was last
        // frame keeps the same person through a pan or a video; failing
        // both, the largest - the nearest person is almost always who the
        // photographer means.
        val boxes = rects.map { FrameMapping.normalize(it, crop) }
        val fx = if (mirrored) 1f - focusX else focusX
        val fy = focusY
        val tapped = SystemClock.uptimeMillis() - focusTapMs < TAP_PICKS_MS
        val prev = lastFace
        val i = when {
            tapped -> boxes.indices.minBy { (boxes[it].cx - fx).let { d -> d * d } + (boxes[it].cy - fy).let { d -> d * d } }
            prev != null && boxes.indices.any { near(boxes[it], prev) } ->
                boxes.indices.filter { near(boxes[it], prev) }
                    .minBy { (boxes[it].cx - prev.cx).let { d -> d * d } + (boxes[it].cy - prev.cy).let { d -> d * d } }
            else -> faces.indices.maxBy { faces[it].boundingBox.width().toLong() * faces[it].boundingBox.height() }
        }
        lastFace = boxes[i]
        return FaceResult(
            subject = boxes[i],
            eyes = eyeLineOf(faces[i], rects[i], crop),
            faceCount = faces.size,
            detectMs = detectMs,
            dtMs = dtMs,
        )
    }

    /** Same person, probably: centres within a face-width of each other. */
    private fun near(a: SubjectBox, b: SubjectBox): Boolean {
        val dx = a.cx - b.cx
        val dy = a.cy - b.cy
        val reach = maxOf(a.w, b.w, 0.08f) * 1.5f
        return dx * dx + dy * dy <= reach * reach
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

    /** Release both detectors' native resources. */
    fun close() {
        runCatching { gestureRecognizer?.close() }
        runCatching { detector.close() }
        runCatching { objectDetector.close() }
    }

    private fun android.graphics.Rect.toFrameRect() =
        FrameRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    companion object {
        /** ~30 Hz. Faster buys nothing the EMA would not smooth away. */
        const val SHARPNESS_STEP = 4
        /** A recognised hand sign is shown for this long after its last frame. */
        const val GESTURE_HOLD_MS = 800L
        /** A tap chooses the face under it for this long. */
        const val TAP_PICKS_MS = 1_500L
        const val MIN_INTERVAL_MS = 33L

        /** A backgrounded app must not return with a dt that instantly locks. */
        const val MAX_DT_MS = 250L

        /**
         * Smallest head to detect, as a fraction of image WIDTH. A full-body
         * portrait on a phone puts the face at roughly a tenth of frame height,
         * which in the 360-wide rotated analysis image is about 7% of width -
         * the previous 0.1 floor could not see a full-body subject at all.
         * 0.06 is ~22px in that image, near the bottom of what FAST mode finds
         * reliably; smaller and detection drops out rather than degrading.
         */
        const val MIN_FACE_SIZE = 0.06f

        /** Eyes sit roughly this far down an ML Kit face box. One source of truth, shared with the engine's target rect. */
        const val EYE_LINE_FRACTION_OF_FACE = GuidanceConstants.EYE_LINE_FRACTION_OF_FACE
    }
}
