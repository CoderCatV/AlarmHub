package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.Fixtures
import com.alarmhub.app.domain.Fixtures.ZONE
import com.alarmhub.app.domain.Fixtures.at
import com.alarmhub.app.domain.Fixtures.dailyAlarm
import com.alarmhub.app.domain.Fixtures.group
import com.alarmhub.app.domain.Fixtures.workdayAlarm
import com.alarmhub.app.domain.model.RepeatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-5 / PRD §5.4 — 响铃时刻二次校验.
 *
 * The point of this suite is the *race*: the trigger was already registered when the pause was
 * applied, so the only thing standing between the user and a 07:00 wake-up they cancelled is this
 * validation.
 */
class RingTimeValidatorTest {

    private val calendar = Fixtures.bundledCalendar

    private fun validate(
        alarm: com.alarmhub.app.domain.model.Alarm?,
        group: com.alarmhub.app.domain.model.Group? = group(),
        triggerAt: Long,
        now: Long = triggerAt,
        ringNow: Boolean = false,
    ) = RingTimeValidator.validate(alarm, group, triggerAt, now, ZONE, calendar, ringNow)

    private fun assertExit(reason: RingExitReason, decision: RingDecision) {
        assertTrue("expected an exit, got $decision", decision is RingDecision.Exit)
        assertEquals(reason, (decision as RingDecision.Exit).reason)
    }

    /** The happy path: nothing changed, so the alarm rings. */
    @Test
    fun `an unchanged alarm rings`() {
        val alarm = dailyAlarm(hour = 7)
        val trigger = at("2025-10-20 07:00:00")

        assertEquals(RingDecision.Ring(trigger), validate(alarm, triggerAt = trigger))
    }

    /** AC-5: the pause landed 1 ms before the trigger — the alarm must stay silent. */
    @Test
    fun `AC-5 - a pause one millisecond before the trigger still silences it`() {
        val trigger = at("2025-10-20 07:00:00")
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = trigger + 1)

