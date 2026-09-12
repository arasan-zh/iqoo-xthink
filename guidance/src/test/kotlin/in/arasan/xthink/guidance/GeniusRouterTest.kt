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
        assertTrue(g.url.startsWith("https://www.google.com/search?btnI=1&q=iqoo"))
        assertTrue(GeniusRouter.route("search for the hackathon schedule") is Route.Website)
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
    fun `projects go to VS Code in a new window and Claude Code`() {
        assertTrue(GeniusRouter.route("create a portfolio website for Priya") is Route.Project)
        val steps = GeniusRouter.steps(Route.Project("a portfolio website for Priya"))
        assertEquals(listOf("OPEN Visual Studio Code", "NEW window", "OPEN terminal", "RUN claude", "ASK a portfolio website for Priya"), steps.map { it.line })
        assertTrue(steps[1].ops.any { it is MacOp.Chord && it.modifiers == (GeniusPlan.MOD_CMD or GeniusPlan.MOD_SHIFT) })
        assertTrue(steps.last().ops.any { it is MacOp.Type && it.text.contains("single self-contained index.html") })
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
    fun `the unknown falls back to the model's plan`() {
        assertTrue(GeniusRouter.route("what time is it in tokyo") is Route.Plan)
    }
}
