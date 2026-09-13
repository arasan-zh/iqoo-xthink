package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudePromptTest {

    @Test
    fun `the trust dialog opens on No - the arrow walks to Yes before Enter`() {
        val trust = "Security guide\n> No, exit\nYes, I trust this folder\n\nEnter to confirm · Esc to cancel"
        assertEquals(listOf("KEY down", "KEY enter"), ClaudePrompt.answer(trust))
        assertEquals("Yes, I trust this folder", ClaudePrompt.line(trust))
        // Yes first and the cursor on it: Enter alone.
        assertEquals(listOf("KEY enter"), ClaudePrompt.answer("Do you want to proceed?\n❯ 1. Yes\n  2. No"))
        // Yes first, the cursor read on No below it: up, then Enter.
        assertEquals(listOf("KEY up", "KEY enter"), ClaudePrompt.answer("Do you want to proceed?\n  1. Yes\n> 2. No, and tell Claude what to do differently"))
        assertEquals(listOf("KEY enter"), ClaudePrompt.answer("Bash(make test)\nAllow this tool for this session?"))
    }

    @Test
    fun `claude at its input box is ready to be asked`() {
        assertEquals(true, ClaudePrompt.readyForInput("Welcome to Claude Code\n> \n? for shortcuts"))
        assertEquals(true, ClaudePrompt.readyForInput("Try \"fix the failing test\""))
        assertEquals(false, ClaudePrompt.readyForInput("Security guide\n> No, exit\nYes, I trust this folder"))
        assertEquals(false, ClaudePrompt.readyForInput("arasan@mac ~ %"))
    }

    @Test
    fun `a y-slash-n wants the letter first`() {
        assertEquals(listOf("TYPE y", "KEY enter"), ClaudePrompt.answer("Overwrite index.html? (y/n)"))
        assertEquals(listOf("TYPE y", "KEY enter"), ClaudePrompt.answer("Continue [Y/n]"))
    }

    @Test
    fun `working, or a plain shell prompt, is no prompt`() {
        assertNull(ClaudePrompt.answer("Thinking…\nReading files"))
        assertNull(ClaudePrompt.answer("arasan@mac ~ %"))
        assertNull(ClaudePrompt.answer(""))
    }

    @Test
    fun `the same prompt reads the same, whatever the noise around it`() {
        val a = ClaudePrompt.key("stuff\nYes, I trust this folder\nEnter to confirm")
        val b = ClaudePrompt.key("other lines\n Yes,  I trust this folder \nEnter to confirm · Esc")
        assertEquals(a, b)
        assertEquals("yesitrustthisfolder", a)
    }
}
