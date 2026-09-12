package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepCounterTest {

    @Test
    fun `the angle at a joint`() {
        assertEquals(180f, JointAngles.angle(Joint(0f, 0f), Joint(0f, 1f), Joint(0f, 2f)), 0.01f)
        assertEquals(90f, JointAngles.angle(Joint(0f, 0f), Joint(0f, 1f), Joint(1f, 1f)), 0.01f)
        assertEquals(45f, JointAngles.angle(Joint(0f, 0f), Joint(0f, 1f), Joint(1f, 0f)), 0.01f)
    }

    private fun feed(c: RepCounter, angles: List<Float>, dt: Long = 100L): Int = angles.count { c.update(it, dt) }

    @Test
    fun `a full squat counts once`() {
        val c = RepCounter(Exercise.SQUAT)
        val reps = feed(c, listOf(175f, 170f, 150f, 120f, 95f, 90f, 92f, 120f, 150f, 165f, 175f))
        assertEquals(1, reps); assertEquals(1, c.count); assertEquals(RepCounter.Phase.UP, c.phase)
    }

    @Test
    fun `a half squat does not count, a wobble at the bottom counts once`() {
        val c = RepCounter(Exercise.SQUAT)
        assertEquals(0, feed(c, listOf(175f, 140f, 120f, 130f, 175f)))
        assertEquals(1, feed(c, listOf(95f, 90f, 105f, 92f, 98f, 91f, 120f, 175f)))
        assertEquals(1, c.count)
    }

    @Test
    fun `a bounce faster than a person is noise`() {
        val c = RepCounter(Exercise.SQUAT)
        assertEquals(0, feed(c, listOf(175f, 90f, 175f), dt = 50L))
        assertEquals(1, feed(c, listOf(90f, 90f, 90f, 175f), dt = 100L))
    }

    @Test
    fun `push-ups use the elbow's thresholds`() {
        val c = RepCounter(Exercise.PUSHUP)
        assertEquals(3, feed(c, listOf(170f, 90f, 90f, 90f, 165f, 88f, 88f, 88f, 170f, 92f, 92f, 92f, 160f)))
        c.reset()
        assertEquals(0, c.count); assertEquals(RepCounter.Phase.WAITING, c.phase)
    }
}
