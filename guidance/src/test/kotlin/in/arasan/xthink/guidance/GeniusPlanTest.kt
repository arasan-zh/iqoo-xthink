package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusPlanTest {

    @Test
    fun `chords parse modifiers and named keys`() {
        val c = GeniusPlan.chord("cmd+space")!!
        assertEquals(GeniusPlan.MOD_CMD, c.modifiers); assertEquals(HidKeymap.SPACE, c.usage)
        val p = GeniusPlan.chord("Cmd+Shift+P")!!
        assertEquals(GeniusPlan.MOD_CMD or GeniusPlan.MOD_SHIFT, p.modifiers); assertEquals(0x04 + ('p' - 'a'), p.usage)
        assertEquals(0x35, GeniusPlan.chord("ctrl+`")!!.usage)
        assertEquals(HidKeymap.ENTER, GeniusPlan.chord("enter")!!.usage)
        assertNull(GeniusPlan.chord("cmd+"))
        assertNull(GeniusPlan.chord("hyper+x"))
    }

    @Test
    fun `OPEN is Spotlight, the name, Enter`() {
        val ops = GeniusPlan.open("Terminal")
        assertTrue(ops[0] is MacOp.Chord && (ops[0] as MacOp.Chord).modifiers == GeniusPlan.MOD_CMD)
        assertTrue(ops.any { it is MacOp.Type && it.text == "Terminal" })
        assertTrue(ops.any { it is MacOp.Chord && it.usage == HidKeymap.ENTER })
    }

    @Test
    fun `a model answer becomes steps, prose ignored`() {
        val answer = """
            Sure, here is the plan:
            OPEN Terminal
            TERMINAL ssh dev@server.local
            - KEY enter
            WAIT 2000
            (that should connect)
            DONE
        """.trimIndent()
        val steps = GeniusPlan.parse(answer)
        assertEquals(listOf("OPEN Terminal", "TERMINAL ssh dev@server.local", "KEY enter", "WAIT 2000", "DONE"), steps.map { it.line })
        assertTrue(steps[1].ops.any { it is MacOp.Type && it.text == "ssh dev@server.local" })
        assertTrue(steps.last().ops.isEmpty())
    }

    @Test
    fun `CLAUDE opens VS Code, its terminal, claude, then the request`() {
        val ops = GeniusPlan.claude("create a portfolio website for Arasan")
        val typed = ops.filterIsInstance<MacOp.Type>().map { it.text }
        assertEquals(listOf("Visual Studio Code", "claude", "create a portfolio website for Arasan"), typed)
        assertTrue(ops.any { it is MacOp.Chord && it.usage == 0x35 && it.modifiers == GeniusPlan.MOD_CTRL })
    }

    @Test
    fun `done is only done when nothing else is asked`() {
        assertTrue(GeniusPlan.isDone("DONE"))
        assertTrue(!GeniusPlan.isDone("TERMINAL ls\nDONE"))
        assertTrue(!GeniusPlan.isDone("I cannot"))
    }

    @Test
    fun `DONE glued to the last line is split off`() {
        val steps = GeniusPlan.parse("OPEN Safari DONE")
        assertEquals(listOf("OPEN Safari", "DONE"), steps.map { it.line })
        assertTrue(steps[0].ops.any { it is MacOp.Type && it.text == "Safari" })
        assertEquals(listOf("TERMINAL ls -la", "DONE"), GeniusPlan.parse("TERMINAL ls -la done.").map { it.line })
    }

    @Test
    fun `waits are bounded and bad lines dropped`() {
        val steps = GeniusPlan.parse("WAIT 999999\nKEY nonsense+key\nTYPE hello")
        assertEquals(2, steps.size)
        assertEquals(15_000L, (steps[0].ops[0] as MacOp.Wait).ms)
    }
}
