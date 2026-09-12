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
 * A portrait is a range of scales, not one distance. Full body, hip level
 * and head-and-shoulders are all portraits; the photographer picks, the app
 * coaches the rest. Distance advice appears only past the extremes.
 */
class PortraitScaleTest {

    private val fullBody = profile(ShotType.FULL_BODY)
    private val halfBody = profile(ShotType.HALF_BODY)

    /** A HALF_BODY-composed frame at a given face height. Eyes on the upper third. */
    private fun personAt(h: Float) = box(cx = 0.5f, cy = 0.33f + h * 0.15f, h = h, w = h * 0.75f)

    // ------------------------------------------------------------------
    // The profiles carry the bands
    // ------------------------------------------------------------------

    @Test
    fun `the shipped JSON gives the portrait profiles a band and everything else a point`() {
        assertEquals(0.06f, fullBody.sizeMin, 1e-6f)
        assertEquals(0.16f, fullBody.sizeMax, 1e-6f)
        assertEquals(0.16f, halfBody.sizeMin, 1e-6f)
        assertEquals(0.38f, halfBody.sizeMax, 1e-6f)

        // Unbanded profiles collapse to their target, i.e. the old behaviour.
        val headshot = profile(ShotType.HEADSHOT)
        assertEquals(headshot.targetSizeRatio, headshot.sizeMin, 1e-6f)
        assertEquals(headshot.targetSizeRatio, headshot.sizeMax, 1e-6f)
    }

