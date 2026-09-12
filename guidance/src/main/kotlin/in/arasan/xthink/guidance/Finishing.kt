package `in`.arasan.xthink.guidance

import kotlin.math.roundToInt

/** An edge of the photo. */
enum class Side { TOP, BOTTOM, LEFT, RIGHT }

/**
 * Room painted in on one side, as a fraction of the photo's height (TOP,
 * BOTTOM) or width (LEFT, RIGHT) as shot.
 */
data class Extension(val side: Side, val fraction: Float)

/**
 * The finish after the shutter, as one conversation with the on-device
 * model and one set of instructions for the inpainter.
 *
 * The model looks at the photo once. It is told what the detectors
 * measured (where the face is, which parts of the body are in the frame,
 * which edges are plain) and what could be painted out, numbered. It
 * answers a fixed six-line plan: the crop, the headroom, the sides to
 * paint room into, the numbers to paint out, the look, and why. This file
 * writes the facts, reads the plan, and turns it into geometry:
 *
 *  - CROP goes to [PhotographerCrop], which knows where a body may be cut.
 *  - HEADROOM ADD and EXTEND become [Extension]s - room painted in by LaMa
 *    - but only where the rules agree: the edge must be plain enough to
 *    paint, and the person must actually be cramped against it.
 *  - REMOVE becomes holes through [Retouch].
 *
 * The model never draws a mask and never places a pixel; it decides, and
 * the geometry here is what LaMa is handed. Every function is pure, so the
 * whole conversation is tested without a phone.
 */
object Finishing {

    /** The model's plan, read. [NONE] is what the rules do on their own. */
    data class Plan(
        /** Where to cut, or null for the rules' ladder. */
        val cut: Cut? = null,
        /** The model asked for no crop at all. */
        val keepFrame: Boolean = false,
        /** The model asked for room above the head. */
        val headroom: Boolean = false,
        /** The model said there is too much room above the head. */
        val trimHeadroom: Boolean = false,
        /** Sides the model wants room painted into. */
        val extend: Set<Side> = emptySet(),
        /** Candidate numbers to paint out. */
        val remove: List<Int> = emptyList(),
        /** The look, by name, or null. */
        val look: String? = null,
        val why: String = "",
    ) {
        companion object { val NONE = Plan() }
    }

    /** What LaMa is asked to do: holes to fill on the photo as shot, then strips to paint in. */
    data class LamaJob(val remove: List<CropRect>, val extend: List<Extension>) {
        val isEmpty: Boolean get() = remove.isEmpty() && extend.isEmpty()
        companion object { val NONE = LamaJob(emptyList(), emptyList()) }
    }

    // ------------------------------------------------------------------
    // What the model is told
    // ------------------------------------------------------------------

    /**
     * The detectors' findings in words. A vision model at 512 px reads a
     * face fine but cannot measure; the facts let it decide from numbers
     * where numbers matter (how close to the edge, how plain the edge).
     *
     * @param edges luminance spread (0..255) of the strip along each edge.
     */
    fun facts(face: SubjectBox, pose: BodyPose?, edges: Map<Side, Float>): String {
        val where = when {
            face.cx < NEAR_SIDE -> "against the left edge"
            face.cx > 1f - NEAR_SIDE -> "against the right edge"
            face.cx < 0.40f -> "left of centre"
            face.cx > 0.60f -> "right of centre"
            else -> "centred"
        }
        val top = (face.top * 100f).roundToInt().coerceAtLeast(0)
        val faceLine = "The face is ${(face.h * 100f).roundToInt()}% of the frame tall, its top ${top}% below the top edge, $where."
        val body = if (pose == null) {
            "No body found."
        } else {
            val inFrame = mutableListOf<String>()
            val out = mutableListOf<String>()
            (if (pose.shoulderY != null) inFrame else out) += "shoulders"
            (if (pose.hipY != null) inFrame else out) += "hips"
            (if (pose.kneeY != null) inFrame else out) += "knees"
            (if (pose.ankleY != null) inFrame else out) += "feet"
            val a = if (inFrame.isEmpty()) "" else "In the frame: ${inFrame.joinToString(", ")}"
            val b = if (out.isEmpty()) "" else "out of the frame: ${out.joinToString(", ")}"
            listOf(a, b).filter { it.isNotEmpty() }.joinToString("; ").replaceFirstChar { it.uppercase() } + "."
        }
        val edgeWords = listOf(Side.TOP, Side.LEFT, Side.RIGHT, Side.BOTTOM).map { s ->
            val name = if (s == Side.TOP) "above the head" else "${s.name.lowercase()} edge"
            "$name ${plainness(edges[s])}"
        }
        return "$faceLine $body Edges: ${edgeWords.joinToString(", ")}."
    }

