package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishingTest {

    private val cramped = SubjectBox(cx = 0.5f, cy = 0.12f, w = 0.24f, h = 0.20f) // top at 0.02
    private val roomy = SubjectBox(cx = 0.5f, cy = 0.40f, w = 0.24f, h = 0.20f)
    private val plainEdges = mapOf(Side.TOP to 5f, Side.LEFT to 5f, Side.RIGHT to 5f, Side.BOTTOM to 5f)

    // --- what the model is told ---

    @Test
    fun `the facts name the face, the body and the edges in words`() {
        val pose = BodyPose(shoulderY = 0.3f, hipY = 0.6f, kneeY = 0.85f, ankleY = null)
        val text = Finishing.facts(SubjectBox(cx = 0.25f, cy = 0.20f, w = 0.12f, h = 0.16f), pose, mapOf(Side.TOP to 3f, Side.LEFT to 60f, Side.RIGHT to 25f))
        assertEquals(
            "The face is 16% of the frame tall, its top 12% below the top edge, against the left edge. " +
                "In the frame: shoulders, hips, knees; out of the frame: feet. " +
                "Edges: above the head plain, left edge busy, right edge soft, bottom edge unknown.",
            text,
        )
        assertTrue(Finishing.facts(roomy, null, emptyMap()).contains("No body found."))
        assertTrue(Finishing.facts(SubjectBox(cx = 0.38f, cy = 0.3f, w = 0.1f, h = 0.1f), null, emptyMap()).contains("left of centre"))
    }

    // --- what the model answers ---

    @Test
    fun `the six-line plan is read`() {
        val plan = Finishing.parse(
            """
            CROP THIGH
            HEADROOM ADD
            EXTEND LEFT, BOTTOM
            REMOVE 1, 3
            LOOK WARM
            WHY litter and a cramped head
            """.trimIndent(),
            candidateCount = 3,
        )
        assertEquals(Cut.THIGH, plan.cut)
        assertFalse(plan.keepFrame)
        assertTrue(plan.headroom)
        assertEquals(setOf(Side.LEFT, Side.BOTTOM), plan.extend)
        assertEquals(listOf(1, 3), plan.remove)
        assertEquals("WARM", plan.look)
        assertEquals("litter and a cramped head", plan.why)
    }

    @Test
    fun `keys in any case, with colons, or all on one line`() {
        val a = Finishing.parse("crop: keep\nheadroom: ok\nextend: none\nremove: none\nlook: mono\nwhy: already framed", 2)
        assertTrue(a.keepFrame)
        assertNull(a.cut)
        assertFalse(a.headroom)
        assertTrue(a.extend.isEmpty())
        assertTrue(a.remove.isEmpty())
        assertEquals("MONO", a.look)

        val b = Finishing.parse("CROP CHEST HEADROOM TOO MUCH EXTEND RIGHT REMOVE 2 LOOK FILM WHY tight and clean", 2)
        assertEquals(Cut.CHEST, b.cut)
        assertTrue(b.trimHeadroom)
        assertEquals(setOf(Side.RIGHT), b.extend)
        assertEquals(listOf(2), b.remove)
        assertEquals("FILM", b.look)
        assertEquals("tight and clean", b.why)
    }

    @Test
    fun `a reason under a made-up key is still the reason`() {
        val plan = Finishing.parse("CROP KEEP\nHEADROOM OK\nEXTEND RIGHT\nREMOVE 1\nLOOK FILM\nGOOD natural indoor vibe.", 1)
        assertEquals("GOOD natural indoor vibe", plan.why)
        assertEquals(listOf(1), plan.remove)
        // ...but a stray line before the plan is not.
        assertEquals("", Finishing.parse("Sure, here is the plan\nCROP KEEP", 1).why)
    }

    @Test
    fun `numbers out of range are dropped and prose is no plan`() {
        assertEquals(listOf(2), Finishing.parse("REMOVE 2, 7, 0", 3).remove)
        assertEquals(Finishing.Plan.NONE, Finishing.parse("What a lovely photo of a person by the sea.", 3))
        assertEquals(Finishing.Plan.NONE, Finishing.parse(null, 3))
        assertEquals(Finishing.Plan.NONE, Finishing.parse("", 3))
    }

    // --- what the rules allow ---

    @Test
    fun `a plain top edge earns headroom on the rules alone`() {
        val e = Finishing.extensions(Finishing.Plan.NONE, cramped, null, plainEdges)
        assertEquals(1, e.size)
        assertEquals(Side.TOP, e[0].side)
        assertEquals(HeadroomExtension.extraTop(cramped, 5f), e[0].fraction, 0.0001f)
    }

    @Test
    fun `the model's ADD earns headroom on a soft edge, but not on a busy one`() {
        val soft = mapOf(Side.TOP to 30f)
        assertTrue(Finishing.extensions(Finishing.Plan.NONE, cramped, null, soft).isEmpty())
        val asked = Finishing.Plan(headroom = true)
        val e = Finishing.extensions(asked, cramped, null, soft).single()
        assertEquals(Side.TOP, e.side)
        assertEquals(HeadroomExtension.extraTop(cramped, 0f), e.fraction, 0.0001f)
        assertTrue(Finishing.extensions(asked, cramped, null, mapOf(Side.TOP to 80f)).isEmpty())
        // ...and never when the head already has room.
        assertTrue(Finishing.extensions(asked, roomy, null, soft).isEmpty())
    }

    @Test
    fun `a side is painted only on the model's word, on the side the person is against, only when plain`() {
        val left = SubjectBox(cx = 0.2f, cy = 0.4f, w = 0.2f, h = 0.2f)
        val asked = Finishing.Plan(extend = setOf(Side.LEFT, Side.RIGHT))
        assertEquals(listOf(Extension(Side.LEFT, Finishing.SIDE_EXTRA)), Finishing.extensions(asked, left, null, plainEdges))
        // The model names the plain side; the room still goes where the person is cramped.
        assertEquals(listOf(Extension(Side.LEFT, Finishing.SIDE_EXTRA)), Finishing.extensions(Finishing.Plan(extend = setOf(Side.RIGHT)), left, null, plainEdges))
        assertTrue(Finishing.extensions(Finishing.Plan.NONE, left, null, plainEdges).isEmpty())
        assertTrue(Finishing.extensions(asked, left, null, mapOf(Side.LEFT to 90f, Side.RIGHT to 1f)).isEmpty())
        // Nobody against an edge: no side is painted, whatever was asked.
        assertTrue(Finishing.extensions(asked, roomy, null, plainEdges).isEmpty())
    }

    @Test
    fun `floor is painted only under feet that are fully in`() {
        val asked = Finishing.Plan(extend = setOf(Side.BOTTOM))
        val feetIn = BodyPose(ankleY = 0.85f)
        val feetCut = BodyPose(ankleY = 0.99f)
        assertEquals(listOf(Extension(Side.BOTTOM, Finishing.BOTTOM_EXTRA)), Finishing.extensions(asked, roomy, feetIn, plainEdges))
        assertTrue(Finishing.extensions(asked, roomy, feetCut, plainEdges).isEmpty())
        assertTrue(Finishing.extensions(asked, roomy, null, plainEdges).isEmpty())
    }

    @Test
    fun `the job carries the holes and the strips, and says what it did`() {
        val c = listOf(
            Retouch.Candidate(1, CropRect(0.05f, 0.80f, 0.15f, 0.92f), "Food"),
            Retouch.Candidate(2, CropRect(0.80f, 0.10f, 0.95f, 0.30f), null),
        )
        val job = Finishing.job(Finishing.Plan(remove = listOf(2)), c, listOf(Extension(Side.TOP, 0.1f)))
        assertEquals(1, job.remove.size)
        assertTrue(job.remove[0].left < 0.80f)
        assertEquals(listOf("Room painted in above the head", "One distraction painted out"), Finishing.describe(job))
        assertTrue(Finishing.LamaJob.NONE.isEmpty)
    }

    // --- geometry ---

    @Test
    fun `shifting through two sides keeps the face where it is in pixels`() {
        val exts = listOf(Extension(Side.TOP, 0.1f), Extension(Side.LEFT, 0.2f))
        val face = SubjectBox(cx = 0.5f, cy = 0.12f, w = 0.24f, h = 0.20f)
        val s = Finishing.shift(face, exts)
        assertEquals((0.5f + 0.2f) / 1.2f, s.cx, 0.001f)
        assertEquals((0.12f + 0.1f) / 1.1f, s.cy, 0.001f)
        assertEquals(0.24f / 1.2f, s.w, 0.001f)
        assertEquals(0.20f / 1.1f, s.h, 0.001f)
        // Same answer as the single-side helper for the top alone.
        val only = listOf(Extension(Side.TOP, 0.1f))
        assertEquals(HeadroomExtension.shift(face, 0.1f).cy, Finishing.shift(face, only).cy, 0.0001f)
        val pose = Finishing.shift(BodyPose(hipY = 0.6f), exts)!!
        assertEquals((0.6f + 0.1f) / 1.1f, pose.hipY!!, 0.001f)
        assertNull(pose.kneeY)
    }

    @Test
    fun `the canvas grows by the strips and the original sits past them`() {
        val c = Finishing.canvas(1000, 800, listOf(Extension(Side.TOP, 0.1f), Extension(Side.LEFT, 0.2f), Extension(Side.RIGHT, 0.05f)))
        assertEquals(1250, c[0])
        assertEquals(880, c[1])
        assertEquals(200, c[2])
        assertEquals(80, c[3])
    }

    @Test
    fun `each strip is a hole along its edge and its band holds context beside it`() {
        val exts = listOf(Extension(Side.TOP, 0.1f), Extension(Side.RIGHT, 0.2f))
        val top = Finishing.hole(exts[0], exts)
        assertEquals(CropRect(0f, 0f, 1f, 0.1f / 1.1f), top)
        val right = Finishing.hole(exts[1], exts)
        assertEquals(1f - 0.2f / 1.2f, right.left, 0.0001f)
        assertEquals(1f, right.right, 0f)
        val band = Finishing.band(exts[0], exts)
        assertEquals(0f, band.top, 0f)
        assertEquals(top.bottom * 3f, band.bottom, 0.0001f)
        assertEquals(1f, band.right, 0f)
        // A deep strip's band never leaves the photo.
        val deep = listOf(Extension(Side.BOTTOM, 0.5f))
        assertEquals(0f, Finishing.band(deep[0], deep).top, 0f)
    }
}
