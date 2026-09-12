package `in`.arasan.xthink.guidance

/**
 * Steve's eyes. The phone camera looks at the Mac; the text recogniser
 * reads the screen every couple of seconds; these functions decide what
 * that reading means - whether the screen has changed, whether what the
 * phone typed has appeared, what to show on the panel - without a model.
 * The model is asked to narrate only when something happened.
 */
object MacWatch {

    /** The reading, as lines: trimmed, empty ones dropped. */
    fun lines(text: String): List<String> = text.lineSequence().map { it.trim() }.filter { it.length >= MIN_LINE }.toList()

    /**
     * Did the screen change materially between two readings? Measured on
     * the sets of lines: a cursor blink or a clock tick changes one line,
     * a new window changes most of them.
     */
    fun changed(previous: String, next: String): Boolean {
        val a = lines(previous).toSet()
        val b = lines(next).toSet()
        if (a.isEmpty() && b.isEmpty()) return false
        if (a.isEmpty() || b.isEmpty()) return true
        val overlap = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        return overlap / union < CHANGE_SIMILARITY
    }

    /** Everything a plan types on the Mac, in order, or null when it types nothing. */
    fun typed(steps: List<PlanStep>): String? =
        steps.flatMap { it.ops }.filterIsInstance<MacOp.Type>().joinToString(" ") { it.text.trim() }.trim().ifBlank { null }

    /**
     * Did the typed text land? True when most of its words (three letters
     * or more) can be read on the screen; null when there is nothing to
     * judge by - nothing typed, or no readable words in it.
     */
    fun inputSeen(typed: String?, screen: String): Boolean? {
        val words = words(typed ?: return null)
        if (words.isEmpty()) return null
        val haystack = screen.lowercase()
        val seen = words.count { haystack.contains(it) }
        return seen.toFloat() / words.size >= SEEN_FRACTION
    }

    /** The words of a text worth looking for: letters and digits, three or more, lower case, distinct. */
    fun words(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]{3,}").findAll(text.lowercase()).map { it.value }.distinct().toList()

    /** The first readable lines, for the panel. */
    fun headline(screen: String, max: Int = 2): String = lines(screen).take(max).joinToString("  ·  ")

    /** Line-set similarity under which the screen counts as changed. */
    const val CHANGE_SIMILARITY = 0.6f
    /** Fraction of typed words that must be readable for the input to count as seen. */
    const val SEEN_FRACTION = 0.6f
    /** Shorter lines are recogniser noise. */
    const val MIN_LINE = 2
}
