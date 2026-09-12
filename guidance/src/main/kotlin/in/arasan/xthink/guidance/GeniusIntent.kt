package `in`.arasan.xthink.guidance

/**
 * The model's reading of a spoken request, in one line: `KIND | ARG`.
 * The model understands; the macros execute. When its line cannot be
 * read, the word router decides instead - the model is never the only
 * thing between a sentence and the keyboard.
 */
object GeniusIntent {

    const val FORMAT = "KIND | ARG"

    /**
     * Does the model's reading fit the words? A small model will name an
     * app that was never said, or answer with "undefined". A route is
     * plausible when what it names was in the sentence (or, for shell
     * work, when the sentence sounded like shell work). Otherwise the
     * router's reading of the words wins.
     */
    fun plausible(route: Route, spoken: String): Boolean {
        val words = spoken.lowercase().split(Regex("""[^a-z0-9+.@]+""")).filter { it.isNotBlank() }.toSet()
        fun mentioned(text: String): Boolean {
            val ws = text.lowercase().split(Regex("""[^a-z0-9+.@]+""")).filter { it.length > 1 }
            if (ws.isEmpty()) return false
            val hits = ws.count { it in words || words.any { w -> w.startsWith(it) || it.startsWith(w) && w.length > 3 } }
            return hits * 2 >= ws.size
        }
        return when (route) {
            is Route.Open -> mentioned(route.app) || GeniusRouter.route(spoken).let { it is Route.Open && it.app == route.app }
            is Route.Website -> mentioned(route.query)
            is Route.Terminal -> GeniusRouter.route(spoken) is Route.Terminal || mentioned(route.request)
            is Route.Write -> mentioned(route.request)
            // A story or a letter is prose even when the model calls it a project.
            is Route.Project -> mentioned(route.request) && GeniusRouter.route(spoken) !is Route.Write
            is Route.WhatsApp -> spoken.replace(" ", "").contains(route.number)
            is Route.Plan -> true
            is Route.Help -> true
        }
    }

    /** The model's route when it is plausible for the words, else the router's. */
    fun decide(answer: String, spoken: String): Pair<Route, Boolean> {
        val fromModel = parse(answer)
        if (fromModel == null || !plausible(fromModel, spoken)) return GeniusRouter.route(spoken) to false
        // The words are the ground truth for a message: when they carry a
        // longer one to the same number, the model's shortening loses.
        if (fromModel is Route.WhatsApp) {
            val fromWords = GeniusRouter.route(spoken)
            if (fromWords is Route.WhatsApp && fromWords.number == fromModel.number && fromWords.message.length > fromModel.message.length) {
                return fromWords to false
            }
        }
        return fromModel to true
    }

    /** A parsed line, or null when the model wrote something else. */
    fun parse(answer: String): Route? {
        val line = answer.lineSequence().map { it.trim().trim('`', '*', '"') }.firstOrNull { it.contains('|') } ?: return null
        val kind = line.substringBefore('|').trim().uppercase().trimEnd(':')
        val arg = line.substringAfter('|').trim().trim('"', '\'')
        return when (kind) {
            "OPEN" -> if (arg.isBlank()) null else Route.Open(GeniusRouter.appName(arg))
            "WEBSITE", "WEB", "SITE" -> if (arg.isBlank()) null else {
                val a = arg.lowercase().removePrefix("https://").removePrefix("http://").trim('/')
                val domain = Regex("""^[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}(?:/\S*)?$""").matches(a)
                Route.Website(arg, if (domain) "https://$a" else GeniusRouter.luckyUrl(GeniusRouter.webTopic(arg)))
            }
            "TERMINAL", "SHELL", "COMMAND" -> if (arg.isBlank()) null else Route.Terminal(arg, command = arg)
            "WRITE" -> if (arg.isBlank()) null else Route.Write(arg)
            "PROJECT", "CLAUDE", "CODE" -> if (arg.isBlank()) null else Route.Project(arg)
            "WHATSAPP" -> {
                val number = Regex("""\+?\d[\d ]{7,}\d""").find(arg)?.value?.replace(" ", "") ?: return null
                val message = arg.substringAfter(';', "").trim().ifBlank { arg.replace(number, "").trim(' ', ';', ',', '-') }
                Route.WhatsApp(number, message)
            }
            "HELP" -> Route.Help
            "OTHER", "PLAN" -> null
            else -> null
        }
    }
}
