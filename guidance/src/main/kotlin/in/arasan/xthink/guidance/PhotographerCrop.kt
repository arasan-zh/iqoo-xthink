package `in`.arasan.xthink.guidance

import kotlin.math.abs

/**
 * What the body detector found, as fractions of the photo's height. Only
 * the rows matter for a crop. A landmark that is missing, off the frame or
 * low-confidence is null - the rules then fall back to the face alone.
 */
data class BodyPose(
    val shoulderY: Float? = null,
    val hipY: Float? = null,
    val kneeY: Float? = null,
    val ankleY: Float? = null,
)

/** A crop as fractions of the source photo, left/top/right/bottom in 0..1. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

/**
 * One proposed crop and the reasons a photographer would give for it.
 * [rationale] is short and in the photographer's words: the card shows it,
 * the user decides.
 */
data class CropProposal(val crop: CropRect, val rationale: List<String>)

/**
 * The photographer's crop, applied after the shutter.
 *
 * A camera phone frames what the hand happened to hold. A photographer
 * then crops: never through a joint, eyes near the upper third, a little
 * room above the head and none wasted. These are the rules that survive
 * every portrait textbook, written as geometry.
 *
 *  - FULL BODY with feet in frame: keep them, with a little floor.
 *  - Knees in frame but feet not: cut MID-THIGH. Cutting at the knee or
 *    ankle reads as an amputation; mid-limb reads as intended.
 *  - Hips in but knees not: cut mid-thigh if there is room, else at the hip.
 *  - Face only: a head-and-shoulders crop, chest-deep.
 *  - Headroom: about a third of a face height. Less looks cramped, more
 *    looks like the subject is sinking.
 *  - Eyes settle near 38% from the top when the bottom rule allows it.
 *  - The aspect ratio of the original is kept, so the result looks like a
 *    photograph, not a sticker.
 *
 * Everything is a fraction of the photo, so the same rule works on the
 * 12 MP JPEG and the 480px thumbnail used to find the body.
 */
object PhotographerCrop {

    /** Least headroom above the face, in face heights. */
    const val HEADROOM_FACES = 0.35f

    /** Most headroom the eye-line rule may ask for, in face heights. */
    const val HEADROOM_MAX_FACES = 1.2f

    /** Where the eyes settle, as a fraction of the crop's height from its top. */
    const val EYE_LINE = 0.38f

    /** Cut this far down the thigh (0 = hip, 1 = knee). Mid-thigh, never the joint. */
    const val THIGH_CUT = 0.55f

    /** Room below the feet, in face heights, when they are kept. */
    const val FLOOR_FACES = 0.4f

    /** Head-and-shoulders: the bottom sits this many face heights below the face. */
    const val CHEST_FACES = 1.6f

    /**
     * A crop must take at least this fraction off the photo's height to be
     * worth offering; below it, the photo is already framed and the card
     * stays quiet rather than nitpicking.
     */
    const val MIN_CHANGE = 0.10f

    /**
     * The aspect a photographer prints a portrait at: 4:5. The phone shoots
     * a 20:9 strip because that is the screen; nobody frames a person that
     * way on purpose.
     */
    const val PORTRAIT_ASPECT = 4f / 5f

    /**
     * Shapes a portrait may take, preferred first. A taller shape is
     * accepted only when it lets a longer safe cut fit - keeping the person
     * whole beats the nicer print shape.
     */
    val ASPECTS: List<Pair<Float, String>> = listOf(
        4f / 5f to "4:5",
        3f / 4f to "3:4",
        2f / 3f to "2:3",
        9f / 16f to "9:16",
    )