    @Test
    fun `the portrait bands are contiguous - no scale falls between them`() {
        // A face exactly on the seam is inside both, so whichever profile the
        // selector lands on, distance is still not an error there.
        assertEquals(fullBody.sizeMax, halfBody.sizeMin, 1e-6f)
        assertTrue(fullBody.acceptsSize(0.16f))
        assertTrue(halfBody.acceptsSize(0.16f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an inverted band is refused at construction`() {
        CompositionProfile(ShotType.HALF_BODY, 0.25f, 0.33f, 0.5f, 0.08f, 0f, sizeMin = 0.4f, sizeMax = 0.2f)
    }

    // ------------------------------------------------------------------
    // Inside the band, distance is not an error
    // ------------------------------------------------------------------

    @Test
    fun `hip level and head-and-shoulders both count as correctly framed`() {
        for (h in listOf(0.17f, 0.22f, 0.25f, 0.30f, 0.36f)) {
            val engine = GuidanceEngine(halfBody)
            val verb = engine.update(LEVEL, personAt(h), eyes(), 100L).verb
            assertNotEquals("h=$h must not ask for a distance change", Verb.STEP_CLOSER, verb)
            assertNotEquals("h=$h must not ask for a distance change", Verb.STEP_BACK, verb)
            assertEquals("h=$h contributes no size error", 0f, engine.alignment.sizeErr, 1e-6f)
        }
    }

    @Test
    fun `a full-body distance counts as correctly framed on the full-body profile`() {
        for (h in listOf(0.07f, 0.10f, 0.14f)) {
            val engine = GuidanceEngine(fullBody)
            // FULL_BODY wants eyes at 0.28 and a little more headroom.
            val subject = box(cx = 0.5f, cy = 0.28f + h * 0.15f, h = h, w = h * 0.75f)
            val verb = engine.update(LEVEL, subject, eyes(y = 0.28f), 100L).verb
            assertNotEquals("h=$h", Verb.STEP_CLOSER, verb)
            assertNotEquals("h=$h", Verb.STEP_BACK, verb)
        }
    }

    // ------------------------------------------------------------------
    // Past the extremes, distance advice returns - from the band edge
    // ------------------------------------------------------------------

    @Test
    fun `a face filling the frame is told to step back`() {
        val engine = GuidanceEngine(halfBody)
        val verb = engine.update(LEVEL, box(cx = 0.5f, cy = 0.45f, h = 0.50f, w = 0.38f), eyes(), 100L).verb
        assertEquals(Verb.STEP_BACK, verb)
        assertTrue("error is measured from the band edge, so it is positive", engine.alignment.sizeErr > 0f)
    }

    @Test
    fun `a face too small to be a portrait is told to step closer`() {
        val engine = GuidanceEngine(fullBody)
        val verb = engine.update(LEVEL, box(cx = 0.5f, cy = 0.30f, h = 0.04f, w = 0.03f), eyes(y = 0.28f), 100L).verb
        assertEquals(Verb.STEP_CLOSER, verb)
        assertTrue(engine.alignment.sizeErr < 0f)
    }

    @Test
    fun `the size error is relative to the nearest edge, not the target`() {
        // h = 0.42 on HALF_BODY (max 0.38): error is (0.42-0.38)/0.38, not
        // (0.42-0.25)/0.25. The band edge is the thing you are past.
        val engine = GuidanceEngine(halfBody)
        engine.update(LEVEL, box(cx = 0.5f, cy = 0.45f, h = 0.42f, w = 0.32f), eyes(), 100L)
        assertEquals((0.42f - 0.38f) / 0.38f, engine.alignment.sizeErr, 1e-4f)
    }

    // ------------------------------------------------------------------
    // The selector picks the scale by face size
    // ------------------------------------------------------------------

    @Test
    fun `a small face selects the full-body profile, a larger one half-body`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        assertEquals(ShotType.FULL_BODY, s.shotTypeFor(1, 0.08f))
        assertEquals(ShotType.FULL_BODY, s.shotTypeFor(1, 0.15f))
        assertEquals(ShotType.HALF_BODY, s.shotTypeFor(1, 0.20f))
        assertEquals(ShotType.HALF_BODY, s.shotTypeFor(1, 0.35f))
    }

    @Test
    fun `beyond both extremes the selector stays on the nearest scale`() {
        // Too small is still FULL_BODY (which will say step closer); a
        // face-filling frame is still HALF_BODY (which will say step back).
        // HEADSHOT is never selected - that crop is not a portrait.
        val s = ShotTypeSelector(Fixtures.PROFILES)
        assertEquals(ShotType.FULL_BODY, s.shotTypeFor(1, 0.02f))
        assertEquals(ShotType.HALF_BODY, s.shotTypeFor(1, 0.60f))
        assertNotEquals(ShotType.HEADSHOT, s.shotTypeFor(1, 0.45f))
    }

    @Test
    fun `no face is a landscape regardless of any height`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        assertEquals(ShotType.LANDSCAPE, s.shotTypeFor(0, 0.25f))
    }

    @Test
    fun `a face hovering on the seam does not flap the profile`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { s.update(1, 33L, 0.12f) }
        assertEquals(ShotType.FULL_BODY, s.current)

        // Alternating either side of 0.16 every frame. Each frame that agrees
        // with the current type withdraws the pending change, so it never
        // earns its 500ms - the profile holds still.
        repeat(40) { i -> s.update(1, 33L, if (i % 2 == 0) 0.155f else 0.165f) }
        assertEquals(ShotType.FULL_BODY, s.current)
    }

    @Test
    fun `a scale change that persists is believed`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { s.update(1, 33L, 0.10f) }
        assertEquals(ShotType.FULL_BODY, s.current)
        repeat(20) { s.update(1, 33L, 0.25f) }
        assertEquals(ShotType.HALF_BODY, s.current)
    }

    // ------------------------------------------------------------------
    // The whole chain: walking towards the subject never nags about distance
    // ------------------------------------------------------------------

    @Test
    fun `walking from full-body to head-and-shoulders never produces a distance instruction`() {
        val profiles = Fixtures.PROFILES
        val selector = ShotTypeSelector(profiles)
        val engine = GuidanceEngine(profiles.getValue(ShotType.LANDSCAPE))
        val distanceVerbs = setOf(Verb.STEP_CLOSER, Verb.STEP_BACK, Verb.ZOOM_IN, Verb.ZOOM_OUT)
        val seen = mutableListOf<Pair<Float, Verb>>()

        // 0.08 -> 0.36 in small steps, each held long enough for the
        // selector to settle. Eye line follows the profile in use.
        var h = 0.08f
        while (h <= 0.36f) {
            repeat(20) {
                val shot = selector.update(1, 33L, h)
                val p = profiles.getValue(shot)
                engine.setProfile(p)
                val subject = box(cx = 0.5f, cy = p.targetEyeLineY + h * 0.15f, h = h, w = h * 0.75f)
                val verb = engine.update(LEVEL, subject, eyes(y = p.targetEyeLineY), 33L).verb
                seen += h to verb
            }
            h += 0.02f
        }

        val offenders = seen.filter { it.second in distanceVerbs }
        assertTrue("distance advice inside the portrait range: $offenders", offenders.isEmpty())
        assertFalse("the walk should have crossed into HALF_BODY", selector.current == ShotType.FULL_BODY)
    }
}