    /** An edge's texture in one word. */
    fun plainness(stdDev: Float?): String = when {
        stdDev == null -> "unknown"
        stdDev <= HeadroomExtension.PLAIN_STDDEV -> "plain"
        stdDev <= PLAIN_FOR_LAMA -> "soft"
        else -> "busy"
    }

    // ------------------------------------------------------------------
    // What the model answers
    // ------------------------------------------------------------------

    private val KEYS = listOf("CROP", "HEADROOM", "EXTEND", "REMOVE", "LOOK", "WHY")
    private val LOOKS = listOf("NATURAL", "WARM", "COOL", "VIVID", "MONO", "FILM")

    /**
     * Read the six-line plan. Lines may come in any order, keys in any
     * case, with or without a colon; a plan squeezed onto one line is
     * split at the keys. Anything unreadable is simply not in the plan -
     * an unreadable answer is [Plan.NONE], and the rules decide alone.
     */
    fun parse(answer: String?, candidateCount: Int): Plan {
        if (answer.isNullOrBlank()) return Plan.NONE
        var lines = answer.lines().map { it.trim().trimStart('*', '-', ' ') }.filter { it.isNotEmpty() }
        if (lines.size == 1) {
            // One line: "CROP THIGH HEADROOM ADD EXTEND NONE ..." - split at the keys.
            lines = Regex("(?i)\\b(?=(?:${KEYS.joinToString("|")})\\b)").split(lines[0]).map { it.trim() }.filter { it.isNotEmpty() }
        }
        var plan = Plan.NONE
        var seen = 0
        for (line in lines) {
            val m = Regex("^([A-Za-z]+)\\s*[:\\-]?\\s*(.*)$").find(line) ?: continue
            val key = m.groupValues[1].uppercase()
            val value = m.groupValues[2].trim().trimEnd('.')
            val upper = value.uppercase()
            if (key !in KEYS) {
                // The last line under a made-up key ("GOOD natural light") is the reason.
                if (seen >= KEYS.size - 1 && plan.why.isEmpty()) plan = plan.copy(why = line.trim('"', ' ', '.'))
                continue
            }
            seen++
            when (key) {
                "CROP" -> {
                    val keep = upper.startsWith("KEEP") || upper.startsWith("NONE") || upper.startsWith("NO ") || upper == "NO"
                    plan = plan.copy(cut = if (keep) null else PhotographerCrop.parseCut(value), keepFrame = keep)
                }
                "HEADROOM" -> plan = plan.copy(
                    headroom = upper.startsWith("ADD") || upper.startsWith("MORE"),
                    trimHeadroom = "MUCH" in upper || upper.startsWith("TRIM") || upper.startsWith("LESS"),
                )
                "EXTEND" -> plan = plan.copy(extend = Side.entries.filter { Regex("\\b${it.name}\\b").containsMatchIn(upper) }.toSet())
                "REMOVE" -> plan = plan.copy(
                    remove = Regex("\\d+").findAll(upper).map { it.value.toInt() }.filter { it in 1..candidateCount }.distinct().toList(),
                )
                "LOOK" -> plan = plan.copy(look = LOOKS.firstOrNull { Regex("\\b$it\\b").containsMatchIn(upper) })
                "WHY" -> plan = plan.copy(why = value.trim('"', ' '))
            }
        }
        return plan
    }

