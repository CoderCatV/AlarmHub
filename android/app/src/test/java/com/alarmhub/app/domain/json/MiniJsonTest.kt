package com.alarmhub.app.domain.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2.3 — the hand-written JSON reader the holiday data file depends on. */
class MiniJsonTest {

    private fun MiniJson.Value.obj(): MiniJson.Value.Obj = this as MiniJson.Value.Obj

    private fun MiniJson.Value.arr(): MiniJson.Value.Arr = this as MiniJson.Value.Arr

    private fun MiniJson.Value.str(): String = (this as MiniJson.Value.Str).value

    @Test
    fun `parses the scalar types`() {
        assertEquals(MiniJson.Value.Null, MiniJson.parse("null"))
        assertEquals(MiniJson.Value.Bool(true), MiniJson.parse("true"))
        assertEquals(MiniJson.Value.Bool(false), MiniJson.parse("false"))
        assertEquals(MiniJson.Value.Num(42.0), MiniJson.parse("42"))
        assertEquals(MiniJson.Value.Num(-1.5), MiniJson.parse("-1.5"))
        assertEquals(MiniJson.Value.Num(1000.0), MiniJson.parse("1e3"))
        assertEquals(MiniJson.Value.Str("hi"), MiniJson.parse(""""hi""""))
    }

    @Test
    fun `parses nested objects and arrays and keeps key order`() {
        val value = MiniJson.parse("""{"a":[1,{"b":"c"}],"d":null}""").obj()

        assertEquals(listOf("a", "d"), value.entries.keys.toList())
        val array = value.entries.getValue("a").arr()
        assertEquals(2, array.items.size)
        assertEquals(MiniJson.Value.Num(1.0), array.items[0])
        assertEquals(MiniJson.Value.Str("c"), array.items[1].obj().entries.getValue("b"))
        assertEquals(MiniJson.Value.Null, value.entries.getValue("d"))
    }

    @Test
    fun `parses an empty object and an empty array`() {
        assertTrue(MiniJson.parse("{}").obj().entries.isEmpty())
        assertTrue(MiniJson.parse("[]").arr().items.isEmpty())
        assertTrue(MiniJson.parse("  [ ]  ").arr().items.isEmpty())
    }

    @Test
    fun `handles the standard string escapes including unicode`() {
        assertEquals("a\"b\\c/d\ne\tf", MiniJson.parse(""""a\"b\\c\/d\ne\tf"""").str())
        assertEquals("汉", MiniJson.parse(""""\u6c49"""").str())
        assertEquals("\r\b\u000C", MiniJson.parse(""""\r\b\f"""").str())
    }

    @Test
    fun `ignores whitespace everywhere it is legal`() {
        val value = MiniJson.parse(
            """
            {
              "year" : 2025 ,
              "holiday" : [ "2025-01-01" , "2025-01-02" ]
            }
            """.trimIndent(),
        ).obj()

        assertEquals(MiniJson.Value.Num(2025.0), value.entries.getValue("year"))
        assertEquals(2, value.entries.getValue("holiday").arr().items.size)
    }

    @Test
    fun `rejects malformed input rather than guessing`() {
        val bad = listOf(
            "",
            "{",
            "[1,",
            """{"a":}""",
            """{"a" 1}""",
            """{a:1}""",
            """"unterminated""",
            """tru""",
            """{"a":1} trailing""",
            """[1 2]""",
            """{"a":"\q"}""",
            """{"a":1,}""",
        )

        for (input in bad) {
            val failure = runCatching { MiniJson.parse(input) }.exceptionOrNull()
            assertTrue("'$input' should be rejected, got $failure", failure is MiniJson.JsonException)
        }
    }

    @Test
    fun `reports the offset of the problem`() {
        // '"'=0 'a'=2 'o'=6, and the reader skips the whitespace before reporting, so 6 is the
        // start of the offending token.
        val failure = runCatching { MiniJson.parse("""{"a": oops}""") }
            .exceptionOrNull() as MiniJson.JsonException

        assertEquals(6, failure.offset)
    }

    @Test
    fun `parses the real holiday file shape`() {
        val root = MiniJson.parse(
            """{"_comment":["a"],"years":[{"year":2025,"holiday":[],"workday":[],"rest":[{"name":"元旦","dates":[]}]}]}""",
        ).obj()

        val year = root.entries.getValue("years").arr().items.single().obj()

        assertEquals(2025.0, (year.entries.getValue("year") as MiniJson.Value.Num).value, 0.0)
        assertEquals("元旦", year.entries.getValue("rest").arr().items.single().obj().entries.getValue("name").str())
    }
}
