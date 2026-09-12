package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.Fixtures.LEVEL
import `in`.arasan.xthink.guidance.Fixtures.box
import `in`.arasan.xthink.guidance.Fixtures.eyes
import `in`.arasan.xthink.guidance.Fixtures.feed
import `in`.arasan.xthink.guidance.Fixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Losing the subject, and the shot type staying still while the detector does
 * not. Both of these were found by running v0.2c on the phone - no unit test
 * existed that could have caught either, because both need a *sequence* of
 * frames that only real detection produces.
 */
class SubjectLossTest {

    private val headshot = profile(ShotType.HEADSHOT)
    private val landscape = profile(ShotType.LANDSCAPE)

    /** Too small, so the engine is mid "step closer" when the subject leaves. */
    private fun tooSmall() = box(h = 0.20f)

    // ---------------------------------------------------------------------
    // The instruction must not outlive the subject
    // ---------------------------------------------------------------------

    @Test
    fun `losing the subject replaces the advice immediately, not in 600ms`() {
        val engine = GuidanceEngine(headshot)
        assertEquals(
            Verb.STEP_CLOSER,
            engine.feed(4, subject = tooSmall(), eyeLine = eyes()).verb,
        )

        // The subject walks out. The very next frame must stop telling the
        // photographer to step closer to nothing - the 600ms lockout does not
        // apply, because this is not two instructions competing.
        val next = engine.update(LEVEL, null, null, 33L)
        assertEquals(Verb.SEEKING, next.verb)
        assertEquals("Looking for your subject", next.text)
    }

    @Test
    fun `finding the subject again is just as prompt`() {
        val engine = GuidanceEngine(headshot)
        engine.feed(4, subject = tooSmall(), eyeLine = eyes())
        assertEquals(Verb.SEEKING, engine.update(LEVEL, null, null, 33L).verb)

        // Coming back must not wait out a lockout either.
        val back = engine.update(LEVEL, tooSmall(), eyes(), 33L)
        assertEquals(Verb.STEP_CLOSER, back.verb)
    }

    @Test
    fun `a landscape shot expects no subject and is never left seeking`() {
        // targetSizeRatio 0.00 means there is nothing to look for, so an empty
        // frame is a perfectly good landscape - it must still coach and lock.
        val engine = GuidanceEngine(landscape)
        val verbs = (1..20).map { engine.update(LEVEL, null, null, 100L).verb }
        assertFalse("a landscape is not a lost subject: $verbs", verbs.contains(Verb.SEEKING))
        assertTrue(verbs.contains(Verb.LOCKED))
    }

    @Test
    fun `a missing subject blocks composition and empties the dwell`() {
        val engine = GuidanceEngine(headshot)
        engine.feed(10, subject = box(), eyeLine = eyes())
        engine.reportFocusLocked(true)
        engine.feed(4, subject = box(), eyeLine = eyes())
        assertTrue(engine.compositionOk)

        engine.update(LEVEL, null, null, 33L)
        assertFalse("composition cannot be judged without the subject", engine.compositionOk)
        assertEquals(0f, engine.lockProgress, 1e-6f)
    }

    @Test
    fun `seeking never locks, however long the subject stays away`() {
        val engine = GuidanceEngine(headshot)
        val verbs = (1..30).map { engine.update(LEVEL, null, null, 100L).verb }.toSet()
        assertEquals(setOf(Verb.SEEKING), verbs)
    }

    @Test
    fun `a subject still present is not seeking, even when badly framed`() {
        val engine = GuidanceEngine(headshot)
        val verb = engine.update(Attitude(20f, 0f), tooSmall(), eyes(), 33L).verb
        assertNotEquals(Verb.SEEKING, verb)
        assertEquals(Verb.LEVEL_CCW, verb)
    }

    // ---------------------------------------------------------------------
    // Shot type has to stop flapping
    // ---------------------------------------------------------------------

    @Test
    fun `a face dropped for a frame or two does not tear down the profile`() {
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        // Settle on a headshot.
        repeat(20) { selector.update(faceCount = 1, dtMs = 33L) }
        assertEquals(ShotType.HALF_BODY, selector.current)

        // The detector loses the face for three frames, then finds it again.
        // This is the 0-1-0-1 flapping seen on the phone.
        assertEquals(ShotType.HALF_BODY, selector.update(0, 33L))
        assertEquals(ShotType.HALF_BODY, selector.update(0, 33L))
        assertEquals(ShotType.HALF_BODY, selector.update(0, 33L))
        assertEquals(ShotType.HALF_BODY, selector.update(1, 33L))
        assertEquals(ShotType.HALF_BODY, selector.current)
    }

