package `in`.arasan.xthink.camera

import android.Manifest
import android.content.Context
import android.content.ContentValues
import android.media.AudioManager
import android.content.pm.ActivityInfo
import android.content.ContextWrapper
import android.app.Activity
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
import android.speech.tts.TextToSpeech
import android.content.ClipData
import android.content.ClipboardManager
import `in`.arasan.xthink.ui.AskState
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
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import `in`.arasan.xthink.guidance.AlignmentState
import `in`.arasan.xthink.guidance.AutoCapturePolicy
import `in`.arasan.xthink.guidance.CoachMode
import `in`.arasan.xthink.guidance.DirectionCues
import `in`.arasan.xthink.guidance.CommandSafety
import `in`.arasan.xthink.guidance.GeniusPlan
import `in`.arasan.xthink.guidance.PlanStep
import `in`.arasan.xthink.guidance.GeniusIntent
import `in`.arasan.xthink.guidance.GeniusRouter
import `in`.arasan.xthink.guidance.Finishing
import `in`.arasan.xthink.guidance.LookRation
import `in`.arasan.xthink.guidance.MacWatch
import `in`.arasan.xthink.guidance.WatchSession
import `in`.arasan.xthink.guidance.Route
import `in`.arasan.xthink.guidance.Exercise
import `in`.arasan.xthink.guidance.RepCounter
import `in`.arasan.xthink.ui.FitState
import `in`.arasan.xthink.ui.WatchState
import `in`.arasan.xthink.ui.zoomCapFor
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
/** A job the user asked to repeat may go round this many times before Steve gives up. */
private const val GENIUS_MAX_ATTEMPTS_REPEAT = 8

/** A clean plan runs by itself after this many seconds unless cancelled. */
private const val GENIUS_AUTORUN_S = 5

/** In ASK, an answer is dropped once the phone has turned this far from where it was asked. */
private const val ASK_MOVE_DEG = 25f

/** The coach model loads on its own only this long after start, and only on a cool phone. */
private const val COACH_DEFERRED_LOAD_MS = 60_000L

