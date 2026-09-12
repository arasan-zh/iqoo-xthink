package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.GuidanceConstants.DEADZONE_PITCH_DEG
import `in`.arasan.xthink.guidance.GuidanceConstants.DEADZONE_ROLL_DEG
import `in`.arasan.xthink.guidance.GuidanceConstants.DEADZONE_SIZE_RATIO
import `in`.arasan.xthink.guidance.GuidanceConstants.DEADZONE_XY
import `in`.arasan.xthink.guidance.GuidanceConstants.EMA_ALPHA_ANGLE
import `in`.arasan.xthink.guidance.GuidanceConstants.EMA_ALPHA_BOX
import `in`.arasan.xthink.guidance.GuidanceConstants.FOCUS_INVALIDATE_DISTANCE
import `in`.arasan.xthink.guidance.GuidanceConstants.GAZE_DEADZONE
import `in`.arasan.xthink.guidance.GuidanceConstants.INSTRUCTION_LOCKOUT_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.LEAD_ROOM_CX_LOOKING_LEFT
import `in`.arasan.xthink.guidance.GuidanceConstants.LEAD_ROOM_CX_LOOKING_RIGHT
import `in`.arasan.xthink.guidance.GuidanceConstants.LOCK_DWELL_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.MAGNITUDE_BIG_FACTOR
import `in`.arasan.xthink.guidance.GuidanceConstants.MAGNITUDE_MOVE_FACTOR
import `in`.arasan.xthink.guidance.GuidanceConstants.STEP_PROGRESS_EPSILON
import `in`.arasan.xthink.guidance.GuidanceConstants.STEP_STALL_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.ZOOM_EPSILON
import `in`.arasan.xthink.guidance.GuidanceConstants.ZOOM_MAX_ADVISED
import `in`.arasan.xthink.guidance.GuidanceConstants.SCALE_PITCH_DEG
import `in`.arasan.xthink.guidance.GuidanceConstants.SCALE_ROLL_DEG
import `in`.arasan.xthink.guidance.GuidanceConstants.SCALE_SIZE
import `in`.arasan.xthink.guidance.GuidanceConstants.SCALE_XY
import `in`.arasan.xthink.guidance.GuidanceConstants.STABILITY_RATE_DEG_PER_S
import `in`.arasan.xthink.guidance.GuidanceConstants.WEIGHT_PITCH
import `in`.arasan.xthink.guidance.GuidanceConstants.WEIGHT_ROLL
import `in`.arasan.xthink.guidance.GuidanceConstants.WEIGHT_SIZE
import `in`.arasan.xthink.guidance.GuidanceConstants.WEIGHT_X
import `in`.arasan.xthink.guidance.GuidanceConstants.WEIGHT_Y
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The whole coach. Pure Kotlin, no Android, no I/O, no clock of its own -
 * :app feeds it frames and the elapsed time between them.
 *
 * Priority ladder, highest first:
 *   roll -> pitch -> distance -> x/y framing -> tap-focus -> LOCKED
 *
 * Exactly one [Instruction] comes out per [update]. Never two arrows.
 *
 * Two rules cut across the ladder:
 *
 *  - **Translation beats rotation.** When pitch is out of its deadzone *and*
 *    the vertical framing error points the same way, moving the phone fixes
 *    both at once, so the engine says MOVE_UP / MOVE_DOWN rather than TILT.
 *    TILT is reserved for a genuine rotation error: pitch off while framing is
 *    already good, or the two errors disagreeing in sign.
 *
 *  - **The instruction lockout applies to every verb, LOCKED included.** An
 *    instruction is held for at least 600 ms before any other one can replace
 *    it. Since the lock dwell is 400 ms, a lock that becomes available mid
 *    lockout appears when the lockout expires. That is deliberate: it stops the
 *    card flickering between an arrow and the lock at the boundary.
 *
 * @param profile composition targets for the current shot type.
 * @param mirrored true for the front camera, whose preview is flipped. Only
 *        MOVE_LEFT / MOVE_RIGHT invert; up/down and the roll verbs do not.
 * @param hasAutofocus false for a fixed-focus camera. On this phone the front
 *        camera reports CONTROL_AF_AVAILABLE_MODES = [OFF], so TAP_FOCUS there
 *        would ask for something the hardware cannot do. When false the
 *        tap-focus rung is skipped and focus never blocks the lock.
 */
