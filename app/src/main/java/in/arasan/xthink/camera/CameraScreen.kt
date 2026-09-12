package `in`.arasan.xthink.camera

import android.Manifest
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
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
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import `in`.arasan.xthink.guidance.CommandSafety
import `in`.arasan.xthink.guidance.GeniusPlan
import `in`.arasan.xthink.guidance.PlanStep
import `in`.arasan.xthink.guidance.GeniusIntent
import `in`.arasan.xthink.guidance.GeniusRouter
import `in`.arasan.xthink.guidance.Route
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
import `in`.arasan.xthink.ui.GeniusState
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
private const val SHARP_DECAY = 0.97f

/** A frame this far below the reference is soft: no automatic shutter. */
private const val SHARP_FRACTION = 0.35f

/** 35mm-equivalent focal lengths at 1x, measured in docs/HARDWARE.md. */
private const val REAR_FOCAL_MM = 23.5f
private const val FRONT_FOCAL_MM = 21.2f

/** Video focus tracking: re-aim no more often than this... */
private const val TRACK_INTERVAL_MS = 500L

/** ...and only when the subject has moved this far (fraction of the frame). */
private const val TRACK_MOVE = 0.04f

/** Subject gone this long: back to continuous AF. */
private const val TRACK_LOST_MS = 1_500L

/** Genius stops proposing after this many plan-and-check rounds. */
private const val GENIUS_MAX_ATTEMPTS = 3

/** The coach model loads on its own only this long after start, and only on a cool phone. */
private const val COACH_DEFERRED_LOAD_MS = 60_000L

