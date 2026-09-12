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

    // --- Digital zoom -------------------------------------------------------
    // This phone exposes ONE rear camera; its 1x-10x range is a digital crop of
    // the main sensor, not an optical lens change. See docs/HARDWARE.md.
    /** Never advise past this. Beyond it the crop costs more than the framing gains. */
    const val ZOOM_MAX_ADVISED = 3.0f
    /** Distance advice must stall this long before zoom is offered instead. */
    const val STEP_STALL_MS = 3000L
    /** Relative size improvement that counts as the photographer making progress. */
    const val STEP_PROGRESS_EPSILON = 0.05f
    /** Zoom ratios within this of each other are the same ratio. */
    const val ZOOM_EPSILON = 0.05f

    // --- Vertical strategy: translate vs rotate ---------------------------------
    /**
     * MOVE_UP / MOVE_DOWN must be followed for this long without the vertical
     * error improving before the engine concludes the photographer cannot
     * move the camera that way - an arm has a reach - and switches to tilting.
     * Same idea, same duration, as the step-closer -> zoom fallback.
     */
    const val VERTICAL_STALL_MS = 3000L
    /** Vertical framing improvement that counts as the photographer moving. */
    const val VERTICAL_PROGRESS_EPSILON = 0.02f
    /**
     * Once tilting is the strategy, the pitch rung tolerates this much instead
     * of DEADZONE_PITCH_DEG. Without it the tilt that fixes the framing is
     * immediately undone by "tilt back to level", and the two rungs fight
     * forever - the "go above your head" trap. A modest tilt is a perfectly
     * good portrait perspective; an overhead angle is a nice one.
     */
    const val PITCH_RELAXED_DEADZONE_DEG = 15.0f

    /** Eyes sit roughly this far down a detector face box. Shared with :app. */
    const val EYE_LINE_FRACTION_OF_FACE = 0.4f

    // --- Auto capture -----------------------------------------------------------
    /**
     * Minimum gap between automatic shots. One capture per lock acquisition
     * already stops a held lock from firing repeatedly; the cooldown stops a
     * scene that locks, breaks and re-locks every second from producing a
     * burst of near-identical frames.
     */
    const val AUTO_CAPTURE_COOLDOWN_MS = 3000L

    // --- Subject loss ---------------------------------------------------------
    /**
     * A subject has to be gone this long before the engine believes it. On a
     * ten-minute run at full-body distance, 27% of all instruction changes
     * were into SEEKING, and the dropouts split cleanly in two: detector
     * blinks under ~200ms (a ~22px face at the MIN_FACE_SIZE floor), and real
     * losses at 430-499ms, bounded by SHOT_TYPE_HOLD_MS. 300ms absorbs every
     * blink - during it the engine holds the last known box, so a lock
     * survives - and still fires before the profile flips to LANDSCAPE.
     */
    const val SUBJECT_LOSS_GRACE_MS = 300L

    // --- Shot type stability ------------------------------------------------
    /**
     * A new face count must hold this long before the composition target is
     * torn down and rebuilt. Detectors drop a face for a frame or two all the
     * time; without this the profile flaps and takes the lock dwell with it.
     */
    const val SHOT_TYPE_HOLD_MS = 500L

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
