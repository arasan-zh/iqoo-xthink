package `in`.arasan.xthink.guidance

/**
 * What a spoken request may never turn into. Genius types what a model
 * wrote into a real terminal; a model can be wrong, and a microphone can
 * mishear. Anything that deletes, elevates, formats, or pipes the network
 * into a shell is refused outright - the plan is shown with the reason
 * and never runs. Conservative on purpose: a demo that refuses `rm` is
 * embarrassing; a demo that runs `rm -rf ~` is over.
 */
object CommandSafety {

    private val REFUSED: List<Pair<Regex, String>> = listOf(
        Regex("""(^|[\s;&|])rm\s""") to "deletes files",
        Regex("""(^|[\s;&|])rmdir\s""") to "deletes directories",
        Regex("""(^|[\s;&|])sudo(\s|$)""") to "asks for root",
        Regex("""(^|[\s;&|])su(\s|$)""") to "switches user",
        Regex("""(^|[\s;&|])(mkfs|diskutil|fdisk|dd)(\s|$)""") to "touches disks",
        Regex("""(^|[\s;&|])(shutdown|reboot|halt|killall|pkill)(\s|$)""") to "stops the machine or its apps",
        Regex("""(curl|wget)[^|]*\|\s*(sh|bash|zsh)""") to "pipes the internet into a shell",
        Regex(""">\s*/dev/""") to "writes to a device",
        Regex("""(^|[\s;&|])chmod\s+-R|chown\s+-R""") to "rewrites permissions recursively",
        Regex("""(^|[\s;&|])git\s+(push\s+.*--force|reset\s+--hard|clean\s+-f)""") to "destroys git history or files",
        Regex(""":\(\)\s*\{""") to "is a fork bomb",
        Regex("""(^|[\s;&|])(launchctl|defaults\s+write|csrutil|nvram)(\s|$)""") to "changes system settings",
        Regex("""(^|[\s;&|])(mv|cp)\s+[^;&|]*\s+/(bin|usr|etc|System|Library)(/|\s|$)""") to "moves into system folders",
    )

    /** Why [command] is refused, or null when it may run. */
    fun refusal(command: String): String? {
        val c = command.trim()
        if (c.isEmpty()) return null
        for ((re, why) in REFUSED) if (re.containsMatchIn(c)) return "refused: $why"
        return null
    }

    /**
     * Every step's verdict. A plan with any refused step must not run at
     * all - a later step may depend on the refused one.
     */
    fun refusals(steps: List<PlanStep>): List<String?> = steps.map { step ->
        val upper = step.line.uppercase()
        when {
            upper.startsWith("TERMINAL ") -> refusal(step.line.substringAfter(' '))
            upper.startsWith("TYPE ") -> refusal(step.line.substringAfter(' '))
            else -> null
        }
    }

    fun isSafe(steps: List<PlanStep>): Boolean = refusals(steps).all { it == null }
}
