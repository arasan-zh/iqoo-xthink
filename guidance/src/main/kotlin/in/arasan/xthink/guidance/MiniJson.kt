package `in`.arasan.xthink.guidance

/** Thrown when [MiniJson] cannot read the text it was given. */
class JsonException(message: String) : RuntimeException(message)

/**
 * A small recursive-descent JSON reader.
 *
 * :guidance is pure Kotlin, so `org.json` is unavailable and pulling in
 * kotlinx-serialization would mean a compiler plugin plus a runtime dependency
 * for one flat config file. This handles the whole grammar - objects, arrays,
 * strings with escapes, numbers, true/false/null - and nothing else.
 *
 * Numbers come back as [Double], objects as [Map], arrays as [List].
 */
object MiniJson {

    fun parse(source: String): Any? {
        val p = Parser(source)
        val value = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw JsonException("Trailing content at offset ${p.pos}")
        return value
    }

    /** Convenience: parse and require the document to be an object. */
    fun parseObject(source: String): Map<String, Any?> {
        val value = parse(source)
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw JsonException("Expected a JSON object at the top level, got ${describe(value)}")
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> "an object"
        is List<*> -> "an array"
        is String -> "a string"
        is Double -> "a number"
        is Boolean -> "a boolean"
        else -> value::class.simpleName ?: "unknown"
    }

    private class Parser(private val s: String) {

        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= s.length) throw JsonException("Unexpected end of JSON at offset $pos")
            return s[pos]
        }

        private fun expect(c: Char) {
            if (atEnd()) throw JsonException("Expected '$c' but reached the end of the JSON")
            if (s[pos] != c) throw JsonException("Expected '$c' at offset $pos but found '${s[pos]}'")
            pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            return when (val c = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else ->
                    if (c == '-' || c in '0'..'9') parseNumber()
                    else throw JsonException("Unexpected character '$c' at offset $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw JsonException("Expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw JsonException("Expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string at offset $pos")
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> appendEscape(sb)
                    else -> sb.append(c)
                }
            }
        }

        private fun appendEscape(sb: StringBuilder) {
            if (atEnd()) throw JsonException("Unterminated escape at offset $pos")
            when (val e = s[pos++]) {
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                '/' -> sb.append('/')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                'u' -> {
                    if (pos + 4 > s.length) throw JsonException("Truncated unicode escape at offset $pos")
                    val hex = s.substring(pos, pos + 4)
                    val code = hex.toIntOrNull(16)
                        ?: throw JsonException("Bad unicode escape '$hex' at offset $pos")
                    sb.append(code.toChar())
                    pos += 4
                }
                else -> throw JsonException("Bad escape character '$e' at offset ${pos - 1}")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            if (!atEnd() && s[pos] == '-') pos++
            while (!atEnd() && s[pos] in '0'..'9') pos++
            if (!atEnd() && s[pos] == '.') {
                pos++
                while (!atEnd() && s[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                while (!atEnd() && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            return text.toDoubleOrNull() ?: throw JsonException("Bad number '$text' at offset $start")
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!s.startsWith(literal, pos)) throw JsonException("Expected '$literal' at offset $pos")
            pos += literal.length
            return value
        }
    }
}
