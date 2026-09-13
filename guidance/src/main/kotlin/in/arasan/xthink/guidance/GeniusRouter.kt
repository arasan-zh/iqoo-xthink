package `in`.arasan.xthink.guidance

/**
 * What a spoken request is, decided by its words before any model is
 * asked. A small on-device model handed a whole action language copies
 * the nearest example; handed one narrow blank ("what shell command?",
 * "write this letter") it does well. So the routing is rules, and the
 * model fills in the one blank a route leaves.
 */
sealed class Route {
    /** "open safari" - the app's name is in the words. [newWindow]: "... in a new window". */
    data class Open(val app: String, val newWindow: Boolean = false) : Route()

    /** A plain search - results, not the first hit. */
    data class Search(val query: String) : Route()

    /** "type hello there" - dictation into whatever has focus. */
    data class Type(val text: String) : Route()

    /** A key chord with a name: "close the window" -> cmd+w. */
    data class Key(val chord: String, val label: String) : Route()

    /** A website: a domain if one was named, else a search that lands on the first result. */
    data class Website(val query: String, val url: String) : Route()

    /** Shell work; the model supplies the one-line command - here already, or asked for later. */
    data class Terminal(val request: String, val command: String? = null) : Route()

    /** Writing: a letter, notes, a story. The model writes it; TextEdit gets it. */
    data class Write(val request: String) : Route()

    /** A project: VS Code, a new window, Claude Code, a single-HTML-file brief. */
    /** Code for Claude Code. [skill] names a slash command on the Mac that holds the structure - `/portfolio` - or null for the router's brief. */
    data class Project(val request: String, val skill: String? = null) : Route()

    /** WhatsApp: a message to a number or a contact's name. */
    data class WhatsApp(val number: String, val message: String) : Route() {
        val contact: String get() = number
    }

    /** Nothing matched: the model plans in the full verb language. */
    data class Plan(val request: String) : Route()

    /** "What can you do?" - the list, nothing performed. */
    object Help : Route()
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

    /** What speech makes of app names, mapped back. Longest match first. */
    private val ALIASES: List<Pair<Regex, String>> = listOf(
        Regex("""\b(?:visual|whistle|vishal|visual's) studio code\b|\bvs ?code\b|\bstudio code\b|\bvisual studio\b""") to "Visual Studio Code",
        Regex("""\bwhat'?s ?app\b""") to "WhatsApp",
        Regex("""\bgoogle chrome\b|\bchrome\b""") to "Google Chrome",
        Regex("""\bsystem settings\b|\bsettings\b""") to "System Settings",
        Regex("""\btext ?edit\b""") to "TextEdit",
        Regex("""\bterminal\b""") to "Terminal",
        Regex("""\bsafari\b""") to "Safari",
        Regex("""\bnotes\b""") to "Notes",
        Regex("""\bfinder\b""") to "Finder",
        Regex("""\bmail\b""") to "Mail",
        Regex("""\bcalendar\b""") to "Calendar",
        Regex("""\bmusic\b""") to "Music",
        Regex("""\bmessages\b""") to "Messages",
        Regex("""\bxcode\b""") to "Xcode",
        Regex("""\bslack\b""") to "Slack",
        Regex("""\bpreview\b""") to "Preview",
        Regex("""\bphotos\b""") to "Photos",
        Regex("""\bspotify\b""") to "Spotify",
        Regex("""\bzoom\b""") to "zoom.us",
    )

    /** Claude Code, as speech hears it. */
    private val CLAUDE = Regex("""\b(?:claude|cloud|claw|clod|cloud card|clod card|claude code|cloud code|claw code)\b""")

