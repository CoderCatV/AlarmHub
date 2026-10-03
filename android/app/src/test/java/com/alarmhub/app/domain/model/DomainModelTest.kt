package com.alarmhub.app.domain.model

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M2.1 — the domain objects themselves: the weekday mask, the rule each alarm maps to, and the
 * factory defaults PRD §4.3 fixes.
 */
class DomainModelTest {

    // -----------------------------------------------------------------------------------------
    // WeekdayMask
    // -----------------------------------------------------------------------------------------

    @Test
    fun `weekday mask maps bit zero to monday and bit six to sunday`() {
        assertEquals(listOf(0), WeekdayMask.of(listOf(0)).selectedWeekdays)
        assertEquals(listOf(6), WeekdayMask.of(listOf(6)).selectedWeekdays)
        assertEquals(listOf(0, 1, 2, 3, 4), WeekdayMask.MONDAY_TO_FRIDAY.selectedWeekdays)
        assertEquals((0..6).toList(), WeekdayMask(WeekdayMask.ALL_DAYS).selectedWeekdays)
    }

    @Test
    fun `weekday mask rejects anything that is not seven bits`() {
        assertTrue(runCatching { WeekdayMask(-1) }.isFailure)
        assertTrue(runCatching { WeekdayMask(128) }.isFailure)
        assertTrue(runCatching { WeekdayMask(0) }.isSuccess)
        assertTrue(runCatching { WeekdayMask(WeekdayMask.ALL_DAYS) }.isSuccess)
    }

    @Test
    fun `an empty weekday mask says so`() {
        assertTrue(WeekdayMask(0).isEmpty)
        assertFalse(WeekdayMask.of(listOf(3)).isEmpty)
    }

    @Test
    fun `monday through friday is bits zero to four`() {
        assertEquals(0b0011111, WeekdayMask.MONDAY_TO_FRIDAY.bits)
    }

    // -----------------------------------------------------------------------------------------
    // Alarm -> rule
    // -----------------------------------------------------------------------------------------

    @Test
    fun `each repeat type maps to the matching rule`() {
        assertEquals(AlarmRule.Daily, alarm(RepeatType.DAILY).rule)
        assertEquals(AlarmRule.Workday, alarm(RepeatType.WORKDAY).rule)
        assertEquals(AlarmRule.Holiday, alarm(RepeatType.HOLIDAY).rule)
        assertEquals(AlarmRule.Weekly(WeekdayMask.of(listOf(0, 2))), alarm(RepeatType.WEEKLY, repeatDays = 0b101).rule)
        assertEquals(AlarmRule.Once(java.time.LocalDate.parse("2025-10-15")), alarm(RepeatType.ONCE, onceDate = "2025-10-15").rule)
        assertEquals(AlarmRule.Once(null), alarm(RepeatType.ONCE, onceDate = null).rule)
    }

    @Test
    fun `alarm exposes its time and its schedule`() {
        val a = alarm(RepeatType.DAILY, hour = 7, minute = 30)

        assertEquals(LocalTime.of(7, 30), a.time)
        assertEquals(setOf(AlarmRule.Daily), a.schedule.rules)
        assertEquals(LocalTime.of(7, 30), a.schedule.time)
    }

    @Test
    fun `an alarm rejects a weekday mask that is not seven bits`() {
        assertTrue(runCatching { alarm(RepeatType.WEEKLY, repeatDays = 200) }.isFailure)
    }

    /** PRD §5.3 step 3's 「启用闹钟」: off, expired, and permanently disabled are all excluded. */
    @Test
    fun `only a live alarm contributes ring days`() {
        assertTrue(alarm(RepeatType.DAILY).contributesToRingDays)
        assertFalse(alarm(RepeatType.DAILY, enabled = false).contributesToRingDays)
        assertFalse(alarm(RepeatType.DAILY, expired = true).contributesToRingDays)
        assertFalse(alarm(RepeatType.DAILY, permanentDisabled = true).contributesToRingDays)
    }

    // -----------------------------------------------------------------------------------------
    // Settings factory defaults (PRD §4.3)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `settings factory defaults are the ones the PRD fixes`() {
        val settings = Settings()

        // D6: this default is the reason the project exists.
        assertTrue("defaultDeleteOnceAfterRing ships ON", settings.defaultDeleteOnceAfterRing)
        assertEquals("defaultPauseDays ships at 1", 1, settings.defaultPauseDays)
        assertEquals(10, settings.defaultSnoozeMinutes)
        assertEquals(3, settings.defaultSnoozeMaxCount)
        assertEquals(10, settings.defaultAutoStopMinutes)
        assertEquals(5, settings.defaultFadeInSeconds)
        assertEquals(TimeFormat.H24, settings.timeFormat)
        assertEquals(ThemeMode.SYSTEM, settings.theme)
        assertEquals(VolumeKeyAction.SNOOZE, settings.volumeKeyAction)
        assertFalse(settings.permissionCheckDone)
        assertNull(settings.defaultRingtoneUri)
    }

    @Test
    fun `a pause must skip at least one ring day`() {
        assertTrue(runCatching { Settings(defaultPauseDays = 0) }.isFailure)
    }

    // -----------------------------------------------------------------------------------------
    // Schedule collapsing
    // -----------------------------------------------------------------------------------------

    @Test
    fun `alarms at the same time are merged into one schedule`() {
        val alarms = listOf(
            alarm(RepeatType.DAILY, hour = 7, minute = 0),
            alarm(RepeatType.WORKDAY, hour = 7, minute = 0),
            alarm(RepeatType.DAILY, hour = 8, minute = 0),
        )

        val schedules = AlarmSchedule.of(alarms) { it.schedule }

        assertEquals(2, schedules.size)
        assertEquals(LocalTime.of(7, 0), schedules[0].time)
        assertEquals(setOf(AlarmRule.Daily, AlarmRule.Workday), schedules[0].rules)
        assertEquals(setOf(AlarmRule.Daily), schedules[1].rules)
    }

    @Test
    fun `schedules come back in ring-time order`() {
        val alarms = listOf(
            alarm(RepeatType.DAILY, hour = 13, minute = 30),
            alarm(RepeatType.DAILY, hour = 7, minute = 0),
            alarm(RepeatType.DAILY, hour = 8, minute = 0),
        )

        val times = AlarmSchedule.of(alarms) { it.schedule }.map { it.time }

        assertEquals(listOf(LocalTime.of(7, 0), LocalTime.of(8, 0), LocalTime.of(13, 30)), times)
    }

    @Test
    fun `a schedule without rules is rejected`() {
        assertTrue(runCatching { AlarmSchedule(LocalTime.of(7, 0), emptySet()) }.isFailure)
    }

    private fun alarm(
        repeatType: RepeatType,
        hour: Int = 7,
        minute: Int = 0,
        repeatDays: Int = 0,
        onceDate: String? = null,
        enabled: Boolean = true,
        expired: Boolean = false,
        permanentDisabled: Boolean = false,
    ): Alarm = Alarm(
        id = 1,
        groupId = 1,
        hour = hour,
        minute = minute,
        repeatType = repeatType,
        repeatDays = repeatDays,
        onceDate = onceDate,
        enabled = enabled,
        expired = expired,
        permanentDisabled = permanentDisabled,
    )
}
