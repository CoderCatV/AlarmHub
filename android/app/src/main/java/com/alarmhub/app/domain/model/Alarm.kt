package com.alarmhub.app.domain.model

import java.time.LocalTime

/**
 * A group of alarms — the unit that pause operates on (PRD §4.1, FR-1).
 *
 * Domain object, not a Room entity: no annotations, no Android types. The data layer maps to and
 * from this at M3 (docs/TECH-STACK.md §4.1).
 */
data class Group(
    val id: Long,
    val name: String,
    /** ARGB packed into an [Int], e.g. `0xff4c9dff`. */
    val color: Int,
    val sortOrder: Int,
    /** Built-in groups (未分组 / 工作日 / 节假日) cannot be deleted (PRD FR-1.2). */
    val isSystem: Boolean,
    val permanentDisabled: Boolean = false,
    /** Resume instant of a temporary pause, epoch millis. `null` = not paused (PRD §4.1). */
    val pauseUntil: Long? = null,
    /** When the pause was applied, so the UI can say "已暂停 3 小时". */
    val pausedAt: Long? = null,
    val updatedAt: Long = 0L,
    /** Convenience for the delete prompt (PRD FR-1.4); not a stored column on the group itself. */
    val alarmCount: Int = 0,
)

/**
 * A single alarm (PRD §4.2).
 *
 * The repeat rule is stored in the same shape as the database and the frozen JSON contract
 * (`repeatType` + `repeatDays` + `onceDate`) and exposed through [rule] / [schedule], so there is
 * exactly one representation to persist and one to compute with.
 */
data class Alarm(
    val id: Long,
    val groupId: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    val repeatType: RepeatType = RepeatType.ONCE,
    /** 7-bit mask, bit0 = Monday … bit6 = Sunday. Only meaningful for [RepeatType.WEEKLY]. */
    val repeatDays: Int = 0,
    /** `yyyy-MM-dd`, only for [RepeatType.ONCE]. */
    val onceDate: String? = null,
    val ringtoneUri: String? = null,
    val ringtoneName: String? = null,
    val vibrate: Boolean = true,
    val fadeInSeconds: Int = 5,
    val snoozeEnabled: Boolean = true,
    val snoozeMinutes: Int = 10,
    val snoozeMaxCount: Int = 3,
    /** 0 = ring until dismissed (PRD FR-4.3.5). */
    val autoStopMinutes: Int = 10,
    /** Only meaningful for [RepeatType.ONCE] (PRD FR-3.5). */
    val deleteAfterRing: Boolean = false,
    val enabled: Boolean = true,
    val permanentDisabled: Boolean = false,
    val pauseUntil: Long? = null,
    /** Rang already and was kept because `deleteAfterRing` was false (PRD §5.6). */
    val expired: Boolean = false,
    /**
     * Snoozes used during the current ring, capped by [snoozeMaxCount] (PRD FR-4.3.4).
     *
     * Part of the domain model rather than scheduling bookkeeping because it is a rule input: the
     * ring flow compares it against [snoozeMaxCount] to decide whether 贪睡 is still allowed, and
     * M6 will surface it. `lastTriggerAt` by contrast is not here — nothing in the rules reads it.
     */
    val snoozeCount: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    init {
        /*
         * hour/minute **are** checked here, and this replaced a comment that argued the opposite.
         *
         * The old reasoning was: "they arrive as Int from the database and the bridge, and
         * `LocalTime.of` below rejects an out-of-range value loudly". That was wrong in a way a real
         * device proved: a 24-hour/12-hour mix-up in the wheel produced hour = 26, and the loud
         * rejection happened inside `LocalDate.atTime` **after** the value had already travelled
         * through the whole write path — where it surfaced as a `DateTimeException` from a stack
         * frame that said nothing about the wheel, and (for a repeating alarm, whose date is not
         * back-filled) only *after* the row had been inserted.
         *
         * Rejecting at construction means an invalid Alarm cannot exist to be written at all, and
         * the failure names the field. Loud-and-early beats loud-and-late.
         */
        require(hour in 0..23) { "hour must be 0..23, was $hour" }
        require(minute in 0..59) { "minute must be 0..59, was $minute" }
        // The mask *is* checked for the same reason: a bad mask would not throw anywhere, it would
        // silently make a WEEKLY alarm ring on the wrong days.
        require(repeatDays in 0..WeekdayMask.ALL_DAYS) { "repeatDays must be a 7-bit mask, was $repeatDays" }
    }

    val time: LocalTime get() = LocalTime.of(hour, minute)

    /** The repeat rule as the schedule maths sees it (PRD §5.2). */
    val rule: AlarmRule
        get() = when (repeatType) {
            RepeatType.ONCE -> AlarmRule.Once(onceDate?.let(java.time.LocalDate::parse))
            RepeatType.DAILY -> AlarmRule.Daily
            RepeatType.WEEKLY -> AlarmRule.Weekly(WeekdayMask(repeatDays))
            RepeatType.WORKDAY -> AlarmRule.Workday
            RepeatType.HOLIDAY -> AlarmRule.Holiday
        }

    val schedule: AlarmSchedule get() = AlarmSchedule(time, setOf(rule))

    /**
     * True when the user has switched this alarm off, it has expired, or it was permanently
     * disabled — the alarm-level half of "启用" in PRD §5.3 step 3.
     */
    val contributesToRingDays: Boolean get() = enabled && !expired && !permanentDisabled
}

/** Whether a pause applies to a whole group or to a single alarm (PRD FR-2.1 / FR-2.8). */
enum class PauseScope { GROUP, ALARM }
