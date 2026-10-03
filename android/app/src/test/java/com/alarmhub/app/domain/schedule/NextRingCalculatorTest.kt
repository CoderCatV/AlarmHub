package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.Fixtures
import com.alarmhub.app.domain.Fixtures.ZONE
import com.alarmhub.app.domain.Fixtures.at
import com.alarmhub.app.domain.Fixtures.dailyAlarm
import com.alarmhub.app.domain.Fixtures.group
import com.alarmhub.app.domain.Fixtures.weeklyAlarm
import com.alarmhub.app.domain.Fixtures.workdayAlarm
import com.alarmhub.app.domain.model.WeekdayMask
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.4 — PRD §5.1's next-ring calculation, including the priority order of PRD §5.5 that decides
 * why an alarm has no next ring.
 */
class NextRingCalculatorTest {

    private val calendar = Fixtures.bundledCalendar
    private val calculator = NextRingCalculator(calendar)

    @Test
    fun `daily alarm rings at its time today when that time is still ahead`() {
        val alarm = dailyAlarm(hour = 7, minute = 30)

        assertEquals(at("2025-10-20 07:30:00"), calculator.nextRing(alarm, group(), at("2025-10-20 06:00:00"), ZONE))
    }

    @Test
    fun `daily alarm rolls to tomorrow once today's time has passed`() {
        val alarm = dailyAlarm(hour = 7, minute = 30)

        assertEquals(at("2025-10-21 07:30:00"), calculator.nextRing(alarm, group(), at("2025-10-20 07:30:00"), ZONE))
        assertEquals(at("2025-10-21 07:30:00"), calculator.nextRing(alarm, group(), at("2025-10-20 23:59:59"), ZONE))
    }

    /** An alarm switched off has no next ring, whatever its repeat rule says (PRD §5.1). */
    @Test
    fun `a switched-off alarm has no next ring`() {
        val alarm = dailyAlarm().copy(enabled = false)

        assertNull(calculator.nextRing(alarm, group(), at("2025-10-20 06:00:00"), ZONE))
        assertEquals(AlarmStatus.DISABLED, calculator.statusOf(alarm, group(), at("2025-10-20 06:00:00"), ZONE).status)
    }

    /** …and neither does one whose group is permanently disabled (PRD FR-2.7). */
    @Test
    fun `an alarm in a permanently disabled group has no next ring`() {
        val alarm = dailyAlarm()

        assertNull(calculator.nextRing(alarm, group(permanentDisabled = true), at("2025-10-20 06:00:00"), ZONE))
        assertEquals(AlarmStatus.DISABLED, calculator.statusOf(alarm, group(permanentDisabled = true), at("2025-10-20 06:00:00"), ZONE).status)
    }

    @Test
    fun `a permanently disabled alarm has no next ring`() {
        val alarm = dailyAlarm().copy(permanentDisabled = true)

        assertNull(calculator.nextRing(alarm, group(), at("2025-10-20 06:00:00"), ZONE))
    }

    /** PRD §5.1 condition (c): the alarm's own pause pushes the next ring past `pauseUntil`. */
    @Test
    fun `an alarm pause pushes the next ring past the resume moment`() {
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = at("2025-10-22 12:00:00"))

