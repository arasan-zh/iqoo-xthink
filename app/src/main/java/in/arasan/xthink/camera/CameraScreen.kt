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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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

private const val TAG = "xThink"

/**
 * v0.2-anchor: rear camera preview, real device attitude, and the guidance
 * engine actually running.
 *
 * There is no subject detection yet, so the engine is fed `subject = null` on a
 * LANDSCAPE profile. That exercises the top of the priority ladder for real -
 * roll, then pitch, then the lock dwell - which is enough to prove the whole
 * chain from sensor to instruction on the phone.
 *
 * Deliberately unstyled. The stock-camera look in CLAUDE.md lands in
 * v0.3-frame, as a separate overlay layer over this same camera code.
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
    var telemetry by remember { mutableStateOf(CameraTelemetry()) }
    var totalError by remember { mutableStateOf(0f) }
    var sensorMissing by remember { mutableStateOf(false) }

    // No subject detection yet, so compose against LANDSCAPE: it is the profile
    // with no size target, which is exactly the "nothing to frame" case.
    val engine = remember {
        GuidanceEngine(loadProfile(context, ShotType.LANDSCAPE))
    }

    // --- sensor -> engine ------------------------------------------------
    // Events are delivered on the main looper and the camera callback posts
    // there too, so the engine is only ever touched by one thread. That is
    // cheaper and less error-prone than locking it.
    DisposableEffect(engine) {
        val sensor = AttitudeSensor(context)
        var lastUiUpdate = 0L
        var lastHeartbeat = 0L

        val started = sensor.start { s ->
            // The engine sees every sample; the UI is throttled, because
            // recomposing a text block at 100 Hz is pure waste.
            val next = engine.update(s.attitude, null, null, s.dtMs)
            val now = SystemClock.uptimeMillis()
            if (now - lastUiUpdate >= UI_THROTTLE_MS) {
                lastUiUpdate = now
                sample = s
                instruction = next
                totalError = engine.totalError
            }
            // Once a second, so the whole chain can be watched over
            // `scripts/dev.sh log` with the phone face down or the screen off.
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now
                Log.i(
                    TAG,
                    "roll=%+.1f pitch=%+.1f imu=%.0fHz err=%.3f lock=%.2f -> %s".format(
                        s.attitude.rollDeg, s.attitude.pitchDeg, s.hz,
                        engine.totalError, engine.lockProgress, next.text,
                    ),
                )
            }
        }
        sensorMissing = !started
        if (!started) {
            Log.w(TAG, "TYPE_GAME_ROTATION_VECTOR unavailable; guidance cannot run")
        }
        onDispose { sensor.stop() }
    }

    // --- camera ----------------------------------------------------------
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val mainHandler = Handler(Looper.getMainLooper())
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            val provider = runCatching { future.get() }.getOrElse {
                Log.e(TAG, "could not get the camera provider", it)
                return@addListener
            }

            val previewBuilder = Preview.Builder()

            // Camera2 interop is how we read what the ISP is doing. Continuous
            // picture AF keeps CONTROL_AF_STATE meaningful without us having to
            // drive a focus routine ourselves.
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
                        // Hop to the main thread so the engine stays single-threaded.
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

            runCatching {
                provider.unbindAll()
                // Rear camera only. No flip button in this step.
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                )
                val characteristics = Camera2CameraInfo.extractCameraCharacteristics(camera.cameraInfo)
                val statics = CameraTelemetry.fromCharacteristics(characteristics)
                mainHandler.post {
                    telemetry = telemetry.copy(
                        maxZoomRatio = statics.maxZoomRatio,
                        minFocusCm = statics.minFocusCm,
                        hasAutofocus = statics.hasAutofocus,
                    )
                    engine.reportZoom(telemetry.zoomRatio, statics.maxZoomRatio)
                }
                Log.i(
                    TAG,
                    "bound rear camera: autofocus=${statics.hasAutofocus} " +
                        "zoom<=${statics.maxZoomRatio}x minFocus=${statics.minFocusCm}cm",
                )
            }.onFailure { Log.e(TAG, "bindToLifecycle failed", it) }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { future.get().unbindAll() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        DebugReadout(
            instruction = instruction,
            sample = sample,
            telemetry = telemetry,
            totalError = totalError,
            sensorMissing = sensorMissing,
            modifier = Modifier.align(Alignment.TopStart),
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
            text = instruction?.text ?: "waiting for the sensor...",
            color = Color(0xFF4ADE80),
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )

        val lines = buildList {
            instruction?.let { add("verb     ${it.verb}  ${it.magnitude}") }
            sample?.let {
                add("roll     %+.2f deg%s".format(it.attitude.rollDeg, if (it.rollReliable) "" else "  (unreliable)"))
                add("pitch    %+.2f deg".format(it.attitude.pitchDeg))
                add("imu      %.0f Hz  dt %d ms".format(it.hz, it.dtMs))
            }
            add("error    %.3f".format(totalError))
            add("af       ${telemetry.afText}${if (telemetry.focusLocked) "  LOCKED" else ""}")
            add("iso      ${telemetry.iso ?: "-"}   shutter ${telemetry.shutterText}")
            add("zoom     %.2fx / %.2fx".format(telemetry.zoomRatio, telemetry.maxZoomRatio))
            add("focus    ${telemetry.minFocusCm?.let { "%.0f cm min".format(it) } ?: "fixed"}")
            add("frames   ${telemetry.frames}")
        }
        for (l in lines) {
            Text(l, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

private const val UI_THROTTLE_MS = 33L // ~30 Hz is plenty for reading numbers
private const val HEARTBEAT_MS = 1000L

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Reads `assets/composition_profiles.json` and hands the text to :guidance,
 * which parses it. The asset API stays on this side of the module boundary.
 */
private fun loadProfile(context: Context, shotType: ShotType): CompositionProfile {
    val json = context.assets.open("composition_profiles.json")
        .bufferedReader()
        .use { it.readText() }
    return CompositionProfile.parse(json, shotType)
}