/** After a plan runs, the Mac gets this long to settle before the camera reads it. */
private const val GENIUS_SETTLE_MS = 2_500L
/** After a CLAUDE step the Mac is given this long before the first look: Claude Code starts slowly. */
private const val GENIUS_CLAUDE_SETTLE_MS = 20_000L
/** When the check says WAIT, look again this much later, this many times at most. */
private const val GENIUS_RECHECK_MS = 12_000L
private const val GENIUS_MAX_WAITS = 10
/** How often the camera reads the Mac screen while Steve's room is open. Cheap: ML Kit, no model. */
private const val WATCH_PERIOD_MS = 2_000L
/** The model narrates a changed screen at most this often. A run or a tap narrates at once. */
private const val WATCH_NARRATE_MIN_MS = 20_000L
private const val WATCH_LOG_MAX = 10
/** Where a finished watch goes first, when installed: WhatsApp, then WhatsApp Business; else the share sheet. */
private val WATCH_MESSENGERS = listOf("com.whatsapp", "com.whatsapp.w4b")
/** The WhatsApp number a finished watch is written to, country code first, no plus. */
private const val WATCH_WHATSAPP_NUMBER = "919442851409"
/** WhatsApp takes a prefilled message of about this much through its link. */
private const val WATCH_WHATSAPP_MAX_CHARS = 4000
/** WATCH: how often the loop wakes; the gap before the microphone is reopened; the grid the scene is compared on. */
private const val WATCH_TICK_MS = 2000L
private const val WATCH_LISTEN_GAP_MS = 400L
/** ...and after a phrase of nothing, this long: fewer restarts, fewer chimes. */
private const val WATCH_LISTEN_GAP_QUIET_MS = 4000L
private const val WATCH_GRID_W = 24
private const val WATCH_GRID_H = 18
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
fun CameraScreen(
    coach: LlmCoach? = null,
    inpainter: Inpainter? = null,
    debugEnhanceUri: String? = null,
    debugRetouchOff: Boolean = false,
    debugGenius: String? = null,
    debugAsk: String? = null,
    startIn: String? = null,
    onHome: () -> Unit = {},
    onOpenRoom: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CameraAndGuidance(coach, inpainter, debugEnhanceUri, debugRetouchOff, debugGenius, debugAsk, startIn, onHome, onOpenRoom)
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
private fun CameraAndGuidance(
    sharedCoach: LlmCoach? = null,
    sharedInpainter: Inpainter? = null,
    debugEnhanceUri: String? = null,
    debugRetouchOff: Boolean = false,
    debugGenius: String? = null,
    debugAsk: String? = null,
    startIn: String? = null,
    onHome: () -> Unit = {},
    onOpenRoom: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayState by remember { mutableStateOf(OverlayState.EMPTY.copy(retouch = !debugRetouchOff)) }
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

    // The footage of a finished watch, waiting to be handed on once the
    // report has gone and the app is back in front; and whether one is due.
    val watchFootage = remember { arrayOfNulls<Uri>(1) }
    val watchFootageDue = remember { booleanArrayOf(false) }
    val lastVideoName = remember { arrayOf("") }
    fun startRecording(withAudio: Boolean = true) {
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
        lastVideoName[0] = name
        val audio = withAudio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val pending = recorder.prepareRecording(context, output).apply { if (audio) withAudioEnabled() }
        activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    shutterSound.play(MediaActionSound.START_VIDEO_RECORDING)
                    overlayState = overlayState.copy(recording = true, recordingMs = 0L)
                    Log.i(TAG, "video: recording (audio=$audio)")
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
                        if (watchFootageDue[0]) { watchFootage[0] = uri; watchFootageDue[0] = false }
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
    // ...and the retouch: LaMa fills what the coach names, offered the same way.
    // One LaMa for the whole app when the activity hands one in, so a
    // trip to a room and back does not close and reload it.
    val inpainter = sharedInpainter ?: remember { Inpainter(context) }
    DisposableEffect(inpainter) { onDispose { if (sharedInpainter == null) inpainter.close() } }
    var pendingEnhance by remember { mutableStateOf<PhotoEnhancer.Proposal?>(null) }
    var reviewClean by remember { mutableStateOf<PhotoEnhancer.Clean?>(null) }
    var reviewSource by remember { mutableStateOf<Uri?>(null) }

    // The retouch waits for the review to open.
    var cleanPending by remember { mutableStateOf<PhotoEnhancer.Result?>(null) }

    /** No review (scenes, objects): the shot stands as taken, with the camera-page look baked in if one is set. */
    fun bakeLook(source: Uri) {
        val look = Looks.ALL[overlayState.look]
        if (look.isNatural) return
        enhancer.saveFinal(source, null, false, look.matrix, ContextCompat.getMainExecutor(context)) { saved ->
            if (saved != null) lastCaptureUri = saved
            Log.i(TAG, "look ${look.name} baked -> $saved")
        }
    }

    /**
     * The review opens the moment the small decode is in: the shot as
     * taken, and a spinner where ENHANCED will be. The coach's plan and
     * LaMa's work arrive after, however long they take.
     */
    fun openReviewEarly(source: Uri, small: Bitmap) {
        pendingEnhance = null
        reviewSource = source
        reviewClean = null
        cleanPending = null
        overlayState = overlayState.copy(
            review = ReviewState(
                before = small.asImageBitmap(),
                after = null,
                rationale = emptyList(),
                enhanced = true,
                look = overlayState.look,
                analysing = true,
            ),
            showLooks = false,
        )
    }

    /** The coach's plan is in: the crop on offer, the look, and what LaMa is to do next. */
    fun applyAnalysis(source: Uri, result: PhotoEnhancer.Result) {
        if (reviewSource != source) return
        val r = overlayState.review ?: return
        if (result.small == null) { pendingEnhance = null; reviewSource = null; cleanPending = null; overlayState = overlayState.copy(review = null); return }
        pendingEnhance = result.proposal
        overlayState = overlayState.copy(
            review = r.copy(
                after = result.proposal?.after?.asImageBitmap(),
                rationale = result.proposal?.proposal?.rationale ?: emptyList(),
                look = if (overlayState.look != 0) overlayState.look else (result.suggestedLook ?: r.look),
                soft = result.soft,
                analysing = false,
                cleaning = !result.job.isEmpty,
            ),
        )
        cleanPending = result
        if (result.soft) Log.i(TAG, "capture looks soft")
    }

    fun closeReview() {
        pendingEnhance = null
        reviewSource = null
        reviewClean = null
        cleanPending = null
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
    val coach = sharedCoach ?: remember { LlmCoach(context) }

    /** What a model is doing right now, for the chip: the coach first, then LaMa. */
    fun modelActivity(): String? = coach.activity() ?: when {
        inpainter.loading -> "LaMa loading"
        inpainter.painting -> "LaMa painting"
        else -> null
    }
    var coachState by remember { mutableStateOf(coach.state) } // a shared model may already be loaded
    val sharpRef = remember { floatArrayOf(0f) }
    // The model loads only when something needs it: STEVE, or - a while
    // after start and only with the phone cool - the crop decision. Loading
    // it at launch heated the phone and slowed guidance and the shutter.
    DisposableEffect(coach) { onDispose { if (sharedCoach == null) coach.close() } }
    fun ensureCoach() {
        if (coachState == LlmCoach.State.MISSING && coach.modelFile() != null) {
            coach.warmUp(ContextCompat.getMainExecutor(context)) { coachState = it }
        }
    }


    /** The coach's plan for the finish, when there is a coach and it is free. */
    fun finishAdvisor(): PhotoEnhancer.FinishAdvisor? {
        if (coachState != LlmCoach.State.READY || coach.isBusy) return null
        return PhotoEnhancer.FinishAdvisor { photo, facts, candidates, answer ->
            val started = coach.ask(LlmCoach.Kind.FINISH, photo, LlmCoach.finishPrompt(facts, candidates), ContextCompat.getMainExecutor(context)) { text, done ->
                if (done) answer(text)
            }
            if (!started) answer(null)
        }
    }

    // LaMa's session is loaded once the coach is - the phone is already
    // committed to the heavy work then - so the first retouch is prompt.
    LaunchedEffect(coachState) { if (coachState == LlmCoach.State.READY) inpainter.warmUp() }

    // The chip follows the models on its own clock too, so a wait in a
    // mode with no analysis frames (a room, the review) is never silent.
    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            val m = modelActivity()
            if (m != overlayState.model) overlayState = overlayState.copy(model = m)
        }
    }

    /** A modest copy of what the preview shows, for the coach's eyes. */
    fun previewSnapshot(): Bitmap? = runCatching { previewView.bitmap }.getOrNull()


    LaunchedEffect(debugEnhanceUri, coachState) {
        if (debugEnhanceUri == null) return@LaunchedEffect
        // Wait for the coach to load (or be absent) so the hook tests the same path a capture takes.
        if (coachState == LlmCoach.State.MISSING && coach.modelFile() != null) { ensureCoach(); return@LaunchedEffect }
        if (coachState == LlmCoach.State.LOADING) return@LaunchedEffect
        val u = Uri.parse(debugEnhanceUri)
        enhancer.analyse(u, ContextCompat.getMainExecutor(context), finishAdvisor(), overlayState.retouch, onSmall = { openReviewEarly(u, it) }) { applyAnalysis(u, it) }
    }
    var captureInFlight by remember { mutableStateOf(false) }

    // The haptic lock game. :guidance decides the rhythm; the driver only
    // knows the motor.
    val lockHaptics = remember { LockHaptics() }
    val haptics = remember { HapticDriver(context) }

    // The chosen shot, coached: while a style is chosen Gemma looks through
    // the camera on the rationed clock - the first frame at once, then only
    // a changed frame and no sooner than fifteen seconds, or once in
    // forty-five - and says the one change that gets the shot.
    val shotStyleNow = overlayState.shotStyle
    LaunchedEffect(shotStyleNow) {
        overlayState = overlayState.copy(shotAdvice = null, shotAdvising = false)
        if (shotStyleNow == null) return@LaunchedEffect
        val ration = LookRation()
        var asking = false
        while (overlayState.shotStyle == shotStyleNow) {
            if (!asking && coachState == LlmCoach.State.READY && !coach.isBusy && overlayState.review == null) {
                val snap = previewSnapshot()
                if (snap != null && ration.look(SystemClock.uptimeMillis(), lumaGrid(snap))) {
                    asking = true
                    overlayState = overlayState.copy(shotAdvising = true)
                    val ok = coach.ask(LlmCoach.Kind.LIVE, snap, LlmCoach.shotPrompt(shotStyleNow.name), ContextCompat.getMainExecutor(context)) { text, done ->
                        if (!done) return@ask
                        asking = false
                        if (overlayState.shotStyle == shotStyleNow) {
                            val line = text.trim().trim('"').trimEnd('.')
                            if (line.isNotBlank() && line != overlayState.shotAdvice) haptics.play(HapticCue.TICK, 0.4f)
                            overlayState = overlayState.copy(shotAdvice = line.ifBlank { overlayState.shotAdvice }, shotAdvising = false)
                            Log.i(TAG, "shot ${shotStyleNow.name} (look ${ration.looks}): $line")
                        }
                    }
                    if (!ok) { asking = false; overlayState = overlayState.copy(shotAdvising = false) }
                }
            }
            delay(2000)
        }
    }

    // The retouch, once the review is up: LaMa does what the plan says,
    // then the chip appears. An empty plan, or no LaMa file, and the review
    // just stops saying "looking".
    LaunchedEffect(cleanPending) {
        val result = cleanPending ?: return@LaunchedEffect
        val source = reviewSource ?: return@LaunchedEffect
        cleanPending = null
        if (result.job.isEmpty || !inpainter.available) {
            Log.i(TAG, "retouch: skipped (holes=${result.job.remove.size} strips=${result.job.extend.size} lama=${inpainter.available})")
            overlayState.review?.let { r -> overlayState = overlayState.copy(review = r.copy(cleaning = false)) }
            return@LaunchedEffect
        }
        enhancer.retouch(result, inpainter, ContextCompat.getMainExecutor(context)) { clean ->
            if (reviewSource != source) return@retouch
            reviewClean = clean
            if (clean != null) haptics.click()
            overlayState.review?.let { r ->
                overlayState = overlayState.copy(
                    review = r.copy(
                        cleaning = false,
                        cleanBefore = clean?.before?.asImageBitmap(),
                        cleanAfter = clean?.after?.asImageBitmap(),
                        cleanNote = clean?.let { c -> c.why.ifBlank { Finishing.describe(c.job).joinToString(", ").lowercase() } },
                        useClean = clean != null,
                    ),
                )
            }
        }
    }


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
    var fitMode by remember { mutableStateOf(false) }
    // WATCH: the session, and what the panel shows of it.
    var watchMode by remember { mutableStateOf(false) }
    val watchSession = remember { arrayOfNulls<WatchSession>(1) }
    var watchLooking by remember { mutableStateOf(false) }
    var watchListening by remember { mutableStateOf(false) }
    val watchStarted = remember { arrayOf("") }
    val watchVideo = remember { arrayOfNulls<String>(1) }
    var signsMode by remember { mutableStateOf(false) }
    val keyboard = remember { MacKeyboard(context) }
    val reader = remember { ScreenReader() }
    val speech = remember { SpeechInput(context) }
    var keyboardState by remember { mutableStateOf(MacKeyboard.State.NO_BLUETOOTH) }
    var gPhase by remember { mutableStateOf("READY") }
    var gHeard by remember { mutableStateOf("") }
    var gPlan by remember { mutableStateOf<List<PlanStep>>(emptyList()) }
    var gStep by remember { mutableIntStateOf(-1) }
    var gAttempt by remember { mutableIntStateOf(0) }
    var gWaits by remember { mutableIntStateOf(0) }
    // The request asked for a job to be repeated until it is done.
    var gRepeat by remember { mutableStateOf(false) }
    var gNote by remember { mutableStateOf<String?>(null) }
    var gDraft by remember { mutableStateOf("") }
    var gCountdown by remember { mutableIntStateOf(0) }
    var gArm by remember { mutableIntStateOf(0) }
    // Steve's eyes: the camera's reading of the Mac, what was typed last,
    // whether it landed, and what the model has said about the screen.
    var gScreen by remember { mutableStateOf("") }
    var gTyped by remember { mutableStateOf<String?>(null) }
    var gInputSeen by remember { mutableStateOf<Boolean?>(null) }
    var gLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var gWatching by remember { mutableStateOf(false) }
    val gNarratedAt = remember { longArrayOf(0L) }
    DisposableEffect(keyboard) { onDispose { keyboard.stop(); reader.close(); speech.close() } }

    // Steve's room is landscape: the Mac's screen is wide, and so is the
    // window onto it. The manifest keeps the activity alive across the
    // turn, so nothing here is lost.
    LaunchedEffect(typeMode) {
        val activity = generateSequence(context as android.content.Context) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity
        activity?.requestedOrientation = if (typeMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

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
                countdown = gCountdown,
                zoom = overlayState.zoomRatio,
                screen = gScreen,
                log = gLog,
                inputSeen = gInputSeen,
                watching = gWatching,
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
        // A clean plan runs by itself after a short count - Cancel stops it.
        // A clean plan runs by itself after a short count; so does a proposed
        // next round of a job the user asked to repeat. Cancel stops either.
        gCountdown = if ((phase == "PLANNED" || (phase == "PROPOSED" && gRepeat)) && refusals.all { it == null }) GENIUS_AUTORUN_S else 0
        gArm += 1
        refreshGenius()
    }

    /** One line in the room's log, stamped. */
    fun geniusLog(line: String) {
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
        gLog = (gLog + "$clock  $line").takeLast(WATCH_LOG_MAX)
    }

    /**
     * After a run, the camera is in the loop: the Mac gets a moment
     * ([delayMs]), the camera reads its screen, and Gemma says whether the
     * request is done, still being worked on (WAIT - look again later), or
     * needs a next step, which is proposed, never performed unasked.
     */
    fun geniusCheck(delayMs: Long = GENIUS_SETTLE_MS) {
        if (gAttempt >= (if (gRepeat) GENIUS_MAX_ATTEMPTS_REPEAT else GENIUS_MAX_ATTEMPTS)) { geniusDone(if (gRepeat) "went round ${gAttempt} times" else "did what was asked"); return }
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
                val asked = coach.askText(LlmCoach.Kind.CHECK, LlmCoach.checkPrompt(gHeard, screen, gAttempt, gRepeat), ContextCompat.getMainExecutor(context)) { text, done ->
                    if (!done || gPhase != "CHECKING") return@askText
                    if (GeniusPlan.isDone(text)) { geniusLog("Checked the screen: done"); geniusDone(null); return@askText }
                    if (GeniusPlan.isWait(text)) {
                        if (gWaits < GENIUS_MAX_WAITS) {
                            gWaits += 1
                            gNote = "The Mac is still working - looking again in ${GENIUS_RECHECK_MS / 1000} s"
                            geniusLog("Checked the screen: still working (${gWaits})")
                            geniusCheck(GENIUS_RECHECK_MS)
                        } else geniusDone("the Mac was still working; not verified")
                        return@askText
                    }
                    val next = GeniusPlan.parse(text).filter { !it.line.uppercase().startsWith("DONE") }
                    if (next.isEmpty()) { geniusLog("Checked the screen: done"); geniusDone(null) } else { geniusLog("Checked the screen: ${next.size} more step(s) proposed"); gAttempt += 1; geniusPropose(next, "PROPOSED") }
                }
                if (!asked) geniusDone("coach busy; not verified")
            }
            if (!started) geniusDone("screen reader busy; not verified")
        }, GENIUS_SETTLE_MS)
    }

    /**
     * One line from the model about what the Mac is showing - after a run
     * (with the typed text to check), on a tap, or when the screen changed
     * and it has been a while. Never in a loop of its own: the trigger is
     * always an event, and the reading loop below is ML Kit, not the model.
     */
    fun geniusNarrate(reason: String, typed: String?) {
        if (gWatching || coachState != LlmCoach.State.READY || coach.isBusy) return
        val screen = gScreen
        if (screen.isBlank()) { if (reason == "asked") { gNote = "Point the camera at the Mac screen"; refreshGenius() }; return }
        gWatching = true
        refreshGenius()
        val asked = coach.askText(LlmCoach.Kind.WATCH, LlmCoach.watchPrompt(gHeard, screen, typed), ContextCompat.getMainExecutor(context)) { text, done ->
            if (!done) return@askText
            gWatching = false
            val line = text.trim().trim('"')
            if (line.isNotBlank()) {
                val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())
                gLog = (gLog + "$clock  $line").takeLast(WATCH_LOG_MAX)
                gNarratedAt[0] = SystemClock.uptimeMillis()
                Log.i(TAG, "steve watch ($reason): $line")
            }
            refreshGenius()
        }
        if (!asked) { gWatching = false; refreshGenius() }
    }

    // The reading loop: while Steve's room is open, the camera reads the
    // Mac screen every couple of seconds. Each reading updates the panel
    // and the input check; a materially changed screen may also earn a
    // line from the model, rate limited.
    LaunchedEffect(typeMode) {
        if (!typeMode) return@LaunchedEffect
        while (typeMode) {
            delay(WATCH_PERIOD_MS)
            if (!typeMode) break
            val snap = previewSnapshot()
            if (snap != null && !reader.busy) {
                reader.read(snap, ContextCompat.getMainExecutor(context)) { text ->
                    if (!typeMode) return@read
                    val previous = gScreen
                    gScreen = text
                    gInputSeen = MacWatch.inputSeen(gTyped, text)
                    val quiet = gPhase !in setOf("LISTENING", "THINKING", "WRITING", "RUNNING", "CHECKING")
                    val due = SystemClock.uptimeMillis() - gNarratedAt[0] > WATCH_NARRATE_MIN_MS
                    if (quiet && due && text.isNotBlank() && MacWatch.changed(previous, text)) geniusNarrate("changed", null)
                    refreshGenius()
                }
            }
        }
    }

    /** The tap. Performs the plan on the table, if CommandSafety lets it. */
    fun geniusRun() {
        val steps = gPlan
        gCountdown = 0
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
            onDone = { ok ->
                if (!ok) { geniusFail("the keyboard link dropped or Stop was pressed"); return@perform }
                gTyped = MacWatch.typed(steps)
                gInputSeen = null
                // The camera is in the loop: the Mac gets a moment (longer
                // after a CLAUDE step, which starts slowly), then the screen
                // is read and Gemma says done, wait, or what comes next.
                val claude = steps.any { it.line.uppercase().startsWith("CLAUDE") }
                geniusCheck(if (claude) GENIUS_CLAUDE_SETTLE_MS else GENIUS_SETTLE_MS)
            },
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
            is Route.Open, is Route.Website, is Route.Search, is Route.Type, is Route.Key -> { geniusPropose(GeniusRouter.steps(route), "PLANNED"); true }
            // A project goes to Claude Code as said, inside the router's fixed
            // brief: the agent structures the work itself, and no model here
            // stands between the words and the card.
            is Route.Project -> { geniusPropose(GeniusRouter.steps(route), "PLANNED"); true }
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
            is Route.WhatsApp -> if (route.contact.isBlank()) {
                geniusFail("say who to message - a name or a number"); true
            } else if (route.message.isNotBlank()) {
                geniusPropose(GeniusRouter.steps(route), "PLANNED"); true
            } else coach.askText(LlmCoach.Kind.COMMAND, LlmCoach.messagePrompt(heard), main) { text, done ->
                if (!done || gPhase != "THINKING") return@askText
                val msg = text.trim().trim('"')
                if (msg.isBlank()) geniusFail("no message came back") else geniusPropose(GeniusRouter.steps(route, msg), "PLANNED")
            }
            is Route.Plan -> coach.askText(LlmCoach.Kind.PLAN, LlmCoach.planPrompt(heard), main) { text, done ->
                if (!done || gPhase != "THINKING") return@askText
                val steps = GeniusPlan.parse(text).filter { !it.line.uppercase().startsWith("DONE") }
                // A plan that only types or waits is the model echoing the
                // request into whatever has focus. That is not a plan.
                val real = steps.any { st -> st.line.uppercase().let { it.startsWith("OPEN ") || it.startsWith("TERMINAL ") || it.startsWith("CLAUDE ") } }
                if (steps.isEmpty() || !real) geniusFail("I don't know how to do that on the Mac yet") else geniusPropose(steps, "PLANNED")
            }
        }
        if (!asked) geniusFail("Steve is busy")
    }

    fun geniusPlan(heard: String) {
        gHeard = heard
        gPlan = emptyList()
        gStep = -1
        gAttempt = 1
        gWaits = 0
        gRepeat = GeniusRouter.isRepeating(heard)
        gNote = null
        if (coachState != LlmCoach.State.READY) { geniusFail("the coach model is not on this phone"); return }
        gPhase = "THINKING"
        refreshGenius()
        gDraft = ""
        val main = ContextCompat.getMainExecutor(context)
        // Gemma reads every request first - fixing what speech misheard,
        // writing the brief when it is a project - as one line, KIND | ARG.
        // When that line cannot be read, the words route it instead.
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

    LaunchedEffect(gArm) {
        if (gCountdown <= 0) return@LaunchedEffect
        while (gCountdown > 0 && (gPhase == "PLANNED" || gPhase == "PROPOSED")) {
            delay(1_000L)
            if (gPhase != "PLANNED" && gPhase != "PROPOSED") break
            gCountdown -= 1
            refreshGenius()
            if (gCountdown == 0) { Log.i(TAG, "genius: countdown reached zero - running"); geniusRun() }
        }
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
            ensureCoach()
            if (keyboard.hasPermission()) keyboard.start(ContextCompat.getMainExecutor(context)) { st -> keyboardState = st; refreshGenius() }
        }
    }

    // --- ASK ----------------------------------------------------------------
    // Point the camera at anything: ask, scan, translate, save. One look
    // per tap; the model is the same Gemma, through the camera.
    var askMode by remember { mutableStateOf(false) }
    var scanMode by remember { mutableStateOf(false) }
    var aPhase by remember { mutableStateOf("READY") }
    var aPrompt by remember { mutableStateOf("") }
    var aAnswer by remember { mutableStateOf("") }
    var aKind by remember { mutableStateOf("Ask") }
    var aNote by remember { mutableStateOf<String?>(null) }
    val notebook = remember { Notebook(context) }
    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.language = Locale.US
        }
        engine
    }
    DisposableEffect(tts) { onDispose { runCatching { tts?.shutdown() } } }

    fun coachLine(): String = when (coachState) {
        LlmCoach.State.READY -> "Gemma is looking through the camera"
        LlmCoach.State.LOADING -> "Gemma is loading\u2026"
        LlmCoach.State.MISSING -> if (coach.modelFile() == null) "No model on this phone" else "Gemma is loading\u2026"
        else -> "Gemma could not load"
    }

    fun refreshAsk() {
        overlayState = overlayState.copy(
            askMode = askMode,
            ask = if (askMode) AskState(
                coach = coachLine(),
                ready = coachState == LlmCoach.State.READY,
                speechAvailable = speech.available,
                phase = aPhase,
                prompt = aPrompt,
                answer = aAnswer,
                note = aNote,
            ) else null,
        )
    }

    fun askFail(why: String) { aPhase = "FAILED"; aNote = why; Log.w(TAG, "ask: $why"); refreshAsk() }

    /** The model looks at the frame with a prompt; the answer streams in and is read aloud at the end. */
    fun askLook(kind: LlmCoach.Kind, label: String, prompt: String, speak: Boolean) {
        if (coachState != LlmCoach.State.READY) { askFail("Gemma is not loaded yet - a moment"); return }
        val snap = previewSnapshot() ?: run { askFail("no frame"); return }
        aKind = label
        aPhase = "LOOKING"
        aAnswer = ""
        aNote = null
        refreshAsk()
        val ok = coach.ask(kind, snap, prompt, ContextCompat.getMainExecutor(context)) { text, done ->
            if (aPhase != "LOOKING") return@ask
            aAnswer = text
            if (done) {
                aPhase = "DONE"
                haptics.play(HapticCue.TICK, 0.5f)
                if (speak && text.isNotBlank()) runCatching { tts?.speak(text.take(600), TextToSpeech.QUEUE_FLUSH, null, "ask") }
                Log.i(TAG, "ask $label: ${text.length} chars: ${text.take(100).replace('\n', ' ')}")
            }
            refreshAsk()
        }
        if (!ok) askFail("Gemma is busy")
    }

    fun askSpeak() {
        if (aPhase == "LISTENING") { speech.stop(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) { audioLauncher.launch(Manifest.permission.RECORD_AUDIO); return }
        aPhase = "LISTENING"; aPrompt = ""; aAnswer = ""; aNote = null
        refreshAsk()
        speech.listen(
            onPartial = { partial -> aPrompt = partial; refreshAsk() },
            onResult = { heard ->
                if (heard.isBlank()) { aPhase = "READY"; aNote = "Didn't catch that"; refreshAsk() }
                else { aPrompt = heard; askLook(LlmCoach.Kind.ASK, "Ask", LlmCoach.askPrompt(heard), speak = true) }
            },
            onDone = { if (aPhase == "LISTENING") { aPhase = "READY"; refreshAsk() } },
        )
    }

    fun askScan() {
        val snap = previewSnapshot() ?: run { askFail("no frame"); return }
        aKind = "Scan"; aPrompt = "Scan"; aAnswer = ""; aNote = null; aPhase = "READING"
        refreshAsk()
        val started = reader.read(snap, ContextCompat.getMainExecutor(context)) { text ->
            if (aPhase != "READING") return@read
            if (text.isBlank()) { askFail("no text in the frame"); return@read }
            aAnswer = text
            refreshAsk()
            // Gemma tidies the OCR when it is there; the raw read stands otherwise.
            if (coachState == LlmCoach.State.READY) {
                val ok = coach.askText(LlmCoach.Kind.TIDY, LlmCoach.tidyPrompt(text), ContextCompat.getMainExecutor(context)) { clean, done ->
                    if (aPhase != "READING") return@askText
                    if (done) {
                        if (clean.isNotBlank()) aAnswer = clean
                        aPhase = "DONE"
                        aNote = "${aAnswer.length} characters"
                        haptics.play(HapticCue.TICK, 0.5f)
                        Log.i(TAG, "ask Scan: ${text.length} read, ${aAnswer.length} clean")
                    }
                    refreshAsk()
                }
                if (!ok) { aPhase = "DONE"; aNote = "${text.length} characters (as read)"; refreshAsk() }
            } else {
                aPhase = "DONE"; aNote = "${text.length} characters (as read)"; refreshAsk()
            }
        }
        if (!started) askFail("reader busy")
    }

    fun askTranslate() {
        if (coachState != LlmCoach.State.READY) { askFail("Gemma is not loaded yet - a moment"); return }
        val snap = previewSnapshot() ?: run { askFail("no frame"); return }
        aKind = "Translate"; aPrompt = "Translate"; aPhase = "LOOKING"; aAnswer = ""; aNote = null
        refreshAsk()
        val main = ContextCompat.getMainExecutor(context)
        val ok = coach.ask(LlmCoach.Kind.TRANSLATE, snap, LlmCoach.TRANSLATE_PROMPT, main) { text, done ->
            if (aPhase != "LOOKING") return@ask
            aAnswer = text
            if (!done) { refreshAsk(); return@ask }
            // Echoed the Tamil/Hindi back? Ask once more, bluntly, text-only.
            if (LlmCoach.looksUntranslated(text)) {
                aNote = "translating\u2026"
                refreshAsk()
                val again = coach.askText(LlmCoach.Kind.WRITE, LlmCoach.retranslatePrompt(text), main) { english, d2 ->
                    if (aPhase != "LOOKING") return@askText
                    if (english.isNotBlank()) aAnswer = english
                    if (d2) { aPhase = "DONE"; aNote = if (LlmCoach.looksUntranslated(aAnswer)) "could not translate this" else null; haptics.play(HapticCue.TICK, 0.5f) }
                    refreshAsk()
                }
                if (!again) { aPhase = "DONE"; refreshAsk() }
                return@ask
            }
            aPhase = "DONE"
            haptics.play(HapticCue.TICK, 0.5f)
            Log.i(TAG, "ask Translate: ${text.length} chars: ${text.take(100).replace('\n', ' ')}")
            refreshAsk()
        }
        if (!ok) askFail("Gemma is busy")
    }

    // An answer belongs to the frame it was asked about: when the phone
    // moves on, the answer goes, so nothing looks stuck.
    LaunchedEffect(askMode) {
        if (!askMode) return@LaunchedEffect
        var ref: Float? = null
        while (true) {
            delay(500L)
            val s = latestAttitude[0] ?: continue
            val yaw = s.attitude.rollDeg + s.attitude.pitchDeg
            val r = ref
            if (r != null && aPhase in setOf("DONE", "SAVED") && kotlin.math.abs(yaw - r) > ASK_MOVE_DEG) {
                aAnswer = ""; aPrompt = ""; aPhase = "READY"; aNote = null
                refreshAsk()
            }
            if (aPhase in setOf("DONE", "SAVED")) { if (r == null) ref = yaw } else ref = null
        }
    }

    fun askCopy() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xThink", aAnswer))
        aNote = "Copied"; haptics.play(HapticCue.TICK, 0.4f); refreshAsk()
    }

    fun askSave() {
        val ok = notebook.append(aKind, aPrompt, aAnswer)
        aPhase = if (ok) "SAVED" else "FAILED"
        aNote = if (ok) "Saved to Documents/xThink/xthink-notes.md" else "could not save"
        haptics.play(if (ok) HapticCue.LOCK else HapticCue.UNLOCK, 0.6f)
        refreshAsk()
    }

    fun askStop() {
        speech.stop()
        runCatching { tts?.stop() }
        aPhase = "READY"; aNote = "stopped"; refreshAsk()
    }

    // Dev hooks: --es ask "<question>", --es scan 1, --es translate 1.
    LaunchedEffect(debugAsk, coachState, askMode) {
        if (debugAsk == null || !askMode) return@LaunchedEffect
        if (coachState != LlmCoach.State.READY) return@LaunchedEffect
        when {
            debugAsk == "scan" -> if (aPhase == "READY" && aPrompt.isEmpty()) askScan()
            debugAsk == "translate" -> if (aPhase == "READY" && aPrompt.isEmpty()) askTranslate()
            aPhase == "READY" && aPrompt.isEmpty() -> { aPrompt = debugAsk; askLook(LlmCoach.Kind.ASK, "Ask", LlmCoach.askPrompt(debugAsk), speak = true) }
        }
    }
    LaunchedEffect(debugAsk) {
        if (debugAsk != null && !askMode) { askMode = true; refreshAsk(); ensureCoach() }
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
                            enhancer.analyse(uri, ContextCompat.getMainExecutor(context), finishAdvisor(), overlayState.retouch, onSmall = { openReviewEarly(uri, it) }) { applyAnalysis(uri, it) }
                        } else {
                            // Scenes, objects, creative: no crop to a person - the shot stands, with the look.
                            bakeLook(uri)
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

    // --- FIT ----------------------------------------------------------------
    // Squats counted from ML Kit Pose on the analysis frames, by the knee
    // angle (RepCounter, pure Kotlin); hand signs from
    // MediaPipe's gesture model, thumbs up as a hands-free shutter.
    var fitPick by remember { mutableStateOf("SQUAT") }
    var repCounter by remember { mutableStateOf(RepCounter(Exercise.SQUAT)) }
    var fitGesture by remember { mutableStateOf<String?>(null) }
    var fitBody by remember { mutableStateOf(false) }
    val lastGestureShotMs = remember { longArrayOf(0L) }

    fun refreshFit() {
        overlayState = overlayState.copy(
            fitMode = fitMode,
            fit = if (fitMode) FitState(
                mode = fitPick,
                count = repCounter.count,
                phase = repCounter.phase.name,
                angleDeg = repCounter.angleDeg.takeIf { !it.isNaN() },
                gesture = fitGesture,
                bodySeen = fitBody,
            ) else null,
        )
    }

    fun applyFitPick() {
        val a = analyzerRef[0] ?: return
        a.fitExercise = if (fitMode) Exercise.SQUAT else null
        a.fitGestures = signsMode
    }

    fun refreshSigns() {
        overlayState = overlayState.copy(signsMode = signsMode, sign = if (signsMode) fitGesture else null)
    }

    fun onFitFrame(angle: Float?, gesture: String?, dtMs: Long) {
        fitBody = angle != null
        if (angle != null && repCounter.update(angle, dtMs)) {
            haptics.play(HapticCue.LOCK, 0.9f)
            runCatching { tts?.speak(repCounter.count.toString(), TextToSpeech.QUEUE_FLUSH, null, "rep") }
            Log.i(TAG, "fit: ${repCounter.exercise} rep ${repCounter.count}")
        }
        if (signsMode) {
            if (gesture != fitGesture) {
                Log.i(TAG, "signs: ${gesture ?: "-"}")
                if (gesture != null) haptics.play(HapticCue.TICK, 0.4f)
            }
            fitGesture = gesture
            refreshSigns()
            return
        }
        refreshFit()
    }

    // ---- WATCH: the camera records, the model looks now and then, the microphone is written down ----

    fun refreshWatch() {
        val s = watchSession[0]
        val now = SystemClock.uptimeMillis()
        overlayState = overlayState.copy(
            watchMode = watchMode,
            watch = if (watchMode && s != null) WatchState(
                recording = activeRecording != null,
                remainingMs = s.remainingMs(now),
                lastSeen = s.lastSeen(),
                lastHeard = s.heard.lastOrNull()?.text,
                seenCount = s.seen.size,
                heardCount = s.heard.size,
                looking = watchLooking,
                listening = watchListening,
            ) else null,
        )
    }

    /** The circumstances, for the file: the light, the focus, the hand, the lens, the phone's heat. */
    fun watchEnvironment(): String {
        val o = overlayState
        val lens = if (lensFacing == CameraSelector.LENS_FACING_BACK) "back" else "front"
        return "Lighting ${o.lighting.value.lowercase()}, focus ${o.focus.value.lowercase()}, stability ${o.stability.value.lowercase()}; $lens camera; phone ${thermalPlan[0].tier.name.lowercase()}."
    }

    /**
     * The microphone, kept open: one phrase at a time, patient through
     * pauses, reopened as each ends - quickly after words, slowly after
     * silence, since every reopening is a chime the system insists on.
     */
    fun watchListen() {
        if (!watchMode || !speech.available) { watchListening = false; return }
        watchListening = true
        var heard = false
        speech.listen(
            onPartial = {},
            onResult = { text -> heard = text.isNotBlank(); watchSession[0]?.noteHeard(SystemClock.uptimeMillis(), text); refreshWatch() },
            onDone = {
                watchListening = false
                if (watchMode) Handler(Looper.getMainLooper()).postDelayed({ watchListen() }, if (heard) WATCH_LISTEN_GAP_MS else WATCH_LISTEN_GAP_QUIET_MS)
            },
            patient = true,
        )
    }

    /**
     * The footage, after the report: the recording is offered to WhatsApp
     * (its contact picker, the video attached) or the share sheet, the
     * moment the app is back in front - so the two go out one after the
     * other, the words first, then the film.
     */
    fun watchShareFootage(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Watch · ${watchStarted[0]}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val takers = context.packageManager.queryIntentActivities(send, 0).map { it.activityInfo.packageName }
        val messenger = WATCH_MESSENGERS.firstOrNull { it in takers }
        val intent = if (messenger != null) Intent(send).setPackage(messenger).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else Intent.createChooser(send, "Send the footage to").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { Log.i(TAG, "watch: footage offered to ${messenger ?: "the share sheet"} ($uri)") }
            .onFailure { Log.w(TAG, "watch: could not offer the footage", it) }
    }

    // Back in front after the report went out: the footage follows.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val uri = watchFootage[0]
                if (uri != null) { watchFootage[0] = null; watchShareFootage(uri) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** The system's listening chimes, off for the watch and back after. */
    fun watchChimes(on: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val direction = if (on) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE
        for (stream in listOf(AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION)) {
            runCatching { audio.adjustStreamVolume(stream, direction, 0) }.onFailure { Log.w(TAG, "watch: chimes stream $stream: ${it.message}") }
        }
    }

    /**
     * The report, sent: copied to the clipboard, then straight into the
     * WhatsApp chat with [WATCH_WHATSAPP_NUMBER] when WhatsApp is on the
     * phone, the report already in the message box - WhatsApp lets no app
     * press Send, so that tap stays - else the system's share sheet, for
     * Notes, mail, anything that takes text. Over the camera; Back
     * returns here.
     */
    fun watchShare(title: String, body: String) {
        runCatching {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, body))
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        val takers = context.packageManager.queryIntentActivities(send, 0).map { it.activityInfo.packageName }
        val messenger = WATCH_MESSENGERS.firstOrNull { it in takers }
        val intent = if (messenger != null) {
            val text = if (body.length > WATCH_WHATSAPP_MAX_CHARS) body.take(WATCH_WHATSAPP_MAX_CHARS) + "\n…" else body
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$WATCH_WHATSAPP_NUMBER?text=" + Uri.encode(text))).setPackage(messenger).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else Intent.createChooser(send, "Send the watch to").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onSuccess { Log.i(TAG, "watch: sent to ${messenger?.let { "$it chat +$WATCH_WHATSAPP_NUMBER" } ?: "the share sheet"} (${body.length} chars)") }
            .onFailure { Log.w(TAG, "watch: could not open ${messenger ?: "the share sheet"}", it) }
    }

    /**
     * The watch is over - the time, the Finish pill, or a mode change.
     * The recording stops, the microphone closes, and the file is
     * written: the model's summary (one ask, when it is there and free)
     * on top of everything seen and heard.
     */
    fun leaveWatch(why: String) {
        if (!watchMode) return
        val now = SystemClock.uptimeMillis()
        val s = watchSession[0]
        watchMode = false
        watchListening = false
        speech.stop()
        watchFootageDue[0] = activeRecording != null
        stopRecording()
        videoMode = false
        overlayState = overlayState.copy(watchMode = false, watch = null, videoMode = false, recording = false)
        haptics.play(HapticCue.UNLOCK)
        watchChimes(true)
        Log.i(TAG, "watch: ended ($why)")
        if (s == null) return
        s.end(now, why)
        val environment = watchEnvironment()
        val video = watchVideo[0]
        val name = "xthink-watch-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".md"
        fun write(summary: String?) {
            val body = s.report(watchStarted[0], environment, video, summary)
            val uri = notebook.write(name, body)
            Log.i(TAG, "watch: file ${if (uri != null) "written -> $uri" else "NOT written"} (${s.seen.size} seen, ${s.heard.size} heard, ${s.looks} looks)")
            watchShare("Watch · ${watchStarted[0]}", body)
        }
        if (coachState == LlmCoach.State.READY && !coach.isBusy && (s.seen.isNotEmpty() || s.heard.isNotEmpty())) {
            val asked = coach.askText(LlmCoach.Kind.WATCH_REPORT, LlmCoach.watchReportPrompt(s.facts(environment), s.seenText(), s.heardText()), ContextCompat.getMainExecutor(context)) { text, done ->
                if (done) write(text.trim().ifBlank { null })
            }
            if (!asked) write(null)
        } else {
            write(null)
        }
    }

    fun enterWatch() {
        if (watchMode) return
        if (videoMode) stopRecording()
        if (typeMode) { keyboard.cancelled = true; typeMode = false }
        if (fitMode || signsMode) { fitMode = false; signsMode = false; analyzerRef[0]?.let { it.fitExercise = null; it.fitGestures = false } }
        askMode = false
        scanMode = false
        watchMode = true
        videoMode = true
        val s = WatchSession(SystemClock.uptimeMillis())
        watchSession[0] = s
        watchStarted[0] = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        watchVideo[0] = null
        watchLooking = false
        overlayState = overlayState.copy(
            videoMode = true, recording = false, review = null, showLooks = false, showShots = false,
            typeMode = false, genius = null, askMode = false, ask = null, scanMode = false,
            fitMode = false, fit = null, signsMode = false, sign = null,
        )
        refreshWatch()
        haptics.play(HapticCue.LOCK)
        watchChimes(false)
        Log.i(TAG, "mode -> WATCH")
        ensureCoach()
        watchListen()
    }

    // The watch's clock, every two seconds: start the recording once the
    // recorder is bound (no audio track - the microphone is the
    // transcript's), look at the frame when the session says it is time
    // (a changed frame no sooner than fifteen seconds after the last look,
    // a still one once a minute), and end at five minutes.
    LaunchedEffect(watchMode) {
        var lastTry = 0L
        while (watchMode) {
            delay(WATCH_TICK_MS)
            if (!watchMode) break
            val s = watchSession[0] ?: break
            val now = SystemClock.uptimeMillis()
            if (s.over(now)) { leaveWatch("time"); break }
            if (activeRecording == null && now - lastTry >= 3000L) {
                lastTry = now
                startRecording(withAudio = false)
                watchVideo[0] = lastVideoName[0]
            }
            if (!watchLooking && coachState == LlmCoach.State.READY && !coach.isBusy) {
                val snap = previewSnapshot()
                if (snap != null && s.look(now, lumaGrid(snap))) {
                    watchLooking = true
                    val at = now
                    val asked = coach.ask(LlmCoach.Kind.WATCH_FRAME, snap, LlmCoach.watchFramePrompt(s.lastSeen()), ContextCompat.getMainExecutor(context)) { text, done ->
                        if (!done) return@ask
                        watchLooking = false
                        s.noteSeen(at, text)
                        Log.i(TAG, "watch: look ${s.looks} -> '${text.trim().replace('\n', ' ').take(120)}'")
                        refreshWatch()
                    }
                    if (!asked) watchLooking = false
                }
            }
            refreshWatch()
        }
    }

    fun selectMode(mode: CoachMode, leaveVideo: Boolean = true) {
        if (watchMode && leaveVideo) leaveWatch("mode")
        if ((fitMode || signsMode) && leaveVideo) {
            fitMode = false
            signsMode = false
            analyzerRef[0]?.let { it.fitExercise = null; it.fitGestures = false }
            overlayState = overlayState.copy(fitMode = false, fit = null, signsMode = false, sign = null)
            Log.i(TAG, "mode -> photo (from FIT/SIGNS)")
        }
        if (askMode && leaveVideo) {
            askMode = false; scanMode = false
            overlayState = overlayState.copy(askMode = false, ask = null, scanMode = false)
            Log.i(TAG, "mode -> photo (from TRANSLATE/SCAN)")
        }
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
        val analyzer = FaceAnalyzer(context) { result ->
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
                retouch = overlayState.retouch,
                shotAdvice = overlayState.shotAdvice,
                shotAdvising = overlayState.shotAdvising,
                look = overlayState.look,
                showLooks = overlayState.showLooks,
                lookPreview = overlayState.lookPreview,
                showGuide = overlayState.showGuide,
                showTune = overlayState.showTune,
                baseFocalMm = overlayState.baseFocalMm,
                shotStyle = overlayState.shotStyle,
                showShots = overlayState.showShots,
                videoMode = overlayState.videoMode,
                recording = overlayState.recording,
                recordingMs = overlayState.recordingMs,
                typeMode = overlayState.typeMode,
                genius = overlayState.genius,
                askMode = overlayState.askMode,
                ask = overlayState.ask,
                scanMode = overlayState.scanMode,
                fitMode = overlayState.fitMode,
                fit = overlayState.fit,
                signsMode = overlayState.signsMode,
                watchMode = overlayState.watchMode,
                watch = overlayState.watch,
                model = modelActivity(),
                sign = overlayState.sign,
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
        analyzer.onFit = { angle, gesture, dt -> mainHandler.post { if (fitMode || signsMode) onFitFrame(angle, gesture, dt) } }
        if (fitMode || signsMode) applyFitPick()
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
    // Ways in, shared by the tabs and the home page's cards.
    fun enterSteve() {
        if (watchMode) leaveWatch("mode")
                if (!typeMode) {
                    if (videoMode) { stopRecording(); videoMode = false }
                    if (askMode) { askMode = false; overlayState = overlayState.copy(askMode = false, ask = null) }
                    if (fitMode || signsMode) { fitMode = false; signsMode = false; analyzerRef[0]?.let { it.fitExercise = null; it.fitGestures = false }; overlayState = overlayState.copy(fitMode = false, fit = null, signsMode = false, sign = null) }
                    typeMode = true
                    gPhase = "READY"; gHeard = ""; gPlan = emptyList(); gStep = -1; gNote = null
                    gScreen = ""; gTyped = null; gInputSeen = null; gLog = emptyList(); gWatching = false; gNarratedAt[0] = 0L
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
    }

    fun leaveSteve() {
        if (!typeMode) return
        Log.i(TAG, "genius: left by the back button")
        keyboard.cancelled = true
        speech.stop()
        typeMode = false
        overlayState = overlayState.copy(typeMode = false, genius = null)
    }

    fun enterScan() {
        if (watchMode) leaveWatch("mode")
                if (!askMode || !scanMode) {
                    if (videoMode) { stopRecording(); videoMode = false }
                    if (typeMode) { keyboard.cancelled = true; typeMode = false }
                    if (fitMode) { fitMode = false }
                    analyzerRef[0]?.let { it.fitExercise = null; it.fitGestures = false }
                    askMode = true; scanMode = true
                    aPhase = "READY"; aPrompt = ""; aAnswer = ""; aNote = null
                    overlayState = overlayState.copy(videoMode = false, recording = false, review = null, showLooks = false, showShots = false, typeMode = false, genius = null, fitMode = false, fit = null, scanMode = true)
                    refreshAsk()
                    ensureCoach()
                    Log.i(TAG, "mode -> SCAN")
                }
    }

    fun enterTranslate() {
        if (watchMode) leaveWatch("mode")
                if (!askMode || scanMode) {
                    scanMode = false
                    overlayState = overlayState.copy(scanMode = false)
                    if (videoMode) { stopRecording(); videoMode = false }
                    if (typeMode) { keyboard.cancelled = true; typeMode = false }
                    if (fitMode) { fitMode = false }
                    if (signsMode) { signsMode = false; overlayState = overlayState.copy(signsMode = false, sign = null) }
                    analyzerRef[0]?.let { it.fitExercise = null; it.fitGestures = false }
                    askMode = true
                    aPhase = "READY"; aPrompt = ""; aAnswer = ""; aNote = null
                    overlayState = overlayState.copy(videoMode = false, recording = false, review = null, showLooks = false, showShots = false, typeMode = false, genius = null)
                    refreshAsk()
                    ensureCoach()
                    Log.i(TAG, "mode -> TRANSLATE")
                }
    }

    fun enterFit() {
        if (watchMode) leaveWatch("mode")
                if (!fitMode) {
                    if (videoMode) { stopRecording(); videoMode = false }
                    if (typeMode) { keyboard.cancelled = true; typeMode = false }
                    askMode = false
                    fitMode = true
                    repCounter = RepCounter(Exercise.SQUAT)
                    fitGesture = null
                    overlayState = overlayState.copy(videoMode = false, recording = false, review = null, showLooks = false, showShots = false, typeMode = false, genius = null, askMode = false, ask = null)
                    applyFitPick()
                    refreshFit()
                    Log.i(TAG, "mode -> FIT ($fitPick)")
                }
    }

    LaunchedEffect(startIn) {
        when (startIn) {
            "STEVE" -> enterSteve()
            "TRANSLATE" -> enterTranslate()
            "SCAN" -> enterScan()
            "FIT" -> enterFit()
            "WATCH" -> enterWatch()
        }
    }


    /**
     * Focus and meter at a point of the preview (fractions), and tell the
     * coach that this is the thing to frame. The ring answers at once; the
     * lens follows. The camera page's tap, and Steve's window's.
     */
    fun focusAt(x: Float, y: Float) {
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
    }

        GuidanceOverlay(
            state = overlayState,
            onZoomSelected = { requested ->
                val ratio = requested.coerceAtMost(zoomCapFor(overlayState.shotStyle))
                cameraControl?.cameraControl?.setZoomRatio(ratio)
            },
            onTypeMode = { enterSteve() },
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
            onFitMode = { enterFit() },
            onWatchMode = { enterWatch() },
            onWatchFinish = { leaveWatch("finish") },
            onFitPick = { pick ->
                fitPick = pick
                repCounter = RepCounter(Exercise.SQUAT)
                fitGesture = null
                applyFitPick()
                refreshFit()
                Log.i(TAG, "fit -> $pick")
            },
            onFitReset = { repCounter.reset(); haptics.play(HapticCue.TICK, 0.4f); refreshFit() },
            onScanMode = { enterScan() },
            onAskScan = { askScan() },
            onAskMode = { enterTranslate() },
            onAskTranslate = { askTranslate() },
            onAskCopy = { askCopy() },
            onAskSave = { askSave() },
            onAskStop = { askStop() },
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
                    askMode = false
                    if (fitMode || signsMode) { fitMode = false; signsMode = false; analyzerRef[0]?.let { it.fitExercise = null; it.fitGestures = false } }
                    overlayState = overlayState.copy(videoMode = true, review = null, typeMode = false, genius = null, askMode = false, ask = null, fitMode = false, fit = null, signsMode = false, sign = null)
                    Log.i(TAG, "mode -> VIDEO")
                }
            },
            onShutter = {
                if (askMode) {
                    if (scanMode) askScan() else askTranslate()
                } else if (typeMode) {
                    geniusSpeak()
                } else if (watchMode) {
                    // The watch owns the recording: the shutter finishes the watch.
                    leaveWatch("shutter")
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
                    // ENHANCED is whatever switches are on: the retouch, the crop (which brings the painted room with it).
                    val cropping = r.enhanced && r.useCrop && r.after != null
                    val painting = r.enhanced && r.useClean && r.cleanBefore != null
                    val job = if (painting) reviewClean?.job?.takeIf { it.remove.isNotEmpty() || cropping } else null
                    enhancer.saveFinal(src, pendingEnhance, cropping, look, ContextCompat.getMainExecutor(context), job, inpainter) { saved ->
                        if (saved != null) {
                            lastCaptureUri = saved
                            val chosen = when {
                                cropping && painting -> r.cleanAfter ?: r.after
                                cropping -> r.after
                                painting -> r.cleanBefore
                                else -> null
                            }
                            overlayState = overlayState.copy(thumbnail = chosen ?: overlayState.thumbnail)
                        }
                        haptics.click()
                        Log.i(TAG, "review: kept ${if (cropping || painting) "enhanced" else "original"} crop=$cropping painted=${job != null} look=${Looks.ALL[r.look].name} saved=$saved")
                        closeReview()
                    }
                }
            },
            onReviewDiscard = {
                Log.i(TAG, "review: discarded, original kept as shot")
                closeReview()
            },
            onReviewToggleClean = {
                overlayState.review?.let { r -> if (r.cleanBefore != null) overlayState = overlayState.copy(review = r.copy(useClean = !r.useClean)) }
            },
            onReviewToggleCrop = {
                overlayState.review?.let { r -> if (r.after != null) overlayState = overlayState.copy(review = r.copy(useCrop = !r.useCrop)) }
            },
            onTap = { x, y -> focusAt(x, y) },
            onGeniusZoom = { z -> cameraControl?.cameraControl?.setZoomRatio(z); Log.i(TAG, "steve: zoom ${z}x") },
            onGeniusFocus = { x, y -> focusAt(x, y) },
            onToggleShots = {
                overlayState = overlayState.copy(showShots = !overlayState.showShots, showLooks = false)
            },
            onPickShot = { style ->
                shotTypes.setStyle(style)
                engine.setProfile(profiles.getValue(shotTypes.current))
                overlayState = overlayState.copy(shotStyle = style)
                val cap = zoomCapFor(style)
                if (overlayState.zoomRatio > cap) cameraControl?.cameraControl?.setZoomRatio(cap)
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
            onToggleRetouch = {
                val on = !overlayState.retouch
                overlayState = overlayState.copy(retouch = on)
                haptics.play(HapticCue.TICK, if (on) 0.6f else 0.3f)
                Log.i(TAG, "retouch ${if (on) "on" else "off"}")
            },
            onToggleEasyShot = {
                val on = !overlayState.easyShot
                autoCapture.relaxed = on
                overlayState = overlayState.copy(easyShot = on)
                haptics.play(HapticCue.TICK, if (on) 0.6f else 0.3f)
                Log.i(TAG, "easy shot ${if (on) "on" else "off"}")
            },
            onHome = { if (typeMode) leaveSteve() else onHome() },
            onOpenRoom = onOpenRoom,
            onGeniusWatch = { geniusNarrate("asked", gTyped) },
            onToggleTune = { overlayState = overlayState.copy(showTune = !overlayState.showTune, showShots = false, showLooks = false) },
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
    retouch: Boolean,
    shotAdvice: String?,
    shotAdvising: Boolean,
    look: Int,
    showLooks: Boolean,
    lookPreview: ImageBitmap?,
    showGuide: Boolean,
    showTune: Boolean,
    baseFocalMm: Float,
    shotStyle: ShotType?,
    showShots: Boolean,
    videoMode: Boolean,
    recording: Boolean,
    recordingMs: Long,
    typeMode: Boolean,
    genius: GeniusState?,
    askMode: Boolean,
    ask: AskState?,
    scanMode: Boolean,
    fitMode: Boolean,
    fit: FitState?,
    signsMode: Boolean,
    watchMode: Boolean,
    watch: WatchState?,
    model: String?,
    sign: String?,
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
        retouch = retouch,
        shotAdvice = shotAdvice,
        shotAdvising = shotAdvising,
        look = look,
        showLooks = showLooks,
        lookPreview = lookPreview,
        showGuide = showGuide,
        showTune = showTune,
        baseFocalMm = baseFocalMm,
        shotStyle = shotStyle,
        showShots = showShots,
        videoMode = videoMode,
        recording = recording,
        recordingMs = recordingMs,
        typeMode = typeMode,
        genius = genius,
        askMode = askMode,
        ask = ask,
        scanMode = scanMode,
        fitMode = fitMode,
        fit = fit,
        signsMode = signsMode,
        watchMode = watchMode,
        watch = watch,
        model = model,
        sign = sign,
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

/** A frame as a small luma grid, for telling whether the scene has changed enough to be worth a look. */
private fun lumaGrid(src: Bitmap): IntArray {
    val small = Bitmap.createScaledBitmap(src, WATCH_GRID_W, WATCH_GRID_H, true)
    val px = IntArray(WATCH_GRID_W * WATCH_GRID_H)
    small.getPixels(px, 0, WATCH_GRID_W, 0, 0, WATCH_GRID_W, WATCH_GRID_H)
    return IntArray(px.size) { i ->
        val c = px[i]
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
    }
}
