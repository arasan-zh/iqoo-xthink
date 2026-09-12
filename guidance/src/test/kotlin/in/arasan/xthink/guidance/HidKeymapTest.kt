package `in`.arasan.xthink.guidance

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HidKeymapTest {

    @Test
    fun `letters map to the HID alphabet, capitals with shift`() {
        assertEquals(HidKey(0x04, false), HidKeymap.keyFor('a'))
        assertEquals(HidKey(0x1D, false), HidKeymap.keyFor('z'))
        assertEquals(HidKey(0x04, true), HidKeymap.keyFor('A'))
        assertEquals(HidKey(0x1D, true), HidKeymap.keyFor('Z'))
    }

    @Test
    fun `digits - 1 through 9 then 0, and their shifted symbols`() {
        assertEquals(HidKey(0x1E, false), HidKeymap.keyFor('1'))
        assertEquals(HidKey(0x26, false), HidKeymap.keyFor('9'))
        assertEquals(HidKey(0x27, false), HidKeymap.keyFor('0'))
        assertEquals(HidKey(0x1E, true), HidKeymap.keyFor('!'))
        assertEquals(HidKey(0x1F, true), HidKeymap.keyFor('@'))
        assertEquals(HidKey(0x27, true), HidKeymap.keyFor(')'))
        assertEquals(HidKey(0x26, true), HidKeymap.keyFor('('))
    }

    @Test
    fun `whitespace and punctuation`() {
        assertEquals(HidKey(HidKeymap.ENTER, false), HidKeymap.keyFor('\n'))
        assertEquals(HidKey(HidKeymap.TAB, false), HidKeymap.keyFor('\t'))
        assertEquals(HidKey(HidKeymap.SPACE, false), HidKeymap.keyFor(' '))
        assertEquals(HidKey(0x2D, false), HidKeymap.keyFor('-'))
        assertEquals(HidKey(0x2D, true), HidKeymap.keyFor('_'))
        assertEquals(HidKey(0x34, true), HidKeymap.keyFor('"'))
        assertEquals(HidKey(0x38, true), HidKeymap.keyFor('?'))
        assertEquals(HidKey(0x31, false), HidKeymap.keyFor('\\'))
    }

    @Test
    fun `characters the keyboard has no key for are dropped, and reported`() {
        assertNull(HidKeymap.keyFor('é'))
        assertNull(HidKeymap.keyFor('→'))
        assertEquals(4, HidKeymap.keysFor("héllo").size)
        assertEquals("é→", HidKeymap.unmappable("héllo →"))
    }

    @Test
    fun `a report is 8 bytes with the modifier first and the usage third`() {
        val r = HidKey(0x04, true).report()
        assertEquals(8, r.size)
        assertEquals(HidKey.MOD_LEFT_SHIFT, r[0])
        assertEquals(0x04.toByte(), r[2])
        assertArrayEquals(ByteArray(8), HidKey.RELEASE)
    }

    @Test
    fun `the descriptor is a well-formed keyboard collection`() {
        val d = HidKeymap.KEYBOARD_DESCRIPTOR
        assertEquals(0x05.toByte(), d[0]); assertEquals(0x01.toByte(), d[1])
        assertEquals(0x09.toByte(), d[2]); assertEquals(0x06.toByte(), d[3])
        assertEquals(0xC0.toByte(), d.last())
        assertTrue(d.size > 50)
    }

    @Test
    fun `a whole command types in order`() {
        val keys = HidKeymap.keysFor("ls -la\n")
        assertEquals(listOf(0x0F, 0x16, 0x2C, 0x2D, 0x0F, 0x04, 0x28), keys.map { it.usage })
        assertTrue(keys.none { it.shift })
    }
}
