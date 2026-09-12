package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.GuidanceConstants.AUTO_CAPTURE_COOLDOWN_MS

/**
 * Decides when a photo should be taken without a tap.
 *
 * The engine says LOCKED when the composition is right and has been for the
 * dwell. That is the moment a coach would say "now". This turns that moment
 * into at most one capture:
 *
 *  - once per lock ACQUISITION - a lock held for five seconds is one photo,
 *    not one hundred and fifty;
 *  - only while [stabilityOk] - a locked composition on a shaking phone is a
 *    blurred photo, so within the same lock it waits for the phone to settle
 *    and fires then;
 *  - never within [AUTO_CAPTURE_COOLDOWN_MS] of the previous shot, automatic
 *    or manual - a scene that locks, breaks and re-locks every second must
 *    not produce a burst;
 *  - only with a subject in frame. A LANDSCAPE profile locks on a level,
 *    steady phone alone, and on the phone that auto-shot an empty room. A
 *    portrait app takes pictures of people; the manual shutter is for
 *    everything else.
 *
 * Pure Kotlin. :app owns the ImageCapture use case and calls [update] with the
 * engine's verb each frame; a `true` means take the picture now.
 */
class AutoCapturePolicy {

    /**
     * "Near enough" mode. Some photographers cannot reach the last few
     * degrees - a tilt up the arm will not give, a step back into a wall.
     * With this on, a frame that is close to the lock (small total error,
     * a fine-adjust instruction, nothing coarse left) and held there for
     * [NEAR_DWELL_MS] counts as locked for the purpose of the shutter. The
     * instruction on screen is unchanged: the coach still says what would
     * be better; it just stops withholding the picture.
     */
    var relaxed: Boolean = false

    private var capturedThisLock = false
    private var sinceCaptureMs = AUTO_CAPTURE_COOLDOWN_MS
    private var nearMs = 0L

    /** How long the photographer has had a subject without reaching the lock. */
    private var struggleMs = 0L

    /** Milliseconds until another automatic shot is allowed; 0 when ready. */
    val cooldownRemainingMs: Long
        get() = (AUTO_CAPTURE_COOLDOWN_MS - sinceCaptureMs).coerceAtLeast(0L)

    /**
     * @param totalError the engine's aggregate error, 0 at the lock; only
     *        consulted when [relaxed].
     */
    /**
     * @param totalError the engine's aggregate error, 0 at the lock; only
     *        consulted when [relaxed].
     * @param sharp false when the frame itself is soft - motion or missed
     *        focus - whatever the gyro says. A blurred picture is never
     *        worth taking automatically.
     */
    fun update(
        verb: Verb,
        stabilityOk: Boolean,
        subjectPresent: Boolean,
        dtMs: Long,
        totalError: Float = 1f,
        sharp: Boolean = true,
    ): Boolean {
        val dt = if (dtMs < 0L) 0L else dtMs
        sinceCaptureMs = (sinceCaptureMs + dt).coerceAtMost(AUTO_CAPTURE_COOLDOWN_MS)

        // The struggle clock: a subject in frame, no lock. "Near enough" is
        // only offered once this has run for NEAR_AFTER_MS - the correct
        // capture is always tried for first.
        struggleMs = if (subjectPresent && verb != Verb.LOCKED) (struggleMs + dt).coerceAtMost(NEAR_AFTER_MS) else 0L

        val near = relaxed && subjectPresent && struggleMs >= NEAR_AFTER_MS && isNear(verb, totalError)
        nearMs = if (near) (nearMs + dt).coerceAtMost(NEAR_DWELL_MS) else 0L
        val goodEnough = verb == Verb.LOCKED || (near && nearMs >= NEAR_DWELL_MS)

        if (!goodEnough || !subjectPresent) {
            // Leaving the lock (or the near zone), or losing the subject,
            // re-arms for the next acquisition.
            if (!subjectPresent || (verb != Verb.LOCKED && !near)) capturedThisLock = false
            return false
        }
        if (capturedThisLock) return false
        if (!stabilityOk || !sharp) return false
        if (sinceCaptureMs < AUTO_CAPTURE_COOLDOWN_MS) return false

        capturedThisLock = true
        sinceCaptureMs = 0L
        struggleMs = 0L
        return true
    }

    /** A manual shot also starts the cooldown, so auto does not double up. */
    fun notifyManualCapture() {
        sinceCaptureMs = 0L
        capturedThisLock = true
    }

    fun reset() {
        capturedThisLock = false
        sinceCaptureMs = AUTO_CAPTURE_COOLDOWN_MS
        nearMs = 0L
        struggleMs = 0L
    }

    companion object {
        /** Aggregate error at or below which a frame is "near enough". */
        const val NEAR_ERROR = 0.40f

        /** Held near for this long before the shutter treats it as a lock. */
        const val NEAR_DWELL_MS = 700L

        /** Only after this long with a subject and no lock is "near enough" offered. */
        const val NEAR_AFTER_MS = 30_000L

        /**
         * Only the fine adjustments count as near. A zoom or a seek is not
         * a small residual, whatever the error number says.
         */
        fun isNear(verb: Verb, totalError: Float): Boolean = when (verb) {
            Verb.LOCKED -> true
            Verb.TILT_UP, Verb.TILT_DOWN, Verb.MOVE_UP, Verb.MOVE_DOWN,
            Verb.MOVE_LEFT, Verb.MOVE_RIGHT, Verb.LEVEL_CW, Verb.LEVEL_CCW,
            Verb.STEP_CLOSER, Verb.STEP_BACK, Verb.HOLD_STEADY,
            -> totalError <= NEAR_ERROR
            else -> false
        }
    }
}
