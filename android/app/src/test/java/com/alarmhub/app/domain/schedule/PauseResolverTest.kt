package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.Fixtures
import com.alarmhub.app.domain.Fixtures.ZONE
import com.alarmhub.app.domain.Fixtures.at
import com.alarmhub.app.domain.Fixtures.day
import com.alarmhub.app.domain.Fixtures.dailyAlarm
import com.alarmhub.app.domain.Fixtures.dayOffAlarm
import com.alarmhub.app.domain.Fixtures.group
import com.alarmhub.app.domain.Fixtures.schedulesOf
import com.alarmhub.app.domain.Fixtures.weeklyAlarm
import com.alarmhub.app.domain.Fixtures.workdayAlarm
import com.alarmhub.app.domain.Fixtures.workdayGroupAlarms
import com.alarmhub.app.domain.model.RepeatType
import com.alarmhub.app.domain.model.WeekdayMask
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.6 — PRD §5.3's 「跳过 N 个响铃日」 verification table, all four rows, plus AC-1 and the
 * boundaries the PRD and the plan call out: 未选星期 / 跨年 / 闰年 / 暂停期内再次暂停.
 *
 * Every expectation below is written as an explicit date, so a failure reads as "the resume moment
 * is wrong", not as "the numbers moved".
 */
class PauseResolverTest {

    /** 2025-10-20 is a Monday; 2025-10-24 the Friday of the same week (PRD §5.3's table). */
    private val monday = "2025-10-20"
    private val friday = "2025-10-24"

    private val resolver = PauseResolver(Fixtures.bundledCalendar)

    /**
     * PRD §5.3's verification table is written about 「工作日组，每天 07:00」, i.e. a group whose
     * alarms have all already rung by 10:00. Every row of the table is replayed against exactly
     * that, because the table's own verdict — 周一 10:00 with N=1 skips *Tuesday* — depends on it:
     * if the group also had a 13:30 alarm, Monday would still have a ring left and would itself be
     * the skipped day. [afternoonAlarmStillCountsToday] pins that distinction.
     */
    private val workdayGroup = schedulesOf(listOf(workdayAlarm(hour = 7, minute = 0)))

    // -----------------------------------------------------------------------------------------
    // PRD §5.3 effect-verification table — all four rows
    // -----------------------------------------------------------------------------------------

    /** Row 1: 周一 10:00（今早已响过）, N=1 → skip 周二, resume 周三 00:00. */
    @Test
    fun `table row 1 - monday 10am skipping one ring day resumes wednesday midnight`() {
        val result = resolver.preview(workdayGroup, days = 1, now = at("$monday 10:00:00"), zone = ZONE)

        assertEquals(day("2025-10-22"), result.resumeAt)
        assertEquals(listOf(day("2025-10-21")), result.skippedRingDays)
        assertEquals(at("2025-10-22 07:00:00"), result.nextRingAt)
    }

    /** Row 2: 周一 06:00（今早还没响）, N=1 → today is the skipped day, resume 周二 00:00. */
    @Test
    fun `table row 2 - monday 6am skipping one ring day resumes tuesday midnight`() {
        val result = resolver.preview(workdayGroup, days = 1, now = at("$monday 06:00:00"), zone = ZONE)

        assertEquals(day("2025-10-21"), result.resumeAt)
        assertEquals(listOf(day(monday)), result.skippedRingDays)
        assertEquals(at("2025-10-21 07:00:00"), result.nextRingAt)
    }

    /** Row 3: 周一 10:00, N=3 → skip 周二/三/四, resume 周五 00:00. */
    @Test
    fun `table row 3 - monday 10am skipping three ring days resumes friday midnight`() {
        val result = resolver.preview(workdayGroup, days = 3, now = at("$monday 10:00:00"), zone = ZONE)

        assertEquals(day("2025-10-24"), result.resumeAt)
        assertEquals(
            listOf(day("2025-10-21"), day("2025-10-22"), day("2025-10-23")),
            result.skippedRingDays,
        )
        assertEquals(at("2025-10-24 07:00:00"), result.nextRingAt)
    }

