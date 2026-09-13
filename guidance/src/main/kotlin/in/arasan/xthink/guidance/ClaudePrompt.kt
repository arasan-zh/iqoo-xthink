package `in`.arasan.xthink.guidance

/**
 * Claude Code's questions, read off a terminal through a camera, and the
 * keys that answer them. The agent stops to ask - trust this folder,
 * allow this tool, proceed?, a numbered choice - and someone has to
 * press Enter. This is that someone's eyes: the common prompts as
 * patterns, tolerant of a noisy reading, answered without the model;
 * the model is only asked when nothing here matches.
 */
object ClaudePrompt {

    /** A yes-or-no in letters: type y, then Enter. */
    private val YES_NO = Regex("""\(\s*y\s*/\s*n\s*\)|\[\s*y\s*/\s*n\s*\]|\by\s*/\s*n\b""", RegexOption.IGNORE_CASE)

    /** A question Enter answers: the highlighted default, a confirm, a trust, a permission. */
    private val ENTER = Regex(
        """enter\s+to\s+(?:confirm|continue|select|accept)|press\s+enter|yes,?\s+i\s+trust|trust\s+this\s+folder|do\s+you\s+want\s+to\s+proceed|allow\s+(?:this|once|always|for\s+this)|\byes,?\s+(?:proceed|continue|allow)|^\s*[❯>]\s*1\.|\b1\.\s*yes\b""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )

    /** The cursor on a choice, as the camera reads it: ❯ most often comes back as >. */
    private val CURSOR = Regex("""^\s*[❯>›»•●]\s*""")
    private val NUMBERED = Regex("""^\s*\d+\.\s""")
    private val YES_WORDS = Regex("""\b(?:yes|trust|allow|proceed|accept|continue)\b""", RegexOption.IGNORE_CASE)
    private val NO_WORDS = Regex("""\b(?:no|exit|cancel|deny|don't)\b""", RegexOption.IGNORE_CASE)

    /** Claude Code sitting at its input box, waiting to be asked. */
    private val READY = Regex("""\?\s*for\s*shortcuts|try\s+"|bypass\s+permissions|^\s*>\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * What to press for the prompt on [screen], as plan lines; null when no
     * prompt is showing. A menu is read as a menu: the cursor's line and
     * the yes line are found, and the arrows walk from one to the other
     * before Enter - the trust dialog opens with the cursor on "No, exit",
     * and a bare Enter there quits Claude.
     */
    fun answer(screen: String): List<String>? {
        if (screen.isBlank()) return null
        if (YES_NO.containsMatchIn(screen)) return listOf("TYPE y", "KEY enter")
        val lines = screen.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val options = lines.indices.filter { i ->
            val l = lines[i]
            CURSOR.containsMatchIn(l) || NUMBERED.containsMatchIn(l) ||
                (YES_WORDS.containsMatchIn(l) || NO_WORDS.containsMatchIn(l)) && l.length <= 40 && !l.endsWith("?")
        }
        if (options.size >= 2) {
            fun bare(i: Int) = lines[i].replace(CURSOR, "").replace(NUMBERED, "").trim()
            val yes = options.firstOrNull { YES_WORDS.containsMatchIn(bare(it)) && !NO_WORDS.containsMatchIn(bare(it).substringBefore(',')) }
            if (yes != null) {
                val selected = options.firstOrNull { CURSOR.containsMatchIn(lines[it]) } ?: options.first()
                val moves = options.indexOf(yes) - options.indexOf(selected)
                val walk = if (moves > 0) List(moves) { "KEY down" } else List(-moves) { "KEY up" }
                return walk + "KEY enter"
            }
        }
        return if (ENTER.containsMatchIn(screen)) listOf("KEY enter") else null
    }

    /** Claude is at its input box: the brief may be typed. */
    fun readyForInput(screen: String): Boolean = READY.containsMatchIn(screen)

    /** The line the prompt was found on, for the log. */
    fun line(screen: String): String {
        val m = YES_NO.find(screen) ?: ENTER.find(screen) ?: return ""
        val start = screen.lastIndexOf('\n', m.range.first).let { if (it < 0) 0 else it + 1 }
        val end = screen.indexOf('\n', m.range.last).let { if (it < 0) screen.length else it }
        return screen.substring(start, end).trim()
    }

    /**
     * A fingerprint of the prompt, so each is answered once even though
     * the screen still shows it for a reading or two after Enter: the
     * prompt's own line, letters only.
     */
    fun key(screen: String): String = line(screen).lowercase().filter { it.isLetterOrDigit() }
}