    /**
     * @param face the largest face, as fractions of the photo.
     * @param eyesY the eye line, fraction of photo height, or null.
     * @param pose body rows, or null when no body was found.
     * @param sourceAspect width / height of the photo as shot.
     * @param targetAspect width / height the crop should have.
     * @return a proposal, or null when the photo is already well framed.
     */
    fun propose(
        face: SubjectBox,
        eyesY: Float?,
        pose: BodyPose?,
        sourceAspect: Float,
        targetAspect: Float? = null,
    ): CropProposal? {
        if (face.h <= 0f || sourceAspect <= 0f) return null
        val faceTop = face.cy - face.h / 2f
        val faceBottom = face.cy + face.h / 2f
        val reasons = mutableListOf<String>()
        val shapes = if (targetAspect != null) listOf(targetAspect to "") else ASPECTS

        // --- top: at least a little headroom, at most a lot ---
        val tightTop = (faceTop - HEADROOM_FACES * face.h).coerceAtLeast(0f)
        val looseTop = (faceTop - HEADROOM_MAX_FACES * face.h).coerceAtLeast(0f)
        val eyes = eyesY ?: (face.cy - face.h * 0.1f)

        // --- bottom: the safe cuts, longest first. The first that fits
        //     under the headroom is the shot. Never through a joint. ---
        val ankle = pose?.ankleY
        val knee = pose?.kneeY
        val hip = pose?.hipY
        val cuts = mutableListOf<Pair<Float, String>>()
        if (ankle != null && ankle < 0.98f) cuts += (ankle + FLOOR_FACES * face.h) to "Feet kept, with a little floor"
        if (knee != null && hip != null) cuts += (hip + THIGH_CUT * (knee - hip)) to "Mid-thigh crop, not at the knee"
        if (hip != null) cuts += (hip + 0.4f * (hip - faceBottom).coerceAtLeast(0f)) to "Cropped below the hip"
        val chestFrom = pose?.shoulderY ?: faceBottom
        cuts += (chestFrom + CHEST_FACES * face.h) to "Head and shoulders"

        // In per-axis fractions a crop of height h has width h * (target /
        // source), so each shape caps how tall the crop can be. Longest cut
        // first; within a cut, the preferred shape first.
        var bottom = -1f
        var widthPerHeight = shapes[0].first / sourceAspect
        var maxHeight = minOf(1f, 1f / widthPerHeight)
        var shapeName = shapes[0].second
        search@ for ((cut, why) in cuts) {
            val b = cut.coerceIn(faceBottom + 0.5f * face.h, 1f)
            for ((aspect, name) in shapes) {
                val wph = aspect / sourceAspect
                val cap = minOf(1f, 1f / wph)
                if (b - tightTop <= cap) {
                    bottom = b
                    widthPerHeight = wph
                    maxHeight = cap
                    shapeName = name
                    reasons += why
                    break@search
                }
            }
        }
        var top: Float
        if (bottom < 0f) {
            // Even head-and-shoulders will not fit: the face fills the frame.
            top = tightTop
            bottom = (top + maxHeight).coerceAtMost(1f)
            reasons += "Tight on the face"
        } else {
            val topForEyes = bottom - (bottom - eyes) / (1f - EYE_LINE)
            top = topForEyes.coerceIn(maxOf(looseTop, bottom - maxHeight), tightTop)
            reasons += "Eyes toward the upper third"
        }
        val height = bottom - top

        // --- width from height at the target aspect, centred on the face ---
        val widthFrac = (height * widthPerHeight).coerceAtMost(1f)
        var left = face.cx - widthFrac / 2f
        if (left < 0f) left = 0f
        if (left + widthFrac > 1f) left = 1f - widthFrac
        val crop = CropRect(left, top, left + widthFrac, bottom)

        // --- worth it? A new aspect always is; the same aspect only when
        //     it takes a real slice off, not a nitpick. ---
        val aspectChanges = abs(widthPerHeight - 1f) > 0.05f
        if (!aspectChanges && 1f - crop.height < MIN_CHANGE) return null
        if (aspectChanges && shapeName.isNotEmpty()) reasons += "Cropped to $shapeName, a portrait shape"
        if (abs(face.cx - 0.5f) > 0.06f) reasons += "Subject centred"
        return CropProposal(crop, reasons.distinct())
    }

    /** Map a crop to pixels of a [width] x [height] image, edges clamped. */
    fun toPixels(crop: CropRect, width: Int, height: Int): IntArray {
        val l = (crop.left * width).toInt().coerceIn(0, width - 1)
        val t = (crop.top * height).toInt().coerceIn(0, height - 1)
        val r = (crop.right * width).toInt().coerceIn(l + 1, width)
        val b = (crop.bottom * height).toInt().coerceIn(t + 1, height)
        return intArrayOf(l, t, r - l, b - t)
    }
}