    // ------------------------------------------------------------------
    // What the rules allow
    // ------------------------------------------------------------------

    /**
     * The room to paint in, as the plan asks and the rules allow.
     *
     *  - Above the head: the rules add it on their own when the strip is
     *    plain enough to reflect ([HeadroomExtension]); the model's ADD
     *    also earns it on a softer strip, because LaMa can paint texture
     *    a reflection cannot. Either way only what the headroom rule says
     *    is missing, never more.
     *  - A side: only on the model's word - and the word is "room to the
     *    side"; which side is the geometry's call. The model tends to name
     *    the side that is already plain; the room goes on the side the
     *    person is against, when that edge is soft enough to paint.
     *  - Below: only with the feet fully in the frame, so what is painted
     *    is floor and not shoes.
     */
    fun extensions(plan: Plan, face: SubjectBox, pose: BodyPose?, edges: Map<Side, Float>): List<Extension> {
        val out = mutableListOf<Extension>()
        val topSpread = edges[Side.TOP] ?: Float.MAX_VALUE
        val topPaintable = plan.headroom && topSpread <= PLAIN_FOR_LAMA
        val top = HeadroomExtension.extraTop(face, if (topPaintable) 0f else topSpread)
        if (top > 0f) out += Extension(Side.TOP, top)
        val sideways = Side.LEFT in plan.extend || Side.RIGHT in plan.extend
        val against = when {
            face.cx < NEAR_SIDE -> Side.LEFT
            face.cx > 1f - NEAR_SIDE -> Side.RIGHT
            else -> null
        }
        if (sideways && against != null && (edges[against] ?: Float.MAX_VALUE) <= PLAIN_FOR_LAMA) out += Extension(against, SIDE_EXTRA)
        val feetIn = pose?.ankleY?.let { it < FEET_IN } ?: false
        if (Side.BOTTOM in plan.extend && feetIn && (edges[Side.BOTTOM] ?: Float.MAX_VALUE) <= PLAIN_FOR_LAMA) out += Extension(Side.BOTTOM, BOTTOM_EXTRA)
        return out
    }

    /** The instructions for LaMa: the plan's holes, then the extensions. */
    fun job(plan: Plan, candidates: List<Retouch.Candidate>, extensions: List<Extension>): LamaJob =
        LamaJob(Retouch.holes(candidates, plan.remove), extensions)

    /** What was done, in the review's words. */
    fun describe(job: LamaJob): List<String> {
        val out = mutableListOf<String>()
        for (e in job.extend) out += when (e.side) {
            Side.TOP -> "Room painted in above the head"
            Side.BOTTOM -> "Floor painted in below"
            Side.LEFT -> "Room painted in on the left"
            Side.RIGHT -> "Room painted in on the right"
        }
        if (job.remove.isNotEmpty()) out += if (job.remove.size == 1) "One distraction painted out" else "${job.remove.size} distractions painted out"
        return out
    }

    // ------------------------------------------------------------------
    // Geometry of the extended photo
    // ------------------------------------------------------------------

    private fun amount(exts: List<Extension>, side: Side): Float = exts.filter { it.side == side }.sumOf { it.fraction.toDouble() }.toFloat()

    /** Width of the extended photo in units of the original width. */
    fun widthScale(exts: List<Extension>): Float = 1f + amount(exts, Side.LEFT) + amount(exts, Side.RIGHT)

    /** Height of the extended photo in units of the original height. */
    fun heightScale(exts: List<Extension>): Float = 1f + amount(exts, Side.TOP) + amount(exts, Side.BOTTOM)

    /** An x fraction of the original photo in the extended photo. */
    fun shiftX(x: Float, exts: List<Extension>): Float = (x + amount(exts, Side.LEFT)) / widthScale(exts)

    /** A y fraction of the original photo in the extended photo. */
    fun shiftY(y: Float, exts: List<Extension>): Float = (y + amount(exts, Side.TOP)) / heightScale(exts)

