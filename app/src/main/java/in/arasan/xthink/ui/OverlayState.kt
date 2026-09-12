package `in`.arasan.xthink.ui

import androidx.compose.ui.graphics.ImageBitmap
import `in`.arasan.xthink.guidance.AlignmentState
import `in`.arasan.xthink.guidance.CoachMode
import `in`.arasan.xthink.guidance.ShotType
import `in`.arasan.xthink.guidance.Instruction
import `in`.arasan.xthink.guidance.SubjectBox
import `in`.arasan.xthink.guidance.ThermalTier
import `in`.arasan.xthink.guidance.Verb

/** One row of the status strip. */
data class StatusValue(val label: String, val value: String, val ok: Boolean)

/**
 * Everything the overlay draws, and nothing about how the camera produced it.
 *
 * CLAUDE.md requires the guidance overlay to be a separate composable layer,
 * restyleable without touching camera code. This type is that boundary: the
 * camera screen builds one of these, the overlay renders it, and neither needs
 * to know anything else about the other.
 */
data class OverlayState(
    val instruction: Instruction?,
    val alignment: AlignmentState,
    /** Normalised subject box in visible-frame coordinates, or null. */
    val subject: SubjectBox?,
    val focus: StatusValue,
    val lighting: StatusValue,
    val stability: StatusValue,
    val composition: StatusValue,
    val zoomRatio: Float,
    val maxZoomRatio: Float,
    /** Most recent capture, for the gallery button. Null until the first shot. */
    val thumbnail: ImageBitmap? = null,
    /** Bumped on every capture; the overlay flashes when it changes. */
    val captureNonce: Int = 0,
    /** The photographer's chosen mode, for the rail and the tabs. */
    val mode: CoachMode = CoachMode.PORTRAIT,
    /** FIT tab: squats, push-ups. */
    val fitMode: Boolean = false,
    val fit: FitState? = null,
    /** SIGNS tab: a hand sign, as an English word. */
    val signsMode: Boolean = false,
    val sign: String? = null,
    /** ASK tab: ask about, read, translate or keep what the camera sees. */
    val askMode: Boolean = false,
    val ask: AskState? = null,
    /** GENIUS tab: say it, the phone does it on a paired Mac; the camera watches the screen. */
    val typeMode: Boolean = false,
    val genius: GeniusState? = null,
    /** VIDEO tab: the shutter records, focus follows the subject. */
    val videoMode: Boolean = false,
    /** True while a clip is being recorded. */
    val recording: Boolean = false,
    /** Length of the clip so far. */
    val recordingMs: Long = 0L,
    /** The guidance card (the words). Off by default: the reticle and the arrows guide. */
    val showGuide: Boolean = false,
    /** Focal length of the lens at 1x, 35mm-equivalent, for the zoom readout. */
    val baseFocalMm: Float = 23.5f,
    /** A shot style the photographer picked (PORTRAIT only), or null for the automatic ladder. */
    val shotStyle: ShotType? = null,
    /** True while the Shots row is open. */
    val showShots: Boolean = false,
    /** The look chosen on the camera page; baked into every capture, pre-selected in the review. */
    val look: Int = 0,
    /** True while the looks row is open on the camera page. */
    val showLooks: Boolean = false,
    /** A frozen frame the looks row previews on; taken when the row opens. */
    val lookPreview: ImageBitmap? = null,
    /** "Near enough" auto-shutter: shoot when close to the lock, not only at it. */
    val easyShot: Boolean = false,
    /** The review after a shutter, or null. While it is up, nothing else is. */
    val review: ReviewState? = null,
    /** Where the photographer last tapped, 0..1 of the preview; a ring is drawn there. */
    val focusPoint: Pair<Float, Float>? = null,
    /** Increments on every tap so the ring animates again at the same spot. */
    val focusNonce: Int = 0,
    /** Front camera: the preview is a mirror, so the horizon tilts the other way. */
    val mirrored: Boolean = false,
    /** The thermal governor's tier. Only shown when it is not COOL. */
    val thermal: ThermalTier = ThermalTier.COOL,
    val thermalHeadroom: Float = Float.NaN,
) {
    val verb: Verb? get() = instruction?.verb
    /** False in CREATIVE: nothing coaches, only the camera controls draw. */
    val assisted: Boolean get() = mode != CoachMode.CREATIVE && !typeMode && !askMode && !fitMode && !signsMode

    /** A chosen Shot style takes the words of guidance off; the frame brackets remain. */
    val guideMuted: Boolean get() = shotStyle != null
    val isLocked: Boolean get() = verb == Verb.LOCKED
    val isSeeking: Boolean get() = verb == Verb.SEEKING

    /** The horizon only appears when it has something to say. */
    val showHorizon: Boolean get() = !alignment.rollInDeadzone
    val showPitchLadder: Boolean get() = !alignment.pitchInDeadzone

    companion object {
        val EMPTY = OverlayState(
            instruction = null,
            alignment = AlignmentState.EMPTY,
            subject = null,
            focus = StatusValue("Focus", "-", false),
            lighting = StatusValue("Lighting", "-", false),
            stability = StatusValue("Stability", "-", false),
            composition = StatusValue("Composition", "-", false),
            zoomRatio = 1f,
            maxZoomRatio = 1f,
        )
    }
}
