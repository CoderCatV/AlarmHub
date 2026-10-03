package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.model.AlarmRule
import com.alarmhub.app.domain.model.RepeatType
import com.alarmhub.app.domain.model.WeekdayMask
import java.time.LocalDate

/**
 * The five repeat rules of PRD §5.2, in the one place that decides what a rule means.
 *
 * [matches] is the authoritative implementation; [ScheduleMath.matches] simply forwards here so
 * the schedule maths and the editor's validation can never disagree about, say, whether an empty
 * weekday mask means "never".
 */
object RepeatRule {

    /**
     * PRD §5.2: does [rule] fire on [date]?
     *
     * Note that the WORKDAY/HOLIDAY answers come from the injected [calendar], which is what makes
     * 调休 work: a Saturday can be a working day and a Wednesday can be a day off.
     */
    fun matches(rule: AlarmRule, date: LocalDate, calendar: HolidayCalendar): Boolean =
        ScheduleMath.matches(rule, date, calendar)

    /**
     * The editor's save-time validation (PRD §5.1): a `WEEKLY` alarm with no weekday selected has
     * no next ring, which must surface as an error rather than as a list row that never fires.
     */
    fun validationError(repeatType: RepeatType, repeatDays: Int, onceDate: String?): String? =
        when (repeatType) {
            RepeatType.WEEKLY ->
                if (WeekdayMask(repeatDays).isEmpty) "请至少选择一个重复的星期" else null
            RepeatType.ONCE ->
                if (onceDate != null) {
                    runCatching { LocalDate.parse(onceDate) }.exceptionOrNull()
                        ?.let { "一次性闹钟的日期格式应为 yyyy-MM-dd" }
                } else {
                    null // Resolved to "the next occurrence of this time" on save (PRD §5.2).
                }
            else -> null
        }

    /** Convenience for callers that have the raw mask rather than a built [AlarmRule]. */
    fun weeklyMatches(repeatDays: Int, date: LocalDate): Boolean =
        ScheduleMath.weekdayIndex(date) in WeekdayMask(repeatDays)
}
