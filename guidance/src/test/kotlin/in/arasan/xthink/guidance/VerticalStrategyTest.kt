package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.Fixtures.LEVEL
import `in`.arasan.xthink.guidance.Fixtures.box
import `in`.arasan.xthink.guidance.Fixtures.eyes
import `in`.arasan.xthink.guidance.Fixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "go above your head" trap, and the MOVE_UP <-> TILT_UP oscillation it
 * produced on the phone.
 *
 * Level pitch plus eyes-on-the-upper-third together define a camera height.
 * When that height is out of the photographer's reach, no sequence of single
 * instructions satisfies both rungs, and they alternate every lockout period.
 * The engine now notices the move is not being followed, concludes the camera
 * cannot go there, and switches to tilting - relaxing the level requirement so
 * the tilt is not immediately undone.
 */
class VerticalStrategyTest {

    private val halfBody = profile(ShotType.HALF_BODY)

    /** Subject high in frame with the camera level: "move phone up". */
    private fun subjectHigh() = box(cx = 0.5f, cy = 0.20f, h = 0.25f, w = 0.19f)
    private fun eyesHigh() = eyes(y = 0.12f)

    /**
     * A correctly framed HALF_BODY subject: h inside the 0.16..0.38 band, eyes
     * on the upper third, box centre 0.1 of face height below them. NOT the
     * shared box() fixture - that is HEADSHOT-sized and STEP_BACK on this profile.
     */
    private fun framedHalfBody() = box(cx = 0.5f, cy = 0.33f + 0.1f * 0.25f, h = 0.25f, w = 0.19f)

    /**
     * Frames of 33ms to see the switch: the stall clock, plus one frame to
     * seed its baseline, plus one because the tracker runs after the ladder
     * has already chosen that frame's instruction - the tilt shows next frame.
     */
    private val stallFrames = (GuidanceConstants.VERTICAL_STALL_MS / 33L).toInt() + 4

