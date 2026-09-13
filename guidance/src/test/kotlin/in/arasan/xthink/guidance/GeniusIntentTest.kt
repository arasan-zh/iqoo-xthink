package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusIntentTest {

    @Test
    fun `the model's line becomes a route`() {
        assertEquals(Route.Open("Safari"), GeniusIntent.parse("OPEN | safari"))
        assertEquals(Route.Open("Visual Studio Code"), GeniusIntent.parse("OPEN | vs code"))
        val w = GeniusIntent.parse("WEBSITE | apple.com") as Route.Website
        assertEquals("https://apple.com", w.url)
        val g = GeniusIntent.parse("WEBSITE | iqoo 15") as Route.Website
        assertTrue(g.url.contains("%5Ciqoo+15"))
        val t = GeniusIntent.parse("TERMINAL | ls -la") as Route.Terminal
        assertEquals("ls -la", t.command)
        assertTrue(GeniusIntent.parse("WRITE | a love letter to Priya") is Route.Write)
        assertTrue(GeniusIntent.parse("PROJECT | a portfolio website for Priya") is Route.Project)
        val wa = GeniusIntent.parse("WHATSAPP | 9442851409 ; hello from xThink") as Route.WhatsApp
        assertEquals("9442851409", wa.number); assertEquals("hello from xThink", wa.message)
    }

    @Test
    fun `prose, OTHER and junk are null so the router decides`() {
        assertNull(GeniusIntent.parse("I think you want to open Safari"))
        assertNull(GeniusIntent.parse("OTHER | what time is it"))
        assertNull(GeniusIntent.parse("OPEN |"))
        assertNull(GeniusIntent.parse("WHATSAPP | say hi"))
    }

    @Test
    fun `an implausible reading loses to the words`() {
        val (r1, fromModel1) = GeniusIntent.decide("OPEN | undefined", "open the terminal and show the current directory")
        assertTrue(r1 is Route.Terminal); assertTrue(!fromModel1)
        val (r2, fromModel2) = GeniusIntent.decide("OPEN | Safari", "open safari browser")
        assertEquals(Route.Open("Safari"), r2); assertTrue(fromModel2)
        val (r3, _) = GeniusIntent.decide("WEBSITE | microsoft", "search for the apple website")
        assertTrue(r3 is Route.Website && r3.query == "apple")
        val (r4, fromModel4) = GeniusIntent.decide("TERMINAL | pwd", "open the terminal and show the current directory")
        assertTrue(r4 is Route.Terminal && r4.command == "pwd"); assertTrue(fromModel4)
        val (r5, fromModel5) = GeniusIntent.decide("WRITE | a love letter to Priya", "write a love letter to Priya")
        assertTrue(r5 is Route.Write); assertTrue(fromModel5)
    }

    @Test
    fun `a story called a project by the model is still a story`() {
        val (r, fromModel) = GeniusIntent.decide("PROJECT | a one page science fiction story", "write a one page science fiction story with the name of a japanese comic")
        assertTrue(r is Route.Write); assertTrue(!fromModel)
        assertEquals(Route.Help, GeniusIntent.parse("HELP | ")) 
    }

    @Test
    fun `the words keep the whole message when the model shortens it`() {
        val (r, fromModel) = GeniusIntent.decide("WHATSAPP | 9442851409 ; hello", "text 9442851409 on whatsapp saying hello from xThink")
        assertTrue(r is Route.WhatsApp && r.message == "hello from xThink"); assertTrue(!fromModel)
    }

    @Test
    fun `the words beat a vaguer model reading`() {
        val (r, fromModel) = GeniusIntent.decide("PROJECT | open whistle Studio code new in new new window", "open whistle Studio code new in new new window")
        assertTrue(r is Route.Open && r.newWindow); assertTrue(!fromModel)
        val (w, _) = GeniusIntent.decide("OTHER | WhatsApp", "in WhatsApp send me a message")
        assertTrue(w is Route.WhatsApp)
        val (m, _) = GeniusIntent.decide("OTHER | Hari Prasad", "send me a message to Hari Prasad")
        assertTrue(m is Route.WhatsApp && m.contact == "Hari Prasad")
    }

    @Test
    fun `a decorated answer still parses`() {
        assertEquals(Route.Open("Terminal"), GeniusIntent.parse("Sure!\n**OPEN | terminal**\n"))
    }

    @Test
    fun `a portfolio from the model is the skill, and a project's brief is the model's to expand`() {
        val (p, fromModel) = GeniusIntent.decide("PORTFOLIO | personal, Priya, video editor", "create a portfolio website for Priya a video editor")
        assertEquals(Route.Project("personal, Priya, video editor", "/portfolio"), p)
        assertTrue(fromModel)
        val (b, _) = GeniusIntent.decide("PROJECT | Build a small to-do app on one page: add, tick off and delete items, saved in the browser; plain HTML, CSS and JavaScript", "build me a to do app")
        assertTrue((b as Route.Project).request.startsWith("Build a small to-do app"))
    }

    @Test
    fun `a misheard app or site is taken from the model, which fixed the hearing`() {
        val (a, fromModel) = GeniusIntent.decide("OPEN | Safari", "open sofa ri")
        assertEquals(Route.Open("Safari"), a)
        assertTrue(fromModel)
        val (w, _) = GeniusIntent.decide("WEBSITE | github.com", "go to get hub")
        assertTrue(w is Route.Website && w.url.contains("github.com"))
        // ...but a site the model only guessed a name for still has to be in the words.
        val (m, fromModelM) = GeniusIntent.decide("WEBSITE | microsoft", "search for the apple website")
        assertTrue(m is Route.Website && m.query == "apple"); assertTrue(!fromModelM)
    }
}
