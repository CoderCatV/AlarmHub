package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.Fixtures
import com.alarmhub.app.domain.calendar.WeekdayOnlyCalendar
import com.alarmhub.app.domain.model.RepeatType
import com.alarmhub.app.domain.model.WeekdayMask
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.2 — PRD §5.2's five repeat rules, matched against the real bundled holiday data.
 *
 * These are the assertions behind AC-2 (国庆调休的周六照常响铃，法定假日的工作日不响).
 */
class RepeatRuleTest {

    private val calendar = Fixtures.bundledCalendar

    private fun date(iso: String): LocalDate = LocalDate.parse(iso)

    // -----------------------------------------------------------------------------------------
    // WORKDAY / HOLIDAY against the real 2025 国庆 dataset
    // -----------------------------------------------------------------------------------------

    /** AC-2, forward: 2025-10-11 is a 调休 Saturday and therefore a working day. */
    @Test
    fun `the adjusted saturday of the 2025 national day is a working day`() {
        val adjustedSaturday = date("2025-10-11")

        assertEquals(java.time.DayOfWeek.SATURDAY, adjustedSaturday.dayOfWeek)
        assertTrue("调休 makes it a workday", calendar.isAdjustedWorkday(adjustedSaturday))
        assertTrue(calendar.isWorkday(adjustedSaturday))
        assertFalse("and therefore not a day off", calendar.isDayOff(adjustedSaturday))
    }

    /** AC-2, inverse: 2025-10-01 is a statutory holiday even though it is a Wednesday. */
    @Test
    fun `a statutory holiday on a wednesday is not a working day`() {
        val nationalDay = date("2025-10-01")

        assertEquals(java.time.DayOfWeek.WEDNESDAY, nationalDay.dayOfWeek)
        assertTrue("a Wednesday, yet a statutory holiday", calendar.isStatutoryHoliday(nationalDay))
        assertFalse(calendar.isWorkday(nationalDay))
        assertTrue(calendar.isDayOff(nationalDay))
    }

    /** An ordinary weekend belongs to HOLIDAY only; an ordinary workday to WORKDAY only. */
    @Test
    fun `ordinary weekdays and weekends are mutually exclusive`() {
        val ordinarySaturday = date("2025-10-18")
        val ordinaryWednesday = date("2025-10-15")

        assertTrue(calendar.isDayOff(ordinarySaturday))
        assertFalse(calendar.isWorkday(ordinarySaturday))
        assertFalse("an ordinary weekend is not a *statutory* holiday", calendar.isStatutoryHoliday(ordinarySaturday))

        assertTrue(calendar.isWorkday(ordinaryWednesday))
        assertFalse(calendar.isDayOff(ordinaryWednesday))
    }

    /** The whole 国庆 week: the algorithm must agree with the notice on every single day. */
    @Test
    fun `the 2025 national day week matches the published notice day by day`() {
        // 10-01..10-08 off (8 days), 10-09..10-10 work, 10-11 work (调休), 10-12 off.
        val expected = mapOf(
            "2025-09-30" to true,
            "2025-10-01" to false, "2025-10-02" to false, "2025-10-03" to false, "2025-10-04" to false,
            "2025-10-05" to false, "2025-10-06" to false, "2025-10-07" to false, "2025-10-08" to false,
            "2025-10-09" to true,
            "2025-10-10" to true,
            "2025-10-11" to true,
            "2025-10-12" to false,
            "2025-10-13" to true,
        )

        for ((iso, isWorkday) in expected) {
            assertEquals("isWorkday($iso)", isWorkday, calendar.isWorkday(date(iso)))
        }
    }

    // -----------------------------------------------------------------------------------------
    // The five rule types
    // -----------------------------------------------------------------------------------------

    @Test
    fun `DAILY matches every day of the week`() {
        for (day in 13..19) {
            val d = LocalDate.of(2025, 10, day)
            assertTrue("DAILY should match $d", RepeatRule.matches(com.alarmhub.app.domain.model.AlarmRule.Daily, d, calendar))
        }
    }

    @Test
    fun `WEEKLY matches only the selected weekdays`() {
        // bit0 = Monday, bit4 = Friday
        val mondayToFriday = WeekdayMask.MONDAY_TO_FRIDAY
        val rule = com.alarmhub.app.domain.model.AlarmRule.Weekly(mondayToFriday)

        assertTrue(RepeatRule.matches(rule, date("2025-10-20"), calendar)) // Monday
        assertTrue(RepeatRule.matches(rule, date("2025-10-24"), calendar)) // Friday
        assertFalse(RepeatRule.matches(rule, date("2025-10-25"), calendar)) // Saturday
        assertFalse(RepeatRule.matches(rule, date("2025-10-26"), calendar)) // Sunday
    }

