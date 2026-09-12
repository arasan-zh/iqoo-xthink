package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameMappingTest {

    // A landscape analysis buffer, as CameraX delivers it.
    private val bufferW = 640
    private val bufferH = 480

    @Test
    fun `rotation swaps the reported dimensions only on the quarter turns`() {
        assertEquals(640 to 480, FrameMapping.rotatedSize(bufferW, bufferH, 0))
        assertEquals(480 to 640, FrameMapping.rotatedSize(bufferW, bufferH, 90))
        assertEquals(640 to 480, FrameMapping.rotatedSize(bufferW, bufferH, 180))
        assertEquals(480 to 640, FrameMapping.rotatedSize(bufferW, bufferH, 270))
    }

    @Test
    fun `rotation is a no-op at zero degrees`() {
        val r = FrameRect(10f, 20f, 110f, 220f)
        assertEquals(r, FrameMapping.rotate(r, bufferW, bufferH, 0))
    }

    @Test
    fun `a quarter turn puts the top-left corner where it belongs`() {
        // A small box hard against the buffer's top-left. Rotating the image
        // 90 degrees clockwise for an upright phone sends that corner to the
        // TOP-RIGHT of the rotated image.
        val corner = FrameRect(0f, 0f, 40f, 30f)
        val rotated = FrameMapping.rotate(corner, bufferW, bufferH, 90)

        // Rotated image is 480 wide, 640 tall.
        assertEquals("right edge", 480f, rotated.right, 1e-3f)
        assertEquals("left edge", 450f, rotated.left, 1e-3f)
        assertEquals("top edge", 0f, rotated.top, 1e-3f)
        assertEquals("bottom edge", 40f, rotated.bottom, 1e-3f)
    }

    @Test
    fun `rotation preserves area at every quarter turn`() {
        val r = FrameRect(100f, 60f, 260f, 180f)
        for (deg in listOf(0, 90, 180, 270)) {
            val out = FrameMapping.rotate(r, bufferW, bufferH, deg)
            assertEquals("width*height at $deg", r.width * r.height, out.width * out.height, 1e-2f)
        }
    }

    @Test
    fun `four quarter turns return the original`() {
        var r = FrameRect(30f, 40f, 130f, 140f)
        val original = r
        var w = bufferW
        var h = bufferH
        repeat(4) {
            r = FrameMapping.rotate(r, w, h, 90)
            val (nw, nh) = FrameMapping.rotatedSize(w, h, 90)
            w = nw
            h = nh
        }
        assertEquals(original.left, r.left, 1e-3f)
        assertEquals(original.top, r.top, 1e-3f)
        assertEquals(original.right, r.right, 1e-3f)
        assertEquals(original.bottom, r.bottom, 1e-3f)
        assertEquals(bufferW, w)
        assertEquals(bufferH, h)
    }

    @Test
    fun `negative and oversized rotations wrap`() {
        val r = FrameRect(10f, 20f, 110f, 220f)
        assertEquals(
            FrameMapping.rotate(r, bufferW, bufferH, 90),
            FrameMapping.rotate(r, bufferW, bufferH, 450),
        )
        assertEquals(
            FrameMapping.rotate(r, bufferW, bufferH, 270),
            FrameMapping.rotate(r, bufferW, bufferH, -90),
        )
    }

    // ------------------------------------------------------------------
    // Normalising against the visible crop
    // ------------------------------------------------------------------

    @Test
    fun `a box centred in the crop normalises to the centre`() {
        val crop = FrameRect(0f, 0f, 480f, 640f)
        val box = FrameRect(190f, 270f, 290f, 370f) // centred at (240, 320)
        val out = FrameMapping.normalize(box, crop)
        assertEquals(0.5f, out.cx, 1e-3f)
        assertEquals(0.5f, out.cy, 1e-3f)
        assertEquals(100f / 480f, out.w, 1e-3f)
        assertEquals(100f / 640f, out.h, 1e-3f)
    }

    @Test
    fun `the crop is what normalisation is relative to, not the whole frame`() {
        // This is the bug the ViewPort exists to prevent: a face dead-centre in
        // what the photographer SEES must read as 0.5, even though it sits
        // elsewhere in the full buffer.
        val crop = FrameRect(0f, 120f, 480f, 520f) // 480x400 visible slice
        val box = FrameRect(215f, 295f, 265f, 345f) // centred at (240, 320)
        val out = FrameMapping.normalize(box, crop)
        assertEquals(0.5f, out.cx, 1e-3f)
        assertEquals(0.5f, out.cy, 1e-3f)

        // Against the whole frame it would have read as 0.5 of 640, which is
        // a different place entirely.
        val wrong = FrameMapping.normalize(box, FrameRect(0f, 0f, 480f, 640f))
        assertEquals(0.5f, wrong.cy, 1e-3f)
        assertEquals("the two frames disagree, which is the whole point", 320f, box.centerY, 1e-3f)
    }

    @Test
    fun `a face partly outside the crop is not clamped`() {
        // The engine should see a big framing error, not a silently valid box.
        val crop = FrameRect(0f, 0f, 480f, 640f)
        val box = FrameRect(-60f, 20f, 40f, 120f)
        val out = FrameMapping.normalize(box, crop)
        assertEquals(-10f / 480f, out.cx, 1e-3f)
    }

    @Test
    fun `a degenerate crop does not divide by zero`() {
        val out = FrameMapping.normalize(FrameRect(0f, 0f, 10f, 10f), FrameRect(5f, 5f, 5f, 5f))
        assertEquals(0f, out.cx, 1e-3f)
        assertEquals(0f, out.cy, 1e-3f)
    }

    @Test
    fun `normalizeY places an eye line in crop coordinates`() {
        val crop = FrameRect(0f, 100f, 480f, 500f)
        assertEquals(0.5f, FrameMapping.normalizeY(300f, crop), 1e-3f)
        assertEquals(0f, FrameMapping.normalizeY(100f, crop), 1e-3f)
        assertEquals(1f, FrameMapping.normalizeY(500f, crop), 1e-3f)
    }

    // ------------------------------------------------------------------
    // Group framing
    // ------------------------------------------------------------------

    @Test
    fun `union spans every face in a group`() {
        val faces = listOf(
            FrameRect(100f, 200f, 160f, 260f),
            FrameRect(300f, 180f, 370f, 250f),
            FrameRect(210f, 240f, 260f, 300f),
        )
        val u = FrameMapping.union(faces)!!
        assertEquals(100f, u.left, 1e-3f)
        assertEquals(180f, u.top, 1e-3f)
        assertEquals(370f, u.right, 1e-3f)
        assertEquals(300f, u.bottom, 1e-3f)
    }

    @Test
    fun `union of one face is that face`() {
        val only = FrameRect(1f, 2f, 3f, 4f)
        assertEquals(only, FrameMapping.union(listOf(only)))
    }

    @Test
    fun `union of nothing is null`() {
        assertNull(FrameMapping.union(emptyList()))
    }

    // ------------------------------------------------------------------
    // Head yaw as the gaze proxy
    // ------------------------------------------------------------------

    @Test
    fun `a square head asks for no lead room`() {
        val gaze = FrameMapping.gazeFromHeadYaw(0f)
        assertEquals(0f, gaze, 1e-4f)
        // Inside the engine's gaze deadzone, so the subject stays centred.
        assertEquals(true, kotlin.math.abs(gaze) < GuidanceConstants.GAZE_DEADZONE)
    }

    @Test
    fun `turning toward frame right gives positive gaze`() {
        // ML Kit: positive headEulerAngleY = face turned to the image's right.
        // Positive gazeDx = looking frame-right = lead room on the left third.
        assertEquals(0.5f, FrameMapping.gazeFromHeadYaw(22.5f), 1e-3f)
        assertEquals(-0.5f, FrameMapping.gazeFromHeadYaw(-22.5f), 1e-3f)
    }

    @Test
    fun `yaw saturates rather than running past full lead room`() {
        assertEquals(1f, FrameMapping.gazeFromHeadYaw(90f), 1e-4f)
        assertEquals(-1f, FrameMapping.gazeFromHeadYaw(-90f), 1e-4f)
    }

    @Test
    fun `a small head turn stays inside the deadzone and does not shift the frame`() {
        // 5 degrees is a glance, not a look. It must not move the target.
        val engine = GuidanceEngine(Fixtures.profile(ShotType.HEADSHOT))
        val gaze = FrameMapping.gazeFromHeadYaw(5f)
        val verb = engine.update(
            Fixtures.LEVEL,
            Fixtures.box(cx = 0.5f),
            EyeLine(y = 0.33f, gazeDx = gaze),
            100L,
        ).verb
        assertEquals(Verb.TAP_FOCUS, verb)
    }

    @Test
    fun `a real head turn moves the target onto the third`() {
        val engine = GuidanceEngine(Fixtures.profile(ShotType.HEADSHOT))
        val gaze = FrameMapping.gazeFromHeadYaw(30f) // turned toward frame right
        val verb = engine.update(
            Fixtures.LEVEL,
            Fixtures.box(cx = 0.5f),
            EyeLine(y = 0.33f, gazeDx = gaze),
            100L,
        ).verb
        assertEquals(Verb.MOVE_RIGHT, verb)
    }
}
