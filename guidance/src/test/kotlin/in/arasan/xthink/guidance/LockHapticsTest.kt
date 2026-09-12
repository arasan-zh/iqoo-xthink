package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHapticsTest {

    private fun ticksIn(windowMs: Long, error: Float, verb: Verb = Verb.MOVE_LEFT): Int {
        val h = LockHaptics()
        var n = 0
        var t = 0L
        while (t < windowMs) {
            if (h.update(error, verb, true, 33L) == HapticCue.TICK) n++
            t += 33L
        }
        return n
    }

    @Test
    fun `silent with no subject`() {
        val h = LockHaptics()
        repeat(100) { assertEquals(HapticCue.NONE, h.update(0.1f, Verb.SEEKING, false, 33L)) }
    }

    @Test
    fun `silent while badly off - a buzzing phone is noise, not guidance`() {
        assertEquals(0, ticksIn(5000L, 0.8f))
        assertEquals(0, ticksIn(5000L, GuidanceConstants.HAPTIC_START_ERROR))
    }

    @Test
    fun `ticks quicken as the error falls`() {
        val far = ticksIn(6000L, 0.5f)
        val mid = ticksIn(6000L, 0.25f)
        val near = ticksIn(6000L, 0.05f)
        assertTrue("far=$far mid=$mid near=$near", far in 5..8)
        assertTrue("far=$far mid=$mid near=$near", mid > far)
        assertTrue("far=$far mid=$mid near=$near", near > mid)
        // error 0.05 -> interval ~203ms -> ~26 ticks in 6s (the 140ms floor is at error 0).
        assertTrue("near should be close to the floor: $near in 6s", near >= 24)
    }

    @Test
    fun `interval is monotonic and bounded`() {
        var last = Long.MAX_VALUE
        var e = GuidanceConstants.HAPTIC_START_ERROR
        while (e >= 0f) {
            val i = LockHaptics.intervalFor(e)
            assertTrue(i <= last)
            assertTrue(i in GuidanceConstants.HAPTIC_INTERVAL_NEAR_MS..GuidanceConstants.HAPTIC_INTERVAL_FAR_MS)
            last = i
            e -= 0.05f
        }
        assertEquals(GuidanceConstants.HAPTIC_INTERVAL_NEAR_MS, LockHaptics.intervalFor(0f))
        assertEquals(GuidanceConstants.HAPTIC_INTERVAL_FAR_MS, LockHaptics.intervalFor(GuidanceConstants.HAPTIC_START_ERROR))
    }

    @Test
    fun `tick strength rises as the error falls`() {
        val h = LockHaptics()
        repeat(60) { h.update(0.5f, Verb.MOVE_LEFT, true, 33L) }
        val weak = h.lastTickStrength
        repeat(60) { h.update(0.05f, Verb.MOVE_LEFT, true, 33L) }
        val strong = h.lastTickStrength
        assertTrue("weak=$weak strong=$strong", strong > weak)
    }

    @Test
    fun `exactly one LOCK, then silence while it holds`() {
        val h = LockHaptics()
        h.update(0.1f, Verb.MOVE_LEFT, true, 33L)
        assertEquals(HapticCue.LOCK, h.update(0f, Verb.LOCKED, true, 33L))
        repeat(300) { assertEquals("the quiet is the reward", HapticCue.NONE, h.update(0f, Verb.LOCKED, true, 33L)) }
    }

    @Test
    fun `one UNLOCK when the lock is lost, then the game resumes`() {
        val h = LockHaptics()
        h.update(0f, Verb.LOCKED, true, 33L)
        assertEquals(HapticCue.UNLOCK, h.update(0.2f, Verb.MOVE_UP, true, 33L))
        // Then ordinary ticking again.
        var ticked = false
        repeat(40) { if (h.update(0.2f, Verb.MOVE_UP, true, 33L) == HapticCue.TICK) ticked = true }
        assertTrue(ticked)
    }

    @Test
    fun `losing the subject while locked is felt once as UNLOCK`() {
        val h = LockHaptics()
        h.update(0f, Verb.LOCKED, true, 33L)
        assertEquals(HapticCue.UNLOCK, h.update(0f, Verb.SEEKING, false, 33L))
        assertEquals(HapticCue.NONE, h.update(0f, Verb.SEEKING, false, 33L))
    }

    @Test
    fun `a negative dt never advances the rhythm`() {
        val h = LockHaptics()
        repeat(50) { assertEquals(HapticCue.NONE, h.update(0.1f, Verb.MOVE_LEFT, true, -1000L)) }
    }

    @Test
    fun `reset forgets the lock`() {
        val h = LockHaptics()
        h.update(0f, Verb.LOCKED, true, 33L)
        h.reset()
        assertEquals(HapticCue.LOCK, h.update(0f, Verb.LOCKED, true, 33L))
    }
}
