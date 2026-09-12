package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.Fixtures.LEVEL
import `in`.arasan.xthink.guidance.Fixtures.box
import `in`.arasan.xthink.guidance.Fixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** OBJECT: a vase, a plate, a product - centred, at the scale it is at. */
class ObjectModeTest {

    private val obj = profile(ShotType.OBJECT)

    @Test
    fun `the object profile is centred, banded, and has a width rule`() {
        assertEquals(0.50f, obj.targetEyeLineY, 1e-6f)
        assertEquals(0.50f, obj.targetCx, 1e-6f)
        assertEquals(0.25f, obj.sizeMin, 1e-6f)
        assertEquals(0.75f, obj.sizeMax, 1e-6f)
        assertEquals("same edge margin as GROUP; 0.90 left a full-width object inside the 12% deadzone", 0.85f, obj.maxWidth, 1e-6f)
    }

    @Test
    fun `object mode selects OBJECT when something is found, LANDSCAPE when not`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        s.setMode(CoachMode.OBJECT)
        assertEquals(ShotType.LANDSCAPE, s.shotTypeFor(0))
        assertEquals(ShotType.OBJECT, s.shotTypeFor(1, 0.4f))
        assertEquals("size does not change the profile in OBJECT", ShotType.OBJECT, s.shotTypeFor(1, 0.1f))
    }

    @Test
    fun `an object has no eye line - the box centre is what is composed`() {
        // Centred, in band, no eyes: correctly framed, no vertical advice.
        val engine = GuidanceEngine(obj)
        val vase = box(cx = 0.5f, cy = 0.5f, h = 0.45f, w = 0.30f)
        val verb = engine.update(LEVEL, vase, null, 33L).verb
        assertNotEquals(Verb.MOVE_UP, verb)
        assertNotEquals(Verb.MOVE_DOWN, verb)
        assertNotEquals(Verb.STEP_CLOSER, verb)
        assertNotEquals(Verb.STEP_BACK, verb)
        assertEquals(0f, engine.alignment.sizeErr, 1e-6f)
        assertEquals(0.5f, engine.alignment.targetCy, 1e-6f)
    }

    @Test
    fun `an object high in frame is asked down to the centre`() {
        val engine = GuidanceEngine(obj)
        val verb = engine.update(LEVEL, box(cx = 0.5f, cy = 0.30f, h = 0.40f, w = 0.30f), null, 33L).verb
        assertEquals(Verb.MOVE_UP, verb)
    }

    @Test
    fun `gaze lead room never applies to an object`() {
        // Even if a caller passed an eye line, OBJECT stays centred.
        val engine = GuidanceEngine(obj)
        engine.update(LEVEL, box(cx = 0.5f, cy = 0.5f, h = 0.45f, w = 0.30f), EyeLine(0.5f, 0.9f), 33L)
        assertEquals(0.5f, engine.alignment.targetCx, 1e-6f)
    }

    @Test
    fun `too small an object is step closer, too wide is step back`() {
        val small = GuidanceEngine(obj)
        assertEquals(Verb.STEP_CLOSER, small.update(LEVEL, box(cx = 0.5f, cy = 0.5f, h = 0.15f, w = 0.10f), null, 33L).verb)
        val wide = GuidanceEngine(obj)
        assertEquals(Verb.STEP_BACK, wide.update(LEVEL, box(cx = 0.5f, cy = 0.5f, h = 0.40f, w = 1.0f), null, 33L).verb)
    }

    @Test
    fun `the angle on an object is the photographer's choice - pitch is free`() {
        assertTrue(obj.pitchFree)
        assertEquals("portraits still want level", false, profile(ShotType.HALF_BODY).pitchFree)
    }

    @Test
    fun `a desk shot aimed steeply down locks instead of asking for level`() {
        // On the phone: 81 OBJECT heartbeats, zero locks, "Tilt up" x22 -
        // the profile wanted a level camera and the object was on a desk.
        val engine = GuidanceEngine(obj)
        val vase = box(cx = 0.5f, cy = 0.5f, h = 0.45f, w = 0.30f)
        var verb = Verb.SEEKING
        repeat(40) {
            engine.reportFocusLocked(true)
            verb = engine.update(Attitude(rollDeg = 0f, pitchDeg = -50f), vase, null, 33L).verb
        }
        assertEquals(Verb.LOCKED, verb)
        assertTrue(engine.alignment.pitchInDeadzone)
        assertEquals("a free pitch contributes nothing to the error", 0f, engine.alignment.totalError, 1e-6f)
    }

    @Test
    fun `a flat lay straight down is just as valid`() {
        val engine = GuidanceEngine(obj)
        val plate = box(cx = 0.5f, cy = 0.5f, h = 0.50f, w = 0.50f)
        val verbs = (1..30).map { engine.update(Attitude(0f, -88f), plate, null, 33L).verb }.toSet()
        assertTrue("no tilt advice on a flat lay: $verbs", verbs.none { it == Verb.TILT_UP || it == Verb.TILT_DOWN })
    }

    @Test
    fun `roll still matters on an object - a tilted table edge is still tilted`() {
        val engine = GuidanceEngine(obj)
        val verb = engine.update(Attitude(rollDeg = 12f, pitchDeg = -40f), box(cx = 0.5f, cy = 0.5f, h = 0.45f, w = 0.30f), null, 33L).verb
        assertEquals(Verb.LEVEL_CCW, verb)
    }

    @Test
    fun `an object counts as a subject for the lock and auto-capture`() {
        val engine = GuidanceEngine(obj)
        val vase = box(cx = 0.5f, cy = 0.5f, h = 0.45f, w = 0.30f)
        var verb = Verb.SEEKING
        repeat(40) {
            engine.reportFocusLocked(true)
            verb = engine.update(LEVEL, vase, null, 33L).verb
        }
        assertEquals(Verb.LOCKED, verb)
        assertTrue(engine.alignment.hasTarget)
        val policy = AutoCapturePolicy()
        assertTrue(policy.update(Verb.LOCKED, true, true, 33L))
    }
}