    /** Phrases that are one key chord on a Mac. */
    private val KEYS: List<Triple<Regex, String, String>> = listOf(
        Triple(Regex("""\bclose (?:the |this )?(?:window|tab)\b"""), "cmd+w", "Close the window"),
        Triple(Regex("""\bquit\b|\bclose (?:the |this )?app\b"""), "cmd+q", "Quit the app"),
        Triple(Regex("""\bnew tab\b"""), "cmd+t", "New tab"),
        Triple(Regex("""\b(?:take a )?screenshot\b"""), "cmd+shift+3", "Screenshot"),
        Triple(Regex("""\bundo\b"""), "cmd+z", "Undo"),
        Triple(Regex("""\bredo\b"""), "cmd+shift+z", "Redo"),
        Triple(Regex("""\bselect all\b"""), "cmd+a", "Select all"),
        Triple(Regex("""\bcopy\b(?! me)"""), "cmd+c", "Copy"),
        Triple(Regex("""\bpaste\b"""), "cmd+v", "Paste"),
        Triple(Regex("""\bsave\b"""), "cmd+s", "Save"),
        Triple(Regex("""\bswitch (?:the )?app\b|\bnext app\b"""), "cmd+tab", "Switch app"),
        Triple(Regex("""\block (?:the )?(?:screen|mac|computer)\b"""), "ctrl+cmd+q", "Lock the screen"),
        Triple(Regex("""\bgo back\b"""), "cmd+[", "Go back"),
        Triple(Regex("""\b(?:press|hit) enter\b"""), "enter", "Enter"),
        Triple(Regex("""\b(?:press|hit) escape\b|\bescape\b"""), "escape", "Escape"),
        Triple(Regex("""\bfull ?screen\b"""), "ctrl+cmd+f", "Full screen"),
        Triple(Regex("""\bspotlight\b"""), "cmd+space", "Spotlight"),
        Triple(Regex("""\bmute\b|\bpause\b|\bplay pause\b"""), "space", "Play / pause"),
    )

    /** Apps a plain "open ..." may name; the rest go to the model. */
    private val APPS = mapOf(
        "safari" to "Safari", "chrome" to "Google Chrome", "terminal" to "Terminal", "notes" to "Notes",
        "vs code" to "Visual Studio Code", "vscode" to "Visual Studio Code", "visual studio code" to "Visual Studio Code",
        "finder" to "Finder", "mail" to "Mail", "calendar" to "Calendar", "music" to "Music", "messages" to "Messages",
        "settings" to "System Settings", "system settings" to "System Settings", "xcode" to "Xcode", "slack" to "Slack",
        "text edit" to "TextEdit", "textedit" to "TextEdit", "preview" to "Preview", "photos" to "Photos",
        "whatsapp" to "WhatsApp", "spotify" to "Spotify", "zoom" to "zoom.us",
    )

