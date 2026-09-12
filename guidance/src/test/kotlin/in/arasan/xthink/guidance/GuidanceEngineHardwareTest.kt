package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.Fixtures.LEVEL
import `in`.arasan.xthink.guidance.Fixtures.box
import `in`.arasan.xthink.guidance.Fixtures.eyes
import `in`.arasan.xthink.guidance.Fixtures.feed
import `in`.arasan.xthink.guidance.Fixtures.profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour forced by what the iQOO 15 actually reports. Measured values are in
 * docs/evidence/capabilities-2026-09-12.txt, reasoning in docs/HARDWARE.md.
 *
 * Two hardware facts drive everything here:
 *  - the front camera is FIXED FOCUS (`CONTROL_AF_AVAILABLE_MODES = [OFF]`)
 *  - only one rear camera is reachable, so 1x-10x is a digital crop, not a lens
 */
class GuidanceEngineHardwareTest {

    private val headshot = profile(ShotType.HEADSHOT)

    /** Too small for a HEADSHOT: 0.20 against a 0.45 target. */
    private fun tooSmall() = box(h = 0.20f)

    /** Too big for a HEADSHOT, with the headroom still legal. */
    private fun tooBig() = box(cy = 0.45f, h = 0.70f)

    // ---------------------------------------------------------------------
    // Fixed-focus camera (the front camera on this phone)
    // ---------------------------------------------------------------------

    @Test
    fun `a fixed-focus camera never asks for a focus tap`() {
        val engine = GuidanceEngine(headshot, mirrored = true, hasAutofocus = false)
        val verbs = (1..10).map { engine.update(LEVEL, box(), eyes(), 100L).verb }
        assertFalse(
            "asking to tap-focus a fixed-focus lens is advice the user cannot follow: $verbs",
            verbs.contains(Verb.TAP_FOCUS),
        )
    }

    @Test
    fun `an autofocus camera given the same frames does ask`() {
        // The contrast that proves the flag is what changed the behaviour.
        val engine = GuidanceEngine(headshot, mirrored = true, hasAutofocus = true)
        val verbs = (1..10).map { engine.update(LEVEL, box(), eyes(), 100L).verb }
        assertTrue(verbs.contains(Verb.TAP_FOCUS))
    }

    @Test
    fun `a fixed-focus camera locks without any focus report`() {
        val engine = GuidanceEngine(headshot, mirrored = true, hasAutofocus = false)
        assertTrue(engine.focusOk)
        assertEquals(Verb.LOCKED, engine.feed(10, subject = box(), eyeLine = eyes()).verb)
    }

    @Test
    fun `reporting a focus lock on a fixed-focus camera changes nothing`() {
        val engine = GuidanceEngine(headshot, hasAutofocus = false)
        engine.update(LEVEL, box(), eyes(), 100L)
        assertTrue(engine.focusOk)
        engine.reportFocusLocked(false) // would normally block the lock
        engine.update(LEVEL, box(), eyes(), 100L)
        assertTrue("fixed focus cannot be 'unlocked'", engine.focusOk)
    }

    @Test
    fun `a cold start with perfect framing says hold steady, not tap to focus`() {
        val engine = GuidanceEngine(headshot, hasAutofocus = false)
        val first = engine.update(LEVEL, box(), eyes(), 100L)
        assertEquals(Verb.HOLD_STEADY, first.verb)
        assertEquals("Hold steady", first.text)
    }

    // ---------------------------------------------------------------------
    // Digital zoom: a fallback, never the first answer
    // ---------------------------------------------------------------------

    @Test
    fun `an engine never told about zoom never advises it`() {
        val engine = GuidanceEngine(headshot)
        val verbs = (1..40).map { engine.update(LEVEL, tooSmall(), eyes(), 100L).verb }.toSet()
        assertEquals(setOf(Verb.STEP_CLOSER), verbs)
    }

