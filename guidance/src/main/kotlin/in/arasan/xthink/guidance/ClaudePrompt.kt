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

    /** What to press for the prompt on [screen], as plan lines; null when no prompt is showing. */
    fun answer(screen: String): List<String>? {
        if (screen.isBlank()) return null
        return when {
            YES_NO.containsMatchIn(screen) -> listOf("TYPE y", "KEY enter")
            ENTER.containsMatchIn(screen) -> listOf("KEY enter")
            else -> null
        }
    }

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
