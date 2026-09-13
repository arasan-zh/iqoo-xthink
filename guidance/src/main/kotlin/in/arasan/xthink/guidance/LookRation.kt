package `in`.arasan.xthink.guidance

/**
 * How often a model may look through the camera during a session the
 * user started: the first frame at once, then only when the frame has
 * changed materially and at least [minMs] have passed, and once anyway
 * every [maxMs] so a still scene is still confirmed. This is the rule
 * in CLAUDE.md as code - the same rationing WATCH uses, for any session.
 */
class LookRation(
    private val minMs: Long = MIN_MS,
    private val maxMs: Long = MAX_MS,
    private val change: Float = CHANGE,
) {
    private var lastLookMs = -1L
    private var lastGrid: IntArray? = null
    private var count = 0

    /** How many looks have been booked. */
    val looks: Int get() = count

    /** A frame is in, as a small luma grid. True: look now, and the look is booked. */
    fun look(now: Long, grid: IntArray): Boolean {
        val since = if (lastLookMs < 0) Long.MAX_VALUE else now - lastLookMs
        val prev = lastGrid
        val due = when {
            prev == null -> true
            since >= maxMs -> true
            since >= minMs && FrameDiff.difference(prev, grid) >= change -> true
            else -> false
        }
        if (!due) return false
        lastLookMs = now
        lastGrid = grid
        count++
        return true
    }

    companion object {
        const val MIN_MS = 15_000L
        const val MAX_MS = 45_000L
        const val CHANGE = 0.06f
    }
}
