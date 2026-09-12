package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.GuidanceConstants.SHOT_TYPE_HOLD_MS

/**
 * Turns a noisy per-frame face count and face size into a stable [ShotType].
 *
 * Every continuous signal in this engine is deadzoned and smoothed, but shot
 * type is discrete and was not - so a detector dropping a face for two frames
 * swapped the whole composition target, reset the deadzone gates, and took the
 * lock dwell with it. On a real run the count went 0-2-1-2-2-1-1-2-0 inside ten
 * seconds and the lock never once left zero.
 *
 * The fix is the same idea as the deadzones: a change has to persist before it
 * is believed. A count or size that flickers and comes back never reaches the
 * engine.
 *
 * ## Portrait is a range of scales, not one distance
 *
 * A portrait can be full body, hip level, or head and shoulders. The
 * photographer chooses; the app coaches the rest. So with a face in frame the
 * selector picks whichever portrait profile's accepted size band contains the
 * face, and inside that band the engine treats distance as correct. Only at
 * the extremes - too small to be a portrait of anyone, or a face filling the
 * frame - does distance advice appear, and it comes from the band's own edge.
 *
 * The earlier worry that inferring shot type from face size would make the
 * target chase the error no longer applies: inside the portrait range the
 * target does not chase, it accepts.
 *
 * @param profiles the parsed composition profiles; the portrait bands come
 *        from their `sizeMin..sizeMax`.
 */
class ShotTypeSelector(
    private val profiles: Map<ShotType, CompositionProfile>,
    initial: ShotType = ShotType.LANDSCAPE,
) {

    var current: ShotType = initial
        private set

    /** The photographer's chosen mode. Changing it is deliberate, so it takes effect at once. */
    var mode: CoachMode = CoachMode.PORTRAIT
        private set

    private var lastCount = 0
    private var lastHeight: Float? = null

    /**
     * Switch modes. No hysteresis: a tap on a mode is not detector noise, and
     * making the photographer wait half a second for it would feel broken.
     */
    fun setMode(newMode: CoachMode) {
        if (newMode == mode) return
        mode = newMode
        reset(shotTypeFor(lastCount, lastHeight, newMode))
    }

    private var candidate: ShotType = initial
    private var candidateMs = 0L

    /** How far the pending change has come, 0..1. Useful for a UI hint. */
    val switchProgress: Float
        get() = if (candidate == current) 0f
        else (candidateMs.toFloat() / SHOT_TYPE_HOLD_MS).coerceIn(0f, 1f)

    /**
     * @param faceCount faces detected this frame.
     * @param dtMs elapsed since the previous frame.
     * @param faceHeight height of the chosen face as a fraction of the frame,
     *        or null when there is no face. Decides which portrait scale.
     */
    fun update(faceCount: Int, dtMs: Long, faceHeight: Float? = null): ShotType {
        lastCount = faceCount
        lastHeight = faceHeight
        val proposed = shotTypeFor(faceCount, faceHeight)
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

    /**
     * No face is a landscape. A face is a portrait at whichever scale its size
     * falls in, walking the portrait ladder from widest to tightest; a face
     * smaller than the widest band is still FULL_BODY (and will be told to
     * step closer), one larger than the tightest is still the tightest (and
     * will be told to step back). No height at all defaults to HALF_BODY.
     *
     * In PORTRAIT, several faces are still a portrait of whoever is nearest -
     * the one unambiguous choice without a tab saying otherwise. GROUP is
     * reached by choosing WIDE.
     */
    /**
     * @param faceCount subjects found this frame. Faces in PORTRAIT and WIDE;
     *        in OBJECT it is 1 when the detector found something, else 0.
     */
    fun shotTypeFor(faceCount: Int, faceHeight: Float? = null, forMode: CoachMode = mode): ShotType {
        if (faceCount <= 0) return ShotType.LANDSCAPE
        // OBJECT: whatever the detector found is the subject, centred.
        if (forMode == CoachMode.OBJECT) return ShotType.OBJECT
        // WIDE: every face is the subject, framed as one group. A single
        // person in a wide venue is a group of one - still GROUP, so the
        // photographer gets the wider composition they asked for.
        if (forMode == CoachMode.WIDE) return ShotType.GROUP
        val h = faceHeight ?: return ShotType.HALF_BODY
        for (type in PORTRAIT_LADDER) {
            val band = profiles[type] ?: continue
            if (h <= band.sizeMax) return type
        }
        return PORTRAIT_LADDER.last()
    }

    companion object {
        /** Widest to tightest. HEADSHOT is deliberately absent: a face that fills the frame is not a portrait. */
        val PORTRAIT_LADDER = listOf(ShotType.FULL_BODY, ShotType.HALF_BODY)
    }
}