    /**
     * Row 4: 周五 20:00, N=1 → skip 下周一, resume 下周二 00:00.
     *
     * This is the row that proves the weekend is not counted. It is also the row that catches the
     * single easiest mistake in this algorithm: applying "time of day > now" to *every* day rather
     * than only to today. Monday's 07:00 is earlier in the wall clock than Friday 20:00, so the
     * wrong reading finds no ring day and reports the resume moment days too late.
     */
    @Test
    fun `table row 4 - friday 8pm skipping one ring day skips the weekend and resumes tuesday midnight`() {
        val result = resolver.preview(workdayGroup, days = 1, now = at("$friday 20:00:00"), zone = ZONE)

        assertEquals(day("2025-10-28"), result.resumeAt)
        assertEquals(listOf(day("2025-10-27")), result.skippedRingDays)
        assertEquals(at("2025-10-28 07:00:00"), result.nextRingAt)
    }

    // -----------------------------------------------------------------------------------------
    // AC-1 restated as one assertion per row, driven through the entity-level entry point
    // -----------------------------------------------------------------------------------------

    /** AC-1: all four PRD §5.3 scenarios, resolved through the real group + alarm entity entry point. */
    @Test
    fun `AC-1 - all four PRD scenarios resolve correctly through the group entry point`() {
        val cases = listOf(
            Triple("$monday 10:00:00", 1, day("2025-10-22")),
            Triple("$monday 06:00:00", 1, day("2025-10-21")),
            Triple("$monday 10:00:00", 3, day("2025-10-24")),
            Triple("$friday 20:00:00", 1, day("2025-10-28")),
        )

        val morningOnly = listOf(workdayAlarm(hour = 7, minute = 0))
        for ((now, days, expectedResumeAt) in cases) {
            val result = resolver.previewForGroup(morningOnly, group(), days, at(now), ZONE)
            assertEquals("resume moment for now=$now N=$days", expectedResumeAt, result.resumeAt)
        }
    }

    /**
     * PRD §5.3 step 3 counts a day as a ring day when *any* enabled alarm in the group is still
     * ahead, so an afternoon alarm makes today itself the skipped day. This is the boundary the
     * table's 「每天 07:00」 wording hides, stated here on purpose.
     */
    @Test
    fun `an alarm later today makes today itself a skipped ring day`() {
        val threeAlarms = schedulesOf(workdayGroupAlarms()) // 07:00, 08:00, 13:30

        val result = resolver.preview(threeAlarms, days = 1, now = at("$monday 10:00:00"), zone = ZONE)

        assertEquals(listOf(day(monday)), result.skippedRingDays)
        assertEquals(day("2025-10-21"), result.resumeAt)
    }

    /** PRD §5.3 step 4: the two values that get written to the database. */
    @Test
    fun `pause state records the absolute resume moment and the moment the pause was applied`() {
        val now = at("$monday 10:00:00")
        val result = resolver.preview(workdayGroup, days = 1, now = now, zone = ZONE)

        val state = resolver.toPauseState(result, now)

        assertEquals(day("2025-10-22"), state.pauseUntil)
        assertEquals(now, state.pausedAt)
    }

    // -----------------------------------------------------------------------------------------
    // Boundaries required by M2.6
    // -----------------------------------------------------------------------------------------

    /** 未选星期: a WEEKLY alarm with an empty mask has no ring day, so there is nothing to skip. */
    @Test
    fun `weekly alarm with no weekday selected has no ring day to skip`() {
        val alarms = listOf(weeklyAlarm(days = WeekdayMask(0).bits))

        val result = resolver.previewForGroup(alarms, group(), days = 1, now = at("$monday 06:00:00"), zone = ZONE)

        assertTrue("nothing can be skipped", result.skippedRingDays.isEmpty())
        assertNull(result.nextRingAt)
        // The scan bound, i.e. "no answer" rather than a plausible-looking nearby date.
        assertEquals(day(LocalDate.parse(monday).plusDays(ScheduleMath.MAX_SCAN_DAYS.toLong()).toString()), result.resumeAt)
    }

    /** 跨年: skipping the last working days of 2025 resumes in 2026. */
    @Test
    fun `pause spanning the new year resolves into the next year`() {
        // 2025-12-29 is a Monday; the 2026 New Year holiday runs Thu 01-01 to Sat 01-03.
        val result = resolver.preview(workdayGroup, days = 3, now = at("2025-12-29 06:00:00"), zone = ZONE)

        assertEquals(
            listOf(day("2025-12-29"), day("2025-12-30"), day("2025-12-31")),
            result.skippedRingDays,
        )
        assertEquals(day("2026-01-01"), result.resumeAt)
        // The first ring after the pause is not 01-02 (holiday), 01-03 (Saturday) — but 01-04 *is*
        // a 调休 working day, so the alarm rings that Sunday. That, plus the year boundary, is
        // exactly what this case is here to pin down.
        assertEquals(at("2026-01-04 07:00:00"), result.nextRingAt)
    }

