package `in`.arasan.xthink.guidance

/**
 * What a spoken request is, decided by its words before any model is
 * asked. A small on-device model handed a whole action language copies
 * the nearest example; handed one narrow blank ("what shell command?",
 * "write this letter") it does well. So the routing is rules, and the
 * model fills in the one blank a route leaves.
 */
sealed class Route {
    /** "open safari" - the app's name is in the words. */
    data class Open(val app: String) : Route()

    /** A website: a domain if one was named, else a search that lands on the first result. */
    data class Website(val query: String, val url: String) : Route()

    /** Shell work; the model supplies the one-line command. */
    data class Terminal(val request: String) : Route()

    /** Writing: a letter, notes, a story. The model writes it; TextEdit gets it. */
    data class Write(val request: String) : Route()

    /** A project: VS Code, a new window, Claude Code, a single-HTML-file brief. */
    data class Project(val request: String) : Route()

    /** WhatsApp: a message to a number. */
    data class WhatsApp(val number: String, val message: String) : Route()

    /** Nothing matched: the model plans in the full verb language. */
    data class Plan(val request: String) : Route()
}

object GeniusRouter {

    private val TERMINAL_WORDS = listOf(
        "terminal", "shell", "command", "bash", "zsh", "directory", "folder", "files", "list ", "ssh", "server",
        "git ", "install", "npm", "pip", "brew", "python", "process", "disk", "ping", "curl", "docker",
    )
    private val WRITE_WORDS = listOf("write", "letter", "story", "note", "poem", "essay", "draft", "compose", "type a")
    private val PROJECT_WORDS = listOf(
        "website", "web site", "web page", "landing page", "portfolio", "project", "html", "app", "code",
        "build", "create", "generate", "implement", "develop", "fix the", "refactor",
    )
    private val WEB_WORDS = listOf("website of", "site of", ".com", ".in", ".org", ".net", ".io", ".dev", "search for", "google", "browse", "go to", "look up")

    /** Apps a plain "open ..." may name; the rest go to the model. */
    private val APPS = mapOf(
        "safari" to "Safari", "chrome" to "Google Chrome", "terminal" to "Terminal", "notes" to "Notes",
        "vs code" to "Visual Studio Code", "vscode" to "Visual Studio Code", "visual studio code" to "Visual Studio Code",
        "finder" to "Finder", "mail" to "Mail", "calendar" to "Calendar", "music" to "Music", "messages" to "Messages",
        "settings" to "System Settings", "system settings" to "System Settings", "xcode" to "Xcode", "slack" to "Slack",
        "text edit" to "TextEdit", "textedit" to "TextEdit", "preview" to "Preview", "photos" to "Photos",
        "whatsapp" to "WhatsApp", "spotify" to "Spotify", "zoom" to "zoom.us",
    )

    fun route(spoken: String): Route {
        val s = spoken.trim().lowercase().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return Route.Plan(spoken)

        // WhatsApp with a number in the words.
        if ("whatsapp" in s || "whats app" in s) {
            val number = Regex("""(\+?\d[\d ]{7,}\d)""").find(spoken)?.groupValues?.get(1)?.replace(" ", "")
            if (number != null) {
                // The message is whatever follows the LAST "saying"/"say"/"that says"/"message"/"text".
                val message = Regex("""^.*\b(?:saying|say|that says|message|text)\s+(.+)$""", RegexOption.IGNORE_CASE)
                    .find(spoken.replace(number, " "))?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: ""
                return Route.WhatsApp(number, message)
            }
        }

        // A plain open of a known app.
        val open = Regex("""^(?:please )?(?:open|launch|start) (?:the |up )?(.+?)(?: app| application| browser)?(?: please)?$""").find(s)
        if (open != null) {
            val name = open.groupValues[1].trim()
            val known = APPS[name]
            if (known != null && " and " !in name) return Route.Open(known)
        }

        // A website.
        val domain = Regex("""\b([a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|org|net|io|dev|co|ai|app|edu|gov))\b""").find(s)
        if (domain != null) return Route.Website(domain.value, "https://${domain.value}")
        if (WEB_WORDS.any { Regex("""(?<![a-z])${Regex.escape(it)}(?![a-z])""").containsMatchIn(s) } || Regex("""\b(open|go to|visit|show me) (?:the )?(.+?) (?:website|site|web page|home ?page)\b""").containsMatchIn(s)) {
            return Route.Website(webTopic(s), luckyUrl(webTopic(s)))
        }
        val terminal = TERMINAL_WORDS.any { it in "$s " }
        val write = WRITE_WORDS.any { it in "$s " }
        val project = PROJECT_WORDS.any { it in "$s " }
        return when {
            project && !write -> Route.Project(spoken.trim())
            write && !terminal -> Route.Write(spoken.trim())
            terminal -> Route.Terminal(spoken.trim())
            project -> Route.Project(spoken.trim())
            else -> Route.Plan(spoken.trim())
        }
    }

