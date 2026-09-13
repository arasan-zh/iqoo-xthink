package `in`.arasan.xthink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import `in`.arasan.xthink.guidance.CoachMode
import `in`.arasan.xthink.guidance.ShotType
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

/**
 * The whole guidance overlay: reticle layer plus every chip, as one composable
 * that takes an [OverlayState] and nothing about the camera.
 *
 * CLAUDE.md requires this separation explicitly - "a SEPARATE composable
 * layer, restyleable without touching camera code" - so CameraScreen builds an
 * OverlayState and this file owns everything about how it looks. Redesigning
 * the look, per CLAUDE.md's stock-camera / reference-mockup requirement, means
 * editing this file and OverlayTokens, never the camera plumbing.
 *
 * @param onZoomSelected forwarded from CameraScreen, which is the only thing
 *        here with an actual camera to call setZoomRatio on.
 */
@Composable
fun GuidanceOverlay(
    state: OverlayState,
    onZoomSelected: (Float) -> Unit,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
    onModeSelected: (CoachMode) -> Unit,
    onFlip: () -> Unit,
    onHome: () -> Unit,
    onOpenRoom: (String) -> Unit = {},
    onToggleTune: () -> Unit = {},
    onGeniusWatch: () -> Unit = {},
    onTap: (x: Float, y: Float) -> Unit,
    onReviewChooseEnhanced: (Boolean) -> Unit,
    onReviewChooseLook: (Int) -> Unit,
    onReviewSave: () -> Unit,
    onReviewDiscard: () -> Unit,
    onReviewToggleClean: () -> Unit = {},
    onReviewToggleCrop: () -> Unit = {},
    onToggleEasyShot: () -> Unit,
    onToggleRetouch: () -> Unit = {},
    onToggleLooks: () -> Unit,
    onPickLook: (Int) -> Unit,
    onToggleGuide: () -> Unit,
    onToggleShots: () -> Unit,
    onPickShot: (ShotType?) -> Unit,
    onVideoMode: () -> Unit,
    onTypeMode: () -> Unit,
    onPairMac: () -> Unit,
    onListen: () -> Unit,
    onGeniusRun: () -> Unit,
    onGeniusStop: () -> Unit,
    onFitMode: () -> Unit,
    onFitPick: (String) -> Unit,
    onFitReset: () -> Unit,
    onWatchMode: () -> Unit = {},
    onWatchFinish: () -> Unit = {},
    onAskMode: () -> Unit,
    onScanMode: () -> Unit,
    onAskScan: () -> Unit,
    onAskTranslate: () -> Unit,
    onAskCopy: () -> Unit,
    onAskSave: () -> Unit,
    onAskStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The capture flash: a brief white wash whenever a photo is taken, auto
    // or manual, so the photographer knows without looking at the gallery.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.captureNonce) {
        if (state.captureNonce > 0) {
            flash.snapTo(0.9f)
            flash.animateTo(0f, tween(110))
        }
    }

    // The preview fills the whole screen, so the subject-tracking frame maps
    // its normalised coordinates against the FULL canvas to stay pixel-true to
    // the live feed. The fixed guides - corner brackets, centre cross, horizon,
    // pitch ladder - are guides, not tracking, and need to sit in the open
    // viewfinder area instead of colliding with the chrome above and below
    // them. This is where that area's vertical centre is measured and handed
    // to Reticle, in fractions of screen height so it survives rotation and
    // any future change to chip sizes without touching this file's layout math.
    var showQr by remember { mutableStateOf(false) }
    var overlayTop by remember { mutableFloatStateOf(0f) }
    var overlayHeight by remember { mutableFloatStateOf(1f) }
    var safeCenterY by remember { mutableFloatStateOf(0f) }
    // The pinch handler is keyed on maxZoomRatio only, so it reads the live
    // zoom through this rather than capturing one frame's state.
    val liveZoom by rememberUpdatedState(state.zoomRatio)
    val currentZoom = { liveZoom }

    val safeCenterYFraction = if (overlayHeight > 0f) {
        ((safeCenterY - overlayTop) / overlayHeight).coerceIn(0.28f, 0.72f)
    } else {
        0.42f
    }

    val steve = state.genius
    if (state.typeMode && steve != null) {
        SteveSurface(
            state = steve,
            onPair = onPairMac,
            onSpeak = onListen,
            onRun = onGeniusRun,
            onStop = onGeniusStop,
            onHome = onHome,
            onWatch = onGeniusWatch,
            model = state.model,
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayTop = it.positionInRoot().y
                overlayHeight = it.size.height.toFloat()
            }
            // Pinch to zoom anywhere on the preview. Buttons consume their own
            // taps first; this only sees the multi-touch gesture. The gesture
            // accumulates against its own running ratio, not the reported
            // zoom: the camera's zoom state arrives a few frames late, and
            // multiplying against a stale value made every pinch stutter.
            // Tap to focus. Chips and buttons sit above and consume their
            // own taps, so this only sees taps on the picture.
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (size.width > 0 && size.height > 0) {
                        onTap(pos.x / size.width, pos.y / size.height)
                    }
                }
            }
            .pointerInput(state.maxZoomRatio) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pinchZoom = currentZoom()
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        if (pressed >= 2 && zoomChange != 1f) {
                            pinchZoom = (pinchZoom * zoomChange).coerceIn(1f, state.maxZoomRatio)
                            onZoomSelected(pinchZoom)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // The alignment layer sits directly on the preview, under every chip.
        // Absent in CREATIVE: no assistance means nothing drawn on the image.
        if (state.assisted) {
            Reticle(
                state = state,
                safeCenterYFraction = safeCenterYFraction,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = XT.Gutter),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // --- top: the mark (install QR) on the left; Chat, Voice, Steve
            //     and the tune switch on the right. The tune chips drop below
            //     only when asked for, so the viewfinder stays clean. ---
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TopIcon("mark", onClick = { showQr = true })
                        if (state.recording) RecordingChip(ms = state.recordingMs)
                        state.model?.let { ModelChip(it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TopIcon("chat", onClick = { onOpenRoom("CHAT") })
                        TopIcon("voice", onClick = { onOpenRoom("VOICE") })
                        if (!state.mirrored) TopIcon("mac", onClick = onTypeMode, tint = Color(0xFF1A1508), accent = true)
                        if (!state.videoMode) TopIcon("tune", onClick = onToggleTune, tint = if (state.showTune) XT.Gold else XT.OnChip)
                    }
                }
                if (state.showTune && !state.videoMode) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    ) {
                        if (state.mode == CoachMode.PORTRAIT && !state.typeMode && !state.askMode && !state.fitMode && !state.watchMode) {
                            ShotChip(style = state.shotStyle, open = state.showShots, onClick = onToggleShots)
                        }
                        if (state.assisted) EasyShotToggle(on = state.easyShot, onToggle = onToggleEasyShot)
                        if (state.assisted && state.guideMuted) GuideMutedChip()
                        else if (state.assisted) GuideToggle(on = state.showGuide, onToggle = onToggleGuide)
                        LookChip(name = Looks.ALL[state.look].name, open = state.showLooks, onClick = onToggleLooks)
                        if (state.mode == CoachMode.PORTRAIT && !state.typeMode && !state.askMode && !state.fitMode && !state.watchMode) {
                            RetouchToggle(on = state.retouch, onToggle = onToggleRetouch)
                        }
                    }
                }
            }

            // --- middle: the mode rail on the left. Its own vertical centre,
            //     measured above, is where the fixed guides get drawn. ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onGloballyPositioned {
                        val top = it.positionInRoot().y
                        safeCenterY = top + it.size.height / 2f
                    },
            ) {
                if (state.assisted) {
                    StatusRail(state = state, modifier = Modifier.align(Alignment.CenterStart))
                }
            }

            // --- bottom stack: guidance card, status strip, mode tab, zoom,
            //     shutter row - matching the overlay reference's order ---
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
            ) {
                // One card's worth of space: the looks when they are open,
                // else the words of guidance when they are switched on.
                val genius = state.genius
                val ask = state.ask
                val fit = state.fit
                val watch = state.watch
                if (state.watchMode && watch != null) {
                    WatchPanel(state = watch, onFinish = onWatchFinish)
                } else if (state.fitMode && fit != null) {
                    FitPanel(state = fit, onMode = onFitPick, onReset = onFitReset)
                } else if (state.askMode && ask != null) {
                    AskPanel(
                        state = ask,
                        onTranslate = if (state.scanMode) onAskScan else onAskTranslate,
                        scan = state.scanMode,
                        onCopy = onAskCopy,
                        onSave = onAskSave,
                        onStop = onAskStop,
                    )
                } else if (state.typeMode && genius != null) {
                    GeniusPanel(state = genius, onPair = onPairMac, onSpeak = onListen, onRun = onGeniusRun, onStop = onGeniusStop)
                } else if (state.showShots) {
                    ShotsRow(style = state.shotStyle, onPick = onPickShot)
                } else if (state.showLooks) {
                    LooksRow(look = state.look, preview = state.lookPreview, onPick = onPickLook)
                } else if (state.assisted && state.showGuide && !state.guideMuted) {
                    GuidanceCard(state = state)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ZoomBar(
                        zoomRatio = state.zoomRatio,
                        maxZoomRatio = minOf(state.maxZoomRatio, zoomCapFor(state.shotStyle)),
                        baseFocalMm = state.baseFocalMm,
                        onZoomSelected = onZoomSelected,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    ModeTabs(
                        mode = state.mode,
                        onModeSelected = onModeSelected,
                        portraitOnly = state.mirrored,
                        watch = state.watchMode,
                        onWatch = onWatchMode,
                        photo = !state.videoMode && !state.typeMode && !state.askMode && !state.fitMode && !state.watchMode,
                    )
                    BottomBar(
                        locked = state.isLocked,
                        thumbnail = state.thumbnail,
                        onShutter = onShutter,
                        onGallery = onGallery,
                        onFlip = onFlip,
                        video = state.videoMode,
                        recording = state.recording,
                        onPhoto = { if (state.videoMode) onModeSelected(state.mode) },
                        onVideo = { if (!state.videoMode) onVideoMode() },
                    )
                }
            }
        }

        FocusRing(point = state.focusPoint, nonce = state.focusNonce)

        if (flash.value > 0.005f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
        }

        if (showQr) {
            QrSheet(url = Links.INSTALL_APK, onDismiss = { showQr = false })
        }

        // The review sits above everything: the next shot waits for it.
        val review = state.review
        if (review != null) {
            ReviewSheet(
                review = review,
                onChooseEnhanced = onReviewChooseEnhanced,
                onChooseLook = onReviewChooseLook,
                onSave = onReviewSave,
                onDiscard = onReviewDiscard,
                onToggleClean = onReviewToggleClean,
                onToggleCrop = onReviewToggleCrop,
            )
        }
    }
}

