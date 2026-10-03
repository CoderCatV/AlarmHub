package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.model.AlarmRule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The time arithmetic shared by every rule in PRD §5.
 *
 * All public entry points exchange **epoch milliseconds** because that is what the database and
 * the frozen bridge contract store (`pauseUntil`, `nextRingAt`, `resumeAt`). Inside, everything is
 * a [LocalDate] / [LocalTime] in the caller's [ZoneId], because "is this a working day" and "is it
 * past 07:00" are calendar questions, not instant questions.
 */
object ScheduleMath {

    /**
     * Defensive upper bound from PRD §5.1 / §5.3: if nothing matches within this many days the
     * alarm is treated as "never rings" (e.g. `WEEKLY` with no weekday selected).
     */
    const val MAX_SCAN_DAYS = 400

    fun dayStart(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The calendar date [epochMillis] falls on, as seen from [zone]. */
    fun dateOf(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /** The wall-clock time [epochMillis] falls on, as seen from [zone]. */
    fun timeOf(epochMillis: Long, zone: ZoneId): LocalTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalTime()

    fun instantOf(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()

    /**
     * The instant of [time] on [date], or `null` when that wall-clock moment does not exist.
     *
     * It can genuinely not exist: in a zone with DST, a spring-forward transition deletes an hour,
     * and an alarm set inside that hour has no instant that day. Skipping that day is the correct
     * behaviour (the wall clock never shows that time), and returning `null` forces every caller to
     * decide that explicitly instead of silently shifting the ring by an hour.
     */
    fun instantOrNull(date: LocalDate, time: LocalTime, zone: ZoneId): Long? =
        try {
            instantOf(date, time, zone)
        } catch (_: java.time.DateTimeException) {
            null
        }

    /**
     * PRD §5.2: does [date] match [rule]?
     *
     * PRD §5.1's "the point in time must be after now" condition is *not* part of this — it is a
     * separate check so that the same matcher can answer both "which days are ring days"
     * (PRD §5.3, where a whole day can count as skipped) and "which instant rings next".
     */
    fun matches(rule: AlarmRule, date: LocalDate, calendar: HolidayCalendar): Boolean =
        when (rule) {
            AlarmRule.Daily -> true
            is AlarmRule.Weekly -> weekdayIndex(date) in rule.days
            AlarmRule.Workday -> calendar.isWorkday(date)
            AlarmRule.Holiday -> calendar.isDayOff(date)
            is AlarmRule.Once -> rule.date == date
        }

    /**
     * Bit position of [date] in the 7-bit weekday mask: 0 = Monday … 6 = Sunday.
     *
     * This is the mask layout pinned by PRD §4.2 and by `web/src/bridge/types.ts`, so the
     * translation lives in exactly one place.
     */
    fun weekdayIndex(date: LocalDate): Int = date.dayOfWeek.value - 1

    /** The first instant strictly after [after] that combines [rule] and [time], or `null` within the scan bound. */
    fun nextRingAfter(rule: AlarmRule, time: LocalTime, after: Long, zone: ZoneId, calendar: HolidayCalendar): Long? =
        findRing(rule, time, after, zone, calendar, strictlyAfter = true)

    /**
     * The first instant at or after [after] that combines [rule] and [time], or `null` within the
     * scan bound.
     *
     * This is the comparison the pause maths needs. A pause resumes at the *start* of a day
     * (PRD §5.3 step 3), and an alarm ringing at 00:00 on that day must be reported as the next
     * ring — "strictly after" would silently skip it and name the following day instead.
     */
    fun nextRingOnOrAfter(rule: AlarmRule, time: LocalTime, after: Long, zone: ZoneId, calendar: HolidayCalendar): Long? =
        findRing(rule, time, after, zone, calendar, strictlyAfter = false)

    private fun findRing(
        rule: AlarmRule,
        time: LocalTime,
        after: Long,
        zone: ZoneId,
        calendar: HolidayCalendar,
        strictlyAfter: Boolean,
    ): Long? {
        /*
         * The first candidate is `after` itself rather than the next midnight. That is what lets a
         * pause ending at 00:00 find an alarm that rings at 00:00, and it is why the helper below
         * exists instead of a plain `candidate > after`: `after` can be Long.MIN_VALUE in a
         * synthetic call, and `after - 1` would overflow.
         */
        var date = dateOf(after, zone)
        repeat(MAX_SCAN_DAYS) {
            if (matches(rule, date, calendar)) {
                val candidate = instantOrNull(date, time, zone)
                if (candidate != null && after.accepts(candidate, strictlyAfter)) return candidate
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun Long.accepts(candidate: Long, strictlyAfter: Boolean): Boolean =
        if (strictlyAfter) candidate > this else candidate >= this
}
