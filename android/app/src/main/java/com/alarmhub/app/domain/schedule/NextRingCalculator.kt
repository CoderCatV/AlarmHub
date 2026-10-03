package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.AlarmSchedule
import com.alarmhub.app.domain.model.Group
import java.time.ZoneId

/**
 * PRD §5.1 — when will this alarm ring next?
 *
 * ```
 * 若 闹钟.enabled = false            → 无下次响铃
 * 若 闹钟.permanentlyDisabled        → 无下次响铃
 * 若 分组.permanentlyDisabled        → 无下次响铃
 * 否则，从「今天」开始向后逐日检查，返回第一个满足以下全部条件的 (日期, 时分)：
 *     a) 该日期匹配重复规则
 *     b) 该日期时间点 > 当前时间
 *     c) 该时间点 > max(闹钟.pauseUntil, 分组.pauseUntil)
 * 若 366 天内无匹配 → 「永不响铃」
 * ```
 *
 * The PRD's 366-day bound is implemented as [ScheduleMath.MAX_SCAN_DAYS] (400), matching the
 * defence-in-depth limit PRD §5.3 already specifies for the pause maths. Both are far beyond any
 * real repeat rule, so the only thing that can ever reach them is a rule that matches no day.
 *
 * Every entry point takes `now` and `zone` explicitly — the domain layer never reads the clock or
 * the default time zone itself (docs/DEVELOPMENT-PLAN.md, M2 关键约束).
 */
class NextRingCalculator(private val calendar: HolidayCalendar) {

    /**
     * The next ring instant strictly after [now], or `null` when this alarm will not ring.
     *
     * [schedules] exists so the caller can hand in the alarm's own schedule when it is ringing
     * alone, or a group-level schedule when the question is "when does this group next ring".
     */
    fun nextRing(
        alarm: Alarm,
        group: Group?,
        now: Long,
        zone: ZoneId,
        schedules: List<AlarmSchedule> = listOf(alarm.schedule),
    ): Long? {
        if (!alarm.enabled || alarm.permanentDisabled || group?.permanentDisabled == true) return null
        return firstRingAfter(schedules, floorOf(alarm.pauseUntil, group?.pauseUntil, now), zone)
    }

    /** The earliest ring instant strictly after [after], or `null` if none of [schedules] ever rings. */
    fun firstRingAfter(schedules: List<AlarmSchedule>, after: Long, zone: ZoneId): Long? =
        earliest(schedules, after, zone, strictlyAfter = true)

    /**
     * The earliest ring instant at or after [after].
     *
     * Used wherever "after" is itself a ring moment: the resume instant of a pause (PRD §5.3, whose
     * midnight is exactly when an alarm at 00:00 rings again) and the trigger-time check of
     * PRD §5.4.
     */
    fun firstRingOnOrAfter(schedules: List<AlarmSchedule>, after: Long, zone: ZoneId): Long? =
        earliest(schedules, after, zone, strictlyAfter = false)

    private fun earliest(schedules: List<AlarmSchedule>, after: Long, zone: ZoneId, strictlyAfter: Boolean): Long? =
        schedules.asSequence()
            .mapNotNull { schedule ->
                schedule.rules.asSequence()
                    .mapNotNull { rule ->
                        if (strictlyAfter) {
                            ScheduleMath.nextRingAfter(rule, schedule.time, after, zone, calendar)
                        } else {
                            ScheduleMath.nextRingOnOrAfter(rule, schedule.time, after, zone, calendar)
                        }
                    }
                    .minOrNull()
            }
            .minOrNull()

    /**
     * The instant a whole group next rings, taking each alarm's own switch and pause into account.
     *
     * Alarms that are switched off, expired or permanently disabled are dropped before collapsing,
     * which is the "启用闹钟" filter PRD §5.3 step 3 also uses.
     */
    fun firstGroupRingAfter(alarms: List<Alarm>, group: Group?, now: Long, zone: ZoneId): Long? =
        firstRingAfter(
            schedules = AlarmSchedule.of(alarms.filter { it.contributesToRingDays }) { it.schedule },
            after = floorOf(null, group?.pauseUntil, now),
            zone = zone,
        )

    /**
     * The status a list row renders, together with the instant it displays.
     *
     * The order follows PRD §5.5 (highest priority first). A `null` [NextRingStatus.nextRingAt]
     * always means "nothing is scheduled", and [NextRingStatus.status] says why.
     */
    fun statusOf(alarm: Alarm, group: Group?, now: Long, zone: ZoneId): NextRingStatus {
        if (alarm.expired) return NextRingStatus(AlarmStatus.EXPIRED, null)
        if (alarm.permanentDisabled || group?.permanentDisabled == true) {
            return NextRingStatus(AlarmStatus.DISABLED, null)
        }
        if (alarm.pauseUntil?.let { it > now } == true) return NextRingStatus(AlarmStatus.PAUSED, null)
        if (!alarm.enabled) return NextRingStatus(AlarmStatus.DISABLED, null)

        val next = nextRing(alarm, group, now, zone)
        return if (next != null) {
            NextRingStatus(AlarmStatus.SCHEDULED, next)
        } else {
            NextRingStatus(AlarmStatus.NEVER, null)
        }
    }

    companion object {
        /**
         * Condition (c) of PRD §5.1, reused by PRD §5.3: the later of the alarm's and its group's
         * pause, floored by "now". One helper, so the rule is never re-derived at a call site.
         */
        fun floorOf(alarmPauseUntil: Long?, groupPauseUntil: Long?, now: Long): Long =
            maxOf(now, alarmPauseUntil ?: Long.MIN_VALUE, groupPauseUntil ?: Long.MIN_VALUE)
    }
}

/** Local stand-in for the contract's `AlarmStatus` (`web/src/bridge/types.ts`). */
enum class AlarmStatus {
    /** Enabled and has a next ring time. */
    SCHEDULED,

    /** Temporarily paused; `pauseUntil` is in the future. */
    PAUSED,

    /** Switched off, or permanently disabled at alarm or group level. */
    DISABLED,

    /** A one-off alarm that already rang and was kept because `deleteAfterRing` was false. */
    EXPIRED,

    /** Enabled, but its repeat rule matches no day (e.g. WEEKLY with no weekday set). */
    NEVER,
}

/** [status] plus the instant the row should display, if any. */
data class NextRingStatus(val status: AlarmStatus, val nextRingAt: Long?)
