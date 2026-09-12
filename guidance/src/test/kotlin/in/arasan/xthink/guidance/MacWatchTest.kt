package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacWatchTest {

    private val terminal = "Last login: Sat Sep 13\narasan@mac ~ %\n"
    private val terminalLater = "Last login: Sat Sep 13\narasan@mac ~ % ls\nDesktop Documents\narasan@mac ~ %\n"
    private val safari = "Safari\nFile Edit View\nApple\nStart Page\n"

    @Test
    fun `a new window is a change, a blink is not`() {
        assertTrue(MacWatch.changed(terminal, safari))
        assertFalse(MacWatch.changed(terminal, terminal))
        assertFalse(MacWatch.changed("", ""))
        assertTrue(MacWatch.changed("", safari))
    }

    @Test
    fun `what a plan types is gathered in order`() {
        val steps = GeniusPlan.parse("OPEN Terminal\nTERMINAL ls -la\nDONE")
        val typed = MacWatch.typed(steps)
        assertTrue(typed != null && typed.contains("Terminal") && typed.contains("ls -la"))
        assertNull(MacWatch.typed(emptyList()))
    }

    @Test
    fun `typed text counts as seen when most of its words are on the screen`() {
        assertEquals(true, MacWatch.inputSeen("ls -la Desktop", "arasan@mac ~ % ls -la\nDesktop Documents"))
        assertEquals(false, MacWatch.inputSeen("hello there Priya", safari))
        assertNull(MacWatch.inputSeen(null, safari))
        assertNull(MacWatch.inputSeen("a b", safari))
    }

    @Test
    fun `the headline is the first readable lines`() {
        assertEquals("Safari  ·  File Edit View", MacWatch.headline(safari))
        assertEquals("", MacWatch.headline(""))
    }
}
