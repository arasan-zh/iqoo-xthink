package `in`.arasan.xthink.guidance

import kotlin.math.acos
import kotlin.math.sqrt

/** A body landmark, as fractions of the frame. */
data class Joint(val x: Float, val y: Float)

/** When both sides of the body are in frame, which angle counts. */
enum class SidePick {
    /** Both move together (a squat, a jumping jack): the average. */
    AVERAGE,
    /** One moves, the other stands (a knee raise): the more bent of the two. */
    MOST_BENT,
}

/**
 * Which exercise, by the joint whose angle tells the story. A rep is DOWN
 * (under the floor) then UP (over the ceiling). Each is counted from a
 * propped-up phone facing a standing person - the one view the pose
 * model gives clean joints in; push-ups, on the floor, were not.
 */
enum class Exercise(val label: String, val downBelowDeg: Float, val upAboveDeg: Float, val side: SidePick = SidePick.AVERAGE) {
    /** Knee angle: hip - knee - ankle. Standing ~175°, a proper squat under 100°. */
    SQUAT("Squats", downBelowDeg = 100f, upAboveDeg = 160f),
    /** Shoulder angle: hip - shoulder - wrist. Arms down ~20°, arms overhead ~160°: DOWN is arms down, UP is arms up. */
    JUMPING_JACK("Jumping jacks", downBelowDeg = 45f, upAboveDeg = 130f),
    /** Hip angle: shoulder - hip - knee, of the raised leg. Standing ~175°, knee at hip height ~95°. */
    KNEE_RAISE("Knee raises", downBelowDeg = 115f, upAboveDeg = 160f, side = SidePick.MOST_BENT),
}

object JointAngles {
    /** The angle that counts from the two sides, per the exercise; null when neither side is in frame. */
    fun pick(left: Float?, right: Float?, side: SidePick): Float? = when {
        left != null && right != null -> if (side == SidePick.AVERAGE) (left + right) / 2f else minOf(left, right)
        else -> left ?: right
    }

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