        assertExit(RingExitReason.ALARM_PAUSED, validate(alarm, triggerAt = trigger))
    }

    /** …and a pause that expired 1 ms earlier does not silence it. */
    @Test
    fun `a pause that expired one millisecond before the trigger lets it ring`() {
        val trigger = at("2025-10-20 07:00:00")
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = trigger - 1)

        assertEquals(RingDecision.Ring(trigger), validate(alarm, triggerAt = trigger))
    }

    /**
     * Boundary: `pauseUntil == trigger` does not silence the trigger, because PRD §5.4 compares
     * `pauseUntil > now`. Only a pause reaching *past* the trigger does. Both sides of the boundary
     * are pinned here and directly below, so an off-by-one in either direction is a failing test.
     */
    @Test
    fun `a pause ending exactly at the trigger does not silence it`() {
        val trigger = at("2025-10-20 07:00:00")
        val alarm = dailyAlarm(hour = 7).copy(pauseUntil = trigger)

        assertEquals(RingDecision.Ring(trigger), validate(alarm, triggerAt = trigger))
    }

    /** AC-5 again from the other side: one millisecond of pause beyond the trigger is enough. */
    @Test
    fun `one millisecond of pause past the trigger is enough to silence it`() {
        val trigger = at("2025-10-20 07:00:00")

        assertExit(
            RingExitReason.ALARM_PAUSED,
            validate(dailyAlarm(hour = 7).copy(pauseUntil = trigger + 1), triggerAt = trigger),
        )
        assertExit(
            RingExitReason.GROUP_PAUSED,
            validate(dailyAlarm(hour = 7), group(pauseUntil = trigger + 1), triggerAt = trigger),
        )
    }

    /** The same race, but the pause is on the group the alarm belongs to. */
    @Test
    fun `AC-5 - a group pause one millisecond before the trigger silences it`() {
        val trigger = at("2025-10-20 07:00:00")
        val alarm = dailyAlarm(hour = 7)

        assertExit(
            RingExitReason.GROUP_PAUSED,
            validate(alarm, group(pauseUntil = trigger + 1), triggerAt = trigger),
        )
    }

    /** A deleted alarm is the first line of the table — and must not be rescheduled either. */
    @Test
    fun `a deleted alarm exits silently`() {
        assertExit(RingExitReason.ALARM_GONE, validate(null, triggerAt = at("2025-10-20 07:00:00")))
    }

    @Test
    fun `the whole priority table is consulted in order`() {
        val trigger = at("2025-10-20 07:00:00")

        assertExit(RingExitReason.ALARM_DISABLED, validate(dailyAlarm(hour = 7).copy(enabled = false), triggerAt = trigger))
        assertExit(
            RingExitReason.ALARM_PERMANENTLY_DISABLED,
            validate(dailyAlarm(hour = 7).copy(permanentDisabled = true), triggerAt = trigger),
        )
        assertExit(
            RingExitReason.GROUP_PERMANENTLY_DISABLED,
            validate(dailyAlarm(hour = 7), group(permanentDisabled = true), triggerAt = trigger),
        )
    }

    /** A stale registration: the alarm was moved to 08:00 but the old 07:00 intent still fires. */
    @Test
    fun `a trigger for a moment the rule no longer produces exits`() {
        val movedAlarm = dailyAlarm(hour = 8)

        assertExit(RingExitReason.TRIGGER_TIME_CHANGED, validate(movedAlarm, triggerAt = at("2025-10-20 07:00:00")))
        assertEquals(
            RingDecision.Ring(at("2025-10-20 08:00:00")),
            validate(movedAlarm, triggerAt = at("2025-10-20 08:00:00")),
        )
    }

    /**
     * A trigger that lands on a date the rule does not match — e.g. yesterday's intent delivered
     * late by a reboot — must not start ringing.
     */
    @Test
    fun `a trigger on a date the rule does not match exits`() {
        val workdayOnly = workdayAlarm(hour = 7)

        // 2025-10-18 is a Saturday, 2025-10-11 is the 调休 Saturday that *is* a working day.
        assertExit(RingExitReason.TRIGGER_TIME_CHANGED, validate(workdayOnly, triggerAt = at("2025-10-18 07:00:00")))
        assertEquals(
            RingDecision.Ring(at("2025-10-11 07:00:00")),
            validate(workdayOnly, triggerAt = at("2025-10-11 07:00:00")),
        )
    }

    /**
     * A trigger that fires late because the device was off must still ring (PRD FR-4.5.2 says
     * missed alarms are not replayed, but an intent already handed to us by the system is not a
     * replay — refusing it would turn a late alarm into no alarm). This is the case a naive
     * `nextRing(now) == triggerAt` check would get wrong.
     */
    @Test
    fun `a late but genuine trigger still rings`() {
        val alarm = dailyAlarm(hour = 7)
        val trigger = at("2025-10-20 07:00:00")

        assertEquals(RingDecision.Ring(trigger), validate(alarm, triggerAt = trigger, now = at("2025-10-20 09:00:00")))
    }

    /** PRD §5.7: the redundant early trigger must not double-ring once the main one is running. */
    @Test
    fun `a second trigger while ringing is swallowed`() {
        val trigger = at("2025-10-20 07:00:00")
        val alarm = dailyAlarm(hour = 7)

        assertExit(RingExitReason.ALREADY_RINGING, validate(alarm, triggerAt = trigger, ringNow = true))
    }

    /** A one-off alarm must be accepted on its own date and rejected on any other. */
    @Test
    fun `a one-off alarm only accepts its own date`() {
        val alarm = workdayAlarm(hour = 7).copy(repeatType = RepeatType.ONCE, onceDate = "2025-10-20")

        assertEquals(RingDecision.Ring(at("2025-10-20 07:00:00")), validate(alarm, triggerAt = at("2025-10-20 07:00:00")))
        assertExit(RingExitReason.TRIGGER_TIME_CHANGED, validate(alarm, triggerAt = at("2025-10-21 07:00:00")))
    }
}
