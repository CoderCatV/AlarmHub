package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.Group
import java.time.ZoneId

/**
 * PRD §5.4 — 响铃时刻二次校验（可靠性核心）.
 *
 * ```
 * 若 闹钟已不存在（被删）            → 静默退出（不响、不重排）
 * 若 闹钟.enabled = false            → 静默退出
 * 若 闹钟.permanentlyDisabled        → 静默退出
 * 若 闹钟.pauseUntil > now           → 静默退出
 * 若 分组.permanentlyDisabled        → 静默退出
 * 若 分组.pauseUntil > now           → 静默退出
 * 若 计算出的本次响铃时间 != 本次触发时间 → 静默退出（防止旧的重复闹钟被重放）
 * 否则                                → 进入响铃流程
 * ```
 *
 * This runs after `AlarmManager` has already woken the app, so it is the last line of defence
 * against "暂停了却还响" (PRD §5.5, risk R6): cancelling a registration races with the alarm
 * firing, and a pending intent can outlive a reboot or an app update.
 */
object RingTimeValidator {

    /**
     * @param alarm `null` means the row is gone from the database — the first line of the table.
     *   The caller is the only one who can answer that, which is why it is passed in rather than
     *   looked up here.
     * @param triggerAt the instant the system claims this alarm should fire.
     * @param now "now", read at validation time rather than at scheduling time.
     * @param ringNow true while a ring is already on screen. PRD §5.7 registers a redundant trigger
     *   5 seconds early; if it arrives after the main one, the trigger-time check below would catch
     *   it anyway, but this states the intent and skips the work.
     * @param skipTriggerTimeCheck set for a snooze re-ring. The last line of the table asks "is this
     *   the occurrence I registered", and a snooze is deliberately *not* the alarm's next regular
     *   occurrence, so that comparison would reject every snooze. Every other check still applies —
     *   the user may have deleted, switched off or paused the alarm during the snooze.
     */
    fun validate(
        alarm: Alarm?,
        group: Group?,
        triggerAt: Long,
        now: Long,
        zone: ZoneId,
        calendar: HolidayCalendar,
        ringNow: Boolean = false,
        skipTriggerTimeCheck: Boolean = false,
    ): RingDecision {
        if (alarm == null) return RingDecision.Exit(RingExitReason.ALARM_GONE)
        if (ringNow) return RingDecision.Exit(RingExitReason.ALREADY_RINGING)
        if (!alarm.enabled) return RingDecision.Exit(RingExitReason.ALARM_DISABLED)
        if (alarm.permanentDisabled) return RingDecision.Exit(RingExitReason.ALARM_PERMANENTLY_DISABLED)
        if (alarm.pauseUntil?.let { it > now } == true) return RingDecision.Exit(RingExitReason.ALARM_PAUSED)
        if (group?.permanentDisabled == true) return RingDecision.Exit(RingExitReason.GROUP_PERMANENTLY_DISABLED)
        if (group?.pauseUntil?.let { it > now } == true) return RingDecision.Exit(RingExitReason.GROUP_PAUSED)

        if (skipTriggerTimeCheck) return RingDecision.Ring(triggerAt)

        /*
         * The last line of the table: the alarm must still be scheduled for exactly the instant it
         * was woken for. Recomputing instead of trusting the alarm's stored fields is what catches a
         * stale registration — an alarm moved from 07:00 to 08:00 whose old 07:00 pending intent is
         * still alive must not fire.
         *
         * The question asked is "what is the first ring at or after the trigger's own midnight",
         * with **on-or-after** semantics from that day's start. Two consequences, both wanted:
         * a trigger the rule still produces at exactly this instant passes; and a trigger for a
         * moment the rule no longer produces (moved, or an occurrence that was skipped by a pause)
         * comes back as some *other* instant and is rejected.
         */
        val scheduleDayStart = ScheduleMath.dayStart(ScheduleMath.dateOf(triggerAt, zone), zone)
        val expected = NextRingCalculator(calendar).firstRingOnOrAfter(listOf(alarm.schedule), scheduleDayStart, zone)
        if (expected != triggerAt) return RingDecision.Exit(RingExitReason.TRIGGER_TIME_CHANGED)

        return RingDecision.Ring(triggerAt)
    }
}

/** Why a trigger was swallowed, for logging and for tests to assert on the exact branch. */
enum class RingExitReason {
    /** The row no longer exists — silently exit *and* do not reschedule. */
    ALARM_GONE,
    ALARM_DISABLED,
    ALARM_PERMANENTLY_DISABLED,
    ALARM_PAUSED,
    GROUP_PERMANENTLY_DISABLED,
    GROUP_PAUSED,
    /** Recomputing did not land on the trigger instant (stale registration). */
    TRIGGER_TIME_CHANGED,
    /** A ring for this alarm is already running (the redundant trigger of PRD §5.7). */
    ALREADY_RINGING,
}

/** The outcome of PRD §5.4. */
sealed interface RingDecision {
    data class Ring(val triggerAt: Long) : RingDecision
    data class Exit(val reason: RingExitReason) : RingDecision
}
