package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkGuideTest {

    private fun box(cx: Float, cy: Float, w: Float, h: Float) = SubjectBox(cx = cx, cy = cy, w = w, h = h)
    private val bigAhead = listOf(box(0.5f, 0.7f, 0.5f, 0.5f)) // 25% of the frame, low, centred
    private val someAhead = listOf(box(0.5f, 0.7f, 0.3f, 0.3f)) // 9%
    private val onLeft = listOf(box(0.15f, 0.75f, 0.3f, 0.4f)) // 12%, left third
    private val ceilingLight = listOf(box(0.5f, 0.1f, 0.5f, 0.3f)) // big but high
    private val speck = listOf(box(0.5f, 0.8f, 0.1f, 0.1f)) // 1%

    /** Feed the same frame until the smoothing has caught up. */
    private fun WalkGuide.settle(boxes: List<SubjectBox>, from: Long, frames: Int = 30, stepMs: Long = 100L): WalkAdvice {
        var a = onObjects(boxes, from)
        for (i in 1 until frames) a = onObjects(boxes, from + i * stepMs)
        return a
    }

    @Test
    fun `a big thing low and ahead means stop, a small one means slow, nothing means clear`() {
        val g = WalkGuide(startMs = 0L)
        assertEquals(WalkVerb.CLEAR, g.settle(emptyList(), 0L).verb)
        assertEquals(WalkVerb.STOP, g.settle(bigAhead, 10_000L).verb)
        assertEquals(WalkVerb.SLOW, g.settle(someAhead, 20_000L).verb)
        assertEquals(WalkVerb.CLEAR, g.settle(emptyList(), 30_000L).verb)
    }

    @Test
    fun `things high in the frame or tiny are not obstacles`() {
        val g = WalkGuide(startMs = 0L)
        assertEquals(WalkVerb.CLEAR, g.settle(ceilingLight, 0L).verb)
        assertEquals(WalkVerb.CLEAR, g.settle(speck, 10_000L).verb)
    }

    @Test
    fun `an obstacle on the left says keep right, with the right-hand pulse`() {
        val g = WalkGuide(startMs = 0L)
        val a = g.settle(onLeft, 0L)
        assertEquals(WalkVerb.KEEP_RIGHT, a.verb)
        assertEquals(HapticCue.DIR_RIGHT, a.cue)
        assertTrue(a.words.contains("Keep right"))
    }

    @Test
    fun `one frame of nothing does not clear a stop - the answer is smoothed and held`() {
        val g = WalkGuide(startMs = 0L)
        g.settle(bigAhead, 0L)
        val a = g.onObjects(emptyList(), 3100L)
        assertEquals(WalkVerb.STOP, a.verb)
    }

    @Test
    fun `an instruction is held before it may change`() {
        val g = WalkGuide(startMs = 0L)
        g.settle(bigAhead, 0L) // STOP, shown at some time <= 2900
        // Everything gone at once: the smoothed area falls, but the lockout keeps STOP for a while.
        val t0 = 3000L
        var first: WalkVerb? = null
        for (i in 0 until 4) {
            val v = g.onObjects(emptyList(), t0 + i * 100L).verb
            if (first == null) first = v
        }
        assertEquals(WalkVerb.STOP, first)
        assertEquals(WalkVerb.CLEAR, g.settle(emptyList(), 4000L).verb)
    }

    @Test
    fun `it speaks on change, repeats stop while walking, and offers the way once standing`() {
        val g = WalkGuide(startMs = 0L)
        g.settle(emptyList(), 0L)
        // The first word is "Path clear."
        assertEquals("Path clear.", g.announcement(3000L)!!.words)
        assertNull(g.announcement(3100L))
        // A wall: STOP, said once...
        g.settle(bigAhead, 4000L)
        val stop = g.announcement(7000L)!!
        assertEquals(HapticCue.DIR_BACK, stop.cue)
        assertNull(g.announcement(7100L))
        // ...and again if the walker keeps walking into it.
        g.onStep(8000L); g.onStep(8500L); g.onStep(9000L)
        assertNull(g.announcement(9000L))
        assertEquals("Stop.", g.announcement(9600L)!!.words)
        // Way clear, standing five seconds: "you can walk", once.
        g.settle(emptyList(), 10_000L)
        assertEquals("Path clear.", g.announcement(13_000L)!!.words)
        assertNull(g.announcement(16_000L))
        val go = g.announcement(20_000L)!!
        assertTrue(go.words.contains("You can walk"))
        assertNull(g.announcement(21_000L))
    }

    @Test
    fun `walking is steps in the last moments, and the journal keeps the story`() {
        val g = WalkGuide(startMs = 0L)
        assertFalse(g.walking(1000L))
        g.onStep(1000L); g.onStep(1500L)
        assertTrue(g.walking(2000L))
        assertFalse(g.walking(5000L))
        g.announcement(2000L) // walking noted
        g.announcement(6000L) // standing noted
        g.settle(bigAhead, 7000L)
        g.end(30_000L, "time")
        val j = g.journal()
        assertTrue(j, j.contains("Started walking"))
        assertTrue(j, j.contains("Stood still after walking"))
        assertTrue(j, j.contains("Obstacle ahead - stop"))
        assertTrue(j, j.contains("0:30  Walk ended (time)"))
        assertEquals(2, g.steps)
        val f = g.facts()
        assertEquals("Duration 0:30 - that is 30 seconds. Steps: 2. Walking: 4 seconds. Standing: 26 seconds. Stopped for an obstacle 1 time (at 0:07). Steered around something 0 times.", f)
    }

    @Test
    fun `walking time counts a walk still under way when it ends`() {
        val g = WalkGuide(startMs = 0L)
        g.onStep(1000L); g.announcement(1000L)
        g.onStep(2000L); g.onStep(3000L)
        g.end(4000L, "finish")
        assertTrue(g.facts(), g.facts().contains("Walking: 3 seconds"))
        assertTrue(g.facts(), g.facts().contains("Standing: 1 seconds"))
    }

    @Test
    fun `five minutes and it is over`() {
        val g = WalkGuide(startMs = 1000L)
        assertFalse(g.over(1000L + WalkGuide.LIMIT_MS - 1))
        assertTrue(g.over(1000L + WalkGuide.LIMIT_MS))
        assertEquals(0L, g.remainingMs(1000L + WalkGuide.LIMIT_MS + 5))
        assertEquals(WalkGuide.LIMIT_MS, g.remainingMs(1000L))
    }

    @Test
    fun `the step detector counts a walking swing and ignores a held phone`() {
        val d = StepDetector()
        var steps = 0
        // 20 Hz samples. Two seconds still, then four strides at 2 Hz.
        var t = 0L
        repeat(40) { if (d.feed(9.81f + (if (it % 2 == 0) 0.2f else -0.2f), t)) steps++; t += 50 }
        assertEquals(0, steps)
        repeat(80) { i ->
            val phase = (i % 10) / 10f
            val mag = 9.81f + if (phase < 0.3f) 3.0f else -1.0f
            if (d.feed(mag, t)) steps++
            t += 50
        }
        assertEquals(8, steps)
    }
}
