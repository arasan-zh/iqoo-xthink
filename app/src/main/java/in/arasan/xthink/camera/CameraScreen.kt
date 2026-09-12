package `in`.arasan.xthink.camera

import android.Manifest
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.arasan.xthink.guidance.AlignmentState
import `in`.arasan.xthink.guidance.AutoCapturePolicy
import `in`.arasan.xthink.guidance.CoachMode
import `in`.arasan.xthink.guidance.DirectionCues
import `in`.arasan.xthink.guidance.HapticCue
import `in`.arasan.xthink.guidance.LockHaptics
import `in`.arasan.xthink.guidance.CompositionProfile
import `in`.arasan.xthink.guidance.GuidanceEngine
import `in`.arasan.xthink.guidance.Instruction
import `in`.arasan.xthink.guidance.ShotType
import `in`.arasan.xthink.guidance.ShotTypeSelector
import `in`.arasan.xthink.guidance.SubjectBox
import `in`.arasan.xthink.guidance.ThermalGovernor
import `in`.arasan.xthink.guidance.ThermalPlan
import `in`.arasan.xthink.guidance.ThermalTier
import `in`.arasan.xthink.guidance.Verb
import `in`.arasan.xthink.ui.Looks
import `in`.arasan.xthink.ui.ReviewState
import `in`.arasan.xthink.ui.GuidanceOverlay
import `in`.arasan.xthink.ui.OverlayState
import `in`.arasan.xthink.ui.StatusValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TAG = "xThink"

/** How long a tapped focus point stays before the camera returns to continuous AF. */
private const val FOCUS_HOLD_S = 5L





/** The sharpness reference forgets slowly, so a new scene re-baselines within seconds. */
private const val SHARP_DECAY = 0.985f

/** A frame this far below the reference is soft: no automatic shutter. */
private const val SHARP_FRACTION = 0.55f
private const val HEARTBEAT_MS = 1000L
private const val ANALYSIS_WIDTH = 480
private const val ANALYSIS_HEIGHT = 360

/**
 * v0.3-frame: the real overlay, on the same camera plumbing v0.2-anchor built.
 *
 * Portrait-only in this build - a single face is always the subject, however
 * many are in frame, via [FaceAnalyzer] and [ShotTypeSelector]. CameraScreen's
 * job stops at building an [OverlayState] each frame; everything about how
 * that state is drawn lives in the ui package, per CLAUDE.md's requirement
 * that the guidance overlay be a separate composable layer.
 */
