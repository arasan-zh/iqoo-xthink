package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.Fixtures.LEVEL
import `in`.arasan.xthink.guidance.Fixtures.box
import `in`.arasan.xthink.guidance.Fixtures.eyes
import `in`.arasan.xthink.guidance.Fixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WIDE: groups and venues. */
class WideModeTest {

    private val group = profile(ShotType.GROUP)

    @Test
    fun `the group profile carries a band and a width rule`() {
        assertEquals(0.08f, group.sizeMin, 1e-6f)
        assertEquals(0.70f, group.sizeMax, 1e-6f)
        assertEquals(0.85f, group.maxWidth, 1e-6f)
        assertEquals("portrait profiles have no width rule", 1f, profile(ShotType.HALF_BODY).maxWidth, 1e-6f)
    }

    @Test
    fun `wide mode selects GROUP for any number of faces, LANDSCAPE for none`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        s.setMode(CoachMode.WIDE)
        assertEquals(ShotType.LANDSCAPE, s.shotTypeFor(0, 0.1f))
        assertEquals(ShotType.GROUP, s.shotTypeFor(1, 0.10f))
        assertEquals(ShotType.GROUP, s.shotTypeFor(1, 0.30f))
        assertEquals(ShotType.GROUP, s.shotTypeFor(5, 0.12f))
    }

    @Test
    fun `portrait mode is unchanged by the existence of wide`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        assertEquals(CoachMode.PORTRAIT, s.mode)
        assertEquals(ShotType.FULL_BODY, s.shotTypeFor(3, 0.10f))
        assertEquals(ShotType.HALF_BODY, s.shotTypeFor(3, 0.30f))
    }

    @Test
    fun `a mode switch takes effect at once - a tap is not detector noise`() {
        val s = ShotTypeSelector(Fixtures.PROFILES)
        repeat(20) { s.update(2, 33L, 0.25f) }
        assertEquals(ShotType.HALF_BODY, s.current)
        s.setMode(CoachMode.WIDE)
        assertEquals("no 500ms hold for a deliberate choice", ShotType.GROUP, s.current)
        s.setMode(CoachMode.PORTRAIT)
        assertEquals(ShotType.HALF_BODY, s.current)
    }

    @Test
    fun `a row of people is one face tall and that is fine`() {
        // Union of five faces in a row: 0.14 tall, 0.7 wide. Inside the band, under the width rule.
        val engine = GuidanceEngine(group)
        val row = box(cx = 0.5f, cy = 0.35f + 0.1f * 0.14f, h = 0.14f, w = 0.70f)
        val verb = engine.update(LEVEL, row, eyes(y = 0.35f), 33L).verb
        assertNotEquals(Verb.STEP_CLOSER, verb)
        assertNotEquals(Verb.STEP_BACK, verb)
        assertEquals(0f, engine.alignment.sizeErr, 1e-6f)
    }

    @Test
    fun `a group spilling past the edges is told to step back, whatever its height`() {
        val engine = GuidanceEngine(group)
        // w = 1.0 against a 0.85 limit is a 17.6% overshoot - past the 12%
        // size deadzone. (0.95 would be 11.8%: inside it, correctly ignored.)
        val spill = box(cx = 0.5f, cy = 0.40f, h = 0.14f, w = 1.0f)
        assertEquals(Verb.STEP_BACK, engine.update(LEVEL, spill, eyes(y = 0.35f), 33L).verb)
        assertTrue(engine.alignment.sizeErr > 0f)
    }

    @Test
    fun `the width rule never cancels a too-far - a narrow small group is still step closer`() {
        // h below the band, w well under the limit. Width says nothing;
        // height says step closer. The answer is step closer.
        val engine = GuidanceEngine(group)
        val tiny = box(cx = 0.5f, cy = 0.36f, h = 0.05f, w = 0.10f)
        assertEquals(Verb.STEP_CLOSER, engine.update(LEVEL, tiny, eyes(y = 0.35f), 33L).verb)
        assertTrue(engine.alignment.sizeErr < 0f)
    }

    @Test
    fun `a venue with nobody in it coaches level and pitch and locks`() {
        val engine = GuidanceEngine(profile(ShotType.LANDSCAPE))
        val verbs = (1..25).map { engine.update(LEVEL, null, null, 100L).verb }
        assertTrue(verbs.contains(Verb.LOCKED))
        assertTrue(verbs.none { it == Verb.SEEKING })
    }
}
