package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.GuidanceConstants.HAPTIC_INTERVAL_FAR_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.HAPTIC_INTERVAL_NEAR_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.HAPTIC_START_ERROR

/** What the phone should do this frame. NONE almost always. */
enum class HapticCue {
    NONE,
    /** One soft pulse of the closing-in rhythm. Strength scales with [LockHaptics.lastTickStrength]. */
    TICK,
    /** The lock landed. Once. */
    LOCK,
    /** The lock was lost. Once, softer. */
    UNLOCK,
}

/**
 * The haptic lock game: you feel the frame come together without looking.
 *
 * Pure. :app calls [update] once per engine tick and plays whatever comes
 * back. The rhythm is the whole idea - a Geiger counter that quickens as
 * [GuidanceEngine.totalError] falls:
 *
 *  - silent with no subject, and silent while the error is above
 *    [HAPTIC_START_ERROR]: a phone that buzzes while you are badly off is
 *    noise, not guidance;
 *  - below it, TICKs at an interval that slides from [HAPTIC_INTERVAL_FAR_MS]
 *    down to [HAPTIC_INTERVAL_NEAR_MS] as the error approaches zero;
 *  - exactly one LOCK when LOCKED lands, then silence while it holds - the
 *    quiet is the reward;
 *  - one UNLOCK when it is lost, so a blink that survives the grace is felt
 *    as nothing and a real loss is felt once.
 */
class LockHaptics {

    private var sinceTickMs = 0L
    private var wasLocked = false

    /** 0..1, how close the last TICK was to lock. Lets the driver scale amplitude. */
    var lastTickStrength: Float = 0f
        private set

    fun update(totalError: Float, verb: Verb, subjectPresent: Boolean, dtMs: Long): HapticCue {
        val dt = if (dtMs < 0L) 0L else dtMs

        if (!subjectPresent || verb == Verb.SEEKING) {
            val cue = if (wasLocked) HapticCue.UNLOCK else HapticCue.NONE
            wasLocked = false
            sinceTickMs = 0L
            return cue
        }

        if (verb == Verb.LOCKED) {
            if (wasLocked) return HapticCue.NONE
            wasLocked = true
            sinceTickMs = 0L
            return HapticCue.LOCK
        }

        if (wasLocked) {
            wasLocked = false
            sinceTickMs = 0L
            return HapticCue.UNLOCK
        }

        if (totalError >= HAPTIC_START_ERROR) {
            sinceTickMs = 0L
            return HapticCue.NONE
        }

        sinceTickMs += dt
        val interval = intervalFor(totalError)
        if (sinceTickMs < interval) return HapticCue.NONE

        sinceTickMs = 0L
        lastTickStrength = 1f - (totalError / HAPTIC_START_ERROR).coerceIn(0f, 1f)
        return HapticCue.TICK
    }

    fun reset() {
        sinceTickMs = 0L
        wasLocked = false
        lastTickStrength = 0f
    }

    companion object {
        /** Pulse interval for an error inside the game's range: linear, far to near. */
        fun intervalFor(totalError: Float): Long {
            val t = (totalError / HAPTIC_START_ERROR).coerceIn(0f, 1f)
            return (HAPTIC_INTERVAL_NEAR_MS + (HAPTIC_INTERVAL_FAR_MS - HAPTIC_INTERVAL_NEAR_MS) * t).toLong()
        }
    }
}
