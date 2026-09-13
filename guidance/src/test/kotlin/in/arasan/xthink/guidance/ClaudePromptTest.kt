package `in`.arasan.xthink.guidance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudePromptTest {

    @Test
    fun `the trust prompt, the confirm, the permission - Enter`() {
        val trust = "Security guide\n> No, exit\nYes, I trust this folder\n\nEnter to confirm · Esc to cancel"
        assertEquals(listOf("KEY enter"), ClaudePrompt.answer(trust))
        assertEquals("Yes, I trust this folder", ClaudePrompt.line(trust))
        assertEquals(listOf("KEY enter"), ClaudePrompt.answer("Do you want to proceed?\n❯ 1. Yes\n  2. No"))
        assertEquals(listOf("KEY enter"), ClaudePrompt.answer("Bash(make test)\nAllow this tool for this session?"))
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
