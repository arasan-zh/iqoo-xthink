package `in`.arasan.xthink.guidance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearEnoughCaptureTest {

    private fun run(p: AutoCapturePolicy, verb: Verb, error: Float, frames: Int, dt: Long = 100L): Int {
        var shots = 0
        repeat(frames) { if (p.update(verb, stabilityOk = true, subjectPresent = true, dtMs = dt, totalError = error)) shots++ }
        return shots
    }

    @Test
    fun `strict mode never shoots short of the lock`() {
        val p = AutoCapturePolicy()
        assertTrue(run(p, Verb.TILT_UP, 0.2f, 30) == 0)
    }

    @Test
    fun `relaxed mode shoots a small tilt held near the lock`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        val shots = run(p, Verb.TILT_UP, 0.2f, 30)
        assertTrue("expected one shot, got $shots", shots == 1)
    }

    @Test
    fun `relaxed mode needs the dwell - a flicker through the near zone is not a shot`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue(run(p, Verb.TILT_UP, 0.2f, 4) == 0) // 400 ms < NEAR_DWELL_MS
        assertTrue(run(p, Verb.SEEKING, 1f, 2) == 0)
        assertTrue(run(p, Verb.TILT_UP, 0.2f, 4) == 0)
    }

    @Test
    fun `relaxed mode is not fooled by a large error or a coarse verb`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue(run(p, Verb.TILT_UP, 0.7f, 30) == 0)
        assertTrue(run(p, Verb.ZOOM_IN, 0.1f, 30) == 0)
        assertTrue(run(p, Verb.SEEKING, 0.0f, 30) == 0)
    }

    @Test
    fun `one shot per near episode, then re-armed after leaving and the cooldown`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue(run(p, Verb.MOVE_UP, 0.3f, 30) == 1)
        assertTrue(run(p, Verb.MOVE_UP, 0.3f, 30) == 0)        // still the same episode
        run(p, Verb.SEEKING, 1f, 40)                            // leave, wait out the cooldown
        assertTrue(run(p, Verb.MOVE_UP, 0.3f, 30) == 1)
    }

    @Test
    fun `a true lock still shoots in relaxed mode`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue(run(p, Verb.LOCKED, 0f, 1) == 1)
        assertFalse(p.update(Verb.LOCKED, true, true, 100L, 0f)) // once per lock
    }
}
