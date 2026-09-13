package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusRouterTest {

    @Test
    fun `a plain open goes to the app`() {
        assertEquals(Route.Open("Safari"), GeniusRouter.route("open safari"))
        assertEquals(Route.Open("Visual Studio Code"), GeniusRouter.route("Open VS Code"))
        assertEquals(Route.Open("WhatsApp"), GeniusRouter.route("launch whatsapp"))
        assertEquals(Route.Open("Safari"), GeniusRouter.route("open Safari browser"))
        assertEquals(Route.Open("Google Chrome"), GeniusRouter.route("open chrome browser please"))
    }

    @Test
    fun `websites - a domain, or a search that lands on the site`() {
        val r = GeniusRouter.route("open apple.com") as Route.Website
        assertEquals("https://apple.com", r.url)
        val g = GeniusRouter.route("open the iqoo website") as Route.Website
        assertTrue(g.url.startsWith("https://duckduckgo.com/?q=%5Ciqoo"))
        val steps = GeniusRouter.steps(r)
        assertEquals(listOf("OPEN Safari", "GO TO apple.com"), steps.map { it.line })
    }

    @Test
    fun `the site's name comes out of a whole sentence`() {
        assertEquals("apple", GeniusRouter.webTopic("open safari and search for the apple website and then open the website"))
        assertEquals("iqoo 15", GeniusRouter.webTopic("show me the iqoo 15 website"))
        assertEquals("hackathon schedule", GeniusRouter.webTopic("search for the hackathon schedule"))
        val r = GeniusRouter.route("open safari and search for the apple website and then open it") as Route.Website
        assertEquals("apple", r.query)
    }

    @Test
    fun `youtube plays the first hit for a title`() {
        val r = GeniusRouter.route("play Raavana Mavandaa Lyrical in YouTube") as Route.Website
        assertEquals("https://duckduckgo.com/?q=%5Csite%3Ayoutube.com+raavana+mavandaa+lyrical", r.url)
        val p = GeniusRouter.route("play Vaathi coming") as Route.Website
        assertTrue(p.url.contains("site%3Ayoutube.com+vaathi+coming"))
        val s = GeniusRouter.route("open youtube and play Vaathi coming song") as Route.Website
        assertTrue(s.url.endsWith("vaathi+coming"))
        assertEquals("https://www.youtube.com", (GeniusRouter.route("play a song in youtube") as Route.Website).url)
        assertEquals("https://www.youtube.com", (GeniusRouter.route("open youtube") as Route.Website).url)
    }

    @Test
    fun `terminal work is terminal even when it starts with open`() {
        assertTrue(GeniusRouter.route("open the terminal and show the current directory") is Route.Terminal)
        assertTrue(GeniusRouter.route("connect to the dev server") is Route.Terminal)
        assertEquals("TERMINAL pwd", GeniusRouter.steps(Route.Terminal("x"), "pwd").first().line)
    }

    @Test
    fun `writing goes to TextEdit with the model's text`() {
        assertTrue(GeniusRouter.route("write a love letter to Priya") is Route.Write)
        assertTrue(GeniusRouter.route("write a science fiction story about Mars") is Route.Write)
        val steps = GeniusRouter.steps(Route.Write("x"), "Dear Priya,\nHello.")
        assertEquals(listOf("OPEN TextEdit", "NEW document", "WRITE 18 characters"), steps.map { it.line })
        assertTrue(GeniusRouter.steps(Route.Write("x")).isEmpty())
    }

    @Test
    fun `projects go to a new Terminal window and Claude Code`() {
        assertTrue(GeniusRouter.route("create a portfolio website for Priya") is Route.Project)
        val steps = GeniusRouter.steps(Route.Project("a portfolio website for Priya"))
        assertEquals(listOf("OPEN Terminal", "NEW window", "RUN claude", "ASK a portfolio website for Priya"), steps.map { it.line })
        assertTrue(steps[1].ops.any { it is MacOp.Chord && it.modifiers == GeniusPlan.MOD_CMD })
        assertTrue(steps.last().ops.any { it is MacOp.Type && it.text.contains("new folder named after the project") })
    }

    @Test
    fun `whatsapp with a number and a message`() {
        val r = GeniusRouter.route("open whatsapp and text the number 9442851409 saying hello from xThink") as Route.WhatsApp
        assertEquals("9442851409", r.number)
        assertEquals("hello from xThink", r.message)
        val steps = GeniusRouter.steps(r)
        assertEquals("NEW chat to 9442851409", steps[1].line)
    }

    @Test
    fun `a story is prose, and help lists the abilities`() {
        assertTrue(GeniusRouter.route("write a one page science fiction story with the name of a japanese comic") is Route.Write)
        assertTrue(GeniusRouter.route("write a letter to priya") is Route.Write)
        assertTrue(GeniusRouter.route("write the code for a landing page") is Route.Project)
        assertEquals(Route.Help, GeniusRouter.route("what can you do"))
        assertTrue(GeniusRouter.steps(Route.Help).size == GeniusRouter.HELP.size)
        assertTrue(GeniusRouter.steps(Route.Help).all { it.ops.isEmpty() })
    }

    @Test
    fun `speech-mangled names still open the right app, in a new window when asked`() {
        assertEquals(Route.Open("Visual Studio Code", newWindow = true), GeniusRouter.route("open whistle Studio code new in new new window"))
        assertEquals(Route.Open("Visual Studio Code"), GeniusRouter.route("open vs code"))
        assertEquals(Route.Open("WhatsApp"), GeniusRouter.route("open what's app"))
        val steps = GeniusRouter.steps(Route.Open("Visual Studio Code", newWindow = true))
        assertEquals(listOf("OPEN Visual Studio Code", "NEW window"), steps.map { it.line })
    }

    @Test
    fun `claude code as speech hears it is a project with the brief`() {
        val r = GeniusRouter.route("open Cloud card and bill me a super premium portfolio website") as Route.Project
        assertTrue(r.request.contains("portfolio website"))
        assertTrue(GeniusRouter.route("open claude code and build a landing page for xThink") is Route.Project)
    }

    @Test
    fun `messages go to a contact by name, and never to a code editor`() {
        val r = GeniusRouter.route("send me a message to Hari Prasad") as Route.WhatsApp
        assertEquals("Hari Prasad", r.contact)
        val w = GeniusRouter.route("in WhatsApp send me a message")
        assertTrue(w is Route.WhatsApp && w.contact.isBlank())
        val s = GeniusRouter.route("text Priya on whatsapp saying see you at nine") as Route.WhatsApp
        assertEquals("Priya", s.contact); assertEquals("see you at nine", s.message)
        assertEquals("NEW chat to Hari Prasad", GeniusRouter.steps(r, "hi")[1].line)
    }

    @Test
    fun `keys, typing and plain searches`() {
        assertEquals(Route.Key("cmd+w", "Close the window"), GeniusRouter.route("close the window"))
        assertEquals(Route.Key("cmd+shift+3", "Screenshot"), GeniusRouter.route("take a screenshot"))
        assertEquals(Route.Type("hello there"), GeniusRouter.route("type hello there"))
        val q = GeniusRouter.route("search for the hackathon schedule") as Route.Search
        assertEquals("the hackathon schedule", q.query)
        assertTrue(GeniusRouter.route("open the iqoo website") is Route.Website)
    }

    @Test
    fun `the unknown falls back to the model's plan`() {
        assertTrue(GeniusRouter.route("what time is it in tokyo") is Route.Plan)
    }

    @Test
    fun `a named remote machine is shell work over ssh, with a tty for a screen tool`() {
        assertEquals(Route.Terminal("connect to elitedesk and open htop", "ssh -t elitedesk htop"), GeniusRouter.route("connect to elitedesk and open htop"))
        assertEquals(Route.Terminal("ssh into the elite desk", "ssh elitedesk"), GeniusRouter.route("ssh into the elite desk"))
        assertEquals(Route.Terminal("show me top on elitedesk", "ssh -t elitedesk top"), GeniusRouter.route("show me top on elitedesk"))
        assertTrue(GeniusRouter.route("open htop") !is Route.Terminal || (GeniusRouter.route("open htop") as Route.Terminal).command == null)
    }

    @Test
    fun `a portfolio is the Mac's own skill, with the details as its arguments`() {
        val r = GeniusRouter.route("create a portfolio website for arasan") as Route.Project
        assertEquals("/portfolio", r.skill)
        assertEquals("/portfolio personal, arasan", GeniusRouter.steps(r).last().line.removePrefix("ASK "))
        assertEquals("reelzo, video editing studio", GeniusRouter.portfolioArgs("build the reelzo portfolio site, video editing studio"))
        assertEquals("personal", GeniusRouter.portfolioArgs("make me a portfolio"))
        assertEquals(null, (GeniusRouter.route("build a todo app") as Route.Project).skill)
    }

    @Test
    fun `a job to repeat is heard in the words`() {
        assertTrue(GeniusRouter.isRepeating("run make test until it passes"))
        assertTrue(GeniusRouter.isRepeating("keep checking the build and fix it"))
        assertTrue(GeniusRouter.isRepeating("Run the tests repeatedly"))
        assertTrue(!GeniusRouter.isRepeating("run make test"))
        assertTrue(!GeniusRouter.isRepeating("open safari"))
    }

    @Test
    fun `babysitting claude is its own route`() {
        assertEquals(Route.Monitor, GeniusRouter.route("monitor claude in the terminal and press enter when it asks"))
        assertEquals(Route.Monitor, GeniusRouter.route("keep an eye on cloud and answer its prompts"))
        assertEquals(Route.Monitor, GeniusRouter.route("press enter whenever it asks"))
        assertTrue(GeniusRouter.route("open the terminal") !is Route.Monitor)
    }
}
