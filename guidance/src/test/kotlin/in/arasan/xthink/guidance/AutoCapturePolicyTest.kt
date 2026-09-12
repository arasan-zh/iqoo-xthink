package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCapturePolicyTest {

    private val cooldown = GuidanceConstants.AUTO_CAPTURE_COOLDOWN_MS

    @Test
    fun `fires once on a new lock when the phone is steady`() {
        val p = AutoCapturePolicy()
        assertFalse(p.update(Verb.MOVE_LEFT, true, true, 33L))
        assertTrue("the moment the lock lands", p.update(Verb.LOCKED, true, true, 33L))
        assertFalse("not again while the same lock holds", p.update(Verb.LOCKED, true, true, 33L))
        repeat(200) { assertFalse(p.update(Verb.LOCKED, true, true, 33L)) }
    }

    @Test
    fun `waits for the phone to settle within the same lock`() {
        val p = AutoCapturePolicy()
        assertFalse("locked but shaking: a blurred photo", p.update(Verb.LOCKED, false, true, 33L))
        assertFalse(p.update(Verb.LOCKED, false, true, 33L))
        assertTrue("same lock, now steady", p.update(Verb.LOCKED, true, true, 33L))
    }

    @Test
    fun `leaving the lock re-arms for the next one`() {
        val p = AutoCapturePolicy()
        assertTrue(p.update(Verb.LOCKED, true, true, 33L))
        p.update(Verb.MOVE_UP, true, true, 33L)
        // ...but the cooldown still applies to the next acquisition.
        assertFalse("re-locked inside the cooldown", p.update(Verb.LOCKED, true, true, 33L))
        var fired = false
        var t = 0L
        while (t < cooldown + 100) {
            if (p.update(Verb.LOCKED, true, true, 100L)) fired = true
            t += 100L
        }
        assertTrue("fires once the cooldown has elapsed", fired)
    }

    @Test
    fun `a lock that breaks and re-locks every second does not burst`() {
        val p = AutoCapturePolicy()
        var shots = 0
        // 12 seconds of: locked 500ms, unlocked 500ms.
        repeat(12) {
            repeat(15) { if (p.update(Verb.LOCKED, true, true, 33L)) shots++ }
            repeat(15) { if (p.update(Verb.HOLD_STEADY, true, true, 33L)) shots++ }
        }
        // 12s / 3s cooldown = at most 4, plus the first.
        assertTrue("got $shots in 12s; the cooldown should cap this", shots in 3..5)
    }

    @Test
    fun `a manual shot starts the cooldown so auto does not double up`() {
        val p = AutoCapturePolicy()
        p.update(Verb.MOVE_LEFT, true, true, 33L)
        p.notifyManualCapture()
        assertFalse("just shot it by hand; do not shoot it again", p.update(Verb.LOCKED, true, true, 33L))
        assertEquals(cooldown - 33L, p.cooldownRemainingMs)
    }

    @Test
    fun `the first ever lock does not wait for a cooldown`() {
        val p = AutoCapturePolicy()
        assertEquals(0L, p.cooldownRemainingMs)
        assertTrue(p.update(Verb.LOCKED, true, true, 33L))
    }

    @Test
    fun `reset re-arms and clears the cooldown`() {
        val p = AutoCapturePolicy()
        assertTrue(p.update(Verb.LOCKED, true, true, 33L))
        p.reset()
        assertTrue(p.update(Verb.LOCKED, true, true, 33L))
    }

    @Test
    fun `a lock with no subject is not a photo`() {
        // LANDSCAPE locks on a level, steady phone. Auto must not shoot it.
        val p = AutoCapturePolicy()
        repeat(60) { assertFalse(p.update(Verb.LOCKED, true, false, 33L)) }
        // The moment a subject is there and locked, it fires.
        assertTrue(p.update(Verb.LOCKED, true, true, 33L))
    }

    @Test
    fun `losing the subject mid-lock re-arms like leaving the lock does`() {
        val p = AutoCapturePolicy()
        assertTrue(p.update(Verb.LOCKED, true, true, 33L))
        p.update(Verb.LOCKED, true, false, 33L)
        repeat(100) { p.update(Verb.LOCKED, true, false, 33L) } // cooldown elapses
        assertTrue("a new subject under a held lock is a new acquisition", p.update(Verb.LOCKED, true, true, 33L))
    }

    @Test
    fun `a negative dt cannot shorten the cooldown`() {
        val p = AutoCapturePolicy()
        assertTrue(p.update(Verb.LOCKED, true, true, 33L))
        p.update(Verb.MOVE_UP, true, true, -100000L)
        assertFalse(p.update(Verb.LOCKED, true, true, 33L))
    }
}
