package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGovernorTest {

    private val hold = GuidanceConstants.THERMAL_TIER_HOLD_MS

    /** Feed one reading per second until past the hold. */
    private fun ThermalGovernor.settle(headroom: Float, status: Int = 0): ThermalPlan {
        var p = plan
        repeat((hold / 1000L).toInt() + 2) { p = update(headroom, status, 1000L) }
        return p
    }

    @Test
    fun `idle is COOL and everything is on`() {
        val g = ThermalGovernor()
        val p = g.settle(0.398f) // the probe's idle reading
        assertEquals(ThermalTier.COOL, p.tier)
        assertEquals(GuidanceConstants.ANALYSIS_INTERVAL_COOL_MS, p.analysisIntervalMs)
        assertTrue(p.hapticsOn)
        assertTrue(p.autoCaptureOn)
    }

    @Test
    fun `tiers rise with headroom and slow the detector`() {
        val g = ThermalGovernor()
        assertEquals(ThermalTier.WARM, g.settle(0.65f).tier)
        assertEquals(GuidanceConstants.ANALYSIS_INTERVAL_WARM_MS, g.plan.analysisIntervalMs)
        assertEquals(ThermalTier.HOT, g.settle(0.85f).tier)
        assertFalse("a motor is a heater", g.plan.hapticsOn)
        assertTrue(g.plan.autoCaptureOn)
        assertEquals(ThermalTier.CRITICAL, g.settle(0.97f).tier)
        assertFalse(g.plan.autoCaptureOn)
        assertEquals(GuidanceConstants.ANALYSIS_INTERVAL_CRITICAL_MS, g.plan.analysisIntervalMs)
    }

    @Test
    fun `a change must persist for the hold`() {
        val g = ThermalGovernor()
        assertEquals(ThermalTier.COOL, g.update(0.7f, 0, 1000L).tier)
        assertEquals("1s is not 2s", ThermalTier.COOL, g.update(0.7f, 0, 900L).tier)
        assertEquals(ThermalTier.WARM, g.update(0.7f, 0, 200L).tier)
    }

    @Test
    fun `a value hovering at the threshold does not toggle the rate`() {
        val g = ThermalGovernor()
        g.settle(0.65f)
        assertEquals(ThermalTier.WARM, g.tier)
        // 0.58 is below 0.60 but not below 0.55: stay WARM.
        assertEquals(ThermalTier.WARM, g.settle(0.58f).tier)
        // Dip below the hysteresis edge and it steps down.
        assertEquals(ThermalTier.COOL, g.settle(0.54f).tier)
    }

    @Test
    fun `a spike that does not last is ignored`() {
        val g = ThermalGovernor()
        g.settle(0.4f)
        g.update(0.9f, 0, 1000L)  // one hot reading
        g.update(0.4f, 0, 1000L)  // withdrawn
        assertEquals(ThermalTier.COOL, g.tier)
    }

    @Test
    fun `NaN headroom falls back to the status`() {
        val g = ThermalGovernor()
        assertEquals(ThermalTier.COOL, g.settle(Float.NaN, status = 0).tier)
        assertEquals(ThermalTier.HOT, g.settle(Float.NaN, status = 2).tier)
        assertEquals(ThermalTier.CRITICAL, g.settle(Float.NaN, status = 3).tier)
    }

    @Test
    fun `status is a floor - it can raise a tier, never lower one`() {
        val g = ThermalGovernor()
        assertEquals("headroom says HOT, status says nothing", ThermalTier.HOT, g.settle(0.85f, status = 0).tier)
        assertEquals("headroom says COOL, status says MODERATE", ThermalTier.HOT, g.settle(0.3f, status = 2).tier)
    }

    @Test
    fun `cooling down walks back through the tiers`() {
        val g = ThermalGovernor()
        g.settle(0.97f)
        assertEquals(ThermalTier.CRITICAL, g.tier)
        assertEquals(ThermalTier.HOT, g.settle(0.85f).tier)
        assertEquals(ThermalTier.WARM, g.settle(0.65f).tier)
        assertEquals(ThermalTier.COOL, g.settle(0.4f).tier)
        assertTrue(g.plan.hapticsOn)
    }

    @Test
    fun `plans are monotonic - hotter is never faster or noisier`() {
        var last = ThermalPlan.forTier(ThermalTier.COOL)
        for (t in ThermalTier.entries.drop(1)) {
            val p = ThermalPlan.forTier(t)
            assertTrue(p.analysisIntervalMs >= last.analysisIntervalMs)
            assertTrue(!p.hapticsOn || last.hapticsOn)
            assertTrue(!p.autoCaptureOn || last.autoCaptureOn)
            last = p
        }
    }

    @Test
    fun `a negative dt cannot advance the hold`() {
        val g = ThermalGovernor()
        repeat(20) { g.update(0.9f, 0, -1000L) }
        assertEquals(ThermalTier.COOL, g.tier)
    }
}
