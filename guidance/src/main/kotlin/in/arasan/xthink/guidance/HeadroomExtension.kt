package `in`.arasan.xthink.guidance

/**
 * Adding a little room above a head that touches the top of the frame.
 *
 * Only the honest case is handled: when the strip above the face is plain
 * - sky, a wall, a soft backdrop - it can be extended by reflecting and
 * blurring it, and no one can tell. A busy top edge (ceiling lights,
 * trees, other people) is left alone: a fake there would be worse than
 * the crop. The decision lives here, under test; :app does the pixels.
 */
object HeadroomExtension {

    /** Headroom wanted above the face, in face heights - the crop's own figure. */
    const val WANTED_FACES = PhotographerCrop.HEADROOM_FACES

    /** Faces this close to the top edge (in face heights) get the offer. */
    const val TRIGGER_FACES = 0.2f

    /**
     * Luminance standard deviation (0..255) of the top strip at or below
     * which it counts as plain. An overcast sky is ~4, a painted wall ~8,
     * a ceiling with lights 30+.
     */
    const val PLAIN_STDDEV = 16f

    /** Never add more than this fraction of the photo's height. */
    const val MAX_EXTRA = 0.25f

    /**
     * How much to add at the top, as a fraction of the photo's height, or
     * 0 when nothing should be added.
     *
     * @param face the largest face, fractions of the photo.
     * @param topStripStdDev luminance spread of the rows above the face.
     */
    fun extraTop(face: SubjectBox, topStripStdDev: Float): Float {
        if (face.h <= 0f) return 0f
        val faceTop = face.cy - face.h / 2f
        val have = faceTop.coerceAtLeast(0f)
        if (have > TRIGGER_FACES * face.h) return 0f
        if (topStripStdDev > PLAIN_STDDEV) return 0f
        val want = WANTED_FACES * face.h
        val missing = (want - have).coerceAtLeast(0f)
        return missing.coerceAtMost(MAX_EXTRA)
    }

    /**
     * Re-express a y fraction of the original photo in the extended photo,
     * which is [extra] taller at the top.
     */
    fun shiftY(y: Float, extra: Float): Float = (y + extra) / (1f + extra)

    /** A box in the original photo, in the extended photo. */
    fun shift(box: SubjectBox, extra: Float): SubjectBox =
        box.copy(cy = shiftY(box.cy, extra), h = box.h / (1f + extra))

    /** A body row in the original photo, in the extended photo. */
    fun shift(pose: BodyPose?, extra: Float): BodyPose? = pose?.let {
        BodyPose(
            shoulderY = it.shoulderY?.let { y -> shiftY(y, extra) },
            hipY = it.hipY?.let { y -> shiftY(y, extra) },
            kneeY = it.kneeY?.let { y -> shiftY(y, extra) },
            ankleY = it.ankleY?.let { y -> shiftY(y, extra) },
        )
    }
}