    /** 闰年: a one-off alarm on 29 February. */
    @Test
    fun `one-off alarm on a leap day is skipped and resumes the following midnight`() {
        val leapDay = workdayAlarm(repeatType = RepeatType.ONCE).copy(onceDate = "2028-02-29")

        val result = resolver.previewForAlarm(leapDay, days = 1, now = at("2028-02-29 06:00:00"), zone = ZONE)

        assertEquals(day("2028-03-01"), result.resumeAt)
        assertEquals(listOf(day("2028-02-29")), result.skippedRingDays)
        // A one-off alarm fires once: after 2028-02-29 there is nothing left to ring, so the pause
        // sheet must say "no next ring" rather than name a day the alarm will never use.
        assertNull(result.nextRingAt)
    }

    /** Leap-day boundaries really are leap-aware (28 → 29 → 1 March). */
    @Test
    fun `leap day arithmetic is correct across february 28 and march 1`() {
        val leapAlarm = workdayAlarm(repeatType = RepeatType.ONCE).copy(onceDate = "2028-02-29")

        assertEquals(
            at("2028-02-29 07:00:00"),
            NextRingCalculator(Fixtures.bundledCalendar)
                .nextRing(leapAlarm, group(), at("2028-02-28 08:00:00"), ZONE),
        )
    }

    /**
     * 暂停期内再次暂停: the scan always restarts from `now`, and the existing `pauseUntil` only
     * raises the floor that decides whether *today* still has a ring left. So a second pause during
     * a pause skips the next N ring days counted from the end of the current one, rather than
     * silently extending it by N more.
     */
    @Test
    fun `pausing again while a pause is active restarts the count from today`() {
        // Paused until Thursday 00:00, then paused again on Monday 10:00 for one more ring day.
        val pausedAgain = workdayAlarm(pauseUntil = day("2025-10-23"))
        val result = resolver.previewForAlarm(pausedAgain, days = 1, now = at("$monday 10:00:00"), zone = ZONE)

        // The existing pause still covers Tue and Wed, so the next ring day is Thursday: it is
        // counted *again* rather than the old pause simply being extended.
        assertEquals("the next ring day after the existing pause is Thursday", listOf(day("2025-10-23")), result.skippedRingDays)
        assertEquals(day("2025-10-24"), result.resumeAt)
        assertEquals(at("2025-10-24 07:00:00"), result.nextRingAt)
    }

    /** Once the pause has expired it no longer raises today's floor. */
    @Test
    fun `an expired pause does not hold back today's ring day`() {
        val expiredPause = workdayAlarm(pauseUntil = day("2025-10-19"))
        val result = resolver.previewForAlarm(expiredPause, days = 1, now = at("$monday 06:00:00"), zone = ZONE)

        assertEquals(day("2025-10-21"), result.resumeAt)
        assertEquals(listOf(day(monday)), result.skippedRingDays)
    }

    /** A single alarm's own pause only counts that alarm's ring days (PRD FR-2.8). */
    @Test
    fun `single alarm pause counts only that alarm's ring days`() {
        // Only the 13:30 alarm exists, and at 14:00 it has already rung today.
        val lateAlarm = workdayAlarm(id = 9, hour = 13, minute = 30)

        val result = resolver.previewForAlarm(lateAlarm, days = 1, now = at("$monday 14:00:00"), zone = ZONE)

        assertEquals(listOf(day("2025-10-21")), result.skippedRingDays)
        assertEquals(day("2025-10-22"), result.resumeAt)
    }

    /** N=7 skips exactly one working week and resumes on the same weekday a week later. */
    @Test
    fun `skipping seven ring days resumes on the same weekday the following week`() {
        val result = resolver.preview(workdayGroup, days = 7, now = at("$monday 06:00:00"), zone = ZONE)

        assertEquals(7, result.skippedRingDays.size)
        assertEquals(listOf(day(monday), day("2025-10-21"), day("2025-10-22"), day("2025-10-23"), day("2025-10-24"), day("2025-10-27"), day("2025-10-28")), result.skippedRingDays)
        assertEquals(day("2025-10-29"), result.resumeAt)
    }

