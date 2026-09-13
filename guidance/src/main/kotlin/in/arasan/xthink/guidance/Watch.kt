package `in`.arasan.xthink.guidance

import kotlin.math.abs

/**
 * How different two frames are, on small luma grids (the same size),
 * 0 = identical, 1 = black against white. Cheap enough for every
 * couple of seconds; it decides whether the model is worth waking.
 */
object FrameDiff {
    fun difference(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || a.size != b.size) return 1f
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return (sum.toFloat() / a.size) / 255f
    }
}

/**
 * Keeping watch: the camera records, the model looks now and then and
 * writes down what mattered, the microphone is turned into words, and
 * at the end it all goes into one file a person can read.
 *
 * The model is expensive and the session is long, so the looks are
 * rationed here: one when the frame has changed materially and at least
 * [LOOK_MIN_MS] have passed, and one anyway every [LOOK_MAX_MS] so a
 * still scene is still confirmed still. Five minutes at most, and the
 * session ends itself. Pure; :app records, snapshots, listens, asks,
 * and writes the file.
 */
class WatchSession(val startMs: Long, private val limitMs: Long = LIMIT_MS) {

    /** One line, stamped with when in the session it was written. */
    data class Note(val atMs: Long, val text: String)

    private val seenNotes = mutableListOf<Note>()
    private val heardNotes = mutableListOf<Note>()
    private var lastLookMs = -1L
    private var lastGrid: IntArray? = null
    private var looksBooked = 0
    private var endedMs = -1L
    private var endWhy = ""

    /** What the model wrote about the frames, oldest first. */
    val seen: List<Note> get() = seenNotes
    /** What was heard, oldest first. */
    val heard: List<Note> get() = heardNotes
    /** How many times the model was asked to look. */
    val looks: Int get() = looksBooked
    val ended: Boolean get() = endedMs >= 0

    fun remainingMs(now: Long): Long = (limitMs - (now - startMs)).coerceAtLeast(0L)
    fun over(now: Long): Boolean = now - startMs >= limitMs

    /**
     * A frame is in, as a small luma grid. True when the model should
     * look at it now - and the look is booked, so ask it. False: not yet.
     */
    fun look(now: Long, grid: IntArray): Boolean {
        val since = if (lastLookMs < 0) Long.MAX_VALUE else now - lastLookMs
        val prev = lastGrid
        val due = when {
            prev == null -> true
            since >= LOOK_MAX_MS -> true
            since >= LOOK_MIN_MS && FrameDiff.difference(prev, grid) >= CHANGE -> true
            else -> false
        }
        if (!due) return false
        lastLookMs = now
        lastGrid = grid
        looksBooked++
        return true
    }

    /** The model's note on a frame. Blank, or a shrug, is not a note. */
    fun noteSeen(now: Long, text: String?) {
        val t = text?.let { latinOnly(it) }?.trim()?.trim('"')?.trimEnd('.') ?: return
        // A shrug anywhere in the answer - the model likes to repeat its
        // last note and then say so - and a note the same as the last are
        // not notes.
        if (t.isBlank() || "NOTHING NEW" in t.uppercase() || t.uppercase() == "NOTHING") return
        if (t == lastSeen()) return
        seenNotes += Note(now, t)
    }

    /** Words from the microphone. */
    fun noteHeard(now: Long, text: String?) {
        val t = text?.trim() ?: return
        if (t.isBlank()) return
        heardNotes += Note(now, t)
    }

    /**
     * The model sometimes finishes an English note with the same thing
     * again in another script; the notes are English, so other scripts
     * (CJK, Indic, Arabic, Cyrillic...) are dropped and the spaces closed.
     */
    fun latinOnly(text: String): String =
        text.replace(Regex("""[\u0400-\u04FF\u0590-\u06FF\u0900-\u0DFF\u0E00-\u0E7F\u1100-\u11FF\u3040-\u30FF\u3400-\u4DBF\u4E00-\u9FFF\uAC00-\uD7AF\uFF00-\uFFEF\u3000-\u303F]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ").trim()

    /** The last thing written down about the picture, if any. */
    fun lastSeen(): String? = seenNotes.lastOrNull()?.text

    fun end(now: Long, why: String) {
        if (endedMs >= 0) return
        endedMs = now
        endWhy = why
    }

    fun clock(atMs: Long): String {
        val s = ((atMs - startMs) / 1000L).coerceAtLeast(0L)
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** The numbers and the circumstances, one line: what the summary is written from. */
    fun facts(environment: String, now: Long = endedMs): String {
        val at = if (now >= 0) now else startMs
        return "Length ${clock(at)}. The model looked ${looksBooked} times and wrote ${seenNotes.size} notes; ${heardNotes.size} things were heard. $environment"
    }

    fun seenText(): String = if (seenNotes.isEmpty()) "(nothing noted)" else seenNotes.joinToString("\n") { "${clock(it.atMs)}  ${it.text}" }
    fun heardText(): String = if (heardNotes.isEmpty()) "(nothing heard)" else heardNotes.joinToString("\n") { "${clock(it.atMs)}  \"${it.text}\"" }

    /**
     * The file, as Markdown: when, how long, the video, the environment,
     * the model's summary if it wrote one, then everything seen and
     * everything heard, in order.
     */
    fun report(startedAt: String, environment: String, video: String?, summary: String?): String {
        val at = if (endedMs >= 0) endedMs else startMs
        val sb = StringBuilder()
        sb.append("# Watch · ").append(startedAt).append("\n\n")
        sb.append("Length ").append(clock(at)).append(if (endWhy.isNotBlank()) " (ended: $endWhy)" else "").append(". ")
        sb.append(if (video != null) "Video: $video - no audio track; what was heard is written below." else "No video was kept.").append("\n\n")
        sb.append("Environment: ").append(environment).append("\n\n")
        if (!summary.isNullOrBlank()) sb.append("## What mattered\n\n").append(summary.trim()).append("\n\n")
        sb.append("## Seen (").append(seenNotes.size).append(", from ").append(looksBooked).append(" looks)\n\n")
        for (n in seenNotes) sb.append("- ").append(clock(n.atMs)).append("  ").append(n.text).append("\n")
        if (seenNotes.isEmpty()) sb.append("- nothing noted\n")
        sb.append("\n## Heard (").append(heardNotes.size).append(")\n\n")
        for (n in heardNotes) sb.append("- ").append(clock(n.atMs)).append("  \"").append(n.text).append("\"\n")
        if (heardNotes.isEmpty()) sb.append("- nothing heard\n")
        return sb.toString()
    }

    companion object {
        /** The session ends here, whatever is happening. */
        const val LIMIT_MS = 5L * 60L * 1000L
        /** No look sooner than this after the last, however much changed. */
        const val LOOK_MIN_MS = 15_000L
        /** A look at least this often, changed or not. */
        const val LOOK_MAX_MS = 60_000L
        /** Frame difference (0..1) that counts as a material change. */
        const val CHANGE = 0.06f
    }
}
