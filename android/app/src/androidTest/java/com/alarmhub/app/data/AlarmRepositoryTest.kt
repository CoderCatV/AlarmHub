package com.alarmhub.app.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alarmhub.app.data.db.AppDatabase
import com.alarmhub.app.data.db.GroupEntity
import com.alarmhub.app.domain.TimeSource
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.RepeatType
import com.alarmhub.app.domain.model.ThemeMode
import com.alarmhub.app.domain.model.TimeFormat
import com.alarmhub.app.domain.model.VolumeKeyAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M3 data-layer acceptance, run on a device.
 *
 * These cannot be JVM unit tests: Room's generated DAOs, its SQLite driver, foreign-key enforcement
 * and the single-row `settings` table are all real Android behaviour. Robolectric was deliberately
 * not added — it would be a new test-only dependency plus a second, divergent SQLite, for tests adb
 * can already run in a couple of seconds (see docs/M3-STATUS.md).
 *
 * ```
 * adb shell am instrument -w -e class com.alarmhub.app.data.AlarmRepositoryTest \
 *     com.alarmhub.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class AlarmRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AlarmRepository

    /** A pinned clock, so the timestamps the repository writes are deterministic in assertions. */
    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            // Room does not enforce foreign keys unless asked, so the RESTRICT constraint that
            // PRD FR-1.4's behaviour depends on would otherwise silently not apply.
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .allowMainThreadQueries()
            .build()
        repository = AlarmRepository(database, TimeSource.fixed(fixedNow))
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -----------------------------------------------------------------------------------------
    // M3.2 seed data
    // -----------------------------------------------------------------------------------------

    @Test
    fun seedingCreatesTheThreeSystemGroupsAndDefaultSettings() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)

        val groups = repository.groups()
        assertEquals(3, groups.size)
        assertEquals(listOf("未分组", "工作日", "节假日"), groups.map { it.name })
        assertEquals(listOf(0, 1, 2), groups.map { it.sortOrder })
        assertTrue("all three built-ins are system groups", groups.all { it.isSystem })
        assertEquals(BuiltInGroups.UNGROUPED_ID, groups.first().id)

        val settings = repository.settings()
        // PRD D6: this default is the whole reason the project exists.
        assertTrue("defaultDeleteOnceAfterRing ships ON", settings.defaultDeleteOnceAfterRing)
        assertEquals(1, settings.defaultPauseDays)
        assertEquals(TimeFormat.H24, settings.timeFormat)
        assertEquals(ThemeMode.SYSTEM, settings.theme)
        assertEquals(VolumeKeyAction.SNOOZE, settings.volumeKeyAction)
        assertFalse(settings.permissionCheckDone)
    }

    @Test
    fun seedingIsIdempotent() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        BuiltInGroups.seedIfEmpty(database)
        BuiltInGroups.seedIfEmpty(database)

        assertEquals("startup must not create a second 未分组", 3, repository.groups().size)
    }

    @Test
    fun seedingAddsASettingsRowToAnInstallThatLacksOne() = runBlocking(Dispatchers.IO) {
        // Simulates an upgrade: groups exist, the settings row does not.
        database.alarmDao().insertGroup(BuiltInGroups.all.first())
        assertNull(database.alarmDao().settings())

        BuiltInGroups.seedIfEmpty(database)

        assertNotNull(
            "a missing settings row must be created rather than left to crash getSettings()",
            database.alarmDao().settings(),
        )
        assertEquals(1, repository.groups().size)
    }

    // -----------------------------------------------------------------------------------------
    // M3.3 repository behaviour, PRD FR-1.4
    // -----------------------------------------------------------------------------------------

    @Test
    fun savingAndReadingBackAnAlarmPreservesEveryField() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)

        val saved = repository.saveAlarm(
            Alarm(
                id = 0,
                groupId = BuiltInGroups.WORKDAY_ID,
                hour = 7,
                minute = 5,
                label = "起床上班",
                repeatType = RepeatType.WORKDAY,
                vibrate = false,
                fadeInSeconds = 12,
                snoozeEnabled = false,
                snoozeMinutes = 7,
                snoozeMaxCount = 2,
                autoStopMinutes = 0,
                deleteAfterRing = true,
                pauseUntil = 123_456_789L,
            ),
        )

        val read = repository.alarm(saved.id)!!
        assertEquals(7, read.hour)
        assertEquals(5, read.minute)
        assertEquals("a Chinese label must survive the round trip", "起床上班", read.label)
        assertEquals(RepeatType.WORKDAY, read.repeatType)
        assertFalse(read.vibrate)
        assertEquals(12, read.fadeInSeconds)
        assertFalse(read.snoozeEnabled)
        assertEquals(7, read.snoozeMinutes)
        assertEquals(2, read.snoozeMaxCount)
        assertEquals(0, read.autoStopMinutes)
        assertTrue(read.deleteAfterRing)
        assertEquals(123_456_789L, read.pauseUntil)
        assertTrue(read.enabled)
        assertEquals(fixedNow, read.createdAt)
    }

    @Test
    fun everyRepeatTypeRoundTripsThroughTheEnumConverter() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)

        for (type in RepeatType.entries) {
            val saved = repository.saveAlarm(
                Alarm(
                    id = 0,
                    groupId = BuiltInGroups.UNGROUPED_ID,
                    hour = 6,
                    minute = 0,
                    repeatType = type,
                    repeatDays = if (type == RepeatType.WEEKLY) 0b0010101 else 0,
                ),
            )
            assertEquals(type, repository.alarm(saved.id)!!.repeatType)
        }
    }

    /** PRD FR-1.4: deleting a group moves its alarms to 未分组 instead of destroying them. */
    @Test
    fun deletingAGroupMovesItsAlarmsInsteadOfDeletingThem() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        val tempGroupId = repository.saveGroup(GroupEntity(name = "临时", color = 1, sortOrder = 9).toDomain())
        repository.saveAlarm(alarmIn(tempGroupId, hour = 7))
        repository.saveAlarm(alarmIn(tempGroupId, hour = 8))

        val moved = repository.deleteGroup(tempGroupId, BuiltInGroups.UNGROUPED_ID)

        assertEquals(2, moved)
        assertEquals("the alarms survive", 2, repository.alarms().size)
        assertTrue(repository.alarms().all { it.groupId == BuiltInGroups.UNGROUPED_ID })
        assertNull(repository.group(tempGroupId))
    }

    /** The RESTRICT that makes the FR-1.4 behaviour enforceable rather than a convention. */
    @Test
    fun aGroupWithAlarmsCannotBeDeletedDirectly() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 7))

        val failure = runCatching { database.alarmDao().deleteGroup(BuiltInGroups.WORKDAY_ID) }.exceptionOrNull()

        assertNotNull("the foreign key must refuse this, not silently orphan the alarm", failure)
        assertEquals("the group is still there", 3, repository.groups().size)
    }

    @Test
    fun alarmCountsAreReportedPerGroupForTheDeletePrompt() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 7))
        repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 8))
        repository.saveAlarm(alarmIn(BuiltInGroups.HOLIDAY_ID, hour = 9))

        val byName = repository.groups().associate { it.name to it.alarmCount }

        assertEquals(2, byName["工作日"])
        assertEquals(1, byName["节假日"])
        assertEquals(0, byName["未分组"])
    }

    @Test
    fun reorderingPersistsTheGivenOrder() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        val reversed = repository.groups().map { it.id }.reversed()

        repository.reorderGroups(reversed)

        assertEquals(reversed, repository.groups().map { it.id })
    }

    // -----------------------------------------------------------------------------------------
    // Scheduler input: only alarms that can ring are schedulable (PRD 5.1 / 5.3)
    // -----------------------------------------------------------------------------------------

    @Test
    fun schedulableAlarmsExcludeSwitchedOffDisabledAndExpiredRows() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        val live = repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 7))
        repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 8).copy(enabled = false))
        repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 9).copy(permanentDisabled = true))
        repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 10).copy(expired = true))

        val schedulable = repository.schedulableAlarms()

        assertEquals(1, schedulable.size)
        assertEquals(live.id, schedulable.single().alarm.id)
    }

    /** PRD §5.4 bookkeeping: the registration instant has to outlive the process. */
    @Test
    fun theRegisteredTriggerInstantIsStoredAndCleared() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        val saved = repository.saveAlarm(alarmIn(BuiltInGroups.WORKDAY_ID, hour = 7))

        assertNull(repository.lastTriggerAt(saved.id))
        repository.setLastTriggerAt(saved.id, 987_654_321L)
        assertEquals(987_654_321L, repository.lastTriggerAt(saved.id))
        repository.setLastTriggerAt(saved.id, null)
        assertNull(repository.lastTriggerAt(saved.id))
    }

    // -----------------------------------------------------------------------------------------
    // PRD FR-3: 响铃后删除 and the bulk conversion
    // -----------------------------------------------------------------------------------------

    @Test
    fun bulkConversionOnlyTouchesOneOffAlarmsThatAreNotAlreadyMarked() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        repository.saveAlarm(alarmIn(BuiltInGroups.UNGROUPED_ID, hour = 7).copy(repeatType = RepeatType.ONCE))
        repository.saveAlarm(alarmIn(BuiltInGroups.UNGROUPED_ID, hour = 8).copy(repeatType = RepeatType.ONCE))
        repository.saveAlarm(
            alarmIn(BuiltInGroups.UNGROUPED_ID, hour = 9).copy(repeatType = RepeatType.ONCE, deleteAfterRing = true),
        )
        // A repeating alarm must never be converted (PRD FR-3.5).
        repository.saveAlarm(alarmIn(BuiltInGroups.UNGROUPED_ID, hour = 10).copy(repeatType = RepeatType.DAILY))

        val (total, alreadyMarked) = repository.countOnceAlarms()
        assertEquals(3, total)
        assertEquals(1, alreadyMarked)

        val affected = repository.bulkSetDeleteAfterRing()

        assertEquals("only the two unmarked one-offs change", 2, affected)
        assertEquals(3, repository.countOnceAlarms().second)
        assertFalse(
            "a repeating alarm must not gain deleteAfterRing",
            repository.alarms().first { it.repeatType == RepeatType.DAILY }.deleteAfterRing,
        )
        assertEquals("idempotent: a second run has nothing left to do", 0, repository.bulkSetDeleteAfterRing())
    }

    @Test
    fun expiresMarkingKeepsTheAlarmButRemovesItFromScheduling() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        val saved = repository.saveAlarm(
            alarmIn(BuiltInGroups.UNGROUPED_ID, hour = 7).copy(repeatType = RepeatType.ONCE),
        )

        repository.markExpired(saved.id)

        assertTrue(repository.alarm(saved.id)!!.expired)
        assertTrue("an expired alarm is still listed", repository.alarms().any { it.id == saved.id })
        assertTrue("but it is not scheduled", repository.schedulableAlarms().none { it.alarm.id == saved.id })
    }

    // -----------------------------------------------------------------------------------------
    // Pause state (PRD FR-2.1 / FR-2.6 / FR-2.7)
    // -----------------------------------------------------------------------------------------

    @Test
    fun pausingAndResumingAGroupRoundTripsAndRespectsMutualExclusion() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)

        repository.setGroupPermanentDisabled(BuiltInGroups.WORKDAY_ID, true)
        assertTrue(repository.group(BuiltInGroups.WORKDAY_ID)!!.permanentDisabled)

        // PRD FR-2.7: the two states are mutually exclusive, so pausing clears the disable...
        repository.pauseGroup(BuiltInGroups.WORKDAY_ID, pauseUntil = 5_000L, pausedAt = 1_000L)
        val paused = repository.group(BuiltInGroups.WORKDAY_ID)!!
        assertEquals(5_000L, paused.pauseUntil)
        assertEquals(1_000L, paused.pausedAt)
        assertFalse(paused.permanentDisabled)

        // ...and a manual resume clears the pause (PRD FR-2.6).
        repository.resumeGroup(BuiltInGroups.WORKDAY_ID)
        val resumed = repository.group(BuiltInGroups.WORKDAY_ID)!!
        assertNull(resumed.pauseUntil)
        assertNull(resumed.pausedAt)
    }

    @Test
    fun anAlarmPauseAlsoClearsAPermanentDisable() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        val saved = repository.saveAlarm(
            alarmIn(BuiltInGroups.WORKDAY_ID, hour = 7).copy(permanentDisabled = true),
        )

        repository.pauseAlarm(saved.id, pauseUntil = 9_000L)

        val read = repository.alarm(saved.id)!!
        assertEquals(9_000L, read.pauseUntil)
        assertFalse(read.permanentDisabled)
    }

    @Test
    fun settingsUpdatesOnlyChangeWhatTheCallerTouched() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)

        val updated = repository.updateSettings { it.copy(defaultPauseDays = 3, theme = ThemeMode.DARK) }

        assertEquals(3, updated.defaultPauseDays)
        assertEquals(ThemeMode.DARK, updated.theme)
        assertTrue("untouched defaults survive the write", updated.defaultDeleteOnceAfterRing)
        // And it is persisted, not merely returned.
        assertEquals(3, repository.settings().defaultPauseDays)
        assertEquals(ThemeMode.DARK, repository.settings().theme)
    }

    // -----------------------------------------------------------------------------------------
    // Schema stability
    // -----------------------------------------------------------------------------------------

    /**
     * The committed schema JSON must describe the tables the entities declare, or a future migration
     * would be written against a description that no longer holds. The file is read from the
     * androidTest assets, which is the copy Gradle shipped.
     */
    @Test
    fun theExportedSchemaDescribesEveryTable() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = context.assets
            .open("com.alarmhub.app.data.db.AppDatabase/1.json")
            .bufferedReader()
            .use { it.readText() }

        for (table in listOf("alarm_groups", "alarms", "settings")) {
            assertTrue("schema JSON should mention table $table", json.contains("\"tableName\": \"$table\""))
        }
        assertTrue("schema JSON should pin version 1", json.contains("\"version\": 1"))
        assertTrue("the scheduler's bookkeeping column must be in the schema", json.contains("last_trigger_at"))
    }

    private fun alarmIn(groupId: Long, hour: Int) = Alarm(
        id = 0,
        groupId = groupId,
        hour = hour,
        minute = 0,
        repeatType = RepeatType.DAILY,
    )
}