    /** Two alarms at the same clock time are one ring day, not two. */
    @Test
    fun `two alarms at the same time do not each consume a skipped ring day`() {
        val overlapping = listOf(
            workdayAlarm(id = 1, hour = 7, minute = 0),
            workdayAlarm(id = 2, hour = 7, minute = 0),
        )

        val result = resolver.previewForGroup(overlapping, group(), days = 1, now = at("$monday 06:00:00"), zone = ZONE)

        assertEquals(1, result.skippedRingDays.size)
        assertEquals(day(monday), result.skippedRingDays.first())
    }

    /** A DAILY alarm rings every day, including the weekend, so nothing is skipped over it. */
    @Test
    fun `daily alarm makes every day a ring day`() {
        val everyDay = listOf(dailyAlarm(hour = 7))

        val result = resolver.previewForGroup(everyDay, group(), days = 2, now = at("$friday 20:00:00"), zone = ZONE)

        assertEquals(listOf(day("2025-10-25"), day("2025-10-26")), result.skippedRingDays)
        assertEquals(day("2025-10-27"), result.resumeAt)
    }

    // -----------------------------------------------------------------------------------------
    // AC-2 — statutory working days vs days off (PRD §5.2, 国庆调休)
    // -----------------------------------------------------------------------------------------

    /**
     * AC-2: the 工作日 group rings on 2025-10-11 (the 调休 Saturday) and does not ring on
     * 2025-10-01 (a statutory holiday that falls on a Wednesday).
     */
    @Test
    fun `AC-2 - workday group rings on the adjusted saturday and not on the national day holiday`() {
        // 2025-10-10 is the Friday before the 调休 Saturday; 20:00 so today's 07:00 is behind us.
        val result = resolver.preview(workdayGroup, days = 1, now = at("2025-10-10 20:00:00"), zone = ZONE)

        assertEquals("2025-10-11 is the skipped ring day", listOf(day("2025-10-11")), result.skippedRingDays)
        // The pause covers the whole of the 调休 Saturday, so it ends at Sunday's midnight.
        assertEquals("resume at the 调休 Saturday's next midnight", day("2025-10-12"), result.resumeAt)
        // The Sunday is a day off, so the next ring is Monday the 13th — this is what proves the
        // 调休 Saturday really was treated as a working day: it is the only reason a ring was
        // skipped at all this week.
        assertEquals(at("2025-10-13 07:00:00"), result.nextRingAt)

        // ...and on the holiday itself there is no ring at all.
        val onHoliday = NextRingCalculator(Fixtures.bundledCalendar)
            .nextRing(workdayAlarm(), group(), at("2025-10-01 00:00:00"), ZONE)
        assertEquals(at("2025-10-09 07:00:00"), onHoliday)
    }

    /** The 节假日 group is the mirror image: it rings on 2025-10-01 and not on 2025-10-11. */
    @Test
    fun `AC-2 - holiday group rings on the statutory holiday and not on the adjusted saturday`() {
        val calculator = NextRingCalculator(Fixtures.bundledCalendar)
        val holidayAlarm = dayOffAlarm(hour = 8)

        assertEquals(
            at("2025-10-01 08:00:00"),
            calculator.nextRing(holidayAlarm, group(), at("2025-09-30 09:00:00"), ZONE),
        )
        // 2025-10-12 is the Sunday after the 调休 Saturday: a day off, so the alarm rings that
        // morning. Two days later is a Tuesday, so the next day off is the weekend of 10-18/19.
        assertEquals(
            at("2025-10-12 08:00:00"),
            calculator.nextRing(holidayAlarm, group(), at("2025-10-11 09:00:00"), ZONE),
        )
        assertEquals(
            at("2025-10-18 08:00:00"),
            calculator.nextRing(holidayAlarm, group(), at("2025-10-12 09:00:00"), ZONE),
        )
    }

    /** Beyond the bundled years the dataset degrades to Monday–Friday (PRD FR-6.3). */
    @Test
    fun `years without bundled data degrade to monday through friday`() {
        val calendar = Fixtures.bundledCalendar
        assertTrue("2025 is bundled", calendar.coversYear(2025))
        assertTrue("2026 is bundled", calendar.coversYear(2026))
        assertTrue("2027 is not bundled", !calendar.coversYear(2027))

        // 2027-10-01 is a Friday: with holiday data it would be a day off; degraded, it is a工作day.
        assertEquals(true, calendar.isWorkday(LocalDate.parse("2027-10-01")))
        assertEquals(false, calendar.isStatutoryHoliday(LocalDate.parse("2027-10-01")))
    }
}
