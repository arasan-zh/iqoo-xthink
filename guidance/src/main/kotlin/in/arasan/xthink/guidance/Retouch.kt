package `in`.arasan.xthink.guidance

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The retouch after the shutter: what could be removed from a photo, how
 * the choice is put to the on-device model, how its answer is read, and
 * the geometry the inpainter needs.
 *
 * The model is not asked to draw a mask - it cannot. The detector finds
 * things, this file turns them into a short numbered list in plain words
 * (where, how big, what it looks like), the model answers with numbers,
 * and the numbers become holes for LaMa to fill. Every step here is a
 * pure function, so the whole conversation is unit tested without a
 * phone.
 */
object Retouch {

    /** Something the detector found. Fractions of the frame, top-left origin. */
    data class Candidate(val id: Int, val box: CropRect, val label: String?)

    /** The model's answer, read. */
    data class Decision(val remove: List<Int>, val why: String)

    /**
     * Which detections are worth offering. The subject is never offered,
     * nor anything under the subject's face - that column is the person's
     * body, whatever the detector calls it. Nor anything so large that
     * removing it would be repainting the picture.
     */
    fun eligible(found: List<CropRect>, subject: SubjectBox?, labels: List<String?> = emptyList()): List<Candidate> {
        val body = subject?.let { bodyZone(it) }
        var id = 1
        return found.mapIndexedNotNull { i, box ->
            val clipped = CropRect(box.left.coerceIn(0f, 1f), box.top.coerceIn(0f, 1f), box.right.coerceIn(0f, 1f), box.bottom.coerceIn(0f, 1f))
            if (clipped.width <= 0f || clipped.height <= 0f) return@mapIndexedNotNull null
            if (clipped.area > MAX_AREA || clipped.area < MIN_AREA) return@mapIndexedNotNull null
            if (body != null && overlaps(clipped, body)) return@mapIndexedNotNull null
            Candidate(id++, clipped, labels.getOrNull(i)?.takeIf { it.isNotBlank() })
        }.take(MAX_CANDIDATES)
    }

    /** The column under a face: the person, from above the head to the bottom of the frame. */
    fun bodyZone(face: SubjectBox): CropRect = CropRect(
        left = (face.cx - face.w * BODY_HALF_WIDTHS).coerceIn(0f, 1f),
        top = (face.cy - face.h).coerceIn(0f, 1f),
        right = (face.cx + face.w * BODY_HALF_WIDTHS).coerceIn(0f, 1f),
        bottom = 1f,
    )