    /**
     * The site's name out of a sentence: "open safari and search for the apple
     * website and then open it" -> "apple". Browser names, "and then ..."
     * tails, and the words website/site are noise.
     */
    fun webTopic(spoken: String): String {
        var t = spoken.trim().lowercase()
        t = t.replace(Regex("""\b(?:in|on|with|using|open|launch)\s+(?:safari|chrome|google chrome|the browser|a browser)\b(?:\s+and)?"""), " ")
        t = t.replace(Regex("""\s+and\s+then\b.*$"""), " ")
        t = t.replace(Regex("""\s+then\b.*$"""), " ")
        val after = Regex("""(?:search for|google|look up|browse|go to|visit|show me|open)\s+(?:the\s+)?(.+)$""").find(t)?.groupValues?.get(1)
        if (after != null) t = after
        t = t.replace(Regex("""\b(?:official\s+)?(?:website|web site|site|web page|home ?page|page)\b"""), " ")
        t = t.replace(Regex("""\b(?:and|the|please|now|it|for me)\b"""), " ")
        return t.replace(Regex("""\s+"""), " ").trim().ifEmpty { spoken.trim() }
    }

    /** Google's "I'm Feeling Lucky": the first result, which is the site itself. */
    fun luckyUrl(topic: String): String {
        val q = topic.trim().replace(Regex("\\s+"), "+")
            .replace("&", "%26").replace("#", "%23").replace("?", "%3F")
        return "https://www.google.com/search?btnI=1&q=$q"
    }

    /** The single-file brief Claude Code is handed for a project. */
    fun projectBrief(request: String): String =
        "Create a single self-contained index.html for: $request. Put all HTML, CSS and JavaScript in that one file, " +
            "no build step, no external assets except Google Fonts. Make it polished, responsive and finished - " +
            "real copy, not lorem ipsum. Write only that one file."

    /**
     * The plan for a routed request. [text] is what the model wrote for a
     * Write route, or the command for a Terminal route; null when the
     * route needs none.
     */
    fun steps(route: Route, text: String? = null): List<PlanStep> = when (route) {
        is Route.Open -> listOf(PlanStep("OPEN ${route.app}", GeniusPlan.open(route.app)))
        is Route.Website -> listOf(
            PlanStep("OPEN Safari", GeniusPlan.open("Safari")),
            PlanStep("GO TO ${route.query}", listOf(
                GeniusPlan.chord("cmd+l")!!, MacOp.Wait(500), MacOp.Type(route.url), MacOp.Wait(300),
                GeniusPlan.chord("enter")!!, MacOp.Wait(2500),
            )),
        )
        is Route.Terminal -> text?.let { listOf(PlanStep("TERMINAL $it", GeniusPlan.terminal(it))) } ?: emptyList()
        is Route.Write -> text?.let {
            listOf(
                PlanStep("OPEN TextEdit", GeniusPlan.open("TextEdit")),
                PlanStep("NEW document", listOf(GeniusPlan.chord("cmd+n")!!, MacOp.Wait(900))),
                PlanStep("WRITE ${it.length} characters", listOf(MacOp.Type(it))),
            )
        } ?: emptyList()
        is Route.Project -> listOf(
            PlanStep("OPEN Visual Studio Code", GeniusPlan.open("Visual Studio Code")),
            PlanStep("NEW window", listOf(GeniusPlan.chord("cmd+shift+n")!!, MacOp.Wait(2000))),
            PlanStep("OPEN terminal", listOf(GeniusPlan.chord("ctrl+`")!!, MacOp.Wait(1500))),
            PlanStep("RUN claude", listOf(MacOp.Type("claude"), GeniusPlan.chord("enter")!!, MacOp.Wait(7000))),
            PlanStep("ASK ${route.request}", listOf(MacOp.Type(projectBrief(route.request)), MacOp.Wait(400), GeniusPlan.chord("enter")!!)),
        )
        is Route.WhatsApp -> listOf(
            PlanStep("OPEN WhatsApp", GeniusPlan.open("WhatsApp")),
            PlanStep("NEW chat to ${route.number}", listOf(
                GeniusPlan.chord("cmd+n")!!, MacOp.Wait(1500), MacOp.Type(route.number), MacOp.Wait(2000),
                GeniusPlan.chord("enter")!!, MacOp.Wait(2000),
            )),
            PlanStep("SEND ${(text ?: route.message).take(60)}", listOf(
                MacOp.Type(text ?: route.message), MacOp.Wait(300), GeniusPlan.chord("enter")!!,
            )),
        )
        is Route.Plan -> emptyList()
    }
}
