package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTest {

    private val dark = IntArray(24 * 18) { 20 }
    private val lit = IntArray(24 * 18) { 60 } // 40/255 = 0.16 different from dark
    private val nearly = IntArray(24 * 18) { 24 } // 4/255 = 0.016: not a change

    @Test
    fun `frames differ by mean luma, and mismatched grids count as different`() {
        assertEquals(0f, FrameDiff.difference(dark, dark), 0f)
        assertEquals(40f / 255f, FrameDiff.difference(dark, lit), 0.0001f)
        assertEquals(1f, FrameDiff.difference(dark, IntArray(3)), 0f)
    }

    @Test
    fun `the first frame is looked at, a still scene once a minute, a changed one after fifteen seconds`() {
        val s = WatchSession(startMs = 0L)
        assertTrue(s.look(0L, dark))
        assertFalse(s.look(2_000L, dark))
        assertFalse(s.look(20_000L, nearly)) // changed, but not enough
        assertFalse(s.look(59_000L, dark))
        assertTrue(s.look(60_000L, dark)) // the minute
        assertFalse(s.look(70_000L, lit)) // changed, but too soon after the last look
        assertTrue(s.look(75_000L, lit)) // changed, and fifteen seconds on
        assertEquals(3, s.looks)
    }

    @Test
    fun `a shrug is not a note, and blanks are dropped`() {
        val s = WatchSession(startMs = 0L)
        s.noteSeen(5_000L, "NOTHING NEW")
        s.noteSeen(6_000L, "  Nothing new since the last note. ")
        s.noteSeen(7_000L, "")
        s.noteSeen(8_000L, null)
        assertTrue(s.seen.isEmpty())
        assertNull(s.lastSeen())
        s.noteSeen(9_000L, "\"A man in a blue shirt sits down at the desk.\"")
        assertEquals("A man in a blue shirt sits down at the desk", s.lastSeen())
        s.noteSeen(9_500L, "A man in a blue shirt sits down at the desk. NOTHING NEW")
        s.noteSeen(9_600L, "A man in a blue shirt sits down at the desk.")
        assertEquals(1, s.seen.size)
        s.noteHeard(10_000L, " the build is green ")
        assertEquals("the build is green", s.heard.single().text)
    }

    @Test
    fun `five minutes and it is over`() {
        val s = WatchSession(startMs = 1000L)
        assertFalse(s.over(1000L + WatchSession.LIMIT_MS - 1))
        assertTrue(s.over(1000L + WatchSession.LIMIT_MS))
        assertEquals(0L, s.remainingMs(1000L + WatchSession.LIMIT_MS + 5))
    }

    @Test
    fun `the report carries the facts, the summary, and everything seen and heard in order`() {
        val s = WatchSession(startMs = 0L)
        s.look(0L, dark)
        s.noteSeen(3_000L, "An empty desk with a laptop")
        s.noteHeard(12_000L, "let's start the build")
        s.look(65_000L, lit)
        s.noteSeen(68_000L, "Someone in a red jacket walks in from the left")
        s.end(90_000L, "finish")
        assertEquals("Length 1:30. The model looked 2 times and wrote 2 notes; 1 things were heard. Lighting good.", s.facts("Lighting good."))
        val r = s.report("2026-09-13 06:20", "Lighting good.", "xthink_1.mp4", "A quiet desk, then a visitor.")
        assertTrue(r.startsWith("# Watch · 2026-09-13 06:20\n"))
        assertTrue(r.contains("Length 1:30 (ended: finish). Video: xthink_1.mp4 - no audio track"))
        assertTrue(r.contains("## What mattered\n\nA quiet desk, then a visitor."))
        assertTrue(r.contains("## Seen (2, from 2 looks)\n\n- 0:03  An empty desk with a laptop\n- 1:08  Someone in a red jacket"))
        assertTrue(r.contains("## Heard (1)\n\n- 0:12  \"let's start the build\""))
        // Without a summary the section is left out, not left empty.
        assertFalse(s.report("x", "e", null, null).contains("What mattered"))
        assertTrue(s.report("x", "e", null, null).contains("No video was kept."))
    }
}
