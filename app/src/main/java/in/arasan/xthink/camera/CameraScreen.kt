package `in`.arasan.xthink.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.arasan.xthink.guidance.AlignmentState
import `in`.arasan.xthink.guidance.CompositionProfile
import `in`.arasan.xthink.guidance.GuidanceEngine
import `in`.arasan.xthink.guidance.Instruction
import `in`.arasan.xthink.guidance.ShotType
import `in`.arasan.xthink.guidance.ShotTypeSelector
import `in`.arasan.xthink.guidance.SubjectBox
import `in`.arasan.xthink.guidance.Verb
import `in`.arasan.xthink.ui.GuidanceOverlay
import `in`.arasan.xthink.ui.OverlayState
import `in`.arasan.xthink.ui.StatusValue
import java.util.concurrent.Executors

private const val TAG = "xThink"
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
fun CameraScreen() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CameraAndGuidance()
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
private fun CameraAndGuidance() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayState by remember { mutableStateOf(OverlayState.EMPTY) }
    var sensorMissing by remember { mutableStateOf(false) }

    val profiles = remember { loadProfiles(context) }
    val engine = remember { GuidanceEngine(profiles.getValue(ShotType.LANDSCAPE)) }

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

    // --- sensor ----------------------------------------------------------
    DisposableEffect(engine) {
        val sensor = AttitudeSensor(context)
        val started = sensor.start { s -> latestAttitude[0] = s }
        sensorMissing = !started
        if (!started) Log.w(TAG, "TYPE_GAME_ROTATION_VECTOR unavailable; guidance cannot run")
        onDispose { sensor.stop() }
    }

    // --- camera + analysis -----------------------------------------------
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
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
            )

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
                    "roll=%+.1f pitch=%+.1f faces=%d %s det=%dms err=%.3f lock=%.2f -> %s".format(
                        attitude.attitude.rollDeg, attitude.attitude.pitchDeg,
                        result.faceCount, stable, result.detectMs,
                        engine.totalError, engine.lockProgress, next.text,
                    ),
                )
            }
        }

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
                // The HAL does not reset zoom to 1.0 on its own - on this
                // phone a fresh bind can start at 2x, which is neither what
                // the composition profiles assume nor what the zoom slider
                // shows by default. Ask for 1x explicitly so a cold launch
                // starts exactly where the mockup and the profiles expect.
                .setCaptureRequestOption(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
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
                    .apply { previewView.viewPort?.let { setViewPort(it) } }
                    .build()

                runCatching {
                    provider.unbindAll()
                    // Rear camera only.
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        group,
                    )
                    cameraControl = camera
                    val characteristics =
                        Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)
                    val statics = CameraTelemetry.fromCharacteristics(characteristics)
                    telemetry = telemetry.copy(
                        maxZoomRatio = statics.maxZoomRatio,
                        minFocusCm = statics.minFocusCm,
                        hasAutofocus = statics.hasAutofocus,
                        maxIso = statics.maxIso,
                    )
                    engine.reportZoom(telemetry.zoomRatio, statics.maxZoomRatio)
                    Log.i(
                        TAG,
                        "bound rear camera: autofocus=${statics.hasAutofocus} " +
                            "zoom<=${statics.maxZoomRatio}x minFocus=${statics.minFocusCm}cm " +
                            "viewPort=${previewView.viewPort != null}",
                    )
                }.onFailure { Log.e(TAG, "bindToLifecycle failed", it) }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get().unbindAll() }
            cameraControl = null
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
