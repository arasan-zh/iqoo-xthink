package `in`.arasan.xthink.guidance

/**
 * One thing the phone does to the Mac: press a chord, type text, or
 * wait. Everything Genius does is a list of these, so a plan can be
 * shown, counted and replayed.
 */
sealed class MacOp {
    /** A key with modifiers held, e.g. Cmd+Space, Enter, Ctrl+`. */
    data class Chord(val usage: Int, val modifiers: Int, val name: String) : MacOp()

    /** Text typed character by character. */
    data class Type(val text: String) : MacOp()

    data class Wait(val ms: Long) : MacOp()
}

/** A plan step as the model wrote it, with what it expands to. */
data class PlanStep(val line: String, val ops: List<MacOp>)

/**
 * The action language Gemma answers in, and what each line means on a
 * Mac. Five verbs, one per line, arguments after a space:
 *
 *  - `OPEN <app>`         Spotlight: Cmd+Space, the name, Enter.
 *  - `TERMINAL <command>` open Terminal, type the command, Enter.
 *  - `CLAUDE <request>`   open VS Code, its terminal (Ctrl+`), start
 *                         `claude`, hand it the request.
 *  - `TYPE <text>`        type text into whatever has focus.
 *  - `KEY <chord>`        press a chord: enter, tab, escape, cmd+space,
 *                         ctrl+c, cmd+shift+p ...
 *  - `WAIT <ms>`          pause.
 *  - `DONE`               nothing more to do.
 *
 * Deterministic macros do the app plumbing; the model only decides which
 * verbs, with which arguments. Anything it writes that is not a verb is
 * ignored, so prose around the plan does no harm.
 */
object GeniusPlan {

    const val MOD_CTRL = 0x01
    const val MOD_SHIFT = 0x02
    const val MOD_ALT = 0x04
    const val MOD_CMD = 0x08

    /** Named keys a chord may use, beyond letters and digits. */
    private val NAMED: Map<String, Int> = mapOf(
        "enter" to HidKeymap.ENTER, "return" to HidKeymap.ENTER,
        "tab" to HidKeymap.TAB, "space" to HidKeymap.SPACE, "escape" to HidKeymap.ESCAPE, "esc" to HidKeymap.ESCAPE,
        "backspace" to HidKeymap.BACKSPACE, "delete" to HidKeymap.BACKSPACE,
        "`" to 0x35, "backtick" to 0x35, "grave" to 0x35,
        "up" to 0x52, "down" to 0x51, "left" to 0x50, "right" to 0x4F,
        "," to 0x36, "." to 0x37, "/" to 0x38, ";" to 0x33, "'" to 0x34, "-" to 0x2D, "=" to 0x2E,
    )

    /** Parse "cmd+shift+p", "enter", "ctrl+`" into a chord, or null. */
    fun chord(spec: String): MacOp.Chord? {
        val parts = spec.trim().lowercase().split('+', '-', ' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var mods = 0
        var key: Int? = null
        for (p in parts) {
            when (p) {
                "cmd", "command", "meta", "super", "win" -> mods = mods or MOD_CMD
                "ctrl", "control" -> mods = mods or MOD_CTRL
                "alt", "option", "opt" -> mods = mods or MOD_ALT
                "shift" -> mods = mods or MOD_SHIFT
                else -> {
                    key = NAMED[p] ?: when {
                        p.length == 1 && p[0] in 'a'..'z' -> 0x04 + (p[0] - 'a')
                        p.length == 1 && p[0] in '1'..'9' -> 0x1E + (p[0] - '1')
                        p == "0" -> 0x27
                        else -> return null
                    }
                }
            }
        }
        val k = key ?: return null
        return MacOp.Chord(k, mods, spec.trim())
    }

    /** Cmd+Space, the app's name, a beat for Spotlight, Enter, a beat for the launch. */
    fun open(app: String): List<MacOp> = listOf(
        chord("cmd+space")!!, MacOp.Wait(700), MacOp.Type(app.trim()), MacOp.Wait(900),
        chord("enter")!!, MacOp.Wait(1800),
    )

    fun terminal(command: String): List<MacOp> =
        open("Terminal") + listOf(MacOp.Type(command.trim()), chord("enter")!!, MacOp.Wait(1500))

    /** VS Code, its integrated terminal, `claude`, then the request. */
    fun claude(request: String): List<MacOp> =
        open("Visual Studio Code") + listOf(
            chord("ctrl+`")!!, MacOp.Wait(1200),
            MacOp.Type("claude"), chord("enter")!!, MacOp.Wait(5000),
            MacOp.Type(request.trim()), chord("enter")!!, MacOp.Wait(1500),
        )

    /** A model's answer into steps. Lines that are not verbs are dropped. */
    fun parse(answer: String): List<PlanStep> {
        val steps = mutableListOf<PlanStep>()
        for (raw in answer.lines()) {
            val line = raw.trim().trimStart('-', '*', '•', ' ').removePrefix("`").removeSuffix("`").trim()
            if (line.isEmpty()) continue
            val verb = line.substringBefore(' ').uppercase().trimEnd(':')
            // A model may tack DONE onto the last verb's line; it is its own step.
            var arg = line.substringAfter(' ', "").trim()
            var trailingDone = false
            if (verb != "DONE" && Regex("""\s+DONE\.?$""", RegexOption.IGNORE_CASE).containsMatchIn(arg)) {
                arg = arg.replace(Regex("""\s+DONE\.?$""", RegexOption.IGNORE_CASE), "").trim()
                trailingDone = true
            }
            val ops: List<MacOp>? = when (verb) {
                "OPEN" -> if (arg.isNotEmpty()) open(arg) else null
                "TERMINAL" -> if (arg.isNotEmpty()) terminal(arg) else null
                "CLAUDE" -> if (arg.isNotEmpty()) claude(arg) else null
                "TYPE" -> if (arg.isNotEmpty()) listOf(MacOp.Type(arg)) else null
                "KEY" -> chord(arg)?.let { listOf(it, MacOp.Wait(300)) }
                "WAIT" -> arg.toLongOrNull()?.let { listOf(MacOp.Wait(it.coerceIn(0L, 15_000L))) }
                "DONE" -> emptyList()
                else -> null
            }
            if (ops != null) steps += PlanStep(if (trailingDone) "$verb $arg".trim() else line, ops)
            if (trailingDone) steps += PlanStep("DONE", emptyList())
        }
        return steps
    }

    /** True when the answer says the goal is reached and nothing is left to do. */
    fun isDone(answer: String): Boolean =
        parse(answer).let { steps -> steps.isNotEmpty() && steps.all { it.line.uppercase().startsWith("DONE") } }

    /** The check's "still working" - WAIT on its own, nothing to perform yet. */
    fun isWait(answer: String): Boolean =
        answer.lineSequence().map { it.trim().trim('`', '*', '"', '.') }.firstOrNull { it.isNotEmpty() }?.uppercase()?.let { it == "WAIT" || it.startsWith("WAIT ") && it.substringAfter(' ').trim().toLongOrNull() == null } ?: false
}