    @Test
    fun `a face count that really does persist switches the profile`() {
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { selector.update(1, 33L) }
        assertEquals(ShotType.HALF_BODY, selector.current)

        // 500ms of hold at 33ms a frame is 16 frames.
        repeat(15) { selector.update(0, 33L) }
        assertEquals("not yet - 495ms", ShotType.HALF_BODY, selector.current)
        selector.update(0, 33L)
        assertEquals("528ms, believed", ShotType.LANDSCAPE, selector.current)
    }

    @Test
    fun `switching back and forth restarts the clock each time`() {
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { selector.update(1, 33L) }

        repeat(10) { selector.update(0, 33L) }   // 330ms toward LANDSCAPE
        selector.update(1, 33L)                   // withdrawn
        repeat(10) { selector.update(0, 33L) }   // starts over
        assertEquals(ShotType.HALF_BODY, selector.current)
    }

    @Test
    fun `switchProgress reports how far a pending change has come`() {
        // Portrait-only: the only switch that ever pends is LANDSCAPE<->HALF_BODY,
        // since every face count from 1 up maps to the same HALF_BODY target.
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        assertEquals(ShotType.LANDSCAPE, selector.current)
        assertEquals(0f, selector.switchProgress, 1e-6f)

        selector.update(1, 250L)
        assertEquals(0.5f, selector.switchProgress, 1e-3f)
    }

    @Test
    fun `a face count change that does not cross the landscape-headshot line is not a pending switch`() {
        // Going from one face to three is not a switch at all under the
        // portrait-only policy, so it must not start a hold timer.
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { selector.update(1, 33L) }
        assertEquals(ShotType.HALF_BODY, selector.current)

        selector.update(3, 250L)
        assertEquals(0f, selector.switchProgress, 1e-6f)
        assertEquals(ShotType.HALF_BODY, selector.current)
    }

    @Test
    fun `the count to shot type mapping is the agreed policy - portrait only`() {
        // This build never switches to GROUP: with several faces in frame and
        // no mode tab to say otherwise, a portrait of the largest face is the
        // one unambiguous choice. With no height given, that is HALF_BODY.
        val s = ShotTypeSelector(Fixtures.PROFILES)
        assertEquals(ShotType.LANDSCAPE, s.shotTypeFor(0))
        assertEquals(ShotType.HALF_BODY, s.shotTypeFor(1))
        assertEquals(ShotType.HALF_BODY, s.shotTypeFor(2))
        assertEquals(ShotType.HALF_BODY, s.shotTypeFor(9))
        assertEquals(ShotType.LANDSCAPE, s.shotTypeFor(-1))
    }

    @Test
    fun `reset returns it to a known shot type`() {
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { selector.update(3, 33L) }
        assertEquals(ShotType.HALF_BODY, selector.current)
        selector.reset()
        assertEquals(ShotType.LANDSCAPE, selector.current)
        assertEquals(0f, selector.switchProgress, 1e-6f)
    }

    @Test
    fun `a negative dt cannot drive a switch`() {
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { selector.update(1, 33L) }
        repeat(50) { selector.update(0, -1000L) }
        assertEquals(ShotType.HALF_BODY, selector.current)
    }

    /**
     * The two fixes composing: the hysteresis holds HALF_BODY open for half a
     * second after the face goes, and that is exactly the window in which
     * "looking for your subject" is the honest thing to say. Once it expires
     * the shot is a landscape and normal coaching resumes.
     */
    @Test
    fun `the hysteresis window is what makes seeking meaningful`() {
        val selector = ShotTypeSelector(Fixtures.PROFILES)
        val profiles = Fixtures.PROFILES
        val engine = GuidanceEngine(profiles.getValue(ShotType.LANDSCAPE))

        fun tick(faceCount: Int, subject: SubjectBox?): Verb {
            val shot = selector.update(faceCount, 33L)
            engine.setProfile(profiles.getValue(shot))
            return engine.update(LEVEL, subject, if (subject != null) eyes() else null, 33L).verb
        }

        repeat(20) { tick(1, box()) }
        assertEquals(ShotType.HALF_BODY, selector.current)

        // Face gone. Still a headshot, so the engine says it is looking.
        assertEquals(Verb.SEEKING, tick(0, null))
        assertEquals(ShotType.HALF_BODY, selector.current)

        // Wait out the hold. The shot becomes a landscape and seeking stops.
        repeat(20) { tick(0, null) }
        assertEquals(ShotType.LANDSCAPE, selector.current)
        assertNotEquals(Verb.SEEKING, tick(0, null))
    }
}