    /** The app's proper name for a spoken one, e.g. "vs code" -> "Visual Studio Code". */
    fun appName(spoken: String): String {
        val key = spoken.trim().lowercase().removeSuffix(" app").removeSuffix(" browser").trim()
        return APPS[key] ?: spoken.trim().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    fun route(spoken: String): Route {
        val s = spoken.trim().lowercase().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return Route.Plan(spoken)
        if (Regex("""\b(what can you do|what do you do|what are you able|help me|show me what you can|your abilities|what all can you)\b""").containsMatchIn(s) ||
            s == "help"
        ) return Route.Help

        // A machine named by its nickname: the shell work happens there, over ssh.
        remote(s, spoken)?.let { return it }

        // Claude Code named outright: a project, with whatever follows as the brief.
        if (CLAUDE.containsMatchIn(s) && Regex("""\b(?:build|make|create|write|generate|code|website|app|project|portfolio|page|bill me|design)\b""").containsMatchIn(s)) {
            val brief = Regex("""(?:and|to)\s+(?:build|make|create|write|generate|design|bill me)\s+(?:me\s+)?(.+)$""").find(s)?.groupValues?.get(1)
                ?: s.replace(CLAUDE, " ").replace(Regex("""\b(?:open|launch|start|and|then|please)\b"""), " ").replace(Regex("""\s+"""), " ").trim()
            return project(brief.ifBlank { spoken.trim() })
        }

        // WhatsApp: a message to a number, or to a name. A plain "open
        // whatsapp" is just an open and falls through to the app aliases.
        val mentionsWhatsApp = Regex("""\bwhat'?s ?app\b""").containsMatchIn(s)
        val messaging = Regex("""\b(?:send|text|message|msg|whatsapp)\b""").containsMatchIn(s) &&
            Regex("""\b(?:message|text|msg|saying|say|to)\b""").containsMatchIn(s.replace(Regex("""\bwhat'?s ?app\b"""), " "))
        if (messaging || (mentionsWhatsApp && !Regex("""^(?:please )?(?:open|launch|start)\s+what'?s ?app\s*(?:app)?$""").matches(s))) {
            val number = Regex("""(\+?\d[\d ]{7,}\d)""").find(spoken)?.groupValues?.get(1)?.replace(" ", "")
            val message = Regex("""^.*\b(?:saying|say|that says|says)\s+(.+)$""", RegexOption.IGNORE_CASE)
                .find(spoken)?.groupValues?.get(1)?.trim()?.trim('"', '\'') ?: ""
            if (number != null) return Route.WhatsApp(number, message)
            // The name: after "to"/"for", else right after "text"/"message"; the
            // message tail and the "on whatsapp" tail are cut first.
            val head = s.replace(Regex("""\b(?:saying|say|that says|says)\b.*$"""), " ")
                .replace(Regex("""\b(?:on|in|via|through|using)\s+what'?s ?app\b"""), " ")
                .replace(Regex("""\bwhat'?s ?app\b"""), " ")
            val name = Regex("""\b(?:to|for)\s+((?:[a-z]+\s?){1,3})$""").find(head.trim())?.groupValues?.get(1)
                ?: Regex("""\b(?:text|message|msg)\s+((?:[a-z]+\s?){1,3})$""").find(head.trim())?.groupValues?.get(1)
            val cleaned = name?.replace(Regex("""\b(?:me|my|a|an|the|him|her|them|please|now)\b"""), " ")?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
            if (cleaned.isNotEmpty()) return Route.WhatsApp(cleaned.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, message)
            return Route.WhatsApp("", message) // the caller asks who to
        }

        // A key chord in words.
        for ((re, chord, label) in KEYS) if (re.containsMatchIn(s) && !s.startsWith("open") && !s.startsWith("play ")) return Route.Key(chord, label)

        // "type hello there" - dictation.
        Regex("""^(?:please )?type\s+(.+)$""").find(s)?.let { return Route.Type(spoken.trim().substring(it.range.first + it.value.length - it.groupValues[1].length)) }

        // YouTube: "play X on youtube" - or just "play X" - lands on the video itself.
        val tube = "youtube" in s || "you tube" in s
        val play = Regex("""^(?:please )?play\b""").containsMatchIn(s)
        if (tube || play) {
            var what = Regex("""(?:play|search for|search|find|watch|open)\s+(.+?)\s*(?:on|in|at|from)\s+you ?tube\b""").find(s)?.groupValues?.get(1)
                ?: Regex("""you ?tube\s+(?:and\s+)?(?:play|search for|search|find|watch)\s+(.+)$""").find(s)?.groupValues?.get(1)
                ?: if (play) Regex("""^(?:please )?play\s+(.+)$""").find(s)?.groupValues?.get(1) else null
            what = what?.replace(Regex("""\s+(?:song|video|music|songs|videos)\s*$"""), "")?.trim()
            val q = what?.replace(Regex("""^(?:a|an|the|some|any|me)\s+"""), "")?.trim().orEmpty()
            val generic = q in setOf("", "a", "an", "the", "some", "any", "song", "video", "music", "something")
            return if (generic) Route.Website("youtube", "https://www.youtube.com")
            else Route.Website("youtube: $q", youtubePlayUrl(q))
        }

        // An app by name - as speech hears it - possibly in a new window.
        val newWindow = Regex("""\bnew window\b""").containsMatchIn(s)
        if (Regex("""^(?:please )?(?:open|launch|start)\b""").containsMatchIn(s) || newWindow) {
            for ((re, app) in ALIASES) if (re.containsMatchIn(s)) {
                val rest = s.replace(re, " ").replace(Regex("""\b(?:open|launch|start|the|up|app|application|browser|in|a|new|window|please|and)\b"""), " ").trim()
                // "open safari and search for X" is more than an open: fall through.
                if (rest.isBlank() || newWindow) return Route.Open(app, newWindow)
                break
            }
        }

        // A plain search: results, not the first hit.
        Regex("""^(?:please )?(?:search|google|look up|search for)\s+(?:for\s+)?(.+)$""").find(s)?.let { m ->
            val q = m.groupValues[1].replace(Regex("""\b(?:on|in)\s+(?:google|safari|the web|the internet)\b"""), "").trim()
            if (!Regex("""\b(?:website|site|home ?page)\b""").containsMatchIn(q)) return Route.Search(q)
        }

        // A website.
        val domain = Regex("""\b([a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|in|org|net|io|dev|co|ai|app|edu|gov))\b""").find(s)
        if (domain != null) return Route.Website(domain.value, "https://${domain.value}")
        if (WEB_WORDS.any { Regex("""(?<![a-z])${Regex.escape(it)}(?![a-z])""").containsMatchIn(s) } || Regex("""\b(open|go to|visit|show me) (?:the )?(.+?) (?:website|site|web page|home ?page)\b""").containsMatchIn(s)) {
            return Route.Website(webTopic(s), luckyUrl(webTopic(s)))
        }
        fun hasWord(list: List<String>) = list.any { w -> Regex("""(?<![a-z])${Regex.escape(w.trim())}(?![a-z])""").containsMatchIn(s) }
        val terminal = hasWord(TERMINAL_WORDS)
        val write = hasWord(WRITE_WORDS)
        val project = hasWord(PROJECT_WORDS)
        // Writing words win over making words: "write a one page story" is
        // prose, whatever "page" suggests. Code words alone make a project.
        val code = hasWord(listOf("code", "html", "website", "web site", "web page", "landing page", "app", "portfolio", "project", "implement", "refactor", "fix the"))
        return when {
            write && !terminal && !code -> Route.Write(spoken.trim())
            project && !write -> project(spoken.trim())
            write && !terminal -> if (code) project(spoken.trim()) else Route.Write(spoken.trim())
            terminal -> Route.Terminal(spoken.trim())
            project -> project(spoken.trim())
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

    /**
     * Straight to the first result. DuckDuckGo's backslash operator
     * redirects to it without an interstitial; Google's "I'm Feeling
     * Lucky" parameter just shows results when typed into an address bar.
     */
    fun luckyUrl(topic: String): String = "https://duckduckgo.com/?q=%5C" + encode(topic)

    /** The first YouTube hit for a title: the watch page, which plays. */
    fun youtubePlayUrl(title: String): String = "https://duckduckgo.com/?q=%5Csite%3Ayoutube.com+" + encode(title)

    fun encode(text: String): String = text.trim().replace(Regex("\\s+"), "+")
        .replace("&", "%26").replace("#", "%23").replace("?", "%3F").replace("'", "%27").replace("\"", "%22")

    /** The single-file brief Claude Code is handed for a project. */
    /**
     * A project, and the skill on the Mac that knows its shape when there
     * is one: a portfolio goes to `/portfolio`, whose rules (one file, the
     * sections, the repo wrapper) live in ~/.claude/commands on the Mac.
     */
    fun project(request: String): Route.Project =
        Route.Project(request, if (Regex("""\bportfolio\b""").containsMatchIn(request.lowercase())) "/portfolio" else null)

    /**
     * What follows `/portfolio`: the words minus the asking - "create a
     * portfolio website for arasan" -> "personal, arasan". The skill wants a
     * destination first (reelzo, client, venture, personal); when the words
     * name none, it is personal.
     */
    fun portfolioArgs(request: String): String {
        var s = request.trim().lowercase()
        s = s.replace(Regex("""^(?:please\s+)?(?:can you\s+|could you\s+)?(?:create|make|build|generate|design|write|start|set up)\s+(?:me\s+)?(?:a|an|my|the)?\s*(?:new\s+)?"""), "")
        s = s.replace(Regex("""\bportfolio\s*(?:website|web site|site|page)?\b"""), "").replace(Regex("""^\s*(?:for|about|of)\s+"""), "")
        s = s.replace(Regex("""\s+"""), " ").replace(Regex("""\s+,"""), ",").trim(' ', ',', '.')
        val destination = Regex("""\b(reelzo|client|venture|personal)\b""").containsMatchIn(s)
        return if (s.isBlank()) "personal" else if (destination) s else "personal, $s"
    }

    fun projectBrief(request: String): String =
        "Create a single self-contained index.html for: $request. Put all HTML, CSS and JavaScript in that one file, " +
            "no build step, no external assets except Google Fonts. Make it polished, responsive and finished - " +
            "real copy, not lorem ipsum. Write only that one file."

    /**
     * The plan for a routed request. [text] is what the model wrote for a
     * Write route, or the command for a Terminal route; null when the
     * route needs none.
     */
    /** Machines the user names by nickname, each an ssh host the Mac knows. */
    val REMOTE_HOSTS: Map<String, String> = mapOf("elitedesk" to "elitedesk", "elite desk" to "elitedesk", "elite-desk" to "elitedesk")

    /** Tools that run on the remote machine's own terminal, so the ssh needs a tty. */
    private val REMOTE_TOOLS = Regex("""\b(htop|top|nvidia-smi|df -h|uptime|free -h|docker ps|ls -la|ls)\b""")

    /**
     * "connect to elitedesk and open htop" -> `ssh -t elitedesk htop`;
     * "ssh into the elite desk" -> `ssh elitedesk`. Null when no known
     * machine is named.
     */
    fun remote(s: String, spoken: String): Route.Terminal? {
        val host = REMOTE_HOSTS.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(s) }?.value ?: return null
        val tool = REMOTE_TOOLS.find(s)?.value
        return Route.Terminal(spoken.trim(), if (tool != null) "ssh -t $host $tool" else "ssh $host")
    }

    /**
     * Routes the words settle on their own, with no model in the way: an
     * app or a site by name, a plain search, a key, dictation, a known
     * machine's command, a project for Claude Code (which structures the
     * work itself), help. A Plan, a shell request without its command, a
     * letter to write and a WhatsApp message still go to the model.
     */
    fun isCertain(route: Route): Boolean = when (route) {
        is Route.Open, is Route.Website, is Route.Search, is Route.Key, is Route.Type, is Route.Project, is Route.Help -> true
        is Route.Terminal -> route.command != null
        else -> false
    }

    fun steps(route: Route, text: String? = null): List<PlanStep> = when (route) {
        is Route.Open -> if (!route.newWindow) listOf(PlanStep("OPEN ${route.app}", GeniusPlan.open(route.app))) else listOf(
            PlanStep("OPEN ${route.app}", GeniusPlan.open(route.app)),
            PlanStep("NEW window", listOf(GeniusPlan.chord(if (route.app == "Visual Studio Code") "cmd+shift+n" else "cmd+n")!!, MacOp.Wait(1200))),
        )
        is Route.Search -> listOf(
            PlanStep("OPEN Safari", GeniusPlan.open("Safari")),
            PlanStep("SEARCH ${route.query}", listOf(
                GeniusPlan.chord("cmd+l")!!, MacOp.Wait(500), MacOp.Type("https://duckduckgo.com/?q=" + encode(route.query)), MacOp.Wait(300),
                GeniusPlan.chord("enter")!!, MacOp.Wait(2000),
            )),
        )
        is Route.Type -> listOf(PlanStep("TYPE ${route.text}", listOf(MacOp.Type(route.text))))
        is Route.Key -> listOf(PlanStep("${route.label} (${route.chord})", listOf(GeniusPlan.chord(route.chord)!!, MacOp.Wait(400))))
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
            route.skill?.let { skill ->
                val ask = "$skill ${portfolioArgs(route.request)}"
                PlanStep("ASK $ask", listOf(MacOp.Type(ask), MacOp.Wait(400), GeniusPlan.chord("enter")!!))
            } ?: PlanStep("ASK ${route.request}", listOf(MacOp.Type(projectBrief(route.request)), MacOp.Wait(400), GeniusPlan.chord("enter")!!)),
        )
        is Route.WhatsApp -> listOf(
            PlanStep("OPEN WhatsApp", GeniusPlan.open("WhatsApp")),
            PlanStep("NEW chat to ${route.contact}", listOf(
                GeniusPlan.chord("cmd+n")!!, MacOp.Wait(1500), MacOp.Type(route.contact), MacOp.Wait(2500),
                GeniusPlan.chord("enter")!!, MacOp.Wait(2000),
            )),
            PlanStep("SEND ${(text ?: route.message).take(60)}", listOf(
                MacOp.Type(text ?: route.message), MacOp.Wait(300), GeniusPlan.chord("enter")!!,
            )),
        )
        is Route.Plan -> emptyList()
        is Route.Help -> HELP.map { PlanStep(it, emptyList()) }
    }

    /** What Steve can do, in the words to say. */
    val HELP: List<String> = listOf(
        "\"open safari\" - open an app",
        "\"open apple.com\" / \"search for the iqoo website\" - a website",
        "\"play raavana mavandaa in youtube\" - play a video",
        "\"open the terminal and show the current directory\" - shell work",
        "\"write a love letter to Priya\" / \"write a science fiction story\" - Steve writes, TextEdit gets it",
        "\"create a portfolio website for Priya\" - VS Code, Claude Code, one HTML file",
        "\"text 9442851409 on whatsapp saying hello\" - a WhatsApp message",
    )
}
