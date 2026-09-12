package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionCuesTest {

    @Test
    fun `left and right are different signatures`() {
        val left = DirectionCues.forTransition(Verb.HOLD_STEADY, Verb.MOVE_LEFT)
        val right = DirectionCues.forTransition(Verb.HOLD_STEADY, Verb.MOVE_RIGHT)
        assertEquals(HapticCue.DIR_LEFT, left)
        assertEquals(HapticCue.DIR_RIGHT, right)
    }

    @Test
    fun `every directional verb has a signature, and up and down differ`() {
        assertEquals(HapticCue.DIR_UP, DirectionCues.forTransition(null, Verb.MOVE_UP))
        assertEquals(HapticCue.DIR_UP, DirectionCues.forTransition(null, Verb.TILT_UP))
        assertEquals(HapticCue.DIR_DOWN, DirectionCues.forTransition(null, Verb.MOVE_DOWN))
        assertEquals(HapticCue.DIR_ROTATE, DirectionCues.forTransition(null, Verb.LEVEL_CW))
        assertEquals(HapticCue.DIR_ROTATE, DirectionCues.forTransition(null, Verb.LEVEL_CCW))
        assertEquals(HapticCue.DIR_CLOSER, DirectionCues.forTransition(null, Verb.ZOOM_IN))
        assertEquals(HapticCue.DIR_BACK, DirectionCues.forTransition(null, Verb.STEP_BACK))
    }

    @Test
    fun `a signature plays once - the same verb again is silent`() {
        assertEquals(HapticCue.NONE, DirectionCues.forTransition(Verb.MOVE_LEFT, Verb.MOVE_LEFT))
    }

    @Test
    fun `non-directional states have no signature - the lock game owns those`() {
        assertEquals(HapticCue.NONE, DirectionCues.forTransition(Verb.MOVE_LEFT, Verb.LOCKED))
        assertEquals(HapticCue.NONE, DirectionCues.forTransition(Verb.MOVE_LEFT, Verb.SEEKING))
        assertEquals(HapticCue.NONE, DirectionCues.forTransition(Verb.MOVE_LEFT, Verb.TAP_FOCUS))
        assertEquals(HapticCue.NONE, DirectionCues.forTransition(Verb.MOVE_LEFT, Verb.HOLD_STEADY))
    }
}
