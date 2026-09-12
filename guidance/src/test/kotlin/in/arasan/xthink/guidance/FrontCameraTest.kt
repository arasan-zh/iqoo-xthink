package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.Fixtures.LEVEL
import `in`.arasan.xthink.guidance.Fixtures.box
import `in`.arasan.xthink.guidance.Fixtures.eyes
import `in`.arasan.xthink.guidance.Fixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The selfie lens: mirrored preview, +Z optical axis, fixed focus. */
class FrontCameraTest {

    /** Same generator as AttitudeMathTest: upright portrait, rear lens level facing North. */
    private fun poseMatrix(rollDeg: Float, pitchDeg: Float): FloatArray {
        val p = Math.toRadians(pitchDeg.toDouble()).toFloat()
        val r = Math.toRadians(rollDeg.toDouble()).toFloat()
        val x0 = floatArrayOf(1f, 0f, 0f)
        val y0 = floatArrayOf(0f, -sin(p), cos(p))
        val z0 = floatArrayOf(0f, -cos(p), -sin(p))
        val x = FloatArray(3) { x0[it] * cos(r) - y0[it] * sin(r) }
        val y = FloatArray(3) { x0[it] * sin(r) + y0[it] * cos(r) }
        return floatArrayOf(x[0], y[0], z0[0], x[1], y[1], z0[1], x[2], y[2], z0[2])
    }

    @Test
    fun `the front lens reports the opposite pitch to the rear lens, same roll`() {
        // A pose where the REAR lens is aimed 20 degrees up: the front lens,
        // on the other side of the phone, is aimed 20 degrees down.
        val m = poseMatrix(rollDeg = 15f, pitchDeg = 20f)
        val rear = AttitudeMath.fromRotationMatrix(m, frontFacing = false)
        val front = AttitudeMath.fromRotationMatrix(m, frontFacing = true)
        assertEquals(20f, rear.pitchDeg, 0.05f)
        assertEquals(-20f, front.pitchDeg, 0.05f)
        assertEquals("clockwise is defined from the screen side for both", rear.rollDeg, front.rollDeg, 0.05f)
    }

    @Test
    fun `mirroring flips x and gaze, nothing else`() {
        val b = FrameMapping.mirrorX(box(cx = 0.3f, cy = 0.4f, h = 0.25f, w = 0.19f))
        assertEquals(0.7f, b.cx, 1e-6f)
        assertEquals(0.4f, b.cy, 1e-6f)
        assertEquals(0.25f, b.h, 1e-6f)
        val e = FrameMapping.mirrorX(eyes(y = 0.33f, gazeDx = 0.5f))
        assertEquals(-0.5f, e.gazeDx, 1e-6f)
        assertEquals(0.33f, e.y, 1e-6f)
    }

    @Test
    fun `a face on the left of the mirror is told to move the phone RIGHT`() {
        // Sensor sees the face on its right (cx 0.7). The preview shows it on
        // the left (0.3). Physically the phone must move right - toward the
        // photographer's right - for the face to travel toward centre in a
        // mirror. The mirrored engine on mirrored coordinates says exactly that.
        val engine = GuidanceEngine(profile(ShotType.HALF_BODY), mirrored = true, hasAutofocus = false)
        val sensorBox = box(cx = 0.7f, cy = 0.355f, h = 0.25f, w = 0.19f)
        val verb = engine.update(LEVEL, FrameMapping.mirrorX(sensorBox), FrameMapping.mirrorX(eyes()), 33L).verb
        assertEquals(Verb.MOVE_RIGHT, verb)
        // And the target the overlay draws is in preview space, where the face is.
        assertTrue(engine.alignment.hasTarget)
        assertEquals(0.5f, engine.alignment.targetCx, 1e-6f)
    }

    @Test
    fun `the selfie lens never asks for a focus tap and locks without one`() {
        val engine = GuidanceEngine(profile(ShotType.HALF_BODY), mirrored = true, hasAutofocus = false)
        val framed = box(cx = 0.5f, cy = 0.355f, h = 0.25f, w = 0.19f)
        val verbs = (1..30).map { engine.update(LEVEL, framed, eyes(), 33L).verb }
        assertTrue(verbs.none { it == Verb.TAP_FOCUS })
        assertTrue(verbs.contains(Verb.LOCKED))
    }

    @Test
    fun `lead room is on the correct side in the mirror`() {
        // Head turned toward sensor-LEFT appears turned toward preview-RIGHT.
        // Lead room must open on the preview-right, i.e. the target moves to
        // the LEFT third of the preview.
        val engine = GuidanceEngine(profile(ShotType.HALF_BODY), mirrored = true, hasAutofocus = false)
        val sensorEyes = eyes(y = 0.33f, gazeDx = -0.6f)
        engine.update(LEVEL, box(cx = 0.5f, cy = 0.355f, h = 0.25f, w = 0.19f), FrameMapping.mirrorX(sensorEyes), 33L)
        assertEquals(GuidanceConstants.LEAD_ROOM_CX_LOOKING_RIGHT, engine.alignment.targetCx, 1e-6f)
    }
}
