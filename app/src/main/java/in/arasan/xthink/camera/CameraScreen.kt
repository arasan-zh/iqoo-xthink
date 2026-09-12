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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import `in`.arasan.xthink.guidance.CompositionProfile
import `in`.arasan.xthink.guidance.GuidanceEngine
import `in`.arasan.xthink.guidance.Instruction
import `in`.arasan.xthink.guidance.ShotType
import java.util.concurrent.Executors

private const val TAG = "xThink"
private const val HEARTBEAT_MS = 1000L
private const val ANALYSIS_WIDTH = 480
private const val ANALYSIS_HEIGHT = 360

/**
 * v0.2-anchor: rear camera preview, real device attitude, ML Kit face
 * detection, and the full guidance ladder running on the phone.
 *
 * Deliberately unstyled. The stock-camera look in CLAUDE.md lands in
 * v0.3-frame as a separate overlay layer over this same camera code.
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

    var instruction by remember { mutableStateOf<Instruction?>(null) }
    var sample by remember { mutableStateOf<AttitudeSample?>(null) }
    var faces by remember { mutableStateOf<FaceResult?>(null) }
    var telemetry by remember { mutableStateOf(CameraTelemetry()) }
    var totalError by remember { mutableStateOf(0f) }
    var sensorMissing by remember { mutableStateOf(false) }

    val profiles = remember { loadProfiles(context) }
    val engine = remember { GuidanceEngine(profiles.getValue(ShotType.LANDSCAPE)) }

    // The most recent attitude, held rather than acted on. See the analyser
    // comment below for why the sensor does not drive the engine.
    val latestAttitude = remember { arrayOfNulls<AttitudeSample>(1) }

    // --- sensor ----------------------------------------------------------
    DisposableEffect(engine) {
        val sensor = AttitudeSensor(context)
        val started = sensor.start { s ->
            latestAttitude[0] = s
            sample = s
        }
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
        var lastHeartbeat = 0L

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
            faces = result

            val attitude = latestAttitude[0] ?: return@FaceAnalyzer
            engine.setProfile(profiles.getValue(result.shotType))
            val next = engine.update(attitude.attitude, result.subject, result.eyes, result.dtMs)

            instruction = next
            totalError = engine.totalError

            val now = SystemClock.uptimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now
                Log.i(
                    TAG,
                    "roll=%+.1f pitch=%+.1f faces=%d %s det=%dms err=%.3f lock=%.2f -> %s".format(
                        attitude.attitude.rollDeg, attitude.attitude.pitchDeg,
                        result.faceCount, result.shotType, result.detectMs,
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
                .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        mainHandler.post {
                            val next = CameraTelemetry.from(telemetry, result)
                            telemetry = next
                            engine.reportFocusLocked(next.focusLocked)
                            engine.reportZoom(next.zoomRatio, next.maxZoomRatio)
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
                    val characteristics =
                        Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)
                    val statics = CameraTelemetry.fromCharacteristics(characteristics)
                    telemetry = telemetry.copy(
                        maxZoomRatio = statics.maxZoomRatio,
                        minFocusCm = statics.minFocusCm,
                        hasAutofocus = statics.hasAutofocus,
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
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        DebugReadout(
            instruction = instruction,
            sample = sample,
            faces = faces,
            telemetry = telemetry,
            totalError = totalError,
            sensorMissing = sensorMissing,
            // Preview stays full-bleed; only the readout clears the status bar.
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
        )
    }
}

/**
 * Raw numbers, not a design. Proving the chain works comes before making it
 * look like a camera app.
 */
@Composable
private fun DebugReadout(
    instruction: Instruction?,
    sample: AttitudeSample?,
    faces: FaceResult?,
    telemetry: CameraTelemetry,
    totalError: Float,
    sensorMissing: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (sensorMissing) {
            Text(
                "GAME_ROTATION_VECTOR unavailable",
                color = Color.Red,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }

        Text(
            text = instruction?.text ?: "waiting for a frame...",
            color = Color(0xFF4ADE80),
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )

        val lines = buildList {
            instruction?.let { add("verb     ${it.verb}  ${it.magnitude}") }
            faces?.let { f ->
                add("faces    ${f.faceCount}  ${f.shotType}  det ${f.detectMs}ms")
                f.subject?.let { b ->
                    add("subject  cx %.3f cy %.3f h %.3f".format(b.cx, b.cy, b.h))
                }
                f.eyes?.let { e ->
                    add("eyes     y %.3f  gaze %+.2f".format(e.y, e.gazeDx))
                }
            }
            sample?.let {
                add("roll     %+.2f deg%s".format(it.attitude.rollDeg, if (it.rollReliable) "" else " (bad)"))
                add("pitch    %+.2f deg".format(it.attitude.pitchDeg))
                add("imu      %.0f Hz".format(it.hz))
            }
            add("error    %.3f".format(totalError))
            add("af       ${telemetry.afText}${if (telemetry.focusLocked) "  LOCKED" else ""}")
            add("iso      ${telemetry.iso ?: "-"}   shutter ${telemetry.shutterText}")
            add("zoom     %.2fx / %.2fx".format(telemetry.zoomRatio, telemetry.maxZoomRatio))
            add("frames   ${telemetry.frames}")
        }
        for (l in lines) {
            Text(l, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
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