    @Test
    fun `stepping closer is the answer until it demonstrably stalls`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 10f)

        // STEP_STALL_MS is 3000. Frame 1 seeds the progress baseline, so the
        // stall clock runs from frame 2: 30 more frames of 100ms to reach it.
        for (frame in 1..30) {
            assertEquals(
                "frame $frame - still worth asking them to move",
                Verb.STEP_CLOSER,
                engine.update(LEVEL, tooSmall(), eyes(), 100L).verb,
            )
        }
        assertEquals(
            "3000ms of unheeded STEP_CLOSER means they probably cannot move",
            Verb.ZOOM_IN,
            engine.update(LEVEL, tooSmall(), eyes(), 100L).verb,
        )
    }

    @Test
    fun `progress towards the subject resets the stall clock`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 10f)
        engine.feed(20, subject = tooSmall(), eyeLine = eyes())

        // The photographer actually walks: the subject grows.
        engine.feed(6, subject = box(h = 0.35f), eyeLine = eyes())

        // The clock restarted, so zoom is not offered for another full stall.
        for (frame in 1..20) {
            assertEquals(
                "frame $frame after real progress",
                Verb.STEP_CLOSER,
                engine.update(LEVEL, box(h = 0.30f), eyes(), 100L).verb,
            )
        }
    }

    @Test
    fun `zoom is not offered when there is no zoom headroom`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 1f) // a camera with no zoom at all
        assertEquals(Verb.STEP_CLOSER, engine.feed(40, subject = tooSmall(), eyeLine = eyes()).verb)
    }

    @Test
    fun `zoom is not offered past the advised ceiling`() {
        val engine = GuidanceEngine(headshot)
        // Already at 3x. Cropping further costs more than the framing gains.
        engine.reportZoom(ratio = GuidanceConstants.ZOOM_MAX_ADVISED, maxRatio = 10f)
        assertEquals(Verb.STEP_CLOSER, engine.feed(40, subject = tooSmall(), eyeLine = eyes()).verb)
    }

    @Test
    fun `too big while zoomed in means zoom out, not walk backwards`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 2f, maxRatio = 10f)
        assertEquals(
            "undoing a crop is free; walking backwards is not",
            Verb.ZOOM_OUT,
            engine.update(LEVEL, tooBig(), eyes(), 100L).verb,
        )
    }

    @Test
    fun `too big at 1x means step back, because there is no crop to undo`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 10f)
        assertEquals(Verb.STEP_BACK, engine.update(LEVEL, tooBig(), eyes(), 100L).verb)
    }

    @Test
    fun `suggestedZoom is the ratio that would put the subject on target`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 10f)
        engine.update(LEVEL, tooSmall(), eyes(), 100L)
        // h 0.20 against a 0.45 target needs 2.25x to land on it.
        assertEquals(2.25f, engine.alignment.suggestedZoom, 1e-3f)
    }

    @Test
    fun `suggestedZoom is capped at the advised ceiling`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 10f)
        // A face at 0.05 would need 9x. We will not advise past 3x.
        engine.update(LEVEL, box(cy = 0.40f, h = 0.05f), eyes(), 100L)
        assertEquals(GuidanceConstants.ZOOM_MAX_ADVISED, engine.alignment.suggestedZoom, 1e-3f)
    }

    @Test
    fun `suggestedZoom falls back to 1x when zoom is not the problem`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 10f)
        engine.update(LEVEL, box(), eyes(), 100L)
        assertEquals(1f, engine.alignment.suggestedZoom, 1e-6f)
    }

    @Test
    fun `reportZoom refuses ratios below 1x, since sub-1x needs an ultrawide`() {
        val engine = GuidanceEngine(headshot)
        // This phone exposes no ultrawide, so 0.6x is not a thing we can reach.
        engine.reportZoom(ratio = 0.6f, maxRatio = 10f)
        engine.update(LEVEL, tooBig(), eyes(), 100L)
        assertEquals(
            "clamped to 1x, so there is no phantom crop to undo",
            Verb.STEP_BACK,
            engine.update(LEVEL, tooBig(), eyes(), 100L).verb,
        )
    }

    @Test
    fun `zoom verbs are not mirrored`() {
        // Zooming is not a direction, so the front camera changes nothing.
        assertEquals(Verb.ZOOM_IN, GuidanceEngine.mirror(Verb.ZOOM_IN))
        assertEquals(Verb.ZOOM_OUT, GuidanceEngine.mirror(Verb.ZOOM_OUT))
        assertEquals(Verb.HOLD_STEADY, GuidanceEngine.mirror(Verb.HOLD_STEADY))
    }

    @Test
    fun `reset clears the stall clock`() {
        val engine = GuidanceEngine(headshot)
        engine.reportZoom(ratio = 1f, maxRatio = 10f)
        engine.feed(40, subject = tooSmall(), eyeLine = eyes()) // stalled, now zooming
        engine.reset()
        assertEquals(
            "after a reset the photographer gets the benefit of the doubt again",
            Verb.STEP_CLOSER,
            engine.update(LEVEL, tooSmall(), eyes(), 100L).verb,
        )
    }
}
