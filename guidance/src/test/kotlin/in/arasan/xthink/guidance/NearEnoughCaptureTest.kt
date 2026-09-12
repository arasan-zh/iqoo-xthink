package `in`.arasan.xthink.guidance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearEnoughCaptureTest {

    private fun run(
        p: AutoCapturePolicy, verb: Verb, error: Float, frames: Int, dt: Long = 100L, sharp: Boolean = true,
    ): Int {
        var shots = 0
        repeat(frames) {
            if (p.update(verb, stabilityOk = true, subjectPresent = true, dtMs = dt, totalError = error, sharp = sharp)) shots++
        }
        return shots
    }

    /** Frames of struggling to cover NEAR_AFTER_MS at 100 ms each. */
    private val struggle = (AutoCapturePolicy.NEAR_AFTER_MS / 100L).toInt()

    @Test
    fun `strict mode never shoots short of the lock`() {
        val p = AutoCapturePolicy()
        assertTrue(run(p, Verb.TILT_UP, 0.2f, struggle + 60) == 0)
    }

    @Test
    fun `relaxed mode waits 30 s of trying before a near frame counts`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue("too early", run(p, Verb.TILT_UP, 0.2f, struggle - 20) == 0)
        val shots = run(p, Verb.TILT_UP, 0.2f, 40)
        assertTrue("expected one shot after the struggle, got $shots", shots == 1)
    }

    @Test
    fun `reaching the lock resets the struggle clock`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        run(p, Verb.TILT_UP, 0.2f, struggle - 5)
        assertTrue(run(p, Verb.LOCKED, 0f, 1) == 1)          // the correct capture
        repeat(40) { p.update(Verb.SEEKING, true, subjectPresent = false, dtMs = 100L) } // subject gone, cool down
        assertTrue("clock must restart", run(p, Verb.TILT_UP, 0.2f, struggle - 5) == 0)
    }

    @Test
    fun `relaxed mode needs the dwell - a flicker through the near zone is not a shot`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        run(p, Verb.MOVE_UP, 0.9f, struggle)                  // struggling, far
        assertTrue(run(p, Verb.TILT_UP, 0.2f, 4) == 0)        // 400 ms < NEAR_DWELL_MS
        assertTrue(run(p, Verb.MOVE_UP, 0.9f, 2) == 0)
        assertTrue(run(p, Verb.TILT_UP, 0.2f, 4) == 0)
    }

    @Test
    fun `relaxed mode is not fooled by a large error or a coarse verb`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue(run(p, Verb.TILT_UP, 0.7f, struggle + 60) == 0)
        assertTrue(run(p, Verb.ZOOM_IN, 0.1f, struggle + 60) == 0)
        assertTrue(run(p, Verb.SEEKING, 0.0f, struggle + 60) == 0)
    }

    @Test
    fun `a soft frame is never taken - at the lock or near it`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue(run(p, Verb.LOCKED, 0f, 10, sharp = false) == 0)
        assertTrue(run(p, Verb.LOCKED, 0f, 1, sharp = true) == 1)
        val q = AutoCapturePolicy().apply { relaxed = true }
        run(q, Verb.TILT_UP, 0.2f, struggle)
        assertTrue(run(q, Verb.TILT_UP, 0.2f, 40, sharp = false) == 0)
        assertTrue(run(q, Verb.TILT_UP, 0.2f, 10, sharp = true) == 1)
    }

    @Test
    fun `a true lock still shoots in relaxed mode`() {
        val p = AutoCapturePolicy().apply { relaxed = true }
        assertTrue(run(p, Verb.LOCKED, 0f, 1) == 1)
        assertFalse(p.update(Verb.LOCKED, true, true, 100L, 0f)) // once per lock
    }
}
