package com.alarmhub.app.domain.calendar

import com.alarmhub.app.domain.Fixtures
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.3 — the bundled holiday file (`app/src/main/assets/holidays.json`) and the parser that reads it.
 *
 * The data assertions below are deliberately about **the file that ships**, read from
 * `src/main/assets/`, not about a copy in the test sources. That is the only way a test can catch
 * the failure mode that actually matters here: the algorithm being right while the packaged data
 * is wrong or missing (PRD FR-6.2, risk P5).
 */
class HolidayDataParserTest {

    private val parsed by lazy { HolidayDataParser.parse(Fixtures.assetText("holidays.json")) }

    // -----------------------------------------------------------------------------------------
    // The shipped data file
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the bundled file covers the current and next year`() {
        // PRD FR-6.3 asks for exactly this: 当前年份及下一年份.
        assertTrue("2025 must be covered", parsed.holidays.years.contains(2025))
        assertTrue("2026 must be covered", parsed.holidays.years.contains(2026))
    }

    /** AC-2's two dates, asserted against the file rather than through the calendar wrapper. */
    @Test
    fun `the 2025 adjusted workdays are exactly the five the notice names`() {
        val expected = setOf("2025-01-26", "2025-02-08", "2025-04-27", "2025-09-28", "2025-10-11")
            .map(LocalDate::parse)
            .toSet()

        assertEquals(expected, parsed.holidays.adjustedWorkdays.filter { it.year == 2025 }.toSet())
    }

    @Test
    fun `the 2026 adjusted workdays are exactly the six the notice names`() {
        val expected = setOf("2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10")
            .map(LocalDate::parse)
            .toSet()

        assertEquals(expected, parsed.holidays.adjustedWorkdays.filter { it.year == 2026 }.toSet())
    }

    /** Every 调休 working day must really be a weekend day, or the data contradicts itself. */
    @Test
    fun `every adjusted workday really falls on a weekend`() {
        for (date in parsed.holidays.adjustedWorkdays) {
            assertTrue(
                "$date is listed as a 调休 working day but is not a Saturday or Sunday",
                date.dayOfWeek.value >= 6,
            )
        }
    }

    /** No date may be both a holiday and a working day — that would make WORKDAY/HOLIDAY overlap. */
    @Test
    fun `no date is both a holiday and an adjusted workday`() {
        val overlap = parsed.holidays.statutoryHolidays intersect parsed.holidays.adjustedWorkdays

        assertTrue("the data is self-contradictory on $overlap", overlap.isEmpty())
    }

    /** Every year that has data must contain at least one holiday, and every date must be sane. */
    @Test
    fun `the dataset is internally consistent`() {
        val years = parsed.holidays.statutoryHolidays.map { it.year }.toSet()

        assertEquals(parsed.holidays.years, years)
        assertTrue(parsed.periods.isNotEmpty())
        for (period in parsed.periods) {
            assertTrue("holiday period '${period.name}' is empty", period.dates.isNotEmpty())
            assertTrue(
                "holiday period '${period.name}' has a date outside the covered years",
                period.dates.all { it.year in parsed.holidays.years },
            )
        }
    }

    /**
     * The display-only `rest` blocks must describe exactly the statutory holidays of their year —
     * otherwise the settings/about screens would show a holiday the alarm engine ignores.
     */
    @Test
    fun `the display periods cover exactly the statutory holidays of each year`() {
        for (year in parsed.holidays.years) {
            val fromPeriods = parsed.periods.filter { period -> period.dates.all { it.year == year } }
                .flatMap { it.dates }
                .toSet()
            val statutory = parsed.holidays.statutoryHolidays.filter { it.year == year }.toSet()

            assertEquals("display periods for $year", statutory, fromPeriods)
        }
    }

    @Test
    fun `the parser reports where the data came from`() {
        assertTrue(parsed.source.isNotBlank())
        assertTrue("the source should name the notice", parsed.source.contains("国务院办公厅"))
    }

    // -----------------------------------------------------------------------------------------
    // Parser behaviour
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a minimal document parses`() {
        val result = HolidayDataParser.parse(
            """{"years":[{"year":2030,"holiday":["2030-01-01"],"workday":["2030-01-05"]}]}""",
        )

        assertEquals(setOf(2030), result.holidays.years)
        assertEquals(setOf(LocalDate.parse("2030-01-01")), result.holidays.statutoryHolidays)
        assertEquals(setOf(LocalDate.parse("2030-01-05")), result.holidays.adjustedWorkdays)
        assertTrue(result.periods.isEmpty())
        assertEquals(HolidayDataParser.DEFAULT_SOURCE, result.source)
    }

    @Test
    fun `a document without years is rejected`() {
        val failure = runCatching { HolidayDataParser.parse("""{"years":[]}""") }.exceptionOrNull()

        assertNotNull("an empty dataset must not silently degrade", failure)
        assertTrue(failure is HolidayDataParser.HolidayDataException)
    }

    @Test
    fun `a malformed date is rejected with the offending value`() {
        val failure = runCatching {
            HolidayDataParser.parse("""{"years":[{"year":2030,"holiday":["2030-13-01"]}]}""")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue("the message should name the bad date", failure!!.message!!.contains("2030-13-01"))
    }

    @Test
    fun `a missing year field is rejected`() {
        val failure = runCatching {
            HolidayDataParser.parse("""{"years":[{"holiday":["2030-01-01"]}]}""")
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is HolidayDataParser.HolidayDataException)
    }
}
