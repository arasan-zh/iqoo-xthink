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

    private var capturedThisLock = false
    private var sinceCaptureMs = AUTO_CAPTURE_COOLDOWN_MS

    /** Milliseconds until another automatic shot is allowed; 0 when ready. */
    val cooldownRemainingMs: Long
        get() = (AUTO_CAPTURE_COOLDOWN_MS - sinceCaptureMs).coerceAtLeast(0L)

    fun update(verb: Verb, stabilityOk: Boolean, subjectPresent: Boolean, dtMs: Long): Boolean {
        val dt = if (dtMs < 0L) 0L else dtMs
        sinceCaptureMs = (sinceCaptureMs + dt).coerceAtMost(AUTO_CAPTURE_COOLDOWN_MS)

        if (verb != Verb.LOCKED || !subjectPresent) {
            // Leaving the lock re-arms for the next acquisition.
            capturedThisLock = false
            return false
        }
        if (capturedThisLock) return false
        if (!stabilityOk) return false
        if (sinceCaptureMs < AUTO_CAPTURE_COOLDOWN_MS) return false

        capturedThisLock = true
        sinceCaptureMs = 0L
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
    }
}