    @Test
    fun `a reachable move is asked for as a move, per CLAUDE md`() {
        val engine = GuidanceEngine(halfBody)
        val verb = engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L).verb
        assertEquals(Verb.MOVE_UP, verb)
        assertFalse(engine.alignment.usingRotation)
    }

    @Test
    fun `a move that goes unheeded for the stall period becomes a tilt`() {
        val engine = GuidanceEngine(halfBody)
        // The photographer cannot raise the camera: the frame never changes.
        var verb = Verb.LOCKED
        repeat(stallFrames) { verb = engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L).verb }
        assertEquals("after ${stallFrames * 33}ms of no progress, ask for the thing they CAN do", Verb.TILT_UP, verb)
        assertTrue(engine.alignment.usingRotation)
    }

    @Test
    fun `progress on the move resets the stall clock`() {
        val engine = GuidanceEngine(halfBody)
        // Half the stall, then the subject visibly drops in frame (they raised the phone).
        repeat(stallFrames / 2) { engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L) }
        repeat(stallFrames / 2) { engine.update(LEVEL, box(cx = 0.5f, cy = 0.26f, h = 0.25f, w = 0.19f), eyes(y = 0.18f), 33L) }
        assertFalse("they were moving; do not give up on them", engine.alignment.usingRotation)
    }

    @Test
    fun `once tilting, the pitch that fixes the framing is not undone`() {
        val engine = GuidanceEngine(halfBody)
        repeat(stallFrames) { engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L) }
        assertTrue(engine.alignment.usingRotation)

        // They tilt up 10 degrees - outside the strict 6 degree deadzone,
        // inside the relaxed 15 - and the framing lands on target.
        val tilted = Attitude(rollDeg = 0f, pitchDeg = 10f)
        val verbs = (1..30).map { engine.update(tilted, framedHalfBody(), eyes(), 33L).verb }.toSet()
        assertFalse("no 'tilt back to level' while the tilt is what framed the shot: $verbs", verbs.contains(Verb.TILT_DOWN))
        assertFalse(verbs.contains(Verb.MOVE_UP))
        assertFalse(verbs.contains(Verb.MOVE_DOWN))
        assertFalse("framed inside the band - no distance advice either: $verbs", verbs.contains(Verb.STEP_BACK))
    }

    @Test
    fun `an overhead angle is a fine portrait once tilting`() {
        val engine = GuidanceEngine(halfBody)
        repeat(stallFrames) { engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L) }
        // Looking down 12 degrees at a framed subject: accepted, locks.
        // Focus is reported every frame, as continuous AF does on the phone -
        // the tilt moves the subject box past the focus-invalidation radius,
        // and a one-off report would (correctly) be treated as stale.
        val verb = (1..40).map {
            engine.reportFocusLocked(true)
            engine.update(Attitude(0f, -12f), framedHalfBody(), eyes(), 33L).verb
        }.last()
        assertEquals(Verb.LOCKED, verb)
    }

    @Test
    fun `the strict pitch deadzone still applies while translating`() {
        val engine = GuidanceEngine(halfBody)
        // Framed, level camera... then 10 degrees of pitch with framing good.
        val verb = engine.update(Attitude(0f, 10f), framedHalfBody(), eyes(), 33L).verb
        // Out of the 6 degree deadzone, so the pitch rung fires - and with a
        // framed subject it asks for eye level (raise the phone), not a tilt.
        assertEquals("translate mode: 10 degrees is out of the 6 degree deadzone", Verb.MOVE_UP, verb)
    }

    @Test
    fun `no oscillation - the strategy does not flip back on its own`() {
        val engine = GuidanceEngine(halfBody)
        repeat(stallFrames) { engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L) }
        assertTrue(engine.alignment.usingRotation)

        // Ten seconds of the same unreachable frame. Before: MOVE_UP <-> TILT_UP
        // every 600ms. Now: one strategy, held.
        val verbs = (1..300).map { engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L).verb }.toSet()
        assertEquals(setOf(Verb.TILT_UP), verbs)
    }

    @Test
    fun `reach is remembered across a profile change and a subject blink`() {
        val engine = GuidanceEngine(halfBody)
        repeat(stallFrames) { engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L) }
        assertTrue(engine.alignment.usingRotation)

        engine.setProfile(profile(ShotType.FULL_BODY))
        engine.update(LEVEL, null, null, 33L)
        engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L)
        assertTrue("an arm does not get longer when the profile changes", engine.alignment.usingRotation)
    }

    @Test
    fun `reset forgets, so a new session starts by preferring translation`() {
        val engine = GuidanceEngine(halfBody)
        repeat(stallFrames) { engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L) }
        engine.reset()
        assertEquals(Verb.MOVE_UP, engine.update(LEVEL, subjectHigh(), eyesHigh(), 33L).verb)
        assertFalse(engine.alignment.usingRotation)
    }

    @Test
    fun `a move down that stalls becomes a tilt down`() {
        val engine = GuidanceEngine(halfBody)
        val low = box(cx = 0.5f, cy = 0.62f, h = 0.25f, w = 0.19f)
        var verb = Verb.LOCKED
        repeat(stallFrames) { verb = engine.update(LEVEL, low, eyes(y = 0.55f), 33L).verb }
        assertEquals(Verb.TILT_DOWN, verb)
    }

    // ------------------------------------------------------------------
    // The target rect the brackets will draw
    // ------------------------------------------------------------------

    @Test
    fun `the target rect keeps the subject's size and moves it to the target position`() {
        val engine = GuidanceEngine(halfBody)
        val subject = box(cx = 0.70f, cy = 0.40f, h = 0.25f, w = 0.19f)
        engine.update(LEVEL, subject, eyes(y = 0.33f), 33L)
        val a = engine.alignment
        assertTrue(a.hasTarget)
        assertEquals(0.25f, a.targetH, 1e-6f)
        assertEquals(0.19f, a.targetW, 1e-6f)
        assertEquals("centred horizontally for a lens-facing gaze", 0.50f, a.targetCx, 1e-6f)
        // Eyes at 0.33, box centre 0.1 of face height below them.
        assertEquals(0.33f + 0.1f * 0.25f, a.targetCy, 1e-5f)
    }

    @Test
    fun `the target follows the gaze lead room`() {
        val engine = GuidanceEngine(halfBody)
        engine.update(LEVEL, box(), eyes(gazeDx = 0.6f), 33L)
        assertEquals(GuidanceConstants.LEAD_ROOM_CX_LOOKING_RIGHT, engine.alignment.targetCx, 1e-6f)
    }

    @Test
    fun `no subject means no target`() {
        val engine = GuidanceEngine(profile(ShotType.LANDSCAPE))
        engine.update(LEVEL, null, null, 33L)
        assertFalse(engine.alignment.hasTarget)
    }

    @Test
    fun `a correctly framed subject sits exactly on its target`() {
        val engine = GuidanceEngine(halfBody)
        val h = 0.25f
        val cy = 0.33f + 0.1f * h
        engine.update(LEVEL, box(cx = 0.5f, cy = cy, h = h, w = h * 0.75f), eyes(y = 0.33f), 33L)
        val a = engine.alignment
        assertEquals(0.5f, a.targetCx, 1e-6f)
        assertEquals(cy, a.targetCy, 1e-5f)
        assertNotEquals(Verb.MOVE_UP, engine.update(LEVEL, box(cx = 0.5f, cy = cy, h = h, w = h * 0.75f), eyes(y = 0.33f), 33L).verb)
    }
}