class GuidanceEngine(
    profile: CompositionProfile,
    private val mirrored: Boolean = false,
    private val hasAutofocus: Boolean = true,
) {

    var profile: CompositionProfile = profile
        private set

    // --- smoothing ----------------------------------------------------------
    private val rollEma = AngleEma(EMA_ALPHA_ANGLE)
    private val pitchEma = AngleEma(EMA_ALPHA_ANGLE)
    private val cxEma = Ema(EMA_ALPHA_BOX)
    private val cyEma = Ema(EMA_ALPHA_BOX)
    private val wEma = Ema(EMA_ALPHA_BOX)
    private val hEma = Ema(EMA_ALPHA_BOX)
    private val eyeYEma = Ema(EMA_ALPHA_BOX)
    private val gazeEma = Ema(EMA_ALPHA_BOX)
    private val rateEma = Ema(EMA_ALPHA_ANGLE)

    // --- deadzones ----------------------------------------------------------
    private val rollGate = HysteresisGate(DEADZONE_ROLL_DEG)
    private val pitchGate = HysteresisGate(DEADZONE_PITCH_DEG)
    private val sizeGate = HysteresisGate(DEADZONE_SIZE_RATIO)
    private val xGate = HysteresisGate(DEADZONE_XY)
    private val yGate = HysteresisGate(DEADZONE_XY)
    private val stabilityGate = HysteresisGate(STABILITY_RATE_DEG_PER_S)

    // --- state --------------------------------------------------------------
    private var lastRawRoll: Float? = null
    private var lastRawPitch: Float? = null
    private var dwellMs = 0L
    private var shown: Instruction? = null
    private var shownMs = 0L
    private var focusConfirmed = false
    private var focusAnchored = false
    private var focusAnchorX = 0f
    private var focusAnchorY = 0f

    // Digital zoom. Defaults say "no zoom available", so an engine that is
    // never told about zoom never advises it.
    private var zoomRatio = 1f
    private var maxZoomRatio = 1f
    private var suggestedZoom = 1f

    // Stall detection for the distance rung: how long we have been telling the
    // photographer to step closer without the subject actually getting bigger.
    private var stepAdviceMs = 0L
    private var bestSizeErrAbs = Float.MAX_VALUE

    /** 0..1. Zero when every channel is inside its deadzone. Drives haptics. */
    var totalError: Float = 0f
        private set

    /** Nothing to focus on, or focus was tapped on the subject where it is now. */
    var focusOk: Boolean = true
        private set

    /** Smoothed angular rate is low enough to shoot. */
    var stabilityOk: Boolean = false
        private set

    /** Every geometric channel is inside its deadzone. */
    var compositionOk: Boolean = false
        private set

    /** Continuous alignment state for the HUD. See [AlignmentState]. */
    var alignment: AlignmentState = AlignmentState.EMPTY
        private set

    /** How far through the 400 ms lock dwell we are, 0..1. */
    val lockProgress: Float
        get() = (dwellMs.toFloat() / LOCK_DWELL_MS).coerceIn(0f, 1f)

    /**
     * One frame in, one instruction out.
     *
     * @param a device attitude for this frame.
     * @param subject subject box, or null when nothing is detected.
     * @param eyes eye line of the primary face, or null.
     * @param dtMs milliseconds since the previous [update].
     */
    fun update(a: Attitude, subject: SubjectBox?, eyes: EyeLine?, dtMs: Long): Instruction {
        val dt = if (dtMs < 0L) 0L else dtMs

        val roll = rollEma.update(a.rollDeg)
        val pitch = pitchEma.update(a.pitchDeg)
        updateStability(a, dt)

        val box = smoothBox(subject)
        val eye = smoothEyes(eyes)
        updateFocus(box)

        // --- errors ---------------------------------------------------------
        val rollErr = roll
        val pitchErr = pitch - profile.targetPitchDeg
        val rollIn = rollGate.update(abs(rollErr))
        val pitchIn = pitchGate.update(abs(pitchErr))

        // Distance. A profile with targetSizeRatio 0 (LANDSCAPE) has nothing to
        // size against, so the rung is skipped rather than divided by zero.
        // Inside the profile's accepted band the photographer has chosen the
        // scale - full body, hip level, head and shoulders are all portraits -
        // and distance is simply not an error. Outside it, the error is how
        // far past the nearest edge we are, relative to that edge, so the
        // sign convention (negative = too small = step closer) is unchanged.
        val sizeApplies = box != null && profile.targetSizeRatio > 0f
        val sizeErr = if (sizeApplies) bandError(box.sizeRatio, profile.sizeMin, profile.sizeMax) else 0f
        val sizeIn = if (sizeApplies) sizeGate.update(abs(sizeErr)) else { sizeGate.reset(); true }

        // Horizontal framing, against the gaze-adjusted target.
        val targetCx = effectiveTargetCx(eye)
        val xErr = if (box != null) box.cx - targetCx else 0f
        val xIn = if (box != null) xGate.update(abs(xErr)) else { xGate.reset(); true }

        // Vertical framing. The eye line is the better reference when we have
        // it; otherwise fall back to the box centre (objects have no eyes).
        val yErr = verticalError(box, eye)
        val yApplies = box != null || eye != null
        val yIn = if (yApplies) yGate.update(abs(yErr)) else { yGate.reset(); true }

        // A profile with a size target wants a subject. If it has one and then
        // loses it, every subject-dependent instruction it was giving becomes
        // meaningless - "step closer" to nothing. Say so instead.
        val subjectMissing = profile.targetSizeRatio > 0f && box == null

        compositionOk = !subjectMissing && rollIn && pitchIn && sizeIn && xIn && yIn

        updateStepStall(sizeErr, sizeIn, dt)

        // --- lock dwell -----------------------------------------------------
        if (compositionOk && focusOk) dwellMs += dt else dwellMs = 0L

        // --- ladder ---------------------------------------------------------
        val candidate = if (subjectMissing) {
            instruction(Verb.SEEKING, Magnitude.NUDGE)
        } else {
            ladder(rollErr, rollIn, pitchErr, pitchIn, sizeErr, sizeIn, xErr, xIn, yErr, yIn)
                ?: if (dwellMs >= LOCK_DWELL_MS) instruction(Verb.LOCKED, Magnitude.NUDGE)
                // Geometry is good but the dwell is not served. Holding the
                // last arrow avoids a flicker; on a cold start there is no last
                // arrow, and "hold steady" is exactly the right thing to say.
                // Holding the last arrow is right, but SEEKING is a state,
                // not an arrow. Once a subject is no longer expected, a stale
                // "looking for your subject" must not be what we hold onto.
                else shown?.takeIf { it.verb != Verb.SEEKING }
                    ?: instruction(Verb.HOLD_STEADY, Magnitude.NUDGE)
        }

        // --- 600 ms instruction lockout -------------------------------------
        val current = shown
        val result: Instruction
        if (current == null) {
            result = candidate
            shownMs = 0L
        } else {
            shownMs += dt
            // Crossing into or out of SEEKING is not two instructions
            // competing - it is the situation itself changing. Making it wait
            // out the lockout would leave "step closer" on screen for 600ms
            // after the subject walked off, which is the bug this fixes, and
            // would hold "looking for your subject" just as long after they
            // came back.
            val subjectPresenceChanged =
                (candidate.verb == Verb.SEEKING) != (current.verb == Verb.SEEKING)
            if (candidate.verb == current.verb) {
                // Same arrow, refreshed magnitude and text. Not a switch, so
                // the hold timer keeps running.
                result = candidate
            } else if (subjectPresenceChanged || shownMs >= INSTRUCTION_LOCKOUT_MS) {
                result = candidate
                shownMs = 0L
            } else {
                result = current
            }
        }
        shown = result

        updateTelemetry(roll, pitch, rollErr, pitchErr, rollIn, pitchIn, sizeErr, sizeIn, xErr, xIn, yErr, yIn)
        return result
    }

    /**
     * The priority ladder. Returns null when there is nothing to correct, which
     * hands the decision to the dwell/lock logic in [update].
     */
    private fun ladder(
        rollErr: Float, rollIn: Boolean,
        pitchErr: Float, pitchIn: Boolean,
        sizeErr: Float, sizeIn: Boolean,
        xErr: Float, xIn: Boolean,
        yErr: Float, yIn: Boolean,
    ): Instruction? = when {

        // 1. Roll. A crooked horizon ruins the frame no matter what else is right.
        !rollIn -> instruction(
            if (rollErr > 0f) Verb.LEVEL_CCW else Verb.LEVEL_CW,
            magnitudeFor(rollErr, DEADZONE_ROLL_DEG),
        )

        // 2. Pitch - but prefer translation over rotation.
        !pitchIn -> {
            // Camera aimed up (pitchErr > 0) drops the subject low in frame
            // (yErr > 0). When both agree, one move of the phone fixes both.
            val translationFixesBoth = !yIn && sameSign(pitchErr, yErr)
            if (translationFixesBoth) {
                verticalMove(yErr)
            } else {
                instruction(
                    if (pitchErr > 0f) Verb.TILT_DOWN else Verb.TILT_UP,
                    magnitudeFor(pitchErr, DEADZONE_PITCH_DEG),
                )
            }
        }

        // 3. Distance. Moving the photographer is the whole premise of xThink,
        //    so zoom is a fallback, never the first answer. See distanceAdvice.
        !sizeIn -> distanceAdvice(sizeErr)

        // 4. x/y framing. One axis at a time - whichever is further out, x on a tie.
        !xIn || !yIn ->
            if (!xIn && (yIn || abs(xErr) >= abs(yErr))) horizontalMove(xErr) else verticalMove(yErr)

        // 5. Focus, once the geometry is settled.
        !focusOk -> instruction(Verb.TAP_FOCUS, Magnitude.NUDGE)

        // 6. Nothing left to say. Caller decides between dwelling and LOCKED.
        else -> null
    }

    /** Gaze-aware lead room: leave space in front of where the subject looks. */
    private fun effectiveTargetCx(eye: EyeLine?): Float {
        if (eye == null) return profile.targetCx
        // A vase does not have a gaze.
        if (profile.shotType == ShotType.OBJECT || profile.shotType == ShotType.LANDSCAPE) return profile.targetCx
        return when {
            eye.gazeDx > GAZE_DEADZONE -> LEAD_ROOM_CX_LOOKING_RIGHT
            eye.gazeDx < -GAZE_DEADZONE -> LEAD_ROOM_CX_LOOKING_LEFT
            // Looking down the lens: keep them where the profile wants them.
            else -> profile.targetCx
        }
    }

    /**
     * Signed vertical framing error. Positive = subject sits too low in frame.
     * A headroom violation overrides, since cropping the top of a head is worse
     * than an eye line a few percent off.
     */
    private fun verticalError(box: SubjectBox?, eye: EyeLine?): Float {
        val measured = eye?.y ?: box?.cy ?: return 0f
        var err = measured - profile.targetEyeLineY
        if (box != null) {
            val shortfall = profile.headroomMin - box.headroom
            if (shortfall > 0f && -shortfall < err) err = -shortfall
        }
        return err
    }

    /**
     * Distance advice, zoom-aware.
     *
     * Walking gives a better photograph than cropping, and telling the
     * photographer where to stand is the point of this app, so STEP_* is always
     * the first answer. Zoom is offered in exactly two cases:
     *
     *  - **Too big while already zoomed in.** Undoing a digital crop is free
     *    and lossless, so it beats asking someone to walk backwards.
     *  - **Too small and stepping has demonstrably stalled.** After
     *    [STEP_STALL_MS] of STEP_CLOSER with no measurable progress, the
     *    photographer probably cannot move - a wall, a barrier, a stage - and
     *    a capped crop is better than advice they cannot follow.
     */
    private fun distanceAdvice(sizeErr: Float): Instruction {
        val magnitude = magnitudeFor(sizeErr, DEADZONE_SIZE_RATIO)
        return if (sizeErr > 0f) {
            if (zoomRatio > 1f + ZOOM_EPSILON) instruction(Verb.ZOOM_OUT, magnitude)
            else instruction(Verb.STEP_BACK, magnitude)
        } else {
            if (stepHasStalled() && zoomHeadroom()) instruction(Verb.ZOOM_IN, magnitude)
            else instruction(Verb.STEP_CLOSER, magnitude)
        }
    }

    /** Relative distance outside `lo..hi`; zero anywhere inside. */
    private fun bandError(h: Float, lo: Float, hi: Float): Float = when {
        h < lo -> (h - lo) / lo
        h > hi -> (h - hi) / hi
        else -> 0f
    }

    private fun stepHasStalled(): Boolean = stepAdviceMs >= STEP_STALL_MS

    /** Is there usable zoom left below the advised ceiling? */
    private fun zoomHeadroom(): Boolean =
        maxZoomRatio > zoomRatio + ZOOM_EPSILON && zoomRatio < ZOOM_MAX_ADVISED - ZOOM_EPSILON

    /**
     * Track whether "step closer" is actually working. Progress resets the
     * clock; standing still runs it down towards offering zoom instead.
     */
    private fun updateStepStall(sizeErr: Float, sizeIn: Boolean, dt: Long) {
        val stepping = !sizeIn && sizeErr < 0f
        if (!stepping) {
            stepAdviceMs = 0L
            bestSizeErrAbs = Float.MAX_VALUE
            suggestedZoom = 1f
            return
        }
        val err = abs(sizeErr)
        if (err < bestSizeErrAbs - STEP_PROGRESS_EPSILON) {
            // The subject got meaningfully bigger: they are moving. Keep waiting.
            bestSizeErrAbs = err
            stepAdviceMs = 0L
        } else {
            stepAdviceMs += dt
        }
        // Zoom that would put the subject on target, capped at the advised ceiling.
        suggestedZoom = (zoomRatio / (1f + sizeErr))
            .coerceIn(1f, minOf(maxZoomRatio, ZOOM_MAX_ADVISED))
    }

    private fun verticalMove(yErr: Float): Instruction = instruction(
        // Subject too low in frame -> aim the camera down to bring it up.
        if (yErr > 0f) Verb.MOVE_DOWN else Verb.MOVE_UP,
        magnitudeFor(yErr, DEADZONE_XY),
    )

    private fun horizontalMove(xErr: Float): Instruction = instruction(
        // Subject right of target -> pan right so it slides back to centre.
        if (xErr > 0f) Verb.MOVE_RIGHT else Verb.MOVE_LEFT,
        magnitudeFor(xErr, DEADZONE_XY),
    )

    private fun smoothBox(subject: SubjectBox?): SubjectBox? {
        if (subject == null) {
            cxEma.reset(); cyEma.reset(); wEma.reset(); hEma.reset()
            return null
        }
        return SubjectBox(
            cx = cxEma.update(subject.cx),
            cy = cyEma.update(subject.cy),
            w = wEma.update(subject.w),
            h = hEma.update(subject.h),
        )
    }

    private fun smoothEyes(eyes: EyeLine?): EyeLine? {
        if (eyes == null) {
            eyeYEma.reset(); gazeEma.reset()
            return null
        }
        return EyeLine(y = eyeYEma.update(eyes.y), gazeDx = gazeEma.update(eyes.gazeDx))
    }

    /** Stability from the raw frame-to-frame angular rate, not the smoothed one. */
    private fun updateStability(a: Attitude, dt: Long) {
        val prevRoll = lastRawRoll
        val prevPitch = lastRawPitch
        if (prevRoll != null && prevPitch != null && dt > 0L) {
            val dRoll = abs(shortestDeltaDeg(a.rollDeg, prevRoll))
            val dPitch = abs(shortestDeltaDeg(a.pitchDeg, prevPitch))
            rateEma.update((dRoll + dPitch) * 1000f / dt)
        } else if (prevRoll == null) {
            rateEma.update(0f)
        }
        lastRawRoll = a.rollDeg
        lastRawPitch = a.pitchDeg
        stabilityOk = stabilityGate.update(rateEma.value)
    }

    private fun updateFocus(box: SubjectBox?) {
        if (!hasAutofocus) {
            // Fixed-focus camera. There is nothing to tap, so focus can never
            // be the reason we withhold a lock.
            focusConfirmed = false
            focusAnchored = false
            focusOk = true
            return
        }
        if (box == null) {
            // Nothing to focus on. Not a reason to block the lock.
            focusConfirmed = false
            focusAnchored = false
            focusOk = true
            return
        }
        if (focusConfirmed && !focusAnchored) {
            // Focus was reported before the first frame carrying a subject.
            focusAnchorX = box.cx
            focusAnchorY = box.cy
            focusAnchored = true
        } else if (focusConfirmed) {
            val dx = box.cx - focusAnchorX
            val dy = box.cy - focusAnchorY
            if (sqrt(dx * dx + dy * dy) > FOCUS_INVALIDATE_DISTANCE) {
                // The subject walked away from where we focused. Ask again.
                focusConfirmed = false
                focusAnchored = false
            }
        }
        focusOk = focusConfirmed
    }

    private fun updateTelemetry(
        roll: Float, pitch: Float,
        rollErr: Float, pitchErr: Float, rollIn: Boolean, pitchIn: Boolean,
        sizeErr: Float, sizeIn: Boolean,
        xErr: Float, xIn: Boolean,
        yErr: Float, yIn: Boolean,
    ) {
        val r = excess(rollErr, DEADZONE_ROLL_DEG, SCALE_ROLL_DEG)
        val p = excess(pitchErr, DEADZONE_PITCH_DEG, SCALE_PITCH_DEG)
        val s = excess(sizeErr, DEADZONE_SIZE_RATIO, SCALE_SIZE)
        val x = excess(xErr, DEADZONE_XY, SCALE_XY)
        val y = excess(yErr, DEADZONE_XY, SCALE_XY)
        totalError = sqrt(
            WEIGHT_ROLL * r * r + WEIGHT_PITCH * p * p + WEIGHT_SIZE * s * s +
                WEIGHT_X * x * x + WEIGHT_Y * y * y
        ).coerceIn(0f, 1f)

        alignment = AlignmentState(
            rollDeg = roll,
            pitchDeg = pitch,
            rollErrDeg = rollErr,
            pitchErrDeg = pitchErr,
            offsetX = xErr,
            offsetY = yErr,
            sizeErr = sizeErr,
            rollInDeadzone = rollIn,
            pitchInDeadzone = pitchIn,
            distanceInDeadzone = sizeIn,
            framingInDeadzone = xIn && yIn,
            lockProgress = lockProgress,
            suggestedZoom = suggestedZoom,
            totalError = totalError,
        )
    }

    /** :app reports the result of a focus tap or an autofocus lock. */
    fun reportFocusLocked(locked: Boolean) {
        if (!hasAutofocus) return
        focusConfirmed = locked
        if (locked) {
            focusAnchored = cxEma.seeded
            focusAnchorX = cxEma.value
            focusAnchorY = cyEma.value
        } else {
            focusAnchored = false
        }
        focusOk = locked || !cxEma.seeded
    }

    /**
     * :app reports the live digital zoom. Until this is called the engine
     * assumes no zoom is available and will never advise it.
     *
     * @param ratio current CONTROL_ZOOM_RATIO.
     * @param maxRatio upper bound of CONTROL_ZOOM_RATIO_RANGE.
     */
    fun reportZoom(ratio: Float, maxRatio: Float) {
        zoomRatio = ratio.coerceAtLeast(1f)
        maxZoomRatio = maxRatio.coerceAtLeast(1f)
    }

    /** Swap composition targets, e.g. when the shot type changes. */
    fun setProfile(newProfile: CompositionProfile) {
        if (newProfile == profile) return
        profile = newProfile
        // Old deadzone states describe a different composition; start clean.
        sizeGate.reset(); xGate.reset(); yGate.reset()
        dwellMs = 0L
        focusConfirmed = false
        focusAnchored = false
    }

    /** Forget everything. Used when the camera restarts or flips. */
    fun reset() {
        rollEma.reset(); pitchEma.reset(); rateEma.reset()
        cxEma.reset(); cyEma.reset(); wEma.reset(); hEma.reset()
        eyeYEma.reset(); gazeEma.reset()
        rollGate.reset(); pitchGate.reset(); sizeGate.reset()
        xGate.reset(); yGate.reset(); stabilityGate.reset()
        lastRawRoll = null
        lastRawPitch = null
        dwellMs = 0L
        shown = null
        shownMs = 0L
        focusConfirmed = false
        focusAnchored = false
        stepAdviceMs = 0L
        bestSizeErrAbs = Float.MAX_VALUE
        suggestedZoom = 1f
        totalError = 0f
        focusOk = true
        stabilityOk = false
        compositionOk = false
        alignment = AlignmentState.EMPTY
    }

    /** Build an instruction, inverting left/right when the preview is mirrored. */
    private fun instruction(verb: Verb, magnitude: Magnitude): Instruction {
        val v = if (mirrored) mirror(verb) else verb
        return Instruction(v, magnitude, textFor(v, magnitude))
    }

    private fun magnitudeFor(error: Float, deadzone: Float): Magnitude {
        val e = abs(error)
        return when {
            e <= deadzone * MAGNITUDE_MOVE_FACTOR -> Magnitude.NUDGE
            e <= deadzone * MAGNITUDE_BIG_FACTOR -> Magnitude.MOVE
            else -> Magnitude.BIG
        }
    }

    private fun excess(error: Float, deadzone: Float, scale: Float): Float =
        ((abs(error) - deadzone).coerceAtLeast(0f) / scale).coerceIn(0f, 1f)

    private fun sameSign(a: Float, b: Float): Boolean = (a > 0f && b > 0f) || (a < 0f && b < 0f)

    companion object {

        /** Front camera preview is flipped horizontally; only left/right invert. */
        fun mirror(verb: Verb): Verb = when (verb) {
            Verb.MOVE_LEFT -> Verb.MOVE_RIGHT
            Verb.MOVE_RIGHT -> Verb.MOVE_LEFT
            else -> verb
        }

        fun textFor(verb: Verb, magnitude: Magnitude): String {
            val base = when (verb) {
                Verb.LEVEL_CW -> "Rotate right to level"
                Verb.LEVEL_CCW -> "Rotate left to level"
                Verb.TILT_UP -> "Tilt up"
                Verb.TILT_DOWN -> "Tilt down"
                Verb.MOVE_UP -> "Move phone up"
                Verb.MOVE_DOWN -> "Move phone down"
                Verb.MOVE_LEFT -> "Move phone left"
                Verb.MOVE_RIGHT -> "Move phone right"
                Verb.STEP_CLOSER -> "Step closer"
                Verb.STEP_BACK -> "Step back"
                Verb.ZOOM_IN -> "Zoom in"
                Verb.ZOOM_OUT -> "Zoom out"
                Verb.TAP_FOCUS -> "Tap to focus"
                Verb.HOLD_STEADY -> "Hold steady"
                Verb.SEEKING -> "Looking for your subject"
                Verb.LOCKED -> "Locked"
            }
            return when {
                verb == Verb.LOCKED || verb == Verb.TAP_FOCUS ||
                    verb == Verb.HOLD_STEADY || verb == Verb.SEEKING -> base
                magnitude == Magnitude.NUDGE -> "$base a little"
                magnitude == Magnitude.BIG -> "$base a lot"
                else -> base
            }
        }
    }
}
