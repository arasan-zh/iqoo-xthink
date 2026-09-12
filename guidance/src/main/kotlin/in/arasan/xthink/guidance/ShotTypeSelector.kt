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
         * No faces is a landscape; one is a headshot; several are a group.
         * Half and full body need the mode tabs in v0.3 to reach, because face
         * size cannot distinguish "far away" from "wrong shot type" - the
         * engine's whole job is to correct face size, so inferring intent from
         * it would make the target chase the error.
         */
        fun shotTypeFor(faceCount: Int): ShotType = when {
            faceCount <= 0 -> ShotType.LANDSCAPE
            faceCount == 1 -> ShotType.HEADSHOT
            else -> ShotType.GROUP
        }
    }
}
