package com.alarmhub.app.domain.json

/**
 * The smallest JSON reader that can parse `assets/holidays.json`.
 *
 * The domain layer must stay free of Android types (docs/TECH-STACK.md §4.1), so `org.json` —
 * which only exists in the Android SDK and is stubbed out in JVM unit tests — is not an option.
 * The alternative was a new dependency for one flat data file, so this ~120-line reader pays for
 * itself: it is pure Kotlin, fully unit-tested, and keeps the holiday data file's format
 * completely under our control.
 *
 * Supported: objects, arrays, strings (with the standard escapes and `\uXXXX`), numbers, `true`,
 * `false`, `null`. Not supported (and not needed): duplicate-key handling beyond last-wins,
 * non-finite numbers, comments, trailing content.
 */
object MiniJson {

    /** One decoded JSON value. */
    sealed interface Value {
        data class Obj(val entries: Map<String, Value>) : Value
        data class Arr(val items: List<Value>) : Value
        data class Str(val value: String) : Value
        data class Num(val value: Double) : Value
        data class Bool(val value: Boolean) : Value
        data object Null : Value
    }

    /** Thrown for any malformed input, with the offset so a bad data file is easy to locate. */
    class JsonException(message: String, val offset: Int) : Exception("$message (at offset $offset)")

    fun parse(text: String): Value = Reader(text).run {
        val value = readValue()
        skipWhitespace()
        if (!atEnd) fail("unexpected trailing content")
        value
    }

    private class Reader(private val text: String) {
        private var pos = 0

        val atEnd: Boolean get() = pos >= text.length

        fun fail(message: String): Nothing = throw JsonException(message, pos)

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Value {
            skipWhitespace()
            if (atEnd) fail("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> Value.Str(readString())
                't' -> readLiteral("true", Value.Bool(true))
                'f' -> readLiteral("false", Value.Bool(false))
                'n' -> readLiteral("null", Value.Null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("unexpected character '$c'")
            }
        }

        private fun readLiteral(literal: String, value: Value): Value {
            if (!text.startsWith(literal, pos)) fail("expected '$literal'")
            pos += literal.length
            return value
        }

        private fun readObject(): Value.Obj {
            expect('{')
            val entries = LinkedHashMap<String, Value>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return Value.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                entries[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return Value.Obj(entries)
                    }
                    else -> fail("expected ',' or '}' in object")
                }
            }
        }

        private fun readArray(): Value.Arr {
            expect('[')
            val items = ArrayList<Value>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return Value.Arr(items)
            }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return Value.Arr(items)
                    }
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd) fail("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> sb.append(readEscape())
                    else -> sb.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd) fail("unterminated escape")
            return when (val c = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (pos + 4 > text.length) fail("truncated \\u escape")
                    val hex = text.substring(pos, pos + 4)
                    pos += 4
                    hex.toIntOrNull(16)?.toChar() ?: fail("bad \\u escape '$hex'")
                }
                else -> fail("unknown escape '\\$c'")
            }
        }

        private fun readNumber(): Value.Num {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd && text[pos].isDigit()) pos++
            if (!atEnd && text[pos] == '.') {
                pos++
                while (!atEnd && text[pos].isDigit()) pos++
            }
            if (!atEnd && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd && (text[pos] == '+' || text[pos] == '-')) pos++
                while (!atEnd && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            val number = raw.toDoubleOrNull() ?: fail("bad number '$raw'")
            return Value.Num(number)
        }

        private fun peek(): Char {
            if (atEnd) fail("unexpected end of input")
            return text[pos]
        }

        private fun expect(c: Char) {
            if (atEnd || text[pos] != c) fail("expected '$c'")
            pos++
        }
    }
}