/** After a plan runs, the Mac gets this long to settle before the camera reads it. */
private const val GENIUS_SETTLE_MS = 2_500L
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
fun CameraScreen(debugEnhanceUri: String? = null, debugGenius: String? = null) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CameraAndGuidance(debugEnhanceUri, debugGenius)
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
private fun CameraAndGuidance(debugEnhanceUri: String? = null, debugGenius: String? = null) {
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
    val shutterSound = remember {
        MediaActionSound().apply {
            load(MediaActionSound.SHUTTER_CLICK)
            load(MediaActionSound.START_VIDEO_RECORDING)
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    // --- video ------------------------------------------------------------
    // VIDEO swaps ImageCapture for VideoCapture in the bound group (the
    // analysis stays: guidance keeps running, and it is what focus follows).
    var videoMode by remember { mutableStateOf(false) }
    val recorder = remember {
        Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)),
            )
            .build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    val lastTrack = remember { floatArrayOf(-1f, -1f) } // x, y of the last focus move
    val lastTrackMs = remember { longArrayOf(0L) }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Log.i(TAG, "video: microphone ${if (granted) "granted" else "denied - clips will be silent"}")
    }

    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun startRecording() {
        if (activeRecording != null) return
        val name = "xthink_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/xThink")
        }
        val output = MediaStoreOutputOptions
            .Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        val withAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val pending = recorder.prepareRecording(context, output).apply { if (withAudio) withAudioEnabled() }
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                    overlayState = overlayState.copy(recording = true, recordingMs = 0L)
                    Log.i(TAG, "video: recording (audio=$withAudio)")
                }
                is VideoRecordEvent.Status -> {
                    overlayState = overlayState.copy(recordingMs = event.recordingStats.recordedDurationNanos / 1_000_000L)
                }
                is VideoRecordEvent.Finalize -> {
                    shutterSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                    activeRecording = null
                    val uri = event.outputResults.outputUri
                    if (event.hasError()) {
                        Log.e(TAG, "video: finalize error ${event.error}", event.cause)
                    } else {
                        lastCaptureUri = uri
                        Log.i(TAG, "video: saved ${event.recordingStats.recordedDurationNanos / 1_000_000L} ms -> $uri")
                    }
                    val thumb = runCatching { context.contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
                    overlayState = overlayState.copy(
                        recording = false,
                        thumbnail = thumb?.asImageBitmap() ?: overlayState.thumbnail,
                    )
                }
                else -> Unit
            }
        }
    }
    DisposableEffect(Unit) { onDispose { stopRecording() } }
    DisposableEffect(shutterSound) { onDispose { shutterSound.release() } }

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
                look = if (overlayState.look != 0) overlayState.look else (result.suggestedLook ?: 0),
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
    var coachState by remember { mutableStateOf(LlmCoach.State.MISSING) } // until ensureCoach() loads it
    val sharpRef = remember { floatArrayOf(0f) }
    // The model loads only when something needs it: STEVE, or - a while
    // after start and only with the phone cool - the crop decision. Loading
    // it at launch heated the phone and slowed guidance and the shutter.
    DisposableEffect(coach) { onDispose { coach.close() } }
    fun ensureCoach() {
        if (coachState == LlmCoach.State.MISSING && coach.modelFile() != null) {
            coach.warmUp(ContextCompat.getMainExecutor(context)) { coachState = it }
        }
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

    // --- GENIUS -------------------------------------------------------------
    // Say it; the phone proposes what to do on the Mac; YOU tap Run. The
    // phone is a Bluetooth keyboard; Gemma turns the words into a plan of
    // verbs; every plan is shown in full, checked by CommandSafety, and
    // performed only after a tap. Afterwards the camera reads the Mac
    // screen and Gemma may PROPOSE a next step - which again waits for a
    // tap. Nothing runs unattended, and nothing retries by itself.
    var typeMode by remember { mutableStateOf(false) }
    val keyboard = remember { MacKeyboard(context) }
    val reader = remember { ScreenReader() }
    val speech = remember { SpeechInput(context) }
    var keyboardState by remember { mutableStateOf(MacKeyboard.State.NO_BLUETOOTH) }
    var gPhase by remember { mutableStateOf("READY") }
    var gHeard by remember { mutableStateOf("") }
    var gPlan by remember { mutableStateOf<List<PlanStep>>(emptyList()) }
    var gStep by remember { mutableIntStateOf(-1) }
    var gAttempt by remember { mutableIntStateOf(0) }
    var gNote by remember { mutableStateOf<String?>(null) }
    var gDraft by remember { mutableStateOf("") }
    DisposableEffect(keyboard) { onDispose { keyboard.stop(); reader.close(); speech.close() } }

    fun keyboardLine(): String = when (keyboardState) {
        MacKeyboard.State.NO_BLUETOOTH -> "No Bluetooth on this phone"
        MacKeyboard.State.NEEDS_PERMISSION -> "Bluetooth permission needed"
        MacKeyboard.State.BLUETOOTH_OFF -> "Turn Bluetooth on, then Pair"
        MacKeyboard.State.REGISTERING -> "Becoming a keyboard\u2026"
        MacKeyboard.State.READY -> "Keyboard ready \u2014 on the Mac, connect to \u201cxThink\u201d"
        MacKeyboard.State.CONNECTING -> "Connecting\u2026"
        MacKeyboard.State.CONNECTED -> "Linked to ${keyboard.hostName ?: "the Mac"}"
    }

    fun refreshGenius() {
        overlayState = overlayState.copy(
            typeMode = typeMode,
            genius = if (typeMode) GeniusState(
                keyboard = keyboardLine(),
                connected = keyboardState == MacKeyboard.State.CONNECTED,
                speechAvailable = speech.available,
                phase = gPhase,
                heard = gHeard,
                plan = gPlan.map { it.line },
                step = gStep,
                attempt = gAttempt,
                note = gNote,
                refusals = CommandSafety.refusals(gPlan),
                draft = gDraft,
            ) else null,
        )
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        Log.i(TAG, "keyboard: permissions $grants")
        if (grants.values.all { it }) keyboard.start(ContextCompat.getMainExecutor(context)) { st ->
            keyboardState = st
            refreshGenius()
        }
    }
    val discoverableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        Log.i(TAG, "keyboard: discoverable result=${r.resultCode}")
        keyboard.connectBonded()
    }

    fun geniusFail(why: String) {
        gPhase = "FAILED"
        gNote = why
        Log.w(TAG, "genius: failed - $why")
        haptics.play(HapticCue.UNLOCK, 0.6f)
        refreshGenius()
    }

    fun geniusDone(why: String?) {
        gPhase = "DONE"
        gStep = gPlan.size
        gNote = why
        Log.i(TAG, "genius: done${why?.let { " ($it)" } ?: ""}")
        haptics.play(HapticCue.LOCK, 0.8f)
        refreshGenius()
    }

    /** A plan is on the table; it runs only when the photographer taps Run. */
    fun geniusPropose(steps: List<PlanStep>, phase: String) {
        gPlan = steps
        gStep = -1
        gPhase = phase
        val refusals = CommandSafety.refusals(steps)
        gNote = if (refusals.any { it != null }) "This plan will not run." else null
        Log.i(TAG, "genius: $phase ${steps.size} steps: ${steps.joinToString(" | ") { it.line }} refused=${refusals.count { it != null }}")
        haptics.play(HapticCue.TICK, 0.5f)
        refreshGenius()
    }

    /** After a run: read the Mac screen and let Gemma propose - never perform - a next step. */
    fun geniusCheck() {
        if (gAttempt >= GENIUS_MAX_ATTEMPTS) { geniusDone("did what was asked") ; return }
        gPhase = "CHECKING"
        refreshGenius()
        Handler(Looper.getMainLooper()).postDelayed({
            if (gPhase != "CHECKING") return@postDelayed
            val snap = previewSnapshot()
            if (snap == null) { geniusDone("could not see the screen"); return@postDelayed }
            val started = reader.read(snap, ContextCompat.getMainExecutor(context)) { screen ->
                if (gPhase != "CHECKING") return@read
                if (screen.isBlank()) { geniusDone("nothing readable on the screen"); return@read }
                Log.i(TAG, "genius: screen reads ${screen.length} chars: ${screen.take(100).replace('\n', ' ')}")
                val asked = coach.askText(LlmCoach.Kind.CHECK, LlmCoach.checkPrompt(gHeard, screen, gAttempt), ContextCompat.getMainExecutor(context)) { text, done ->
                    if (!done || gPhase != "CHECKING") return@askText
                    if (GeniusPlan.isDone(text)) { geniusDone(null); return@askText }
                    val next = GeniusPlan.parse(text).filter { !it.line.uppercase().startsWith("DONE") }
                    if (next.isEmpty()) geniusDone(null) else { gAttempt += 1; geniusPropose(next, "PROPOSED") }
                }
                if (!asked) geniusDone("coach busy; not verified")
            }
            if (!started) geniusDone("screen reader busy; not verified")
        }, GENIUS_SETTLE_MS)
    }

    /** The tap. Performs the plan on the table, if CommandSafety lets it. */
    fun geniusRun() {
        val steps = gPlan
        if (steps.isEmpty() || gPhase !in setOf("PLANNED", "PROPOSED")) return
        if (!CommandSafety.isSafe(steps)) { gNote = "This plan will not run."; refreshGenius(); return }
        if (keyboardState != MacKeyboard.State.CONNECTED) { geniusFail("the Mac is not linked - tap Pair Mac"); return }
        gStep = 0
        gPhase = "RUNNING"
        refreshGenius()
        Log.i(TAG, "genius: RUN confirmed - ${steps.size} steps")
        val ops = steps.flatMap { it.ops }
        val stepOfOp = IntArray(ops.size)
        var k = 0
        steps.forEachIndexed { i, st -> repeat(st.ops.size) { stepOfOp[k++] = i } }
        keyboard.perform(
            ops,
            onProgress = { i -> if (i < stepOfOp.size && stepOfOp[i] != gStep) { gStep = stepOfOp[i]; refreshGenius() } },
            onDone = { ok -> if (!ok) geniusFail("the keyboard link dropped or Stop was pressed") else geniusCheck() },
        )
    }

    /** Carry out the reading: the macros for the route, the model for the blank it leaves. */
    fun geniusAct(route: Route, heard: String) {
        val main = ContextCompat.getMainExecutor(context)
        val asked = when (route) {
            is Route.Help -> {
                gPlan = GeniusRouter.steps(route)
                gStep = -1
                gPhase = "DONE"
                gNote = "Say any of these. The plan shows first; Run does it."
                refreshGenius()
                true
            }
            is Route.Open, is Route.Website, is Route.Project -> { geniusPropose(GeniusRouter.steps(route), "PLANNED"); true }
            is Route.Terminal -> if (route.command != null) {
                geniusPropose(GeniusRouter.steps(route, route.command), "PLANNED"); true
            } else coach.askText(LlmCoach.Kind.COMMAND, LlmCoach.shellPrompt(heard), main) { text, done ->
                if (!done || gPhase != "THINKING") return@askText
                val cmd = text.trim().trim('`').trim()
                if (cmd.isBlank()) geniusFail("no command came back") else geniusPropose(GeniusRouter.steps(route, cmd), "PLANNED")
            }
            is Route.Write -> {
                gPhase = "WRITING"
                refreshGenius()
                coach.askText(LlmCoach.Kind.WRITE, LlmCoach.writePrompt(heard), main) { text, done ->
                    if (gPhase != "WRITING") return@askText
                    gDraft = text
                    if (!done) { refreshGenius(); return@askText }
                    val body = text.trim()
                    if (body.isBlank()) geniusFail("nothing was written") else { geniusPropose(GeniusRouter.steps(route, body), "PLANNED"); gDraft = body; refreshGenius() }
                }
            }
            is Route.WhatsApp -> if (route.message.isNotBlank()) {
                geniusPropose(GeniusRouter.steps(route), "PLANNED"); true
            } else coach.askText(LlmCoach.Kind.COMMAND, LlmCoach.messagePrompt(heard), main) { text, done ->
                if (!done || gPhase != "THINKING") return@askText
                val msg = text.trim().trim('"')
                if (msg.isBlank()) geniusFail("no message came back") else geniusPropose(GeniusRouter.steps(route, msg), "PLANNED")
            }
            is Route.Plan -> coach.askText(LlmCoach.Kind.PLAN, LlmCoach.planPrompt(heard), main) { text, done ->
                if (!done || gPhase != "THINKING") return@askText
                val steps = GeniusPlan.parse(text).filter { !it.line.uppercase().startsWith("DONE") }
                if (steps.isEmpty()) geniusFail("no plan came back: ${text.take(80)}") else geniusPropose(steps, "PLANNED")
            }
        }
        if (!asked) geniusFail("Steve is busy")
    }

    fun geniusPlan(heard: String) {
        gHeard = heard
        gPlan = emptyList()
        gStep = -1
        gAttempt = 1
        gNote = null
        if (coachState != LlmCoach.State.READY) { geniusFail("the coach model is not on this phone"); return }
        gPhase = "THINKING"
        refreshGenius()
        gDraft = ""
        val main = ContextCompat.getMainExecutor(context)
        // Gemma reads the sentence first - one line, KIND | ARG. When that
        // line cannot be read, the words route it instead.
        val understood = coach.askText(LlmCoach.Kind.UNDERSTAND, LlmCoach.understandPrompt(heard), main) { text, done ->
            if (!done || gPhase != "THINKING") return@askText
            val (route, fromModel) = GeniusIntent.decide(text, heard)
            Log.i(TAG, "steve: gemma says '${text.trim().take(60)}' -> ${route::class.simpleName}${if (!fromModel) " (router)" else ""}")
            geniusAct(route, heard)
        }
        if (!understood) geniusFail("Steve is busy")
    }


    fun geniusSpeak() {
        if (gPhase == "LISTENING") { speech.stop(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        gPhase = "LISTENING"
        gHeard = ""
        gPlan = emptyList()
        gStep = -1
        gNote = null
        refreshGenius()
        speech.listen(
            onPartial = { partial -> gHeard = partial; refreshGenius() },
            onResult = { heard ->
                if (heard.isBlank()) { gPhase = "READY"; gNote = "Didn't catch that"; refreshGenius() }
                else geniusPlan(heard)
            },
            onDone = { if (gPhase == "LISTENING") { gPhase = "READY"; refreshGenius() } },
        )
    }

    fun geniusStop() {
        Log.i(TAG, "genius: stop pressed")
        keyboard.cancelled = true
        speech.stop()
        gPhase = "READY"
        gPlan = emptyList()
        gStep = -1
        gNote = "stopped"
        refreshGenius()
    }

    LaunchedEffect(debugGenius) {
        if (debugGenius != null && !typeMode) {
            typeMode = true
            refreshGenius()
            if (keyboard.hasPermission()) keyboard.start(ContextCompat.getMainExecutor(context)) { st -> keyboardState = st; refreshGenius() }
        }
    }

    // Dev hook: am start --es genius "<what to say>" plans a request without the microphone. Running it still takes the tap.
    LaunchedEffect(debugGenius, coachState, typeMode) {
        if (debugGenius == null || !typeMode || coachState != LlmCoach.State.READY) return@LaunchedEffect
        if (gPhase == "READY" && gHeard.isEmpty()) geniusPlan(debugGenius)
    }

    // The analyser is created inside the camera effect; the mode switch and
    // the governor both need to reach it from outside, so hold a reference.
    val analyzerRef = remember { arrayOfNulls<FaceAnalyzer>(1) }

    fun capture(auto: Boolean) {
        if (captureInFlight) return
        captureInFlight = true
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
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
                        if (overlayState.mode == CoachMode.PORTRAIT) {
                            enhancer.analyse(uri, ContextCompat.getMainExecutor(context), cutAdvisor()) { openReview(uri, it) }
                        } else {
                            // Scenes, objects, creative: no crop to a person - the shot stands, with the look.
                            openReview(uri, PhotoEnhancer.Result(null, null))
                        }
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

    // A deferred load of the coach, only on a cool phone.
    LaunchedEffect(Unit) {
        delay(COACH_DEFERRED_LOAD_MS)
        if (thermalPlan[0].tier == ThermalTier.COOL) ensureCoach()
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

    fun selectMode(mode: CoachMode, leaveVideo: Boolean = true) {
        if (typeMode && leaveVideo) {
            Log.i(TAG, "genius: left by mode change")
            keyboard.cancelled = true
            typeMode = false
            overlayState = overlayState.copy(typeMode = false, genius = null)
            Log.i(TAG, "mode -> photo (from GENIUS)")
        }
        if (videoMode && leaveVideo) {
            videoMode = false
            overlayState = overlayState.copy(videoMode = false, recording = false)
            Log.i(TAG, "mode -> photo")
        }
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

    DisposableEffect(lifecycleOwner, lensFacing, videoMode) {
        stopRecording()
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
        val forVideo = videoMode
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
                showGuide = overlayState.showGuide,
                baseFocalMm = overlayState.baseFocalMm,
                shotStyle = overlayState.shotStyle,
                showShots = overlayState.showShots,
                videoMode = overlayState.videoMode,
                recording = overlayState.recording,
                recordingMs = overlayState.recordingMs,
                typeMode = overlayState.typeMode,
                genius = overlayState.genius,
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
            // Video: focus follows the subject. Whenever the chosen face (or
            // object) has moved a little since the last move, and not more
            // often than TRACK_INTERVAL_MS, meter and focus where it is now.
            // Lost for a while, hand back to continuous AF.
            if (videoMode) {
                val subj = result.subject
                val now = SystemClock.uptimeMillis()
                if (subj != null) {
                    val moved = lastTrack[0] < 0f || abs(subj.cx - lastTrack[0]) > TRACK_MOVE || abs(subj.cy - lastTrack[1]) > TRACK_MOVE
                    if (moved && now - lastTrackMs[0] >= TRACK_INTERVAL_MS) {
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(subj.cx * previewView.width, subj.cy * previewView.height)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                            .disableAutoCancel()
                            .build()
                        cameraControl?.cameraControl?.startFocusAndMetering(action)
                        lastTrack[0] = subj.cx; lastTrack[1] = subj.cy; lastTrackMs[0] = now
                        Log.i(TAG, "video: focus follows subject at (%.2f, %.2f)".format(subj.cx, subj.cy))
                    }
                } else if (lastTrack[0] >= 0f && now - lastTrackMs[0] > TRACK_LOST_MS) {
                    cameraControl?.cameraControl?.cancelFocusAndMetering()
                    lastTrack[0] = -1f; lastTrack[1] = -1f
                    Log.i(TAG, "video: subject lost, continuous AF")
                }
            }

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
            if (wantsShot && thermalPlan[0].autoCaptureOn && overlayState.easyShot && overlayState.review == null && !videoMode) {
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
                    .addUseCase(if (forVideo) videoCapture else imageCapture)
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
                    overlayState = overlayState.copy(mirrored = isFront, baseFocalMm = if (isFront) FRONT_FOCAL_MM else REAR_FOCAL_MM)
                    Log.i(
                        TAG,
                        "bound ${if (isFront) "front" else "rear"} camera${if (forVideo) " for video" else ""}: mirrored=$isFront " +
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
            onTypeMode = {
                if (!typeMode) {
                    if (videoMode) { stopRecording(); videoMode = false }
                    typeMode = true
                    gPhase = "READY"; gHeard = ""; gPlan = emptyList(); gStep = -1; gNote = null
                    overlayState = overlayState.copy(videoMode = false, recording = false, review = null, showLooks = false)
                    refreshGenius()
                    ensureCoach()
                    Log.i(TAG, "mode -> STEVE")
                    if (keyboard.hasPermission()) {
                        keyboard.start(ContextCompat.getMainExecutor(context)) { st ->
                            keyboardState = st
                            refreshGenius()
                        }
                    } else {
                        bluetoothLauncher.launch(keyboard.permissions)
                    }
                }
            },
            onPairMac = {
                when (keyboardState) {
                    MacKeyboard.State.NEEDS_PERMISSION -> bluetoothLauncher.launch(keyboard.permissions)
                    else -> {
                        if (keyboardState != MacKeyboard.State.READY && keyboardState != MacKeyboard.State.CONNECTING) {
                            keyboard.start(ContextCompat.getMainExecutor(context)) { st -> keyboardState = st; refreshGenius() }
                        }
                        discoverableLauncher.launch(keyboard.discoverableIntent())
                        keyboard.connectBonded()
                    }
                }
            },
            onListen = { geniusSpeak() },
            onGeniusRun = { geniusRun() },
            onGeniusStop = { geniusStop() },
            onVideoMode = {
                if (!videoMode) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    videoMode = true
                    typeMode = false
                    overlayState = overlayState.copy(videoMode = true, review = null, typeMode = false, genius = null)
                    Log.i(TAG, "mode -> VIDEO")
                }
            },
            onShutter = {
                if (typeMode) {
                    geniusSpeak()
                } else if (videoMode) {
                    if (activeRecording == null) startRecording() else stopRecording()
                } else {
                    autoCapture.notifyManualCapture()
                    capture(auto = false)
                }
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
            onToggleShots = {
                overlayState = overlayState.copy(showShots = !overlayState.showShots, showLooks = false)
            },
            onPickShot = { style ->
                shotTypes.setStyle(style)
                engine.setProfile(profiles.getValue(shotTypes.current))
                overlayState = overlayState.copy(shotStyle = style)
                haptics.play(HapticCue.TICK, 0.4f)
                Log.i(TAG, "shot style -> ${style ?: "auto"}")
            },
            onToggleLooks = {
                val open = !overlayState.showLooks
                val preview = if (open) previewSnapshot()?.let { snap ->
                    val scale = 240f / maxOf(snap.width, snap.height)
                    Bitmap.createScaledBitmap(snap, (snap.width * scale).toInt().coerceAtLeast(1), (snap.height * scale).toInt().coerceAtLeast(1), true)
                        .asImageBitmap()
                } else null
                overlayState = overlayState.copy(showLooks = open, showShots = false, lookPreview = preview ?: overlayState.lookPreview)
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
                val toFront = lensFacing == CameraSelector.LENS_FACING_BACK
                lensFacing = if (toFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                // The selfie lens is for people.
                if (toFront) selectMode(CoachMode.PORTRAIT, leaveVideo = false)
            },
            onToggleGuide = {
                overlayState = overlayState.copy(showGuide = !overlayState.showGuide)
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
    showGuide: Boolean,
    baseFocalMm: Float,
    shotStyle: ShotType?,
    showShots: Boolean,
    videoMode: Boolean,
    recording: Boolean,
    recordingMs: Long,
    typeMode: Boolean,
    genius: GeniusState?,
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
        showGuide = showGuide,
        baseFocalMm = baseFocalMm,
        shotStyle = shotStyle,
        showShots = showShots,
        videoMode = videoMode,
        recording = recording,
        recordingMs = recordingMs,
        typeMode = typeMode,
        genius = genius,
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
