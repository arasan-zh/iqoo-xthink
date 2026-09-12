package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetouchTest {

    private val face = SubjectBox(cx = 0.5f, cy = 0.3f, w = 0.12f, h = 0.16f)

    @Test
    fun `the person is never offered - anything in the body column is dropped`() {
        val found = listOf(
            CropRect(0.40f, 0.25f, 0.60f, 0.95f), // the person
            CropRect(0.05f, 0.80f, 0.15f, 0.92f), // litter, bottom-left
            CropRect(0.80f, 0.10f, 0.95f, 0.30f), // a sign, top-right
        )
        val c = Retouch.eligible(found, face, listOf("Person", "Food", null))
        assertEquals(listOf(1, 2), c.map { it.id })
        assertEquals(listOf("Food", null), c.map { it.label })
    }

    @Test
    fun `huge and tiny boxes are not distractions`() {
        val found = listOf(
            CropRect(0f, 0f, 0.8f, 0.8f), // the background
            CropRect(0.1f, 0.1f, 0.11f, 0.11f), // noise
            CropRect(0.05f, 0.80f, 0.15f, 0.92f),
        )
        assertEquals(1, Retouch.eligible(found, null).size)
    }

    @Test
    fun `the description is numbered and placed in plain words`() {
        val c = Retouch.eligible(listOf(CropRect(0.0f, 0.80f, 0.12f, 0.95f), CropRect(0.45f, 0.45f, 0.55f, 0.55f)), null, listOf("Home good", null))
        val text = Retouch.describe(c)
        assertEquals("1: bottom-left edge, 2% of the frame, looks like home good\n2: centre, 1% of the frame", text)
    }

    @Test
    fun `answers are read - numbers only, in range, with the reason`() {
        assertEquals(Retouch.Decision(listOf(1, 3), "litter and a cable"), Retouch.parse("REMOVE 1, 3 | litter and a cable.", 3))
        assertEquals(Retouch.Decision(listOf(2), "stray bag"), Retouch.parse("  remove 2 | stray bag\nmore words", 3))
        assertEquals(Retouch.Decision(emptyList(), "nothing distracts"), Retouch.parse("NONE | nothing distracts", 3))
        assertEquals(emptyList<Int>(), Retouch.parse("REMOVE 7 | out of range", 3).remove)
        assertEquals(emptyList<Int>(), Retouch.parse("The photo is lovely.", 3).remove)
        assertEquals(emptyList<Int>(), Retouch.parse(null, 3).remove)
    }

    @Test
    fun `holes grow past the detector's box and stay inside the frame`() {
        val c = listOf(Retouch.Candidate(1, CropRect(0.0f, 0.5f, 0.10f, 0.60f), null))
        val h = Retouch.holes(c, listOf(1)).single()
        assertEquals(0f, h.left)
        assertTrue(h.right > 0.10f && h.top < 0.5f && h.bottom > 0.6f)
        assertTrue(Retouch.holes(c, listOf(2)).isEmpty())
    }

    @Test
    fun `the inpainting window is square in pixels and holds the holes with margin`() {
        // A 3:4 portrait photo: width is 0.75 of height.
        val aspect = 0.75f
        val hole = CropRect(0.05f, 0.80f, 0.20f, 0.90f)
        val w = Retouch.window(listOf(hole), aspect)
        val px = Retouch.toPixels(w, 3000, 4000)
        assertEquals("square in pixels", px[2], px[3])
        assertTrue(w.left <= hole.left && w.right >= hole.right && w.top <= hole.top && w.bottom >= hole.bottom)
        assertTrue(w.left >= 0f && w.top >= 0f && w.right <= 1f && w.bottom <= 1f)
        assertTrue("never smaller than the floor", px[2] >= 3000 * Retouch.MIN_WINDOW - 1)
    }

    @Test
    fun `holes far apart widen the window up to the whole short side`() {
        val w = Retouch.window(listOf(CropRect(0.02f, 0.02f, 0.1f, 0.1f), CropRect(0.9f, 0.9f, 0.98f, 0.98f)), 1f)
        assertEquals(CropRect(0f, 0f, 1f, 1f), w)
    }

    @Test
    fun `a hole is re-expressed inside its window`() {
        val window = CropRect(0.5f, 0.5f, 1f, 1f)
        assertEquals(CropRect(0f, 0f, 0.5f, 0.5f), Retouch.within(CropRect(0.5f, 0.5f, 0.75f, 0.75f), window))
    }
}
