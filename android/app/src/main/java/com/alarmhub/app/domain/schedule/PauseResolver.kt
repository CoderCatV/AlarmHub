package com.alarmhub.app.domain.schedule

import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.AlarmSchedule
import com.alarmhub.app.domain.model.Group
import java.time.LocalDate
import java.time.ZoneId

/**
 * PRD §5.3 — 「跳过 N 个响铃日」换算成绝对恢复时刻（核心算法）.
 *
 * ```
 * 输入：分组（或单个闹钟）的生效重复规则、N（默认 1）、当前时间 now
 * 1. cursor = now 所在日期
 * 2. count = 0
 * 3. 循环：
 *      days = cursor 当天，该分组内所有启用闹钟中匹配 §5.2 且「时分 > now」的
 *             （仅 cursor == today 时需要这个时分判断）
 *      若 days 非空 → count += 1
 *      若 count == N → 恢复时刻 = cursor + 1 天的 00:00:00.000，结束
 *      cursor += 1 天
 *      （最多向后扫 400 天，防御性上限）
 * 4. 落库：pauseUntil = 恢复时刻，pausedAt = now
 * ```
 *
 * The result is stored as an absolute instant, so at run time pausing is nothing but
 * `now < pauseUntil` (PRD §5.3, 关键设计取舍). [preview] performs the same conversion without
 * persisting anything, which is what keeps the front end from ever re-implementing this
 * (the contract's `previewPause`, docs/DEVELOPMENT-PLAN.md §4).
 */
