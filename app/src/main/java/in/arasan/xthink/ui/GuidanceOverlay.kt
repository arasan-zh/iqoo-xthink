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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import `in`.arasan.xthink.guidance.CoachMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
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
    modifier: Modifier = Modifier,
) {
    // The capture flash: a brief white wash whenever a photo is taken, auto
    // or manual, so the photographer knows without looking at the gallery.
    val flash = remember { Animatable(0f) }
    LaunchedEffect(state.captureNonce) {
        if (state.captureNonce > 0) {
            flash.snapTo(0.6f)
            flash.animateTo(0f, tween(280))
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
    var overlayTop by remember { mutableFloatStateOf(0f) }
    var overlayHeight by remember { mutableFloatStateOf(1f) }
    var safeCenterY by remember { mutableFloatStateOf(0f) }
    val safeCenterYFraction = if (overlayHeight > 0f) {
        ((safeCenterY - overlayTop) / overlayHeight).coerceIn(0.28f, 0.72f)
    } else {
        0.42f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayTop = it.positionInRoot().y
                overlayHeight = it.size.height.toFloat()
            }
            // Pinch to zoom anywhere on the preview. Buttons consume their own
            // taps first; this only sees the multi-touch gesture.
            .pointerInput(state.maxZoomRatio) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) {
                        onZoomSelected((state.zoomRatio * zoomChange).coerceIn(1f, state.maxZoomRatio))
                    }
                }
            },
    ) {
        // The alignment layer sits directly on the preview, under every chip.
        Reticle(
            state = state,
            safeCenterYFraction = safeCenterYFraction,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = XT.Gutter),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // --- top: nothing decorative. A small gap keeps the reticle's
            //     safe area from starting at the status bar. ---
            Spacer(Modifier.height(8.dp))

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
                ModeRail(
                    mode = state.mode,
                    onModeSelected = onModeSelected,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
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
                GuidanceCard(state = state)
                StatusStrip(state = state)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ZoomSlider(
                        zoomRatio = state.zoomRatio,
                        maxZoomRatio = state.maxZoomRatio,
                        onZoomSelected = onZoomSelected,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    ModeTabs(mode = state.mode, onModeSelected = onModeSelected)
                    BottomBar(
                        locked = state.isLocked,
                        thumbnail = state.thumbnail,
                        onShutter = onShutter,
                        onGallery = onGallery,
                    )
                }
            }
        }

        if (flash.value > 0.005f) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
        }
    }
}
