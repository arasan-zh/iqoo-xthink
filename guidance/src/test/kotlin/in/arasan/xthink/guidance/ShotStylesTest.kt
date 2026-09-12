package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotStylesTest {

    private val json = """
        {
          "HEADSHOT":  { "targetSizeRatio": 0.45, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.06, "targetPitchDeg": 0.0 },
          "HALF_BODY": { "targetSizeRatio": 0.25, "sizeMin": 0.16, "sizeMax": 0.38, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
          "FULL_BODY": { "targetSizeRatio": 0.10, "sizeMin": 0.06, "sizeMax": 0.16, "targetEyeLineY": 0.28, "targetCx": 0.50, "headroomMin": 0.10, "targetPitchDeg": 0.0 },
          "GROUP":     { "targetSizeRatio": 0.18, "sizeMin": 0.08, "sizeMax": 0.70, "maxWidth": 0.85, "targetEyeLineY": 0.35, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0, "centerTolerance": 2.0, "focusRequired": 0.0 },
          "OBJECT":    { "targetSizeRatio": 0.55, "sizeMin": 0.25, "sizeMax": 0.75, "maxWidth": 0.85, "targetEyeLineY": 0.50, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0, "pitchToleranceDeg": 90.0 },
          "LANDSCAPE": { "targetSizeRatio": 0.00, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.00, "targetPitchDeg": 0.0 },
          "CLOSE_UP":  { "targetSizeRatio": 0.45, "sizeMin": 0.36, "sizeMax": 0.56, "targetEyeLineY": 0.36, "targetCx": 0.50, "headroomMin": 0.03, "targetPitchDeg": 0.0 },
          "EXTREME_CLOSE_UP": { "targetSizeRatio": 0.68, "sizeMin": 0.55, "sizeMax": 0.90, "targetEyeLineY": 0.42, "targetCx": 0.50, "headroomMin": 0.0, "targetPitchDeg": 0.0 },
          "MEDIUM_SHOT": { "targetSizeRatio": 0.25, "sizeMin": 0.16, "sizeMax": 0.38, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
          "WIDE_SHOT": { "targetSizeRatio": 0.08, "sizeMin": 0.04, "sizeMax": 0.12, "targetEyeLineY": 0.26, "targetCx": 0.50, "headroomMin": 0.14, "targetPitchDeg": 0.0 },
          "LOW_ANGLE": { "targetSizeRatio": 0.25, "sizeMin": 0.14, "sizeMax": 0.42, "targetEyeLineY": 0.36, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 22.0 },
          "HIGH_ANGLE": { "targetSizeRatio": 0.25, "sizeMin": 0.14, "sizeMax": 0.42, "targetEyeLineY": 0.40, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": -30.0 },
          "DUTCH_ANGLE": { "targetSizeRatio": 0.25, "sizeMin": 0.16, "sizeMax": 0.38, "targetEyeLineY": 0.33, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": 0.0, "targetRollDeg": 15.0 },
          "BIRDS_EYE": { "targetSizeRatio": 0.14, "sizeMin": 0.06, "sizeMax": 0.32, "targetEyeLineY": 0.50, "targetCx": 0.50, "headroomMin": 0.0, "targetPitchDeg": -78.0, "pitchToleranceDeg": 12.0 },
          "OVER_SHOULDER": { "targetSizeRatio": 0.25, "sizeMin": 0.14, "sizeMax": 0.42, "targetEyeLineY": 0.35, "targetCx": 0.66, "headroomMin": 0.08, "targetPitchDeg": 0.0 },
          "POV": { "targetSizeRatio": 0.22, "sizeMin": 0.12, "sizeMax": 0.40, "targetEyeLineY": 0.40, "targetCx": 0.50, "headroomMin": 0.08, "targetPitchDeg": -12.0 }
        }
    """.trimIndent()

    private val profiles = CompositionProfile.parseAll(json)

    @Test
    fun `every style has a profile and the new fields parse`() {
        ShotType.entries.forEach { assertTrue(it.name, profiles.containsKey(it)) }
        assertEquals(15f, profiles.getValue(ShotType.DUTCH_ANGLE).targetRollDeg, 0f)
        assertEquals(2f, profiles.getValue(ShotType.GROUP).centerTolerance, 0f)
        assertTrue(!profiles.getValue(ShotType.GROUP).focusRequired)
        assertTrue(profiles.getValue(ShotType.HALF_BODY).focusRequired)
        assertTrue(ShotType.DUTCH_ANGLE.isStyle && !ShotType.HALF_BODY.isStyle)
    }

    @Test
    fun `a dutch angle is level at fifteen degrees`() {
        val e = GuidanceEngine(profiles.getValue(ShotType.DUTCH_ANGLE))
        val box = SubjectBox(cx = 0.5f, cy = 0.45f, w = 0.2f, h = 0.25f)
        val eyes = EyeLine(y = 0.33f, gazeDx = 0f)
        var verb: Verb? = null
        repeat(60) {
            e.reportFocusLocked(true)
            verb = e.update(Attitude(rollDeg = 15f, pitchDeg = 0f), box, eyes, dtMs = 33L).verb
        }
        assertTrue("expected LOCKED at 15 degrees of roll, got $verb", verb == Verb.LOCKED)
        val f = GuidanceEngine(profiles.getValue(ShotType.DUTCH_ANGLE))
        val level = (1..20).map { f.reportFocusLocked(true); f.update(Attitude(0f, 0f), box, eyes, 33L).verb }.last()
        assertTrue("level should be a roll instruction for a dutch angle, got $level", level == Verb.LEVEL_CW || level == Verb.LEVEL_CCW)
    }

    @Test
    fun `a chosen style replaces the portrait ladder in PORTRAIT only`() {
        val sel = ShotTypeSelector(profiles)
        sel.setStyle(ShotType.LOW_ANGLE)
        assertEquals(ShotType.LOW_ANGLE, sel.shotTypeFor(1, 0.3f))
        assertEquals(ShotType.LANDSCAPE, sel.shotTypeFor(0, null))
        sel.setMode(CoachMode.WIDE)
        assertEquals(ShotType.GROUP, sel.shotTypeFor(2, 0.3f))
        sel.setMode(CoachMode.PORTRAIT)
        sel.setStyle(null)
        assertEquals(ShotType.FULL_BODY, sel.shotTypeFor(1, 0.10f))
    }

    @Test
    fun `a group is forgiven off-centre framing and needs no focus tap`() {
        val e = GuidanceEngine(profiles.getValue(ShotType.GROUP))
        // 10% off centre: inside a doubled 6% deadzone, outside a single one.
        val box = SubjectBox(cx = 0.60f, cy = 0.45f, w = 0.5f, h = 0.30f)
        val eyes = EyeLine(y = 0.35f, gazeDx = 0f)
        var verb: Verb? = null
        repeat(60) { verb = e.update(Attitude(0f, 0f), box, eyes, 33L).verb }
        assertTrue("group should lock without a focus tap and without centring, got $verb", verb == Verb.LOCKED)
    }
}