class PauseResolver(
    private val calendar: HolidayCalendar,
    private val nextRingCalculator: NextRingCalculator = NextRingCalculator(calendar),
) {

    /**
     * PRD §5.3 steps 1–3: the resume instant, the ring days being skipped, and the first ring after.
     *
     * @param schedules the enabled alarms the pause applies to — every alarm of the group
     *   (PRD FR-2.1) or just the one alarm (PRD FR-2.8).
     * @param days N, how many ring days to skip. Must be at least 1.
     * @param now the instant the pause is applied at; normally "now", injectable for tests and for
     *   "what would this do if applied at that moment".
     * @param zone the calendar the user is looking at.
     * @param currentPauseUntil an existing pause, if any. It only raises the floor that decides
     *   whether *today* still counts as a ring day; the scan always starts from `now`, so pausing
     *   again inside a pause simply restarts the count from today.
     */
    fun preview(
        schedules: List<AlarmSchedule>,
        days: Int,
        now: Long,
        zone: ZoneId,
        currentPauseUntil: Long? = null,
    ): PauseResolution {
        require(days >= 1) { "a pause must skip at least one ring day, was $days" }

        val today = ScheduleMath.dateOf(now, zone)
        val floor = NextRingCalculator.floorOf(currentPauseUntil, null, now)
        var cursor = today
        val skipped = ArrayList<Long>()

        /*
         * Step 3, verbatim: "days = cursor 当天，该分组内所有启用闹钟中匹配 §5.2 且「时分 > now」的
         * （仅 cursor == today 时需要这个时分判断）".
         *
         * The day-by-day walk below starts at today and advances one day at a time, counting a day
         * as a ring day when `ringsOn` says some enabled alarm still rings on it after `floor`.
         * See `ringsOn` for why `floor` (and not a "only today" special case) is what decides the
         * time-of-day comparison.
         */
        var scanned = 0
        while (scanned < ScheduleMath.MAX_SCAN_DAYS) {
            val rings = ringsOn(schedules, cursor, floor, zone)
            if (rings) {
                skipped += ScheduleMath.dayStart(cursor, zone)
                if (skipped.size == days) {
                    /*
                     * Step 3's exit condition: the skipped day's next midnight.
                     *
                     * The first ring after the pause is asked for with **on-or-after** semantics,
                     * because resumeAt is itself a ring moment: an alarm at 00:00 must ring then,
                     * not be skipped over to the following day.
                     */
                    val resumeAt = ScheduleMath.dayStart(cursor.plusDays(1), zone)
                    return PauseResolution(
                        resumeAt = resumeAt,
                        skippedRingDays = skipped,
                        nextRingAt = nextRingCalculator.firstRingOnOrAfter(schedules, resumeAt, zone),
                    )
                }
            }
            cursor = cursor.plusDays(1)
            scanned++
        }

        // Nothing in this schedule ever rings (e.g. every alarm is WEEKLY with an empty mask).
        // There is no correct resume instant, so hand back the scan bound; the caller reports the
        // group as having no ring days instead of being told a plausible-looking nearby date.
        val resumeAt = ScheduleMath.dayStart(cursor, zone)
        return PauseResolution(
            resumeAt = resumeAt,
            skippedRingDays = skipped,
            nextRingAt = nextRingCalculator.firstRingOnOrAfter(schedules, resumeAt, zone),
        )
    }

    /** PRD §5.3 step 4 — the two values that get written to the database. */
    fun toPauseState(resolution: PauseResolution, now: Long): PauseState =
        PauseState(pauseUntil = resolution.resumeAt, pausedAt = now)

    /** Convenience overload: resolve a pause for one alarm (PRD FR-2.8). */
    fun previewForAlarm(alarm: Alarm, days: Int, now: Long, zone: ZoneId): PauseResolution =
        preview(listOf(alarm.schedule), days, now, zone, alarm.pauseUntil)

    /**
     * Convenience overload: resolve a pause for a whole group (PRD FR-2.1), using the group's own
     * pause as the floor. Inactive alarms are dropped, matching PRD §5.3's 「所有启用闹钟」.
     */
    fun previewForGroup(alarms: List<Alarm>, group: Group?, days: Int, now: Long, zone: ZoneId): PauseResolution =
        preview(
            schedules = AlarmSchedule.of(alarms.filter { it.contributesToRingDays }) { it.schedule },
            days = days,
            now = now,
            zone = zone,
            currentPauseUntil = group?.pauseUntil,
        )

    /**
     * Does at least one alarm ring on [date], given that nothing at or before [floor] counts?
     *
     * [floor] is `max(now, existing pauseUntil)`. It applies to **every** day, not just today, and
     * that is a deliberate reading of PRD §5.3 step 3's parenthetical (「仅 cursor == today 时需要
     * 这个时分判断」):
     *
     *  - For an unpaused group, `floor` is `now`, which is already past midnight on every later day,
     *    so the time comparison only ever bites on today — exactly what the parenthetical says.
     *  - For a group that is *already* paused, an alarm at 07:00 must not be counted as a ring day
     *    on a date that the existing pause still covers. Without this, pausing again during a pause
     *    would count Tue and Wed (which are paused!) as the ring days it just skipped, and the new
     *    resume moment would land two days early instead of one ring day after the old one —
     *    i.e. the alarm would start ringing while the user still believes it is paused.
     */
    private fun ringsOn(schedules: List<AlarmSchedule>, date: LocalDate, floor: Long, zone: ZoneId): Boolean =
        schedules.any { schedule ->
            schedule.rules.any { ScheduleMath.matches(it, date, calendar) } &&
                (ScheduleMath.instantOrNull(date, schedule.time, zone) ?: Long.MIN_VALUE) > floor
        }
}

/** The outcome of a PRD §5.3 conversion. Line-for-line the contract's `PausePreview`. */
data class PauseResolution(
    /** Absolute resume instant: the skipped ring day's next midnight (PRD §5.3 step 3). */
    val resumeAt: Long,
    /** Midnight of each ring day being skipped, so the sheet can list them. */
    val skippedRingDays: List<Long>,
    /** First ring after the pause ends. `null` when nothing follows. */
    val nextRingAt: Long?,
)

/** PRD §5.3 step 4: what is persisted when the user confirms. */
data class PauseState(val pauseUntil: Long, val pausedAt: Long)
