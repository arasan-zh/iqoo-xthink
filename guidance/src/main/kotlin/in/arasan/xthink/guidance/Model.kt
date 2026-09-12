package `in`.arasan.xthink.guidance

/**
 * Device attitude, already converted from TYPE_GAME_ROTATION_VECTOR by :app.
 *
 * Sign conventions, fixed here and relied on by every rule below:
 *  - [rollDeg]  positive = phone rotated clockwise as the photographer sees it.
 *               Correcting a positive roll means rotating counter-clockwise.
 *  - [pitchDeg] positive = camera aimed upwards above the horizon.
 *               Correcting a positive pitch means tilting down.
 */
data class Attitude(val rollDeg: Float, val pitchDeg: Float)

/**
 * Subject bounding box in normalised frame coordinates, 0..1, origin top-left.
 *
 * For people this is the ML Kit face box, which is why [CompositionProfile]
 * target sizes are small for wider shots: a FULL_BODY frame puts the face at
 * roughly a tenth of the frame height.
 */
data class SubjectBox(val cx: Float, val cy: Float, val w: Float, val h: Float) {
    /** Fraction of frame height the subject occupies. Drives the distance rung. */
    val sizeRatio: Float get() = h

    /** Gap between the top of the frame and the top of the box. */
    val headroom: Float get() = cy - h / 2f

    val top: Float get() = cy - h / 2f
    val bottom: Float get() = cy + h / 2f
}

/**
 * Eye line of the primary face.
 *
 * @param y normalised height of the line through both eyes, 0..1.
 * @param gazeDx which way the subject is looking, -1..1. Positive = looking
 *        towards frame right, negative = towards frame left, ~0 = at the lens.
 */
data class EyeLine(val y: Float, val gazeDx: Float)

enum class ShotType { HEADSHOT, HALF_BODY, FULL_BODY, GROUP, OBJECT, LANDSCAPE }

/**
 * What the photographer chose on the mode rail.
 *
 * PORTRAIT: one person, the largest face, framed at whatever scale they are
 * at. WIDE: everyone in frame as one group, or a venue - an office, an event
 * floor - coached on level and pitch when nobody is in it.
 */
enum class CoachMode { PORTRAIT, WIDE }

enum class Verb {
    LEVEL_CW, LEVEL_CCW,
    TILT_UP, TILT_DOWN,
    MOVE_UP, MOVE_DOWN,
    MOVE_LEFT, MOVE_RIGHT,
    STEP_CLOSER, STEP_BACK,
    ZOOM_IN, ZOOM_OUT,
    TAP_FOCUS, HOLD_STEADY, SEEKING, LOCKED,
}

/** How far off we are, in units of the channel's deadzone. */
enum class Magnitude { NUDGE, MOVE, BIG }

/** Exactly one of these comes out of the engine per frame. Never two arrows. */
data class Instruction(val verb: Verb, val magnitude: Magnitude, val text: String)

/**
 * Everything a fighter-jet style alignment HUD needs, as pure numbers.
 *
 * The engine emits one *instruction*; this is the continuous state behind it,
 * so the overlay can draw an artificial horizon, a pitch ladder and a drifting
 * reticle that all move smoothly between instruction changes. No UI code here
 * and none in this module - :app renders it in v0.3.
 */
data class AlignmentState(
    /** Smoothed roll, signed. Rotate the horizon bar by -rollDeg. */
    val rollDeg: Float,
    /** Smoothed pitch, signed. Slides the pitch ladder. */
    val pitchDeg: Float,
    val rollErrDeg: Float,
    val pitchErrDeg: Float,
    /** Signed framing error, fraction of frame. Offsets the reticle. */
    val offsetX: Float,
    val offsetY: Float,
    /** Signed relative size error. Negative = subject too small, step closer. */
    val sizeErr: Float,
    val rollInDeadzone: Boolean,
    val pitchInDeadzone: Boolean,
    val distanceInDeadzone: Boolean,
    val framingInDeadzone: Boolean,
    /** 0..1 progress through the 400 ms lock dwell. Fills the lock ring. */
    val lockProgress: Float,
    /**
     * True once the engine has decided the photographer cannot move the
     * camera vertically and is coaching with tilt instead. The pitch
     * tolerance is relaxed while this holds.
     */
    val usingRotation: Boolean,
    /**
     * Where the subject box SHOULD be, in normalised frame coordinates, for
     * the current profile and gaze - same size as the subject (scale is the
     * photographer's choice), placed at the target centre and eye line. The
     * reticle draws its brackets here so the instruction becomes "move the
     * green frame into the gold one". False when there is no subject.
     */
    val hasTarget: Boolean,
    val targetCx: Float,
    val targetCy: Float,
    val targetW: Float,
    val targetH: Float,
    /** Zoom ratio that would fix the framing, or 1f when zoom is not the answer. */
    val suggestedZoom: Float,
    /** 0..1, same value as [GuidanceEngine.totalError]. Drives haptics. */
    val totalError: Float,
) {
    companion object {
        val EMPTY = AlignmentState(
            rollDeg = 0f, pitchDeg = 0f, rollErrDeg = 0f, pitchErrDeg = 0f,
            offsetX = 0f, offsetY = 0f, sizeErr = 0f,
            rollInDeadzone = false, pitchInDeadzone = false,
            distanceInDeadzone = false, framingInDeadzone = false,
            lockProgress = 0f, usingRotation = false,
            hasTarget = false, targetCx = 0.5f, targetCy = 0.5f, targetW = 0f, targetH = 0f,
            suggestedZoom = 1f, totalError = 0f,
        )
    }
}
