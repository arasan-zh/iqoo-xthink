package `in`.arasan.xthink.guidance

/**
 * One keyboard report's worth of a character: the HID usage code and
 * whether Shift is held. US layout - the layout a Mac assumes of an
 * unknown Bluetooth keyboard.
 */
data class HidKey(val usage: Int, val shift: Boolean) {
    /** The 8-byte boot-protocol keyboard report: modifiers, reserved, six keys. */
    fun report(): ByteArray = byteArrayOf(if (shift) MOD_LEFT_SHIFT else 0, 0, usage.toByte(), 0, 0, 0, 0, 0)

    companion object {
        const val MOD_LEFT_SHIFT: Byte = 0x02

        /** All keys up. */
        val RELEASE: ByteArray = ByteArray(8)
    }
}

/**
 * Text to HID keyboard usages, so the phone can type it on a Mac.
 *
 * Pure Kotlin: the Bluetooth plumbing is :app's, the mapping is here
 * under test, because a wrong table types the wrong letters silently.
 * Unmappable characters (anything outside printable ASCII, tab, newline)
 * are dropped rather than guessed.
 */
object HidKeymap {

    const val ENTER = 0x28

    /** The arrows: right, left, down, up - HID usages 0x4F..0x52. */

    const val RIGHT = 0x4F

    const val LEFT = 0x50

    const val DOWN = 0x51

    const val UP = 0x52
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val BACKSPACE = 0x2A
    const val ESCAPE = 0x29

    private val SHIFTED_DIGITS = ")!@#$%^&*("

    /** The key for one character, or null when the keyboard has none. */
    fun keyFor(c: Char): HidKey? = when {
        c in 'a'..'z' -> HidKey(0x04 + (c - 'a'), shift = false)
        c in 'A'..'Z' -> HidKey(0x04 + (c - 'A'), shift = true)
        c == '0' -> HidKey(0x27, shift = false)
        c in '1'..'9' -> HidKey(0x1E + (c - '1'), shift = false)
        c == '\n' -> HidKey(ENTER, shift = false)
        c == '\t' -> HidKey(TAB, shift = false)
        c == ' ' -> HidKey(SPACE, shift = false)
        else -> {
            val i = SHIFTED_DIGITS.indexOf(c)
            if (i == 0) HidKey(0x27, shift = true)
            else if (i > 0) HidKey(0x1E + (i - 1), shift = true)
            else PUNCTUATION[c]
        }
    }

    /** The keys for a string, in order, unmappable characters skipped. */
    fun keysFor(text: String): List<HidKey> = text.mapNotNull { keyFor(it) }

    /** Characters the keyboard cannot type, for telling the user what was dropped. */
    fun unmappable(text: String): String = text.filter { keyFor(it) == null }.toSet().joinToString("")

    private val PUNCTUATION: Map<Char, HidKey> = mapOf(
        '-' to HidKey(0x2D, false), '_' to HidKey(0x2D, true),
        '=' to HidKey(0x2E, false), '+' to HidKey(0x2E, true),
        '[' to HidKey(0x2F, false), '{' to HidKey(0x2F, true),
        ']' to HidKey(0x30, false), '}' to HidKey(0x30, true),
        '\\' to HidKey(0x31, false), '|' to HidKey(0x31, true),
        ';' to HidKey(0x33, false), ':' to HidKey(0x33, true),
        '\'' to HidKey(0x34, false), '"' to HidKey(0x34, true),
        '`' to HidKey(0x35, false), '~' to HidKey(0x35, true),
        ',' to HidKey(0x36, false), '<' to HidKey(0x36, true),
        '.' to HidKey(0x37, false), '>' to HidKey(0x37, true),
        '/' to HidKey(0x38, false), '?' to HidKey(0x38, true),
    )

    /**
     * The HID report descriptor for a boot-protocol keyboard, no report ID:
     * 8 modifier bits, one reserved byte, five LED output bits + padding,
     * six key usages. The one every host already knows how to read.
     */
    val KEYBOARD_DESCRIPTOR: ByteArray = byteArrayOf(
        0x05.toByte(), 0x01.toByte(),       // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06.toByte(),       // Usage (Keyboard)
        0xA1.toByte(), 0x01.toByte(),       // Collection (Application)
        0x05.toByte(), 0x07.toByte(),       //   Usage Page (Key Codes)
        0x19.toByte(), 0xE0.toByte(),       //   Usage Minimum (224)
        0x29.toByte(), 0xE7.toByte(),       //   Usage Maximum (231)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x01.toByte(),       //   Logical Maximum (1)
        0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
        0x95.toByte(), 0x08.toByte(),       //   Report Count (8)
        0x81.toByte(), 0x02.toByte(),       //   Input (Data, Variable, Absolute) - modifiers
        0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
        0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
        0x81.toByte(), 0x01.toByte(),       //   Input (Constant) - reserved
        0x95.toByte(), 0x05.toByte(),       //   Report Count (5)
        0x75.toByte(), 0x01.toByte(),       //   Report Size (1)
        0x05.toByte(), 0x08.toByte(),       //   Usage Page (LEDs)
        0x19.toByte(), 0x01.toByte(),       //   Usage Minimum (1)
        0x29.toByte(), 0x05.toByte(),       //   Usage Maximum (5)
        0x91.toByte(), 0x02.toByte(),       //   Output (Data, Variable, Absolute) - LEDs
        0x95.toByte(), 0x01.toByte(),       //   Report Count (1)
        0x75.toByte(), 0x03.toByte(),       //   Report Size (3)
        0x91.toByte(), 0x01.toByte(),       //   Output (Constant) - padding
        0x95.toByte(), 0x06.toByte(),       //   Report Count (6)
        0x75.toByte(), 0x08.toByte(),       //   Report Size (8)
        0x15.toByte(), 0x00.toByte(),       //   Logical Minimum (0)
        0x25.toByte(), 0x65.toByte(),       //   Logical Maximum (101)
        0x05.toByte(), 0x07.toByte(),       //   Usage Page (Key Codes)
        0x19.toByte(), 0x00.toByte(),       //   Usage Minimum (0)
        0x29.toByte(), 0x65.toByte(),       //   Usage Maximum (101)
        0x81.toByte(), 0x00.toByte(),       //   Input (Data, Array) - keys
        0xC0.toByte(),                      // End Collection
    )
}