    fun shift(box: SubjectBox, exts: List<Extension>): SubjectBox =
        SubjectBox(cx = shiftX(box.cx, exts), cy = shiftY(box.cy, exts), w = box.w / widthScale(exts), h = box.h / heightScale(exts))

    fun shift(rect: CropRect, exts: List<Extension>): CropRect =
        CropRect(shiftX(rect.left, exts), shiftY(rect.top, exts), shiftX(rect.right, exts), shiftY(rect.bottom, exts))

    fun shift(pose: BodyPose?, exts: List<Extension>): BodyPose? = pose?.let {
        BodyPose(
            shoulderY = it.shoulderY?.let { y -> shiftY(y, exts) },
            hipY = it.hipY?.let { y -> shiftY(y, exts) },
            kneeY = it.kneeY?.let { y -> shiftY(y, exts) },
            ankleY = it.ankleY?.let { y -> shiftY(y, exts) },
        )
    }

    /**
     * The extended photo's size in pixels and where the original sits in
     * it: [width, height, left, top].
     */
    fun canvas(width: Int, height: Int, exts: List<Extension>): IntArray {
        val l = (width * amount(exts, Side.LEFT)).roundToInt()
        val r = (width * amount(exts, Side.RIGHT)).roundToInt()
        val t = (height * amount(exts, Side.TOP)).roundToInt()
        val b = (height * amount(exts, Side.BOTTOM)).roundToInt()
        return intArrayOf(width + l + r, height + t + b, l, t)
    }

    /** The strip one extension adds, as a fraction of the extended photo: the hole LaMa fills. */
    fun hole(ext: Extension, all: List<Extension>): CropRect {
        val ws = widthScale(all)
        val hs = heightScale(all)
        return when (ext.side) {
            Side.TOP -> CropRect(0f, 0f, 1f, amount(all, Side.TOP) / hs)
            Side.BOTTOM -> CropRect(0f, 1f - amount(all, Side.BOTTOM) / hs, 1f, 1f)
            Side.LEFT -> CropRect(0f, 0f, amount(all, Side.LEFT) / ws, 1f)
            Side.RIGHT -> CropRect(1f - amount(all, Side.RIGHT) / ws, 0f, 1f, 1f)
        }
    }

    /**
     * The window LaMa works in for one strip: the strip plus [BAND_CONTEXT]
     * times its depth of the real photo beside it, the full length of the
     * edge. Not square - a strip is not - so it is squashed to the net's
     * square and stretched back; on the plain edges the rules allow, that
     * costs nothing visible.
     */
    fun band(ext: Extension, all: List<Extension>): CropRect {
        val h = hole(ext, all)
        return when (ext.side) {
            Side.TOP -> CropRect(0f, 0f, 1f, (h.bottom * (1f + BAND_CONTEXT)).coerceAtMost(1f))
            Side.BOTTOM -> CropRect(0f, (1f - (1f - h.top) * (1f + BAND_CONTEXT)).coerceAtLeast(0f), 1f, 1f)
            Side.LEFT -> CropRect(0f, 0f, (h.right * (1f + BAND_CONTEXT)).coerceAtMost(1f), 1f)
            Side.RIGHT -> CropRect((1f - (1f - h.left) * (1f + BAND_CONTEXT)).coerceAtLeast(0f), 0f, 1f, 1f)
        }
    }

    /** An edge this textured (luminance std dev, 0..255) or less may be painted by LaMa. */
    const val PLAIN_FOR_LAMA = 40f
    /** Room painted in on a side, as a fraction of the width. */
    const val SIDE_EXTRA = 0.15f
    /** Floor painted in below, as a fraction of the height. */
    const val BOTTOM_EXTRA = 0.12f
    /** A face centred this close to a side edge counts as cramped against it. */
    const val NEAR_SIDE = 0.35f
    /** Ankles above this fraction of the height mean the feet are fully in. */
    const val FEET_IN = 0.92f
    /** Real photo beside a strip, in strip depths, inside LaMa's window. */
    const val BAND_CONTEXT = 2f
}
