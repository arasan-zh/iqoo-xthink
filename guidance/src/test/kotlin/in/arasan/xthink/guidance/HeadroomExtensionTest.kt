package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadroomExtensionTest {

    @Test
    fun `a head against a plain top edge gets the missing headroom`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.12f, w = 0.24f, h = 0.20f) // top at 0.02
        val extra = HeadroomExtension.extraTop(face, topStripStdDev = 5f)
        val want = HeadroomExtension.WANTED_FACES * 0.20f - 0.02f
        assertEquals(want, extra, 0.001f)
    }

    @Test
    fun `a busy top edge is left alone`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.12f, w = 0.24f, h = 0.20f)
        assertEquals(0f, HeadroomExtension.extraTop(face, topStripStdDev = 40f), 0f)
    }

    @Test
    fun `a head with room already gets nothing`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.40f, w = 0.24f, h = 0.20f) // top at 0.30
        assertEquals(0f, HeadroomExtension.extraTop(face, topStripStdDev = 2f), 0f)
    }

    @Test
    fun `extension is capped`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.45f, w = 0.9f, h = 0.90f) // huge face, top at 0
        assertEquals(HeadroomExtension.MAX_EXTRA, HeadroomExtension.extraTop(face, 1f), 0.001f)
    }

    @Test
    fun `shifting keeps the face where it is in pixels`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.12f, w = 0.24f, h = 0.20f)
        val extra = 0.1f
        val s = HeadroomExtension.shift(face, extra)
        // Original photo height H; new height 1.1H. Face top was 0.02H, is now 0.12H of the old -> 0.12/1.1 of new.
        assertEquals((0.02f + extra) / 1.1f, s.cy - s.h / 2f, 0.001f)
        assertEquals(0.20f / 1.1f, s.h, 0.001f)
        val pose = HeadroomExtension.shift(BodyPose(hipY = 0.6f, kneeY = null), extra)!!
        assertEquals((0.6f + extra) / 1.1f, pose.hipY!!, 0.001f)
        assertTrue(pose.kneeY == null)
    }

    @Test
    fun `the crop then keeps the added room as headroom`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.12f, w = 0.24f, h = 0.20f)
        val extra = HeadroomExtension.extraTop(face, 3f)
        val shifted = HeadroomExtension.shift(face, extra)
        val p = PhotographerCrop.propose(shifted, eyesY = null, pose = null, sourceAspect = 0.75f)!!
        val faceTop = shifted.cy - shifted.h / 2f
        assertTrue("crop top ${p.crop.top} must sit above the face top $faceTop", p.crop.top < faceTop)
        assertTrue(p.crop.top >= 0f)
    }
}
