package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotographerCropTest {

    private fun <T> req(v: T?): T { assertNotNull(v); return v!! }

    private val aspect = 3f / 4f // portrait photo

    @Test
    fun `knees in frame but feet out - cut mid-thigh not at the knee`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.20f, w = 0.12f, h = 0.10f)
        val pose = BodyPose(shoulderY = 0.32f, hipY = 0.58f, kneeY = 0.86f, ankleY = null)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.19f, pose = pose, sourceAspect = aspect, targetAspect = aspect))
        val expected = 0.58f + PhotographerCrop.THIGH_CUT * (0.86f - 0.58f)
        assertEquals(expected, p.crop.bottom, 0.02f)
        assertTrue("must stay clear of the knee, got ${p.crop.bottom}", p.crop.bottom < 0.86f - 0.05f)
        assertTrue(p.rationale.any { it.contains("Mid-thigh") })
    }

    @Test
    fun `feet in frame - keep them with floor`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.15f, w = 0.08f, h = 0.07f)
        val pose = BodyPose(shoulderY = 0.24f, hipY = 0.45f, kneeY = 0.65f, ankleY = 0.86f)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.14f, pose = pose, sourceAspect = aspect, targetAspect = aspect))
        assertTrue("feet must be inside the crop", p.crop.bottom > 0.86f)
        assertTrue(p.rationale.any { it.contains("Feet") })
    }

    @Test
    fun `no body - head and shoulders chest deep`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.30f, w = 0.2f, h = 0.16f)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.28f, pose = null, sourceAspect = aspect, targetAspect = aspect))
        val faceBottom = 0.38f
        assertEquals(faceBottom + PhotographerCrop.CHEST_FACES * 0.16f, p.crop.bottom, 0.02f)
        assertTrue("headroom must be above the face", p.crop.top < 0.30f - 0.08f)
    }

    @Test
    fun `headroom is kept above the face`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.30f, w = 0.2f, h = 0.16f)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.28f, pose = null, sourceAspect = aspect, targetAspect = aspect))
        val faceTop = 0.22f
        assertTrue(p.crop.top <= faceTop - PhotographerCrop.HEADROOM_FACES * 0.16f + 0.01f)
    }

    @Test
    fun `already well framed - no proposal`() {
        // Face near the top, feet near the bottom, centred: nothing to gain.
        val face = SubjectBox(cx = 0.5f, cy = 0.10f, w = 0.08f, h = 0.07f)
        val pose = BodyPose(shoulderY = 0.18f, hipY = 0.45f, kneeY = 0.70f, ankleY = 0.94f)
        assertNull(PhotographerCrop.propose(face, eyesY = 0.09f, pose = pose, sourceAspect = aspect, targetAspect = aspect))
    }

    @Test
    fun `crop keeps the photo aspect and stays inside the photo`() {
        val face = SubjectBox(cx = 0.85f, cy = 0.30f, w = 0.2f, h = 0.16f)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.28f, pose = null, sourceAspect = aspect, targetAspect = aspect))
        val c = p.crop
        assertTrue(c.left >= 0f && c.right <= 1f && c.top >= 0f && c.bottom <= 1f)
        // Same aspect in fractional space means equal width and height fractions.
        assertEquals(c.height, c.width, 0.001f)
    }

    @Test
    fun `eyes settle near the upper third in a head-and-shoulders crop`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.50f, w = 0.14f, h = 0.12f)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.48f, pose = null, sourceAspect = aspect, targetAspect = aspect))
        val eyeFrac = (0.48f - p.crop.top) / p.crop.height
        assertEquals(PhotographerCrop.EYE_LINE, eyeFrac, 0.04f)
    }

    @Test
    fun `a three-quarter shot keeps the headroom cap rather than chasing the eye line`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.35f, w = 0.14f, h = 0.12f)
        val pose = BodyPose(shoulderY = 0.48f, hipY = 0.72f, kneeY = 0.98f, ankleY = null)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.33f, pose = pose, sourceAspect = aspect, targetAspect = aspect))
        val faceTop = 0.29f
        val headroomFaces = (faceTop - p.crop.top) / 0.12f
        assertTrue("headroom $headroomFaces faces", headroomFaces <= PhotographerCrop.HEADROOM_MAX_FACES + 0.01f)
        assertTrue(headroomFaces >= PhotographerCrop.HEADROOM_FACES - 0.01f)
    }

    @Test
    fun `a 20-9 strip with a full body takes a taller shape to keep more of the person`() {
        val source = 1862f / 4096f
        val face = SubjectBox(cx = 0.5f, cy = 0.30f, w = 0.30f, h = 0.14f)
        val pose = BodyPose(shoulderY = 0.42f, hipY = 0.62f, kneeY = 0.80f, ankleY = 0.95f)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.28f, pose = pose, sourceAspect = source))
        val c = p.crop
        assertTrue(c.left >= 0f && c.right <= 1f && c.top >= 0f && c.bottom <= 1f)
        // Feet + floor would need 0.82 of the height from the headroom line:
        // no shape holds it. Mid-thigh (0.72) needs 0.54, which 4:5 (cap 0.57)
        // holds - so the person keeps their legs AND the preferred shape.
        assertEquals(0.62f + PhotographerCrop.THIGH_CUT * 0.18f, c.bottom, 0.02f)
        val pxAspect = (c.width * 1862f) / (c.height * 4096f)
        assertEquals(PhotographerCrop.PORTRAIT_ASPECT, pxAspect, 0.02f)
        assertTrue(p.rationale.any { it.contains("Mid-thigh") })

        // Push the knees lower so mid-thigh no longer fits 4:5: the ladder
        // takes a taller shape rather than cutting higher.
        val lower = BodyPose(shoulderY = 0.42f, hipY = 0.70f, kneeY = 0.96f, ankleY = null)
        val q = req(PhotographerCrop.propose(face, eyesY = 0.28f, pose = lower, sourceAspect = source))
        assertEquals(0.70f + PhotographerCrop.THIGH_CUT * 0.26f, q.crop.bottom, 0.02f)
        val qAspect = (q.crop.width * 1862f) / (q.crop.height * 4096f)
        assertTrue("aspect $qAspect must be taller than 4:5", qAspect < PhotographerCrop.PORTRAIT_ASPECT - 0.03f)
        assertTrue("top ${c.top} must keep headroom", c.top <= 0.23f - PhotographerCrop.HEADROOM_FACES * 0.14f + 0.01f)
    }

    @Test
    fun `preferred shape wins when the cut fits it`() {
        // A 3:4 photo, head and shoulders: 4:5 holds it, so 4:5 it is.
        val face = SubjectBox(cx = 0.5f, cy = 0.35f, w = 0.20f, h = 0.15f)
        val p = req(PhotographerCrop.propose(face, eyesY = 0.33f, pose = null, sourceAspect = 0.75f))
        val pxAspect = (p.crop.width * 3f) / (p.crop.height * 4f)
        assertEquals(PhotographerCrop.PORTRAIT_ASPECT, pxAspect, 0.02f)
    }

    @Test
    fun `same aspect and nothing to trim - still no proposal`() {
        val face = SubjectBox(cx = 0.5f, cy = 0.10f, w = 0.08f, h = 0.07f)
        val pose = BodyPose(shoulderY = 0.18f, hipY = 0.45f, kneeY = 0.70f, ankleY = 0.94f)
        assertNull(PhotographerCrop.propose(face, eyesY = 0.09f, pose = pose, sourceAspect = 0.8f, targetAspect = 0.8f))
    }

    @Test
    fun `pixel mapping clamps and never yields an empty rect`() {
        val px = PhotographerCrop.toPixels(CropRect(0.1f, 0.2f, 0.9f, 1.0f), 3000, 4000)
        assertEquals(300, px[0]); assertEquals(800, px[1]); assertEquals(2400, px[2]); assertEquals(3200, px[3])
        val tiny = PhotographerCrop.toPixels(CropRect(0.999f, 0.999f, 1f, 1f), 100, 100)
        assertTrue(tiny[2] >= 1 && tiny[3] >= 1)
    }
}
