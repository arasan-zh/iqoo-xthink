package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookRationTest {

    private val dark = IntArray(24 * 18) { 20 }
    private val lit = IntArray(24 * 18) { 60 }

    @Test
    fun `the first frame at once, a changed one after the minimum, a still one at the maximum`() {
        val r = LookRation(minMs = 15_000L, maxMs = 45_000L, change = 0.06f)
        assertTrue(r.look(0L, dark))
        assertFalse(r.look(2_000L, dark))
        assertFalse(r.look(10_000L, lit)) // changed, but too soon
        assertTrue(r.look(16_000L, lit)) // changed, and past the minimum
        assertFalse(r.look(40_000L, lit)) // still: not yet the maximum
        assertTrue(r.look(61_000L, lit)) // the maximum
        assertEquals(3, r.looks)
    }
}