@Composable
fun CameraScreen(debugEnhanceUri: String? = null) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CameraAndGuidance(debugEnhanceUri)
    } else {
        PermissionPrompt(onGrant = { launcher.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("xThink needs the camera to coach your framing.", color = Color.White)
            Button(onClick = onGrant, modifier = Modifier.padding(top = 16.dp)) {
                Text("Grant camera access")
            }
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
private fun CameraAndGuidance(debugEnhanceUri: String? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayState by remember { mutableStateOf(OverlayState.EMPTY) }
    var sensorMissing by remember { mutableStateOf(false) }
    var lastCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val profiles = remember { loadProfiles(context) }

    // Which lens. Flipping rebinds the camera and builds a fresh engine for
    // it: the front camera is a mirror (left/right invert) and, on this
    // phone, fixed-focus (never ask for a focus tap). Both are constructor
    // facts of the engine, so a new one is the honest way to switch.
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var engine by remember { mutableStateOf(GuidanceEngine(profiles.getValue(ShotType.LANDSCAPE))) }

    // A raw face count flaps - 0-2-1-2-0-1-4-3-0 inside ten seconds on the
    // phone - and every change used to reset the deadzone gates and the lock
    // dwell with it. The selector makes a change earn its place first.
    val shotTypes = remember { ShotTypeSelector(profiles) }

    // The most recent attitude, held rather than acted on. See the analyser
    // comment below for why the sensor does not drive the engine.
    val latestAttitude = remember { arrayOfNulls<AttitudeSample>(1) }

    // The bound camera's control surface, so the zoom slider in the overlay
    // has something to call. Null until the camera finishes binding.
    var cameraControl by remember { mutableStateOf<Camera?>(null) }

    // Capture. The policy in :guidance decides WHEN (one shot per lock, only
    // when steady, with a cooldown); this screen owns the use case and the
    // write to the gallery.
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val autoCapture = remember { AutoCapturePolicy() }

    // After the shutter: the photographer's crop, offered, never imposed.
    val enhancer = remember { PhotoEnhancer(context) }
    var pendingEnhance by remember { mutableStateOf<PhotoEnhancer.Proposal?>(null) }
    var reviewSource by remember { mutableStateOf<Uri?>(null) }

    /** Open the review for a photo just taken (or handed in by the dev hook). */
    fun openReview(source: Uri, result: PhotoEnhancer.Result) {
        val before = result.small
        val proposal = result.proposal
        if (before == null || proposal == null) {
            // No recognised person, nothing to choose: the shot stands as
            // taken, with the camera-page look baked in if one is set.
            val look = Looks.ALL[overlayState.look]
            if (!look.isNatural) {
                enhancer.saveFinal(source, null, false, look.matrix, ContextCompat.getMainExecutor(context)) { saved ->
                    if (saved != null) lastCaptureUri = saved
                    Log.i(TAG, "look ${look.name} baked -> $saved")
                }
            }
            if (result.soft) Log.i(TAG, "capture looks soft")
            return
        }
        pendingEnhance = proposal
        reviewSource = source
        overlayState = overlayState.copy(
            review = ReviewState(
                before = before.asImageBitmap(),
                after = proposal.after.asImageBitmap(),
                rationale = proposal.proposal.rationale,
                enhanced = true,
                look = overlayState.look,
                soft = result.soft,
            ),
        )
    }

    fun closeReview() {
        pendingEnhance = null
        reviewSource = null
        overlayState = overlayState.copy(review = null)
    }

    DisposableEffect(enhancer) { onDispose { enhancer.close() } }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // The on-device coach. Absent, quietly, when the model is not on the phone.
    val coach = remember { LlmCoach(context) }
    var coachState by remember { mutableStateOf(LlmCoach.State.LOADING) } // warmUp settles it
    val sharpRef = remember { floatArrayOf(0f) }
    DisposableEffect(coach) {
        coach.warmUp(ContextCompat.getMainExecutor(context)) { coachState = it }
        onDispose { coach.close() }
    }

    /** The coach's word on the cut, when there is a coach and it is free. */
    fun cutAdvisor(): PhotoEnhancer.CutAdvisor? {
        if (coachState != LlmCoach.State.READY || coach.isBusy) return null
        return PhotoEnhancer.CutAdvisor { photo, answer ->
            val started = coach.ask(LlmCoach.Kind.CROP, photo, LlmCoach.CROP_PROMPT, ContextCompat.getMainExecutor(context)) { text, done ->
                if (done) answer(text)
            }
            if (!started) answer(null)
        }
    }

    /** A modest copy of what the preview shows, for the coach's eyes. */
    fun previewSnapshot(): Bitmap? = runCatching { previewView.bitmap }.getOrNull()

    LaunchedEffect(debugEnhanceUri, coachState) {
        if (debugEnhanceUri == null) return@LaunchedEffect
        // Wait for the coach to load (or be absent) so the hook tests the same path a capture takes.
        if (coachState == LlmCoach.State.LOADING) return@LaunchedEffect
        val u = Uri.parse(debugEnhanceUri)
        enhancer.analyse(u, ContextCompat.getMainExecutor(context), cutAdvisor()) { openReview(u, it) }
    }
    var captureInFlight by remember { mutableStateOf(false) }

    // The haptic lock game. :guidance decides the rhythm; the driver only
    // knows the motor.
    val lockHaptics = remember { LockHaptics() }
    val haptics = remember { HapticDriver(context) }

    // The thermal governor. :guidance decides the tier; this screen applies
    // the plan - detector rate, haptics, auto-capture - and shows a chip only
    // while something is being held back.
    val governor = remember { ThermalGovernor() }
    val thermalPlan = remember { arrayOf(ThermalPlan.forTier(ThermalTier.COOL)) }

    // The analyser is created inside the camera effect; the mode switch and
    // the governor both need to reach it from outside, so hold a reference.
    val analyzerRef = remember { arrayOfNulls<FaceAnalyzer>(1) }

    fun capture(auto: Boolean) {
        if (captureInFlight) return
        captureInFlight = true
        val name = "xthink_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/xThink")
        }
        val options = ImageCapture.OutputFileOptions
            .Builder(context.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    captureInFlight = false
                    val uri: Uri? = output.savedUri
                    lastCaptureUri = uri ?: lastCaptureUri
                    Log.i(TAG, "captured auto=$auto -> $uri")
                    // An auto shot follows the lock thunk by a frame; a second
                    // click on top would read as a stutter. Manual gets its click.
                    if (!auto) haptics.click()
                    val thumb: Bitmap? = uri?.let {
                        runCatching { context.contentResolver.loadThumbnail(it, Size(160, 160), null) }.getOrNull()
                    }
                    overlayState = overlayState.copy(
                        thumbnail = thumb?.asImageBitmap() ?: overlayState.thumbnail,
                        captureNonce = overlayState.captureNonce + 1,
                    )
                    if (uri != null) {
                        enhancer.analyse(uri, ContextCompat.getMainExecutor(context), cutAdvisor()) { openReview(uri, it) }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    captureInFlight = false
                    Log.e(TAG, "capture failed", e)
                }
            },
        )
    }

    // --- thermal ---------------------------------------------------------
    DisposableEffect(governor) {
        val monitor = ThermalMonitor(context)
        var lastMs = SystemClock.uptimeMillis()
        monitor.start { headroom, status ->
            val now = SystemClock.uptimeMillis()
            val before = governor.tier
            val plan = governor.update(headroom, status, now - lastMs)
            lastMs = now
            thermalPlan[0] = plan
            analyzerRef[0]?.minIntervalMs = plan.analysisIntervalMs
            if (plan.tier != before) {
                Log.i(TAG, "thermal %s -> %s (headroom=%.2f status=%d) analysis=%dms haptics=%b auto=%b".format(
                    before, plan.tier, headroom, status, plan.analysisIntervalMs, plan.hapticsOn, plan.autoCaptureOn))
            }
            overlayState = overlayState.copy(thermal = plan.tier, thermalHeadroom = headroom)
        }
        onDispose { monitor.stop() }
    }

    // --- sensor ----------------------------------------------------------
    DisposableEffect(engine) {
        val sensor = AttitudeSensor(context)
        sensor.frontFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
        val started = sensor.start { s -> latestAttitude[0] = s }
        sensorMissing = !started
        if (!started) Log.w(TAG, "TYPE_GAME_ROTATION_VECTOR unavailable; guidance cannot run")
        onDispose { sensor.stop() }
    }

    // --- camera + analysis -----------------------------------------------

    fun selectMode(mode: CoachMode) {
        if (overlayState.mode == mode) return
        shotTypes.setMode(mode)
        analyzerRef[0]?.mode = mode
        engine.setProfile(profiles.getValue(shotTypes.current))
        overlayState = if (mode == CoachMode.CREATIVE) {
            // No assistance: the analyser stops ticking the engine, so clear
            // what it last said rather than leave a stale instruction up.
            overlayState.copy(mode = mode, instruction = null, subject = null, alignment = AlignmentState.EMPTY)
        } else {
            overlayState.copy(mode = mode)
        }
        Log.i(TAG, "mode -> $mode")
    }

    fun openGallery() {
        val uri = lastCaptureUri
        val intent = if (uri != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure { Log.w(TAG, "no viewer for the gallery", it) }
    }

    DisposableEffect(lifecycleOwner, lensFacing) {
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val mainHandler = Handler(Looper.getMainLooper())
        val analysisExecutor = Executors.newSingleThreadExecutor()
        var telemetry = CameraTelemetry()
        var lastHeartbeat = 0L
        var lastVerb: Verb? = null

        // IMPORTANT: the ANALYSER drives the engine, not the sensor.
        //
        // The box EMA in :guidance smooths at alpha 0.25 per update. Ticking
        // the engine at the sensor's 111 Hz while handing it the same face box
        // over and over would drive that filter onto the raw value almost
        // immediately and throw away the anti-jitter CLAUDE.md specifies. One
        // engine tick per new measurement keeps the smoothing constants
        // meaning what they say.
        //
        // ML Kit delivers its callback on the main thread and the capture
        // callback posts there, so the engine still only ever sees one thread.
        val analyzer = FaceAnalyzer { result ->
            val attitude = latestAttitude[0] ?: return@FaceAnalyzer
            val stable = shotTypes.update(result.faceCount, result.dtMs, result.subject?.h)
            engine.setProfile(profiles.getValue(stable))
            val next = engine.update(attitude.attitude, result.subject, result.eyes, result.dtMs)

            overlayState = buildOverlayState(
                instruction = next,
                alignment = engine.alignment,
                subject = result.subject,
                telemetry = telemetry,
                stabilityOk = engine.stabilityOk,
                compositionOk = engine.compositionOk,
                hasAutofocus = telemetry.hasAutofocus,
                thumbnail = overlayState.thumbnail,
                captureNonce = overlayState.captureNonce,
                mode = shotTypes.mode,
                thermal = thermalPlan[0].tier,
                thermalHeadroom = overlayState.thermalHeadroom,
                mirrored = overlayState.mirrored,
                focusPoint = overlayState.focusPoint,
                focusNonce = overlayState.focusNonce,
                review = overlayState.review,
                easyShot = overlayState.easyShot,
                look = overlayState.look,
                showLooks = overlayState.showLooks,
                lookPreview = overlayState.lookPreview,
            )

            // The lock game: feel the frame come together without looking.
            val cue = lockHaptics.update(engine.totalError, next.verb, result.subject != null, result.dtMs)
            if (cue != HapticCue.NONE && thermalPlan[0].hapticsOn) {
                haptics.play(cue, lockHaptics.lastTickStrength)
                if (cue != HapticCue.TICK) Log.i(TAG, "haptic $cue")
            }

            // The coach said "now". Take the picture - unless the phone is
            // too hot for a JPEG encode to be a good idea. The policy still
            // ticks so its cooldown clock stays honest.
            // Sharpness against the scene's own recent best: a frame well
            // below it is motion or missed focus, and not worth a shutter.
            sharpRef[0] = maxOf(sharpRef[0] * SHARP_DECAY, result.sharpness)
            val sharp = sharpRef[0] <= 0f || result.sharpness >= SHARP_FRACTION * sharpRef[0]
            val wantsShot = autoCapture.update(
                next.verb, engine.stabilityOk, result.subject != null, result.dtMs, engine.totalError, sharp,
            )
            // Easy shot on: the coach may press the shutter (at the lock,
            // or near enough). Off: guidance only, the shutter is the
            // photographer's. Never while a review is up.
            if (wantsShot && thermalPlan[0].autoCaptureOn && overlayState.easyShot && overlayState.review == null) {
                capture(auto = true)
            }

            // A direction signature under the thumb when the instruction
            // changes - left, right, up, down feel different - so the
            // photographer knows which way without looking. The lockout keeps
            // changes at least 600ms apart, so these cannot spam.
            if (next.verb != lastVerb) {
                val dir = DirectionCues.forTransition(lastVerb, next.verb)
                if (dir != HapticCue.NONE && thermalPlan[0].hapticsOn) haptics.play(dir)
            }

            // Every change of verb, plus a heartbeat. Transitions are where the
            // bugs live, and a 1 Hz sample cannot see a state that lasts 500ms.
            if (next.verb != lastVerb) {
                Log.i(
                    TAG,
                    "verb %s -> %s  (faces=%d %s)".format(
                        lastVerb ?: "-", next.verb, result.faceCount, stable,
                    ),
                )
                lastVerb = next.verb
            }

            val now = SystemClock.uptimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now
                Log.i(
                    TAG,
                    "roll=%+.1f pitch=%+.1f faces=%d %s det=%dms err=%.3f lock=%.2f zoom=%.1fx sharp=%.0f/%.0f therm=%.2f/%s -> %s".format(
                        attitude.attitude.rollDeg, attitude.attitude.pitchDeg,
                        result.faceCount, stable, result.detectMs,
                        engine.totalError, engine.lockProgress, telemetry.zoomRatio,
                        result.sharpness, sharpRef[0],
                        overlayState.thermalHeadroom, thermalPlan[0].tier, next.text,
                    ),
                )
            }
        }

        analyzerRef[0] = analyzer
        analyzer.mode = shotTypes.mode
        analyzer.mirrored = isFront
        analyzer.minIntervalMs = thermalPlan[0].analysisIntervalMs

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                Log.e(TAG, "could not get the camera provider", it)
                return@addListener
            }

            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                )

                .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        mainHandler.post {
                            telemetry = CameraTelemetry.from(telemetry, result)
                            engine.reportFocusLocked(telemetry.focusLocked)
                            engine.reportZoom(telemetry.zoomRatio, telemetry.maxZoomRatio)
                        }
                    }
                })

            val preview = previewBuilder.build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }

            // Bind preview and analysis through one ViewPort so they share a
            // crop rect. Without it "6% of frame" would mean six percent of a
            // 4:3 buffer while the photographer looks at a taller crop of it.
            previewView.post {
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .addUseCase(imageCapture)
                    .apply { previewView.viewPort?.let { setViewPort(it) } }
                    .build()

                runCatching {
                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                        group,
                    )
                    cameraControl = camera
                    // The HAL does not reset zoom on its own - a fresh bind
                    // once opened at 2x. Reset through CameraControl, the
                    // same path the slider and the pinch use. NOT through a
                    // Camera2 interop request option: interop options override
                    // CameraX's own controls, and a pinned CONTROL_ZOOM_RATIO
                    // silently defeated every setZoomRatio that followed.
                    camera.cameraControl.setZoomRatio(1f)
                    val characteristics =
                        Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)
                    val statics = CameraTelemetry.fromCharacteristics(characteristics)
                    telemetry = telemetry.copy(
                        maxZoomRatio = statics.maxZoomRatio,
                        minFocusCm = statics.minFocusCm,
                        hasAutofocus = statics.hasAutofocus,
                        maxIso = statics.maxIso,
                    )
                    // A fresh engine for this lens: mirrored instructions on
                    // the front camera, and no focus tap where there is no
                    // autofocus - measured from the characteristics, not
                    // assumed from which side the lens is on.
                    val fresh = GuidanceEngine(
                        profiles.getValue(shotTypes.current),
                        mirrored = isFront,
                        hasAutofocus = statics.hasAutofocus,
                    )
                    fresh.reportZoom(telemetry.zoomRatio, statics.maxZoomRatio)
                    engine = fresh
                    lockHaptics.reset()
                    autoCapture.reset()
                    overlayState = overlayState.copy(mirrored = isFront)
                    Log.i(
                        TAG,
                        "bound ${if (isFront) "front" else "rear"} camera: mirrored=$isFront " +
                            "autofocus=${statics.hasAutofocus} zoom<=${statics.maxZoomRatio}x " +
                            "minFocus=${statics.minFocusCm}cm viewPort=${previewView.viewPort != null}",
                    )
                }.onFailure { Log.e(TAG, "bindToLifecycle failed", it) }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get().unbindAll() }
            cameraControl = null
            analyzerRef[0] = null
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        GuidanceOverlay(
            state = overlayState,
            onZoomSelected = { ratio ->
                cameraControl?.cameraControl?.setZoomRatio(ratio)
            },
            onShutter = {
                autoCapture.notifyManualCapture()
                capture(auto = false)
            },
            onGallery = { openGallery() },
            onModeSelected = { selectMode(it) },
            onReviewChooseEnhanced = { on ->
                overlayState.review?.let { r -> overlayState = overlayState.copy(review = r.copy(enhanced = on)) }
            },
            onReviewChooseLook = { i ->
                overlayState.review?.let { r -> overlayState = overlayState.copy(review = r.copy(look = i)) }
            },
            onReviewSave = {
                val r = overlayState.review
                val src = reviewSource
                if (r != null && src != null && !r.saving) {
                    overlayState = overlayState.copy(review = r.copy(saving = true))
                    val look = Looks.ALL[r.look].matrix
                    enhancer.saveFinal(src, pendingEnhance, r.enhanced, look, ContextCompat.getMainExecutor(context)) { saved ->
                        if (saved != null) {
                            lastCaptureUri = saved
                            val chosen = if (r.enhanced) r.after else null
                            overlayState = overlayState.copy(thumbnail = chosen ?: overlayState.thumbnail)
                        }
                        haptics.click()
                        Log.i(TAG, "review: kept ${if (r.enhanced) "enhanced" else "original"} look=${Looks.ALL[r.look].name} saved=$saved")
                        closeReview()
                    }
                }
            },
            onReviewDiscard = {
                Log.i(TAG, "review: discarded, original kept as shot")
                closeReview()
            },
            onTap = { x, y ->
                // Focus and meter where the finger landed, and tell the coach
                // that this is the thing to frame. The ring answers the tap
                // at once; the lens follows.
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(x * previewView.width, y * previewView.height)
                val action = FocusMeteringAction.Builder(point)
                    .setAutoCancelDuration(FOCUS_HOLD_S, TimeUnit.SECONDS)
                    .build()
                cameraControl?.cameraControl?.startFocusAndMetering(action)
                analyzerRef[0]?.let { it.focusX = x; it.focusY = y }
                overlayState = overlayState.copy(
                    focusPoint = x to y,
                    focusNonce = overlayState.focusNonce + 1,
                )
                Log.i(TAG, "tap focus at (${"%.2f".format(x)}, ${"%.2f".format(y)})")
            },
            onToggleLooks = {
                val open = !overlayState.showLooks
                val preview = if (open) previewSnapshot()?.let { snap ->
                    val scale = 240f / maxOf(snap.width, snap.height)
                    Bitmap.createScaledBitmap(snap, (snap.width * scale).toInt().coerceAtLeast(1), (snap.height * scale).toInt().coerceAtLeast(1), true)
                        .asImageBitmap()
                } else null
                overlayState = overlayState.copy(showLooks = open, lookPreview = preview ?: overlayState.lookPreview)
            },
            onPickLook = { i ->
                overlayState = overlayState.copy(look = i)
                haptics.play(HapticCue.TICK, 0.4f)
                Log.i(TAG, "look -> ${Looks.ALL[i].name}")
            },
            onToggleEasyShot = {
                val on = !overlayState.easyShot
                autoCapture.relaxed = on
                overlayState = overlayState.copy(easyShot = on)
                haptics.play(HapticCue.TICK, if (on) 0.6f else 0.3f)
                Log.i(TAG, "easy shot ${if (on) "on" else "off"}")
            },
            onFlip = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (sensorMissing) {
            Text(
                text = "GAME_ROTATION_VECTOR unavailable",
                color = Color.Red,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp),
            )
        }
    }
}

