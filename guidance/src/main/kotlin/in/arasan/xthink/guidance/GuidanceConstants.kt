package `in`.arasan.xthink.guidance

/**
 * Every tuning number the engine uses. These are transcribed from CLAUDE.md
 * and are the single source of truth - do not inline a literal anywhere else.
 */
object GuidanceConstants {

    // --- Smoothing / anti-jitter -------------------------------------------
    /** EMA alpha on angles. */
    const val EMA_ALPHA_ANGLE = 0.15f
    /** EMA alpha on boxes (and on the eye line, which is a box-like quantity). */
    const val EMA_ALPHA_BOX = 0.25f

    // --- Deadzones ----------------------------------------------------------
    const val DEADZONE_ROLL_DEG = 2.5f
    const val DEADZONE_PITCH_DEG = 6.0f
    /** Relative: |h - target| / target. */
    const val DEADZONE_SIZE_RATIO = 0.12f
    /** Absolute, as a fraction of the frame. */
    const val DEADZONE_XY = 0.06f

    /** Exit a deadzone at 1.6x the entry threshold. */
    const val HYSTERESIS_EXIT_MULTIPLIER = 1.6f

    // --- Timing -------------------------------------------------------------
    /** Hold a shown instruction at least this long before switching to another. */
    const val INSTRUCTION_LOCKOUT_MS = 600L
    /** All errors inside their deadzones continuously for this long to LOCK. */
    const val LOCK_DWELL_MS = 400L

    // --- Gaze-aware lead room ----------------------------------------------
    /** Below this the subject is looking at the lens; keep them centred. */
    const val GAZE_DEADZONE = 0.15f
    /** Subject looking frame-right sits on the left third. */
    const val LEAD_ROOM_CX_LOOKING_RIGHT = 0.333f
    /** Subject looking frame-left sits on the right third. */
    const val LEAD_ROOM_CX_LOOKING_LEFT = 0.667f

    // --- Status flags -------------------------------------------------------
    /** Smoothed angular rate above which the shot is judged unsteady, deg/s. */
    const val STABILITY_RATE_DEG_PER_S = 8.0f
    /** Subject drifts this far from where focus was tapped and focus is stale. */
    const val FOCUS_INVALIDATE_DISTANCE = 0.15f

    // --- Magnitude buckets, in multiples of the channel's deadzone ----------
    const val MAGNITUDE_MOVE_FACTOR = 2.0f
    const val MAGNITUDE_BIG_FACTOR = 4.0f

    // --- totalError normalisation ------------------------------------------
    // Error *beyond* the deadzone that counts as a fully saturated channel, so
    // totalError is exactly 0 whenever every channel sits inside its deadzone.
    const val SCALE_ROLL_DEG = 15.0f
    const val SCALE_PITCH_DEG = 20.0f
    const val SCALE_SIZE = 0.40f
    const val SCALE_XY = 0.25f

    // Weights sum to 1, ordered by the priority ladder.
    const val WEIGHT_ROLL = 0.30f
    const val WEIGHT_PITCH = 0.25f
    const val WEIGHT_SIZE = 0.20f
    const val WEIGHT_X = 0.125f
    const val WEIGHT_Y = 0.125f
}
