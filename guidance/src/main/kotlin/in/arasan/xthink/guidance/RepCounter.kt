package `in`.arasan.xthink.guidance

import kotlin.math.acos
import kotlin.math.sqrt

/** A body landmark, as fractions of the frame. */
data class Joint(val x: Float, val y: Float)

/** Which exercise, by the joint whose angle tells the story. */
enum class Exercise(val label: String, val downBelowDeg: Float, val upAboveDeg: Float) {
    /** Knee angle: hip - knee - ankle. Standing ~175°, a proper squat under 100°. */
    SQUAT("Squats", downBelowDeg = 100f, upAboveDeg = 160f),

    /** Elbow angle: shoulder - elbow - wrist. Arms straight ~170°, chest down under 95°. */
    PUSHUP("Push-ups", downBelowDeg = 95f, upAboveDeg = 155f),
}

object JointAngles {
    /** The angle at [b] between [a] and [c], in degrees; 180 when straight. */
    fun angle(a: Joint, b: Joint, c: Joint): Float {
        val abx = a.x - b.x; val aby = a.y - b.y
        val cbx = c.x - b.x; val cby = c.y - b.y
        val dot = abx * cbx + aby * cby
        val mag = sqrt(abx * abx + aby * aby) * sqrt(cbx * cbx + cby * cby)
        if (mag == 0f) return 180f
        return Math.toDegrees(acos((dot / mag).coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}

/**
 * Counts repetitions from a joint angle. A rep is DOWN (angle under the
 * exercise's floor) followed by UP (angle over its ceiling); the gap
 * between floor and ceiling is the hysteresis that keeps a wobble at the
 * bottom from counting twice. Half reps - never all the way down - do not
 * count, and neither does anything faster than a human can move.
 */
class RepCounter(val exercise: Exercise) {

    enum class Phase { WAITING, UP, DOWN }

    var count: Int = 0
        private set

    var phase: Phase = Phase.WAITING
        private set

    /** The most recent angle fed, for the display. */
    var angleDeg: Float = Float.NaN
        private set

    private var downMs = 0L

    /** Feed the joint angle for a frame. Returns true on the frame a rep completes. */
    fun update(angleDeg: Float, dtMs: Long): Boolean {
        this.angleDeg = angleDeg
        val dt = if (dtMs < 0L) 0L else dtMs
        when (phase) {
            Phase.WAITING -> if (angleDeg >= exercise.upAboveDeg) phase = Phase.UP
            Phase.UP -> if (angleDeg <= exercise.downBelowDeg) { phase = Phase.DOWN; downMs = 0L }
            Phase.DOWN -> {
                downMs += dt
                if (angleDeg >= exercise.upAboveDeg) {
                    phase = Phase.UP
                    if (downMs >= MIN_DOWN_MS) { count++; return true }
                }
            }
        }
        return false
    }

    fun reset() { count = 0; phase = Phase.WAITING; downMs = 0L; angleDeg = Float.NaN }

    companion object {
        /** The bottom of a rep lasts at least this long; anything quicker is noise. */
        const val MIN_DOWN_MS = 250L
    }
}
