package com.alarmhub.app.ring

import com.alarmhub.app.data.AlarmWithGroup
import com.alarmhub.app.domain.model.Settings
import com.alarmhub.app.domain.model.TimeFormat
import com.alarmhub.app.ring.media.AlarmSound
import java.time.Instant
import java.time.ZoneId

/**
 * Everything the ring needs, captured at the moment the trigger is validated.
 *
 * Deliberately a self-contained snapshot rather than "read the database from the ring page": the
 * user can edit or delete the alarm while it is ringing, and the page must keep showing what it is
 * ringing *for* rather than becoming blank mid-ring.
 */
data class RingRequest(
    val alarmId: Long,
    val label: String,
    val groupName: String,
    /** ARGB, for the group chip on the ring page. */
    val groupColor: Int,
    val hour: Int,
    val minute: Int,
    val timeFormat: TimeFormat,
    val sound: AlarmSound,
    /**
     * Whether 贪睡 is in play **for this ring** — already the merge of both switches, never just the
     * alarm's own flag.
     *
     * There are two switches (the alarm's `snoozeEnabled` and the global `settings.snoozeEnabled`), and
     * this field is the single place they are combined — see [Companion.from]. Every consumer
     * (`canSnooze`, the ring page's status line, the notification's hint) reads this one value, so
     * there is no consumer left that can forget one of the two. That is not hypothetical: the
     * notification used to read only the alarm's flag and announced 「贪睡 10 分钟（还剩 3 次）」 while
     * the global switch was off and the button was gone.
     */
    val snoozeEnabled: Boolean,
    val snoozeMinutes: Int,
    val snoozeMaxCount: Int,
    /** Snoozes already used for this ring (PRD FR-4.3.4). */
    val snoozeUsed: Int,
    /** 0 = ring until dismissed (PRD FR-4.3.5). */
    val autoStopMinutes: Int,
    /** Only meaningful for a one-off alarm (PRD FR-3.6). */
    val deleteAfterRing: Boolean,
    /**
     * True when this ring is a repeat of a snooze rather than a fresh trigger.
     *
     * The ring page says so ("贪睡中"), and it is why the snooze counter is not reset.
     */
    val isSnooze: Boolean = false,
) {
    val displayedLabel: String get() = label.ifBlank { "闹钟" }

    val snoozesRemaining: Int get() = (snoozeMaxCount - snoozeUsed).coerceAtLeast(0)

    /** Snooze is offered only when the alarm allows it and the allowance is not used up. */
    val canSnooze: Boolean get() = snoozeEnabled && snoozesRemaining > 0

    /**
     * True when this alarm was set up with snooze in play at all (`snoozeMaxCount > 0`).
     *
     * This exists so a *ringing notification* can tell the two ways snooze can be unavailable apart:
     *
     * * `!canSnooze` with `snoozeConfigured` → the allowance is used up → 「已无贪睡次数」 says why;
     * * `!snoozeEnabled` → 贪睡 is off, globally or for this alarm → 「贪睡已关闭」, because there is no
     *   allowance to talk about.
     *
     * Without it, merging the two switches in [Companion.from] converted one bug into another: the
     * notification answered 「已无贪睡次数」 on an alarm whose allowance had never been touched. It lives
     * here rather than in the notification because that is the whole rule of this change — decide once,
     * next to the data, so no consumer has to re-derive it.
     */
    val snoozeConfigured: Boolean get() = snoozeMaxCount > 0

    /** `07:05` or `7:05` depending on the user's 12/24-hour preference. */
    fun clockText(): String = formatClock(hour, minute, timeFormat)

    companion object {
        fun formatClock(hour: Int, minute: Int, timeFormat: TimeFormat): String =
            when (timeFormat) {
                TimeFormat.H24 -> "%02d:%02d".format(hour, minute)
                TimeFormat.H12 -> {
                    val suffix = if (hour < 12) "AM" else "PM"
                    val h12 = when (val h = hour % 12) {
                        0 -> 12
                        else -> h
                    }
                    "%d:%02d %s".format(h12, minute, suffix)
                }
            }

        /** The date line on the ring page, in the device's own calendar. */
        fun dateText(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
            val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val weekday = when (date.dayOfWeek.value) {
                1 -> "周一"
                2 -> "周二"
                3 -> "周三"
                4 -> "周四"
                5 -> "周五"
                6 -> "周六"
                else -> "周日"
            }
            return "%d月%d日 %s".format(date.monthValue, date.dayOfMonth, weekday)
        }

        /**
         * Builds the snapshot from the stored alarm plus the current settings.
         *
         * [snoozeUsed] is passed in rather than read from the row so the caller decides whether this
         * is a fresh ring (counter zero) or a repeat (counter carried over).
         *
         * **The two 贪睡 switches are merged here, and only here.** The alarm has its own
         * `snoozeEnabled` and the settings have a global one; this constructor is the single point
         * where they meet, so no consumer downstream has to remember both. Doing it the other way
         * round — each consumer checking both — is what produced the bug this merge fixes: the
         * notification's snooze hint read the alarm's flag alone, so with the global switch off the
         * shade still said 「贪睡 N 分钟（还剩 M 次）」 for a feature the user had turned off, while the
         * ring page's button was correctly gone. A rule that has to be repeated in every consumer will
         * eventually be missed by one of them.
         */
        fun from(
            row: AlarmWithGroup,
            settings: Settings,
            snoozeUsed: Int,
            isSnooze: Boolean,
            alarmSound: AlarmSound,
        ): RingRequest {
            val alarm = row.alarm
            return RingRequest(
                alarmId = alarm.id,
                label = alarm.label,
                groupName = row.group?.name ?: "未分组",
                groupColor = row.group?.color ?: 0xff8b97a8.toInt(),
                hour = alarm.hour,
                minute = alarm.minute,
                timeFormat = settings.timeFormat,
                sound = alarmSound,
                snoozeEnabled = alarm.snoozeEnabled && settings.snoozeEnabled,
                snoozeMinutes = alarm.snoozeMinutes,
                snoozeMaxCount = alarm.snoozeMaxCount,
                snoozeUsed = snoozeUsed,
                autoStopMinutes = alarm.autoStopMinutes,
                deleteAfterRing = alarm.deleteAfterRing,
                isSnooze = isSnooze,
            )
        }
    }
}

/**
 * Why a ring ended. Every ending path must name one of these, because the two outcomes that matter
 * (delete the one-off, or mark it expired) depend on it and "the ring just stopped" is not enough
 * information to choose correctly.
 */
enum class RingEndReason {
    /** The user pressed 关闭. */
    DISMISSED,

    /** PRD FR-4.3.5: `autoStopMinutes` elapsed. */
    TIMED_OUT,

    /** PRD FR-4.3.4: 贪睡 was used and the alarm has been re-armed. */
    SNOOZED,

    /**
     * The alarm was deleted or switched off from elsewhere while ringing.
     *
     * Distinct from DISMISSED because it must not mark anything expired or delete anything: the row
     * is already gone or already off, and the user did not ask for anything to happen to it.
     */
    CANCELLED,
}
