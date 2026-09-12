package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmaTest {

    @Test
    fun `seeds on the first sample instead of ramping from zero`() {
        val ema = Ema(GuidanceConstants.EMA_ALPHA_BOX)
        assertFalse(ema.seeded)
        assertEquals(0.80f, ema.update(0.80f), 1e-6f)
        assertTrue(ema.seeded)
    }

    @Test
    fun `applies alpha to later samples`() {
        val ema = Ema(0.25f)
        ema.update(0f)
        assertEquals(0.25f, ema.update(1f), 1e-6f)
        assertEquals(0.4375f, ema.update(1f), 1e-6f)
    }

    @Test
    fun `converges on a steady input`() {
        val ema = Ema(GuidanceConstants.EMA_ALPHA_ANGLE)
        ema.update(0f)
        repeat(100) { ema.update(10f) }
        assertEquals(10f, ema.value, 1e-3f)
    }

    @Test
    fun `reset clears the seed`() {
        val ema = Ema(0.25f)
        ema.update(5f)
        ema.reset()
        assertFalse(ema.seeded)
        assertEquals(-3f, ema.update(-3f), 1e-6f)
    }

    @Test
    fun `angle ema takes the short way round the wrap point`() {
        val ema = AngleEma(0.5f)
        ema.update(179f)
        // Naive averaging would swing to ~0; the short way is 2 degrees across
        // the boundary, landing at 180.
        assertEquals(180f, ema.update(-179f), 1e-3f)
    }

    @Test
    fun `normalizeDeg wraps into plus or minus 180`() {
        assertEquals(-170f, normalizeDeg(190f), 1e-4f)
        assertEquals(170f, normalizeDeg(-190f), 1e-4f)
        assertEquals(0f, normalizeDeg(360f), 1e-4f)
        assertEquals(2f, shortestDeltaDeg(-179f, 179f), 1e-4f)
    }
}

class HysteresisGateTest {

    private val enter = GuidanceConstants.DEADZONE_ROLL_DEG      // 2.5
    private val exit = enter * GuidanceConstants.HYSTERESIS_EXIT_MULTIPLIER // 4.0

    @Test
    fun `exit threshold is 1_6x the entry threshold`() {
        assertEquals(4.0f, HysteresisGate(enter).exitThreshold, 1e-6f)
    }

    @Test
    fun `starts outside the deadzone`() {
        assertFalse(HysteresisGate(enter).inside)
    }

    @Test
    fun `entry requires the error to reach the entry threshold`() {
        val gate = HysteresisGate(enter)
        assertFalse("just above entry must stay outside", gate.update(2.6f))
        assertFalse("below exit is still not good enough to enter", gate.update(3.9f))
        assertTrue("exactly at entry counts as inside", gate.update(2.5f))
    }

    @Test
    fun `once inside it holds until the error passes the exit threshold`() {
        val gate = HysteresisGate(enter)
        assertTrue(gate.update(1.0f))
        // This whole band would have been "outside" on the way in. Hysteresis
        // is the point: no flicker while the error hovers at the boundary.
        assertTrue(gate.update(2.6f))
        assertTrue(gate.update(3.5f))
        assertTrue("exactly at exit is still inside", gate.update(4.0f))
        assertFalse("past exit finally drops out", gate.update(4.01f))
    }

    @Test
    fun `after exiting, re-entry needs the full entry threshold again`() {
        val gate = HysteresisGate(enter)
        gate.update(1.0f)
        gate.update(exit + 1f)
        assertFalse(gate.inside)
        assertFalse("3.0 got you in before, but not after an exit", gate.update(3.0f))
        assertTrue(gate.update(2.4f))
    }

    @Test
    fun `takes the absolute value of the error`() {
        val gate = HysteresisGate(enter)
        assertTrue(gate.update(-1.0f))
        assertFalse(gate.update(-9.0f))
    }

    @Test
    fun `reset returns it to outside`() {
        val gate = HysteresisGate(enter)
        gate.update(0f)
        assertTrue(gate.inside)
        gate.reset()
        assertFalse(gate.inside)
    }
}
