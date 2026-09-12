package `in`.arasan.xthink.guidance

import kotlin.math.abs

/**
 * Exponential moving average. Seeds on the first sample so a steady input is
 * reported exactly, with no warm-up ramp from zero.
 */
class Ema(private val alpha: Float) {

    var value: Float = 0f
        private set

    var seeded: Boolean = false
        private set

    fun update(raw: Float): Float {
        value = if (seeded) value + alpha * (raw - value) else raw
        seeded = true
        return value
    }

    fun reset() {
        value = 0f
        seeded = false
    }
}

/**
 * EMA over an angle in degrees, taking the short way round so a sample pair
 * straddling +/-180 does not spin the average the long way.
 */
class AngleEma(private val alpha: Float) {

    var value: Float = 0f
        private set

    var seeded: Boolean = false
        private set

    fun update(rawDeg: Float): Float {
        value = if (seeded) normalizeDeg(value + alpha * shortestDeltaDeg(rawDeg, value)) else normalizeDeg(rawDeg)
        seeded = true
        return value
    }

    fun reset() {
        value = 0f
        seeded = false
    }
}

/** Signed shortest angular distance from [from] to [to], in -180..180. */
fun shortestDeltaDeg(to: Float, from: Float): Float = normalizeDeg(to - from)

/** Wrap an angle into -180..180. */
fun normalizeDeg(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/**
 * A deadzone with hysteresis.
 *
 * Entering costs less than leaving: the error must fall to [enterThreshold] to
 * be judged in-zone, but must then climb past
 * `enterThreshold * exitMultiplier` before it is judged out again. That gap is
 * what stops an instruction flickering on and off at the boundary.
 *
 * Starts out-of-zone: nothing is in the deadzone until measured to be.
 */
class HysteresisGate(
    val enterThreshold: Float,
    val exitMultiplier: Float = GuidanceConstants.HYSTERESIS_EXIT_MULTIPLIER,
) {

    val exitThreshold: Float get() = enterThreshold * exitMultiplier

    var inside: Boolean = false
        private set

    /** Feed an absolute error. Returns true when it is inside the deadzone. */
    fun update(absError: Float): Boolean {
        val e = abs(absError)
        inside = if (inside) e <= exitThreshold else e <= enterThreshold
        return inside
    }

    fun reset() {
        inside = false
    }
}
