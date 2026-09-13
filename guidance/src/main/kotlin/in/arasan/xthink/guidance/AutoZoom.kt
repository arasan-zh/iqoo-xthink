package `in`.arasan.xthink.guidance

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The lens, steered at the text. Each reading of the Mac screen comes
 * with the box the text filled; the next zoom makes that box fill
 * [TARGET] of the frame's longer side - closer when the text is small,
 * back when it spills - in steps no bigger than [STEP_MAX], so a
 * misread does not throw the lens across its range. Nothing readable
 * for a few readings in a row means the screen was lost: back out
 * toward 1x to find it. Pure; :app sets the zoom and the focus.
 */
object AutoZoom {

    /**
     * The zoom to set next, or null to leave it.
     *
     * @param current the zoom now.
     * @param text the box the text filled, fractions of the frame, or null when none was read.
     * @param misses readings in a row with nothing readable.
     * @param maxZoom the lens's own ceiling.
     */
    fun next(current: Float, text: CropRect?, misses: Int, maxZoom: Float): Float? {
        val ceiling = min(maxZoom, MAX)
        if (text == null) {
            if (misses < MISSES_TO_BACK_OUT || current <= 1f) return null
            return (current / STEP_MAX).coerceAtLeast(1f)
        }
        val fill = max(text.width, text.height)
        if (fill <= 0.01f) return null
        val want = (current * TARGET / fill).coerceIn(1f, ceiling)
        val ratio = want / current
        val stepped = when {
            ratio > STEP_MAX -> current * STEP_MAX
            ratio < 1f / STEP_MAX -> current / STEP_MAX
            else -> want
        }.coerceIn(1f, ceiling)
        return if (abs(stepped / current - 1f) < MIN_CHANGE) null else stepped
    }

    /** Where to focus: the middle of the text. */
    fun focus(text: CropRect): Pair<Float, Float> = ((text.left + text.right) / 2f) to ((text.top + text.bottom) / 2f)

    /** The text should fill this much of the frame's longer side. */
    const val TARGET = 0.8f
    /** Never past this, whatever the lens offers: beyond it the picture is digital mush. */
    const val MAX = 6f
    /** One reading may move the zoom by at most this factor. */
    const val STEP_MAX = 1.6f
    /** A change smaller than this is not worth touching the lens for. */
    const val MIN_CHANGE = 0.08f
    /** Nothing readable this many times in a row: the screen is lost, back out. */
    const val MISSES_TO_BACK_OUT = 3
}