/**
 * A ring where the photographer tapped: settles from large to small and
 * fades, the way a stock camera's does, so the tap feels answered even
 * before the lens has moved.
 */
@Composable
private fun FocusRing(point: Pair<Float, Float>?, nonce: Int) {
    if (point == null) return
    val progress = remember(nonce) { Animatable(0f) }
    LaunchedEffect(nonce) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val t = progress.value
    Canvas(modifier = Modifier.fillMaxSize()) {
        val c = Offset(point.first * size.width, point.second * size.height)
        val radius = (34.dp.toPx()) * (1.35f - 0.35f * minOf(1f, t * 2.5f))
        val alpha = if (t < 0.7f) 1f else 1f - (t - 0.7f) / 0.3f
        drawCircle(XT.Amber.copy(alpha = alpha), radius = radius, center = c, style = Stroke(1.5f.dp.toPx()))
        val tick = 6.dp.toPx()
        listOf(Offset(0f, -radius), Offset(0f, radius), Offset(-radius, 0f), Offset(radius, 0f)).forEach { d ->
            val u = Offset(d.x / radius, d.y / radius)
            drawLine(
                XT.Amber.copy(alpha = alpha),
                c + d,
                c + d - Offset(u.x * tick, u.y * tick),
                1.5f.dp.toPx(),
                StrokeCap.Round,
            )
        }
    }
}

/**
 * The zoom a shot style allows: a wide or full-length shot is made by
 * standing back, not by zooming; a close-up may lean on the lens a little.
 * The style stays selectable at any distance - only the range is capped.
 */
fun zoomCapFor(style: ShotType?): Float = when (style) {
    ShotType.WIDE_SHOT, ShotType.BIRDS_EYE -> 1f
    ShotType.MEDIUM_SHOT, ShotType.LOW_ANGLE, ShotType.HIGH_ANGLE, ShotType.DUTCH_ANGLE, ShotType.OVER_SHOULDER -> 2f
    ShotType.CLOSE_UP -> 3f
    ShotType.EXTREME_CLOSE_UP -> 5f
    else -> 10f
}
