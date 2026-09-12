package `in`.arasan.xthink.guidance

/** A rectangle in pixels. :guidance cannot use android.graphics.Rect. */
data class FrameRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * Gets detector output into the normalised 0..1 frame the engine composes in.
 *
 * Two coordinate systems have to be reconciled, and getting it wrong points
 * the guidance the wrong way while still looking plausible - which is exactly
 * the kind of bug that survives to a demo. So the arithmetic lives here, under
 * test, and :app only supplies numbers.
 *
 *  - **ML Kit** reports faces in the image space *after* rotation by
 *    `rotationDegrees`, so for a portrait phone a 640x480 buffer is reported
 *    against a 480x640 upright image.
 *  - **CameraX** reports `ImageProxy.cropRect` in the *unrotated* buffer
 *    space. With a ViewPort set, that rect is the part of the frame the
 *    preview is actually showing.
 *
 * Normalising against the rotated crop rect - rather than the whole buffer -
 * is what makes "6% of frame" mean six percent of what the photographer can
 * see, instead of six percent of a frame that is partly off-screen.
 */
object FrameMapping {

    /** Image dimensions after rotation, as (width, height). */
    fun rotatedSize(sourceWidth: Int, sourceHeight: Int, rotationDegrees: Int): Pair<Int, Int> =
        when (normalizeRotation(rotationDegrees)) {
            90, 270 -> sourceHeight to sourceWidth
            else -> sourceWidth to sourceHeight
        }

    /**
     * Move a rectangle from the unrotated buffer into the rotated image space
     * that the detector reports against.
     */
    fun rotate(
        rect: FrameRect,
        sourceWidth: Int,
        sourceHeight: Int,
        rotationDegrees: Int,
    ): FrameRect {
        val w = sourceWidth.toFloat()
        val h = sourceHeight.toFloat()
        return when (normalizeRotation(rotationDegrees)) {
            // (x, y) -> (h - y, x)
            90 -> FrameRect(h - rect.bottom, rect.left, h - rect.top, rect.right)
            // (x, y) -> (w - x, h - y)
            180 -> FrameRect(w - rect.right, h - rect.bottom, w - rect.left, h - rect.top)
            // (x, y) -> (y, w - x)
            270 -> FrameRect(rect.top, w - rect.right, rect.bottom, w - rect.left)
            else -> rect
        }
    }

    /**
     * Express a detected box as a fraction of the visible crop.
     *
     * Values can fall outside 0..1 when a face is partly outside the preview's
     * crop; that is legitimate and the engine handles it as a large framing
     * error, so it is deliberately not clamped.
     */
    fun normalize(box: FrameRect, crop: FrameRect): SubjectBox {
        val cw = if (crop.width != 0f) crop.width else 1f
        val ch = if (crop.height != 0f) crop.height else 1f
        return SubjectBox(
            cx = (box.centerX - crop.left) / cw,
            cy = (box.centerY - crop.top) / ch,
            w = box.width / cw,
            h = box.height / ch,
        )
    }

    /** Express a point - an eye landmark - as a fraction of the visible crop. */
    fun normalizeY(y: Float, crop: FrameRect): Float {
        val ch = if (crop.height != 0f) crop.height else 1f
        return (y - crop.top) / ch
    }

    /** Smallest rectangle containing all of [rects]. Used to frame a GROUP. */
    fun union(rects: List<FrameRect>): FrameRect? {
        if (rects.isEmpty()) return null
        var l = rects[0].left
        var t = rects[0].top
        var r = rects[0].right
        var b = rects[0].bottom
        for (i in 1 until rects.size) {
            l = minOf(l, rects[i].left)
            t = minOf(t, rects[i].top)
            r = maxOf(r, rects[i].right)
            b = maxOf(b, rects[i].bottom)
        }
        return FrameRect(l, t, r, b)
    }

    /**
     * ML Kit's head yaw, as the gaze proxy the lead-room rule consumes.
     *
     * There is no true eye-gaze signal available. Head yaw is the honest
     * stand-in: when someone turns to look at something, lead room is exactly
     * what the shot wants. It only misses eyes moving inside a still head,
     * which barely reads in a photograph anyway.
     *
     * @param headEulerAngleY degrees; positive means the face is turned toward
     *        the right of the image, which is also positive [EyeLine.gazeDx].
     */
    fun gazeFromHeadYaw(headEulerAngleY: Float): Float =
        (headEulerAngleY / FULL_LEAD_YAW_DEG).coerceIn(-1f, 1f)

    /** Head yaw at which lead room is asked for in full. */
    const val FULL_LEAD_YAW_DEG = 45f

    private fun normalizeRotation(degrees: Int): Int {
        val d = degrees % 360
        return if (d < 0) d + 360 else d
    }
}
