package com.alarmhub.app.domain.model

import java.time.LocalDate

/**
 * What one alarm does, in the only form the schedule maths needs (PRD §5.2).
 *
 * This is deliberately independent of the `Alarm` entity: the same rules drive both real alarms
 * and the "what would be skipped if I paused this group" preview, and the schedule code should
 * not have to care which of the two it is looking at.
 */
sealed interface AlarmRule {

    /** Matches every day. */
    data object Daily : AlarmRule

    /** Matches the given weekdays only; an empty mask matches nothing. */
    data class Weekly(val days: WeekdayMask) : AlarmRule

    /** Matches statutory working days, including 调休 weekends (PRD §5.2). */
    data object Workday : AlarmRule

    /** Matches statutory days off. */
    data object Holiday : AlarmRule

    /**
     * Matches exactly [date], or nothing when [date] is null.
     *
     * PRD §5.2 says an `ONCE` alarm with no date yet resolves to "the next date at this time" and
     * writes it back on save. Until that write happens it matches nothing, which is the honest
     * answer — inventing a date inside the matcher would make the preview and the saved alarm
     * disagree.
     */
    data class Once(val date: LocalDate?) : AlarmRule

    companion object {
        /** [date] as `yyyy-MM-dd`, the format the contract and the database column use. */
        fun once(date: String?): Once = Once(date?.let(LocalDate::parse))
    }
}