    fun overlaps(a: CropRect, b: CropRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /** The list as the model reads it: one line per candidate, numbered from 1. */
    fun describe(candidates: List<Candidate>): String = candidates.joinToString("\n") { c ->
        val pct = (c.box.area * 100f).roundToInt().coerceAtLeast(1)
        val what = c.label?.let { ", looks like ${it.lowercase()}" } ?: ""
        "${c.id}: ${place(c.box)}, $pct% of the frame$what"
    }

    /** Where a box sits, in a photographer's words. */
    fun place(box: CropRect): String {
        val cx = (box.left + box.right) / 2f
        val cy = (box.top + box.bottom) / 2f
        val row = when {
            cy < 0.33f -> "top"
            cy > 0.67f -> "bottom"
            else -> "middle"
        }
        val col = when {
            cx < 0.33f -> "left"
            cx > 0.67f -> "right"
            else -> "centre"
        }
        val edge = box.left < 0.03f || box.right > 0.97f || box.top < 0.03f || box.bottom > 0.97f
        val where = if (row == "middle" && col == "centre") "centre" else if (row == "middle") col else if (col == "centre") row else "$row-$col"
        return if (edge) "$where edge" else where
    }

    /**
     * Read `REMOVE 1,3 | why` or `NONE | why`. Numbers outside 1..[count]
     * are dropped; an unreadable answer removes nothing.
     */
    fun parse(answer: String?, count: Int): Decision {
        val line = answer?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return Decision(emptyList(), "")
        val parts = line.split('|', limit = 2)
        val head = parts[0].trim().uppercase()
        val why = parts.getOrNull(1)?.trim()?.trim('.', ' ') ?: ""
        if (!head.startsWith("REMOVE")) return Decision(emptyList(), why)
        val ids = Regex("\\d+").findAll(head.removePrefix("REMOVE")).map { it.value.toInt() }
            .filter { it in 1..count }.distinct().toList()
        return Decision(ids, why)
    }

    /**
     * The holes: each chosen box grown a little, because a detector's box
     * hugs the object and LaMa wants the edge of the thing inside the hole
     * too - a tight hole leaves a ghost.
     */
    fun holes(candidates: List<Candidate>, remove: List<Int>): List<CropRect> = remove.mapNotNull { id ->
        candidates.firstOrNull { it.id == id }?.box?.let { grow(it, HOLE_GROW) }
    }

    fun grow(box: CropRect, by: Float): CropRect {
        val dx = max(box.width * by, MIN_GROW)
        val dy = max(box.height * by, MIN_GROW)
        return CropRect((box.left - dx).coerceIn(0f, 1f), (box.top - dy).coerceIn(0f, 1f), (box.right + dx).coerceIn(0f, 1f), (box.bottom + dy).coerceIn(0f, 1f))
    }

    /**
     * The square the inpainter works in. LaMa runs at a fixed 512 x 512, so
     * a small distraction in a 4000 px photo would lose all its detail if
     * the whole photo went through. Instead: the smallest square (in
     * pixels, so [aspect] = width / height matters) around the holes with a
     * margin of context, never smaller than [MIN_WINDOW] of the short side,
     * kept inside the frame. Returned as fractions of the frame.
     */
    fun window(holes: List<CropRect>, aspect: Float): CropRect {
        require(holes.isNotEmpty())
        // Work in units of the short side so the square is square in pixels.
        val sx = if (aspect >= 1f) aspect else 1f // width in short-side units
        val sy = if (aspect >= 1f) 1f else 1f / aspect // height in short-side units
        val left = holes.minOf { it.left } * sx
        val top = holes.minOf { it.top } * sy
        val right = holes.maxOf { it.right } * sx
        val bottom = holes.maxOf { it.bottom } * sy
        val span = max(right - left, bottom - top) * (1f + 2f * WINDOW_MARGIN)
        var side = max(span, MIN_WINDOW).coerceAtMost(min(sx, sy))
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        var l = cx - side / 2f
        var t = cy - side / 2f
        if (l < 0f) l = 0f
        if (t < 0f) t = 0f
        if (l + side > sx) l = sx - side
        if (t + side > sy) t = sy - side
        return CropRect(l / sx, t / sy, (l + side) / sx, (t + side) / sy)
    }

    /** Fractions to pixels: left, top, width, height, at least 1 px each, inside the image. */
    fun toPixels(rect: CropRect, width: Int, height: Int): IntArray {
        val l = (rect.left * width).roundToInt().coerceIn(0, width - 1)
        val t = (rect.top * height).roundToInt().coerceIn(0, height - 1)
        val r = (rect.right * width).roundToInt().coerceIn(l + 1, width)
        val b = (rect.bottom * height).roundToInt().coerceIn(t + 1, height)
        return intArrayOf(l, t, r - l, b - t)
    }

    /** A hole in frame fractions, re-expressed inside [window]. */
    fun within(hole: CropRect, window: CropRect): CropRect = CropRect(
        ((hole.left - window.left) / window.width).coerceIn(0f, 1f),
        ((hole.top - window.top) / window.height).coerceIn(0f, 1f),
        ((hole.right - window.left) / window.width).coerceIn(0f, 1f),
        ((hole.bottom - window.top) / window.height).coerceIn(0f, 1f),
    )

    /** Nothing bigger than this is offered: it would be the picture, not a distraction. */
    const val MAX_AREA = 0.30f
    /** Nothing smaller than this is offered: the detector is guessing. */
    const val MIN_AREA = 0.0015f
    const val MAX_CANDIDATES = 6
    /** Half-widths of the face, either side, that count as the person's body. */
    const val BODY_HALF_WIDTHS = 2.2f
    /** How much each hole grows past the detector's box, as a fraction of the box. */
    const val HOLE_GROW = 0.10f
    /** ...and never less than this much of the frame. */
    const val MIN_GROW = 0.012f
    /** Context around the holes inside the inpainting square, each side. */
    const val WINDOW_MARGIN = 0.30f
    /** The square is never smaller than this fraction of the short side. */
    const val MIN_WINDOW = 0.30f
}