        assertEquals(
            "07:00 on the 22nd is before the resume moment, so it rings on the 23rd",
            at("2025-10-23 07:00:00"),
            calculator.nextRing(alarm, group(), at("2025-10-20 06:00:00"), ZONE),
        )
    }

    /** …and so does the group's pause, through the same `max()` in PRD §5.1 condition (c). */
    @Test
    fun `a group pause pushes the next ring past the resume moment`() {
        val alarm = dailyAlarm(hour = 7)

        assertEquals(
            at("2025-10-23 07:00:00"),
            calculator.nextRing(alarm, group(pauseUntil = at("2025-10-22 12:00:00")), at("2025-10-20 06:00:00"), ZONE),
        )
    }

    /** When both are paused the later one wins — that is what `max()` in condition (c) means. */
    @Test
    fun `the later of the alarm and group pauses is the one that counts`() {
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = at("2025-10-24 12:00:00"))

        assertEquals(
            at("2025-10-25 07:00:00"),
            calculator.nextRing(alarm, group(pauseUntil = at("2025-10-22 12:00:00")), at("2025-10-20 06:00:00"), ZONE),
        )
    }

    /** PRD §5.1: no match within the scan bound means 永不响铃, reported as such. */
    @Test
    fun `a weekly alarm with no weekday selected never rings`() {
        val alarm = weeklyAlarm(days = WeekdayMask(0).bits)

        assertNull(calculator.nextRing(alarm, group(), at("2025-10-20 06:00:00"), ZONE))
        assertEquals(AlarmStatus.NEVER, calculator.statusOf(alarm, group(), at("2025-10-20 06:00:00"), ZONE).status)
    }

    /** A one-off alarm whose date has gone is 永不响铃 too, not "rings tomorrow". */
    @Test
    fun `a one-off alarm in the past never rings again`() {
        val alarm = workdayAlarm(hour = 7).copy(
            repeatType = com.alarmhub.app.domain.model.RepeatType.ONCE,
            onceDate = "2025-10-01",
        )

        assertNull(calculator.nextRing(alarm, group(), at("2025-10-20 06:00:00"), ZONE))
        assertEquals(AlarmStatus.NEVER, calculator.statusOf(alarm, group(), at("2025-10-20 06:00:00"), ZONE).status)
    }

    /** §5.5 priority: an expired one-off is EXPIRED even if it would otherwise be scheduled. */
    @Test
    fun `expired wins over every other status`() {
        val alarm = dailyAlarm().copy(expired = true, enabled = false, permanentDisabled = true)

        val status = calculator.statusOf(alarm, group(permanentDisabled = true), at("2025-10-20 06:00:00"), ZONE)

        assertEquals(AlarmStatus.EXPIRED, status.status)
        assertNull(status.nextRingAt)
    }

    /** §5.5 priority: paused outranks the plain disabled check for a live alarm. */
    @Test
    fun `a paused alarm reports PAUSED and no next ring while the pause is active`() {
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = at("2025-10-22 00:00:00"))

        val status = calculator.statusOf(alarm, group(), at("2025-10-20 06:00:00"), ZONE)

        assertEquals(AlarmStatus.PAUSED, status.status)
        assertNull(status.nextRingAt)
    }

    /** Once the pause has elapsed the alarm is scheduled again — nobody has to touch a switch. */
    @Test
    fun `an elapsed pause leaves the alarm scheduled`() {
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = at("2025-10-19 00:00:00"))

        val status = calculator.statusOf(alarm, group(), at("2025-10-20 06:00:00"), ZONE)

        assertEquals(AlarmStatus.SCHEDULED, status.status)
        assertEquals(at("2025-10-20 07:00:00"), status.nextRingAt)
    }

    /** A group's next ring takes the earliest alarm that is actually allowed to ring. */
    @Test
    fun `group next ring ignores switched-off and already-past alarms`() {
        val alarms = listOf(
            workdayAlarm(id = 1, hour = 6, minute = 30).copy(enabled = false),
            workdayAlarm(id = 2, hour = 7, minute = 0),
        )

        assertEquals(
            at("2025-10-21 07:00:00"),
            calculator.firstGroupRingAfter(alarms, group(), at("2025-10-20 20:00:00"), ZONE),
        )
    }

    /** WORKDAY/HOLIDAY alarms are routed through the calendar, so 调休 changes the answer. */
    @Test
    fun `workday alarm next ring crosses the national day holiday`() {
        val alarm = workdayAlarm(hour = 7)

        // From the eve of the holiday the next working day is 10-09 (10-11 is the 调休 Saturday).
        assertEquals(at("2025-10-09 07:00:00"), calculator.nextRing(alarm, group(), at("2025-09-30 20:00:00"), ZONE))
        // …and from 10-10 the very next morning is the 调休 Saturday itself.
        assertEquals(at("2025-10-11 07:00:00"), calculator.nextRing(alarm, group(), at("2025-10-10 20:00:00"), ZONE))
    }

    /**
     * A wall-clock time deleted by a daylight-saving spring-forward is resolved by `java.time` into
     * a *different* readable time (01:30 rather than 00:30), and the domain layer must never hand
     * that back as if it were the alarm's time.
     *
     * [ScheduleMath.instantOrNull] is the one place that decides; this pins what actually happens
     * so nobody later "fixes" it into a silent wrong-time ring.
     */
    @Test
    fun `a wall-clock time inside a DST gap is never reported as that time`() {
        val santiago = ZoneId.of("America/Santiago")
        val gapDay = java.time.LocalDate.of(2025, 9, 7)
        val missingTime = java.time.LocalTime.of(0, 30)

        val instant = ScheduleMath.instantOrNull(gapDay, missingTime, santiago)

        if (instant == null) {
            // Acceptable: the day simply produces no ring for this alarm.
        } else {
            assertTrue(
                "a returned instant must not silently read back as a different wall clock",
                ScheduleMath.timeOf(instant, santiago) != missingTime,
            )
            assertEquals(java.time.LocalTime.of(1, 30), ScheduleMath.timeOf(instant, santiago))
        }
    }

    /** Whatever the gap does, a scan across it must terminate and return a usable instant. */
    @Test
    fun `a DST gap does not stop the scan from finding a ring`() {
        val santiago = ZoneId.of("America/Santiago")
        val gapAlarm = dailyAlarm(hour = 12, minute = 0)

        val from = java.time.LocalDateTime.of(2025, 9, 6, 12, 0).atZone(santiago).toInstant().toEpochMilli()
        val next = calculator.nextRing(gapAlarm, group(), from, santiago)

        assertEquals(java.time.LocalTime.of(12, 0), ScheduleMath.timeOf(next!!, santiago))
        assertTrue("the scan must move forward", next > from)
    }

    /** The floor helper is shared with the pause maths; its semantics are `max(now, pauses)`. */
    @Test
    fun `effective floor is the maximum of now and both pauses`() {
        assertEquals(100L, NextRingCalculator.floorOf(null, null, 100L))
        assertEquals(200L, NextRingCalculator.floorOf(200L, null, 100L))
        assertEquals(300L, NextRingCalculator.floorOf(200L, 300L, 100L))
        assertEquals(100L, NextRingCalculator.floorOf(50L, 50L, 100L))
    }
}
