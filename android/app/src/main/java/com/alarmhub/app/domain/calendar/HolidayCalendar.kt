package com.alarmhub.app.domain.calendar

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Decides whether a date is a statutory working day or day off (PRD §5.2 / FR-6).
 *
 * Injected everywhere so the schedule maths never reads a file itself and so tests can feed a
 * fixed dataset instead of depending on the real year (docs/TECH-STACK.md §4.8).
 */
interface HolidayCalendar {

    /** True when [date] is named as a day off in a 国务院办公厅 holiday notice. */
    fun isStatutoryHoliday(date: LocalDate): Boolean

    /** True when [date] is named as a 调休 make-up working day (usually a weekend). */
    fun isAdjustedWorkday(date: LocalDate): Boolean

    /** True when data for [year] is present, i.e. this calendar's answer is authoritative. */
    fun coversYear(year: Int): Boolean

    /**
     * PRD §5.2 `WORKDAY`: Monday–Friday and not a holiday, **or** a 调休 working day.
     *
     * Note the `||` order: a make-up working day is checked first so a Saturday that the notice
     * turned into a working day wins over the weekend rule.
     */
    fun isWorkday(date: LocalDate): Boolean =
        isAdjustedWorkday(date) ||
            (date.dayOfWeek != DayOfWeek.SATURDAY &&
                date.dayOfWeek != DayOfWeek.SUNDAY &&
                !isStatutoryHoliday(date))

    /**
     * PRD §5.2 `HOLIDAY`: Saturday/Sunday and not a make-up working day, **or** a statutory holiday.
     *
     * Only days the notice actually names count as holidays; an ordinary weekend is matched by the
     * weekend half of the rule. This is what keeps `WORKDAY` and `HOLIDAY` mutually exclusive.
     */
    fun isDayOff(date: LocalDate): Boolean =
        isStatutoryHoliday(date) ||
            (isWeekend(date) && !isAdjustedWorkday(date))

    private fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
}

/**
 * The bundled holiday dataset: the dates each notice names, plus the 调休 working days it names.
 */
data class Holidays(
    val statutoryHolidays: Set<LocalDate>,
    val adjustedWorkdays: Set<LocalDate>,
    /** Years the dataset covers; any other year is answered in degraded mode. */
    val years: Set<Int>,
) {
    companion object {
        val EMPTY = Holidays(emptySet(), emptySet(), emptySet())
    }
}

/**
 * A calendar backed by [holidays].
 *
 * Years the dataset does not cover degrade to "Monday–Friday is a working day" (PRD FR-6.3)
 * rather than throwing or silently answering from a stale year.
 */
class BundledHolidayCalendar(val holidays: Holidays) : HolidayCalendar {
    override fun isStatutoryHoliday(date: LocalDate): Boolean = date in holidays.statutoryHolidays

    override fun isAdjustedWorkday(date: LocalDate): Boolean = date in holidays.adjustedWorkdays

    override fun coversYear(year: Int): Boolean = year in holidays.years
}

/**
 * The fallback required by PRD FR-6.3: no holiday data at all, so "法定工作日" is plain
 * Monday–Friday and "法定节假日" is the weekend. [coversYear] always answers false, which is how
 * the settings page knows to warn the user.
 */
object WeekdayOnlyCalendar : HolidayCalendar {
    override fun isStatutoryHoliday(date: LocalDate): Boolean = false

    override fun isAdjustedWorkday(date: LocalDate): Boolean = false

    override fun coversYear(year: Int): Boolean = false
}

/**
 * Answers from [primary] for years it covers and from [fallback] otherwise.
 *
 * This is the composition the data layer uses at M3: bundled data for 2025–2026, weekday-only
 * beyond it.
 */
class ChainedHolidayCalendar(
    private val primary: HolidayCalendar,
    private val fallback: HolidayCalendar = WeekdayOnlyCalendar,
) : HolidayCalendar {
    private fun source(date: LocalDate): HolidayCalendar =
        if (primary.coversYear(date.year)) primary else fallback

    override fun isStatutoryHoliday(date: LocalDate): Boolean = source(date).isStatutoryHoliday(date)

    override fun isAdjustedWorkday(date: LocalDate): Boolean = source(date).isAdjustedWorkday(date)

    override fun coversYear(year: Int): Boolean = primary.coversYear(year)
}