    /** A WEEKLY alarm whose mask is empty matches nothing (PRD §5.1's 永不响铃). */
    @Test
    fun `WEEKLY with an empty mask matches nothing`() {
        val rule = com.alarmhub.app.domain.model.AlarmRule.Weekly(WeekdayMask(0))

        for (day in 13..19) {
            assertFalse(RepeatRule.matches(rule, LocalDate.of(2025, 10, day), calendar))
        }
    }

    @Test
    fun `ONCE matches exactly one date and nothing else`() {
        val rule = com.alarmhub.app.domain.model.AlarmRule.once("2025-10-15")

        assertTrue(RepeatRule.matches(rule, date("2025-10-15"), calendar))
        assertFalse(RepeatRule.matches(rule, date("2025-10-14"), calendar))
        assertFalse(RepeatRule.matches(rule, date("2025-10-16"), calendar))
    }

    /** `onceDate = null` means "not resolved yet", which matches nothing rather than guessing. */
    @Test
    fun `ONCE without a date matches nothing`() {
        val rule = com.alarmhub.app.domain.model.AlarmRule.once(null)

        assertFalse(RepeatRule.matches(rule, date("2025-10-15"), calendar))
    }

    @Test
    fun `WORKDAY and HOLIDAY never both match the same date`() {
        var d = LocalDate.of(2025, 9, 20)
        while (d.isBefore(LocalDate.of(2025, 11, 1))) {
            val workday = RepeatRule.matches(com.alarmhub.app.domain.model.AlarmRule.Workday, d, calendar)
            val holiday = RepeatRule.matches(com.alarmhub.app.domain.model.AlarmRule.Holiday, d, calendar)
            assertTrue("every date over Sep-Oct 2025 is one or the other: $d", workday != holiday)
            d = d.plusDays(1)
        }
    }

    /** 未选星期 must surface as a save-time error, not as a row that never fires (PRD §5.1). */
    @Test
    fun `validation rejects a WEEKLY alarm with no weekday selected`() {
        assertNotNull(RepeatRule.validationError(RepeatType.WEEKLY, repeatDays = 0, onceDate = null))
        assertNull(RepeatRule.validationError(RepeatType.WEEKLY, repeatDays = WeekdayMask.MONDAY_TO_FRIDAY.bits, onceDate = null))
        assertNull(RepeatRule.validationError(RepeatType.DAILY, repeatDays = 0, onceDate = null))
        assertNull(RepeatRule.validationError(RepeatType.ONCE, repeatDays = 0, onceDate = null))
    }

    /** A malformed one-off date is rejected rather than parsed into something surprising. */
    @Test
    fun `validation rejects a malformed one-off date`() {
        assertNotNull(RepeatRule.validationError(RepeatType.ONCE, repeatDays = 0, onceDate = "2025/10/15"))
        assertNull(RepeatRule.validationError(RepeatType.ONCE, repeatDays = 0, onceDate = "2025-10-15"))
    }

    /** The weekday mask layout is pinned: bit0 = Monday … bit6 = Sunday (PRD §4.2). */
    @Test
    fun `weekday mask indices run monday zero through sunday six`() {
        assertEquals(0, ScheduleMath.weekdayIndex(date("2025-10-20")))
        assertEquals(1, ScheduleMath.weekdayIndex(date("2025-10-21")))
        assertEquals(4, ScheduleMath.weekdayIndex(date("2025-10-24")))
        assertEquals(5, ScheduleMath.weekdayIndex(date("2025-10-25")))
        assertEquals(6, ScheduleMath.weekdayIndex(date("2025-10-26")))

        assertEquals(listOf(0, 1, 2, 3, 4), WeekdayMask.MONDAY_TO_FRIDAY.selectedWeekdays)
        assertTrue(WeekdayMask.of(listOf(6)).selectedWeekdays == listOf(6))
        assertFalse(WeekdayMask(0).selectedWeekdays.isNotEmpty())
    }

    /** Unknown years fall back to Mon–Fri (PRD FR-6.3), and the fallback says so. */
    @Test
    fun `the weekday-only fallback behaves like a plain monday to friday week`() {
        assertFalse(WeekdayOnlyCalendar.coversYear(2030))
        assertTrue(WeekdayOnlyCalendar.isWorkday(date("2030-10-01"))) // a Tuesday
        assertFalse(WeekdayOnlyCalendar.isWorkday(date("2030-10-05"))) // a Saturday
        assertFalse(WeekdayOnlyCalendar.isStatutoryHoliday(date("2030-10-01")))
    }
}
