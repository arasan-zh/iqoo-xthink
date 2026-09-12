package `in`.arasan.xthink.ui

import androidx.compose.ui.graphics.ImageBitmap
import `in`.arasan.xthink.guidance.AlignmentState
import `in`.arasan.xthink.guidance.CoachMode
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
    /** The thermal governor's tier. Only shown when it is not COOL. */
    val thermal: ThermalTier = ThermalTier.COOL,
    val thermalHeadroom: Float = Float.NaN,
) {
    val verb: Verb? get() = instruction?.verb
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
