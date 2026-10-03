package com.alarmhub.app.domain

import com.alarmhub.app.domain.calendar.BundledHolidayCalendar
import com.alarmhub.app.domain.calendar.ChainedHolidayCalendar
import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.calendar.HolidayDataParser
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.AlarmRule
import com.alarmhub.app.domain.model.AlarmSchedule
import com.alarmhub.app.domain.model.Group
import com.alarmhub.app.domain.model.RepeatType
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertNotNull

/**
 * Shared fixtures for the M2 rule tests.
 *
 * Two things this file deliberately does *not* do: it never calls the real clock, and it never
 * reads the device's time zone. Every value a rule depends on is a parameter or a literal — that
 * is what lets PRD §5.3's scenario table be replayed exactly on a plain JVM.
 */
object Fixtures {

    /** PRD §5.3's table is written in Beijing time and the bundled holiday data is Chinese. */
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** `at("2025-10-20 10:00:00")` → epoch millis in [ZONE]. Keeps test dates readable. */
    fun at(dateTime: String): Long =
        LocalDateTime.parse(dateTime, DATE_TIME).atZone(ZONE).toInstant().toEpochMilli()

    /** `day("2025-10-20")` → epoch millis of that day's 00:00 in [ZONE]. */
    fun day(date: String): Long = LocalDate.parse(date).atStartOfDay(ZONE).toInstant().toEpochMilli()

    /**
     * The real bundled holiday dataset, read straight from `app/src/main/assets/holidays.json`.
     *
     * Reading the real file (rather than a copy) is the point: AC-2 asks whether the app rings on
     * 2025-10-11, and that question is only answered by the data that actually ships. If the file
     * cannot be found the test fails loudly instead of quietly using a stub.
     */
    val bundledCalendar: HolidayCalendar by lazy {
        val parsed = HolidayDataParser.parse(assetText("holidays.json"))
        ChainedHolidayCalendar(BundledHolidayCalendar(parsed.holidays))
    }

    /** A group with no pause applied, the state PRD §5.3's table assumes. */
    fun group(
        id: Long = 2,
        name: String = "工作日",
        permanentDisabled: Boolean = false,
        pauseUntil: Long? = null,
        pausedAt: Long? = null,
    ): Group = Group(
        id = id,
        name = name,
        color = 0xff4c9dff.toInt(),
        sortOrder = 1,
        isSystem = true,
        permanentDisabled = permanentDisabled,
        pauseUntil = pauseUntil,
        pausedAt = pausedAt,
        alarmCount = 3,
    )

    /** 工作日 07:00 — the alarm PRD §5.3's verification table is written about. */
    fun workdayAlarm(
        id: Long = 1L,
        hour: Int = 7,
        minute: Int = 0,
        groupId: Long = 2L,
        enabled: Boolean = true,
        expired: Boolean = false,
        permanentDisabled: Boolean = false,
        pauseUntil: Long? = null,
        repeatType: RepeatType = RepeatType.WORKDAY,
    ): Alarm = Alarm(
        id = id,
        groupId = groupId,
        hour = hour,
        minute = minute,
        label = "起床上班",
        repeatType = repeatType,
        enabled = enabled,
        expired = expired,
        permanentDisabled = permanentDisabled,
        pauseUntil = pauseUntil,
    )

    /** The three alarms PRD §1.3 describes for the 工作日 group: 07:00, 08:00 and 13:30. */
    fun workdayGroupAlarms(): List<Alarm> = listOf(
        workdayAlarm(id = 1, hour = 7, minute = 0),
        workdayAlarm(id = 2, hour = 8, minute = 0),
        workdayAlarm(id = 3, hour = 13, minute = 30),
    )

    fun schedulesOf(alarms: List<Alarm>): List<AlarmSchedule> = AlarmSchedule.of(alarms) { it.schedule }

    fun weeklyAlarm(days: Int, hour: Int = 7): Alarm =
        workdayAlarm(repeatType = RepeatType.WEEKLY).copy(repeatDays = days, hour = hour)

    fun dailyAlarm(hour: Int = 7, minute: Int = 0): Alarm =
        workdayAlarm(repeatType = RepeatType.DAILY, hour = hour, minute = minute)

    fun dayOffAlarm(hour: Int = 8): Alarm = workdayAlarm(repeatType = RepeatType.HOLIDAY, hour = hour)

    fun ruleOf(alarm: Alarm): AlarmRule = alarm.rule

    /** [LocalTime] of an epoch instant, for readable assertions. */
    fun timeAt(epochMillis: Long): LocalTime = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZONE).toLocalTime()

    /**
     * Locates `app/src/main/assets/<name>` from the unit-test working directory (the `app` module
     * directory, which is where Gradle places test working directories).
     */
    fun assetFile(name: String): File {
        val candidates = listOf(
            File("src/main/assets/$name"),
            File("app/src/main/assets/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("could not locate assets/$name; looked in $candidates", found)
        return found!!
    }

    /** The bundled data file's contents — the exact bytes that ship in the APK. */
    fun assetText(name: String): String = assetFile(name).readText()
}
