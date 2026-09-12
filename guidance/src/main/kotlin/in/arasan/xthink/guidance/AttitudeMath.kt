package `in`.arasan.xthink.guidance

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Turns an Android rotation matrix into the [Attitude] the engine expects.
 *
 * :app calls `SensorManager.getRotationMatrixFromVector()` on a
 * TYPE_GAME_ROTATION_VECTOR event and hands the nine floats here. Keeping the
 * trigonometry in :guidance means the part that is easy to get wrong is the
 * part that is unit tested - the Android side is reduced to one system call.
 *
 * ## The coordinate systems
 *
 * The matrix maps DEVICE coordinates to WORLD coordinates (East, North, Up),
 * row-major, so the columns are where the device axes point in the world:
 *
 * ```
 *   R[0] R[1] R[2]      device X (screen right) -> (R[0], R[3], R[6])
 *   R[3] R[4] R[5]      device Y (screen up)    -> (R[1], R[4], R[7])
 *   R[6] R[7] R[8]      device Z (out of screen)-> (R[2], R[5], R[8])
 * ```
 *
 * Device Z points out of the screen towards the photographer, so the REAR
 * camera looks along -Z. Its optical axis in world coordinates is therefore
 * `-(R[2], R[5], R[8])`.
 */
object AttitudeMath {

    /**
     * Below this, the rear camera is pointing so close to straight up or down
     * that roll stops being meaningful - every roll angle looks the same when
     * the lens faces the sky. [isRollReliable] reports it so the UI can stop
     * showing a horizon rather than show a spinning one.
     */
    const val ROLL_RELIABLE_MIN = 0.08f

    /**
     * @param r nine floats from `SensorManager.getRotationMatrixFromVector`.
     * @param frontFacing true for the selfie lens, which looks along +Z - out
     *        of the screen, at the photographer - instead of the rear lens's
     *        -Z. Its elevation is therefore the vertical component of
     *        +(R[2], R[5], R[8]): pitch changes sign, roll does not, because
     *        "clockwise as the photographer sees it" is defined from the
     *        screen side for both lenses.
     */
    fun fromRotationMatrix(r: FloatArray, frontFacing: Boolean = false): Attitude {
        require(r.size >= 9) { "expected a 3x3 rotation matrix, got ${r.size} floats" }

        // Elevation of the camera's optical axis above the horizon: the
        // vertical component of -(R[2], R[5], R[8]) for the rear lens,
        // +(...) for the front.
        val axisUp = if (frontFacing) r[8] else -r[8]
        val pitch = asin(axisUp.coerceIn(-1f, 1f))

        // Roll is how far the screen-right axis has tilted off horizontal.
        // Measuring it against the screen-up axis with atan2 keeps it correct
        // through a full rotation and well conditioned while the camera is
        // anywhere near the horizon.
        val roll = atan2(-r[6], r[7])

        return Attitude(
            rollDeg = Math.toDegrees(roll.toDouble()).toFloat(),
            pitchDeg = Math.toDegrees(pitch.toDouble()).toFloat(),
        )
    }

    /**
     * False when the camera points so near vertical that the roll angle is
     * numerically meaningless. Both terms of the roll atan2 collapse there.
     */
    fun isRollReliable(r: FloatArray): Boolean {
        require(r.size >= 9) { "expected a 3x3 rotation matrix, got ${r.size} floats" }
        return abs(r[6]) + abs(r[7]) >= ROLL_RELIABLE_MIN
    }
}
