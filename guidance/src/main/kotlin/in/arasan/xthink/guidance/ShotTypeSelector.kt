package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.GuidanceConstants.SHOT_TYPE_HOLD_MS

/**
 * Turns a noisy per-frame face count into a stable [ShotType].
 *
 * Every continuous signal in this engine is deadzoned and smoothed, but shot
 * type is discrete and was not - so a detector dropping a face for two frames
 * swapped the whole composition target, reset the deadzone gates, and took the
 * lock dwell with it. On a real run the count went 0-2-1-2-2-1-1-2-0 inside ten
 * seconds and the lock never once left zero.
 *
 * The fix is the same idea as the deadzones: a change has to persist before it
 * is believed. A count that flickers and comes back never reaches the engine.
 */
class ShotTypeSelector(initial: ShotType = ShotType.LANDSCAPE) {

    var current: ShotType = initial
        private set

    private var candidate: ShotType = initial
    private var candidateMs = 0L

    /** How far the pending change has come, 0..1. Useful for a UI hint. */
    val switchProgress: Float
        get() = if (candidate == current) 0f
        else (candidateMs.toFloat() / SHOT_TYPE_HOLD_MS).coerceIn(0f, 1f)

    fun update(faceCount: Int, dtMs: Long): ShotType {
        val proposed = shotTypeFor(faceCount)
        val dt = if (dtMs < 0L) 0L else dtMs

        if (proposed == current) {
            // Whatever was pending has been withdrawn.
            candidate = current
            candidateMs = 0L
            return current
        }

        if (proposed == candidate) {
            candidateMs += dt
            if (candidateMs >= SHOT_TYPE_HOLD_MS) {
                current = proposed
                candidateMs = 0L
            }
        } else {
            candidate = proposed
            candidateMs = dt
        }
        return current
    }

    fun reset(to: ShotType = ShotType.LANDSCAPE) {
        current = to
        candidate = to
        candidateMs = 0L
    }

    companion object {
        /**
         * Portrait-only build: no faces is a landscape, any number of faces is
         * a portrait of the largest one.
         *
         * A portrait is HALF_BODY, not HEADSHOT. HEADSHOT's 0.45 face target
         * is a tight beauty crop - the face spans 18-63% of the frame and the
         * head top sits exactly on the headroom minimum - and on the phone it
         * kept saying "step closer" until the face swallowed the screen.
         * HALF_BODY's 0.25 is head-and-shoulders: face at 25-50%, natural
         * headroom around 18%, room for the person to exist in the picture.
         * That is what a well-composed photo of a person means.
         *
         * GROUP and FULL_BODY need mode tabs to reach deliberately - with
         * several faces in frame and no tab to say otherwise, a portrait of
         * whoever is nearest is the one unambiguous choice.
         */
        fun shotTypeFor(faceCount: Int): ShotType = when {
            faceCount <= 0 -> ShotType.LANDSCAPE
            else -> ShotType.HALF_BODY
        }
    }
}
