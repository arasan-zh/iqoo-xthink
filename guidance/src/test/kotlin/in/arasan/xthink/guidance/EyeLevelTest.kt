package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pointing down at a seated subject: lower the phone, do not tilt up. */
class EyeLevelTest {

    private val json = """
        {
          "HEADSHOT":  { "targetSizeRatio": 0.45, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.06, "targetPitchDeg": 0.0 },
          "HALF_BODY": { "targetSizeRatio": 0.25, "sizeMin": 0.16, "sizeMax": 0.38, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
          "FULL_BODY": { "targetSizeRatio": 0.10, "sizeMin": 0.06, "sizeMax": 0.16, "targetEyeLineY": 0.28, "targetCx": 0.50, "headroomMin": 0.10, "targetPitchDeg": 0.0 },
          "GROUP":     { "targetSizeRatio": 0.18, "sizeMin": 0.08, "sizeMax": 0.70, "maxWidth": 0.85, "targetEyeLineY": 0.35, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
          "OBJECT":    { "targetSizeRatio": 0.55, "sizeMin": 0.25, "sizeMax": 0.75, "maxWidth": 0.85, "targetEyeLineY": 0.50, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0, "pitchToleranceDeg": 90.0 },
          "LANDSCAPE": { "targetSizeRatio": 0.00, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.00, "targetPitchDeg": 0.0 }
        }
    """.trimIndent()
    private val half = CompositionProfile.parse(json, ShotType.HALF_BODY)

    @Test
    fun `camera pointing down at a framed subject - move the phone down, not tilt up`() {
        val e = GuidanceEngine(half)
        val box = SubjectBox(cx = 0.5f, cy = 0.45f, w = 0.2f, h = 0.25f)
        val eyes = EyeLine(y = 0.33f, gazeDx = 0f)
        var verb: Verb? = null
        repeat(20) { e.reportFocusLocked(true); verb = e.update(Attitude(rollDeg = 0f, pitchDeg = -15f), box, eyes, 50L).verb }
        assertEquals(Verb.MOVE_DOWN, verb)
    }

    @Test
    fun `camera pointing up at a tall subject - move the phone up`() {
        val e = GuidanceEngine(half)
        val box = SubjectBox(cx = 0.5f, cy = 0.45f, w = 0.2f, h = 0.25f)
        var verb: Verb? = null
        repeat(20) { e.reportFocusLocked(true); verb = e.update(Attitude(0f, 15f), box, EyeLine(0.33f, 0f), 50L).verb }
        assertEquals(Verb.MOVE_UP, verb)
    }

    @Test
    fun `no subject - the level is a tilt`() {
        val e = GuidanceEngine(CompositionProfile.parse(json, ShotType.LANDSCAPE))
        var verb: Verb? = null
        repeat(20) { verb = e.update(Attitude(0f, -15f), null, null, 50L).verb }
        assertEquals(Verb.TILT_UP, verb)
    }

    @Test
    fun `an arm that cannot reach gets the tilt after the stall`() {
        val e = GuidanceEngine(half)
        val box = SubjectBox(cx = 0.5f, cy = 0.45f, w = 0.2f, h = 0.25f)
        var verb: Verb? = null
        val frames = (GuidanceConstants.VERTICAL_STALL_MS / 50L).toInt() + 40
        repeat(frames) { e.reportFocusLocked(true); verb = e.update(Attitude(0f, -15f), box, EyeLine(0.33f, 0f), 50L).verb }
        assertTrue("after the stall, got $verb", verb == Verb.TILT_UP || verb == Verb.LOCKED)
    }
}
