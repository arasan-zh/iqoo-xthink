package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoZoomTest {

    @Test
    fun `small text zooms in, a step at a time, toward filling the frame`() {
        val small = CropRect(0.4f, 0.45f, 0.6f, 0.55f) // a fifth of the width
        // Wants 1 * 0.8 / 0.2 = 4x, but one step is 1.6x at most.
        assertEquals(1.6f, AutoZoom.next(1f, small, 0, 10f)!!, 0.001f)
        // At 2.5x the same box now fills half: wants 2.5 * 0.8 / 0.5 = 4x, within a step.
        val half = CropRect(0.25f, 0.4f, 0.75f, 0.6f)
        assertEquals(4f, AutoZoom.next(2.5f, half, 0, 10f)!!, 0.001f)
    }

    @Test
    fun `text that fills the frame is left alone, spilling text backs the lens off`() {
        val full = CropRect(0.1f, 0.2f, 0.9f, 0.8f) // 0.8 wide: exactly the target
        assertNull(AutoZoom.next(3f, full, 0, 10f))
        val spill = CropRect(0f, 0f, 1f, 1f)
        assertEquals(3f * 0.8f, AutoZoom.next(3f, spill, 0, 10f)!!, 0.001f)
    }

    @Test
    fun `the lens has a ceiling, ours and the phone's`() {
        val tiny = CropRect(0.48f, 0.49f, 0.52f, 0.51f)
        assertEquals(AutoZoom.MAX, AutoZoom.next(5f, tiny, 0, 10f)!!, 0.001f)
        assertEquals(3f, AutoZoom.next(2.5f, tiny, 0, 3f)!!, 0.001f)
        assertNull(AutoZoom.next(AutoZoom.MAX, tiny, 0, 10f))
    }

    @Test
    fun `nothing readable backs out only after a few misses, and never below 1x`() {
        assertNull(AutoZoom.next(3f, null, 1, 10f))
        assertNull(AutoZoom.next(3f, null, 2, 10f))
        assertEquals(3f / AutoZoom.STEP_MAX, AutoZoom.next(3f, null, 3, 10f)!!, 0.001f)
        assertNull(AutoZoom.next(1f, null, 5, 10f))
        assertEquals(1f, AutoZoom.next(1.2f, null, 3, 10f)!!, 0.001f)
    }

    @Test
    fun `focus goes to the middle of the text`() {
        val (fx, fy) = AutoZoom.focus(CropRect(0.2f, 0.3f, 0.8f, 0.4f))
        assertEquals(0.5f, fx, 0.001f)
        assertEquals(0.35f, fy, 0.001f)
    }
}