/**
 * Turns the engine's output and the ISP's telemetry into what the overlay
 * draws. This is the one place camera facts and guidance facts meet; the
 * overlay package never sees either source directly.
 */
private fun buildOverlayState(
    instruction: Instruction,
    alignment: AlignmentState,
    subject: SubjectBox?,
    telemetry: CameraTelemetry,
    stabilityOk: Boolean,
    compositionOk: Boolean,
    hasAutofocus: Boolean,
    thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    captureNonce: Int,
    mode: CoachMode,
    thermal: ThermalTier,
    thermalHeadroom: Float,
    mirrored: Boolean,
    focusPoint: Pair<Float, Float>?,
    focusNonce: Int,
    review: ReviewState?,
    easyShot: Boolean,
    look: Int,
    showLooks: Boolean,
    lookPreview: ImageBitmap?,
): OverlayState {
    val focus = when {
        !hasAutofocus -> StatusValue("Focus", "Fixed", true)
        telemetry.focusLocked -> StatusValue("Focus", "Good", true)
        telemetry.afState == null -> StatusValue("Focus", "-", false)
        else -> StatusValue("Focus", "Focusing", false)
    }
    val lighting = StatusValue("Lighting", telemetry.lightingText, telemetry.lightingOk)
    val stability = StatusValue("Stability", if (stabilityOk) "Stable" else "Hold still", stabilityOk)
    val composition = StatusValue(
        "Composition",
        when {
            instruction.verb == Verb.LOCKED -> "Centered"
            instruction.verb == Verb.SEEKING -> "No subject"
            compositionOk -> "Good"
            else -> "Adjusting"
        },
        compositionOk,
    )

    return OverlayState(
        instruction = instruction,
        alignment = alignment,
        subject = subject,
        focus = focus,
        lighting = lighting,
        stability = stability,
        composition = composition,
        zoomRatio = telemetry.zoomRatio,
        maxZoomRatio = telemetry.maxZoomRatio,
        thumbnail = thumbnail,
        captureNonce = captureNonce,
        mode = mode,
        thermal = thermal,
        thermalHeadroom = thermalHeadroom,
        mirrored = mirrored,
        focusPoint = focusPoint,
        focusNonce = focusNonce,
        review = review,
        easyShot = easyShot,
        look = look,
        showLooks = showLooks,
        lookPreview = lookPreview,
    )
}


private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Reads `assets/composition_profiles.json` and hands the text to :guidance,
 * which parses it. The asset API stays on this side of the module boundary.
 */
private fun loadProfiles(context: Context): Map<ShotType, CompositionProfile> {
    val json = context.assets.open("composition_profiles.json")
        .bufferedReader()
        .use { it.readText() }
    return CompositionProfile.parseAll(json)
}
