package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AttitudeMathTest {

    // ------------------------------------------------------------------
    // Build the matrix Android would hand us, from a known pose, so the test
    // asserts a round trip rather than a hand-copied pile of decimals.
    // ------------------------------------------------------------------

    /**
     * Reference pose: phone upright in portrait, rear camera level and facing
     * North. World axes are East, North, Up.
     *
     *   device X (screen right) -> East
     *   device Y (screen up)    -> Up
     *   device Z (out of screen)-> South, because the rear camera looks North
     *
     * Pitch rotates about East so the camera rises; roll then turns the phone
     * clockwise about its own optical axis, as the photographer sees it.
     */
    private fun poseMatrix(rollDeg: Float, pitchDeg: Float): FloatArray {
        val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val r = Math.toRadians(rollDeg.toDouble()).toFloat()

        // Pitch first, about the East axis.
        val x0 = floatArrayOf(1f, 0f, 0f)
        val y0 = floatArrayOf(0f, -sin(p), cos(p))
        val z0 = floatArrayOf(0f, -cos(p), -sin(p))

        // Then roll, about the optical axis.
        val x = FloatArray(3) { x0[it] * cos(r) - y0[it] * sin(r) }
        val y = FloatArray(3) { x0[it] * sin(r) + y0[it] * cos(r) }

        // Row-major: the columns are where the device axes land in the world.
        return floatArrayOf(
            x[0], y[0], z0[0],
            x[1], y[1], z0[1],
            x[2], y[2], z0[2],
        )
    }

    @Test
    fun `the test's own pose matrices are orthonormal`() {
        // If this fails, every other assertion here is meaningless.
        for (roll in -60..60 step 30) {
            for (pitch in -60..60 step 30) {
                val m = poseMatrix(roll.toFloat(), pitch.toFloat())
                val cols = listOf(
                    floatArrayOf(m[0], m[3], m[6]),
                    floatArrayOf(m[1], m[4], m[7]),
                    floatArrayOf(m[2], m[5], m[8]),
                )
                for (c in cols) {
                    assertEquals("unit length", 1f, c[0] * c[0] + c[1] * c[1] + c[2] * c[2], 1e-4f)
                }
                for (i in 0..2) {
                    for (j in i + 1..2) {
                        val dot = (0..2).sumOf { (cols[i][it] * cols[j][it]).toDouble() }.toFloat()
                        assertEquals("columns $i,$j orthogonal", 0f, dot, 1e-4f)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // The conversion itself
    // ------------------------------------------------------------------

    @Test
    fun `the reference pose is level`() {
        val a = AttitudeMath.fromRotationMatrix(poseMatrix(0f, 0f))
        assertEquals(0f, a.rollDeg, 1e-3f)
        assertEquals(0f, a.pitchDeg, 1e-3f)
    }

    @Test
    fun `round trips every combination of roll and pitch`() {
        for (roll in -170..170 step 10) {
            for (pitch in -80..80 step 10) {
                val a = AttitudeMath.fromRotationMatrix(poseMatrix(roll.toFloat(), pitch.toFloat()))
                assertEquals("roll at ($roll, $pitch)", roll.toFloat(), a.rollDeg, 0.05f)
                assertEquals("pitch at ($roll, $pitch)", pitch.toFloat(), a.pitchDeg, 0.05f)
            }
        }
    }

    @Test
    fun `rolling the phone clockwise reads positive`() {
        // Positive roll is what GuidanceEngine answers with LEVEL_CCW.
        val a = AttitudeMath.fromRotationMatrix(poseMatrix(rollDeg = 25f, pitchDeg = 0f))
        assertTrue(a.rollDeg > 0f)
        assertEquals(25f, a.rollDeg, 0.05f)
    }

    @Test
    fun `aiming the camera above the horizon reads positive pitch`() {
        val a = AttitudeMath.fromRotationMatrix(poseMatrix(rollDeg = 0f, pitchDeg = 30f))
        assertTrue(a.pitchDeg > 0f)
        assertEquals(30f, a.pitchDeg, 0.05f)
    }

    @Test
    fun `a phone flat on the table has the camera pointing straight down`() {
        // Identity: screen up, so the rear camera faces the table.
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val a = AttitudeMath.fromRotationMatrix(identity)
        assertEquals(-90f, a.pitchDeg, 1e-3f)
    }

    @Test
    fun `roll survives past the 180 degree wrap without jumping`() {
        val near = AttitudeMath.fromRotationMatrix(poseMatrix(179f, 0f)).rollDeg
        val past = AttitudeMath.fromRotationMatrix(poseMatrix(-179f, 0f)).rollDeg
        assertEquals(179f, near, 0.05f)
        assertEquals(-179f, past, 0.05f)
        // They are 2 degrees apart the short way, which is what AngleEma relies on.
        assertEquals(2f, abs(shortestDeltaDeg(past, near)), 0.05f)
    }

    // ------------------------------------------------------------------
    // Roll reliability near vertical
    // ------------------------------------------------------------------

    @Test
    fun `roll is reliable while shooting anywhere near the horizon`() {
        for (pitch in -60..60 step 15) {
            assertTrue(
                "pitch $pitch should still give a usable horizon",
                AttitudeMath.isRollReliable(poseMatrix(0f, pitch.toFloat())),
            )
        }
    }

    @Test
    fun `roll is unreliable with the camera pointing at the sky`() {
        // Camera straight up: every roll angle looks identical, so the UI
        // should stop drawing a horizon rather than draw a spinning one.
        assertFalse(AttitudeMath.isRollReliable(poseMatrix(0f, 90f)))
        assertFalse(AttitudeMath.isRollReliable(poseMatrix(0f, -90f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a matrix that is too short`() {
        AttitudeMath.fromRotationMatrix(floatArrayOf(1f, 0f, 0f))
    }

    // ------------------------------------------------------------------
    // The whole chain: sensor matrix -> Attitude -> instruction
    // ------------------------------------------------------------------

    @Test
    fun `a clockwise-rolled phone makes the engine say rotate left`() {
        val engine = GuidanceEngine(Fixtures.profile(ShotType.HEADSHOT))
        val attitude = AttitudeMath.fromRotationMatrix(poseMatrix(rollDeg = 12f, pitchDeg = 0f))
        val instruction = engine.update(attitude, Fixtures.box(), Fixtures.eyes(), 100L)
        assertEquals(Verb.LEVEL_CCW, instruction.verb)
    }

    @Test
    fun `a camera aimed high makes the engine say tilt down`() {
        val engine = GuidanceEngine(Fixtures.profile(ShotType.HEADSHOT))
        val attitude = AttitudeMath.fromRotationMatrix(poseMatrix(rollDeg = 0f, pitchDeg = 20f))
        val instruction = engine.update(attitude, Fixtures.box(), Fixtures.eyes(y = 0.33f), 100L)
        assertEquals(Verb.TILT_DOWN, instruction.verb)
    }
}
