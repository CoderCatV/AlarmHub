package com.alarmhub.app.alarm

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alarmhub.app.data.AlarmRepository
import com.alarmhub.app.data.BuiltInGroups
import com.alarmhub.app.data.db.AppDatabase
import com.alarmhub.app.domain.TimeSource
import com.alarmhub.app.domain.calendar.WeekdayOnlyCalendar
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.RepeatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M3.4 — the scheduler's registration bookkeeping.
 *
 * The parts that need a real `AlarmManager` (that a trigger actually fires, and that a pause
 * suppresses it) are verified end-to-end on the emulator with `tools/m3-acceptance.ps1`; what is
 * testable in isolation is the arithmetic that keeps one alarm's registration from evicting
 * another's, and the fact that cancelling forgets the registration instant.
 */
@RunWith(AndroidJUnit4::class)
class AlarmSchedulerTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var scheduler: AlarmScheduler

    private val fixedNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = AlarmRepository(database, TimeSource.fixed(fixedNow))
        scheduler = AlarmScheduler(
            ApplicationProvider.getApplicationContext(),
            repository,
            WeekdayOnlyCalendar,
            TimeSource.fixed(fixedNow),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    /**
     * PRD M3.4's "requestCode 由 alarm.id 派生，杜绝复用错乱" is the whole point of this test: if two
     * registrations ever shared a request code, cancelling one would silently cancel the other.
     *
     * The count is asserted against `TriggerKind.entries` rather than a literal, so adding a trigger
     * kind (SNOOZE arrived at M4) cannot silently start violating this without the test noticing.
     *
     * **The literal on the last line is a deliberate tripwire.** It failed when ALERT was added for PRD
     * FR-7.10, which is what it is for: a new kind changes how many `AlarmManager` registrations each
     * alarm holds and the `requestCodeFor` bit layout, and neither should happen without someone
     * stopping to confirm it is intended. It is — ALERT fires one hour before the ring so the "coming
     * up" notification can appear while the app is not running — so the number and the comment move
     * together.
     */
    @Test
    fun everyTriggerKindForOneAlarmGetsADistinctRequestCode() {
        val codes = TriggerKind.entries.map { AlarmScheduler.requestCodeFor(42L, it) }

        assertEquals("one code per kind", TriggerKind.entries.size, codes.toSet().size)
        // MAIN, PRE, PAUSE, SNOOZE, ALERT.
        assertEquals("a fresh alarm carries exactly these kinds", 5, TriggerKind.entries.size)
    }

    @Test
    fun differentAlarmsNeverShareARequestCode() {
        val seen = mutableSetOf<Int>()
        // A realistic upper bound for PRD NFR 容量 (200 alarms) plus headroom.
        for (alarmId in 1L..500L) {
            for (kind in TriggerKind.entries) {
                val code = AlarmScheduler.requestCodeFor(alarmId, kind)
                assertTrue(
                    "request code $code for alarm=$alarmId kind=$kind collides with an earlier one",
                    seen.add(code),
                )
            }
        }
    }

    @Test
    fun requestCodesStayInsideTheRangeIntentAllows() {
        // Intent's request code is an Int, but Android's broadcast bookkeeping uses the low 24 bits.
        // Anything above that would wrap and could collide at run time without failing here.
        for (alarmId in listOf(1L, 999L, 100_000L, 2_097_151L)) {
            for (kind in TriggerKind.entries) {
                val code = AlarmScheduler.requestCodeFor(alarmId, kind)
                assertTrue("request code $code must be non-negative", code >= 0)
                assertTrue("request code $code must fit 24 bits", code <= 0xFFFFFF)
            }
        }
    }

    @Test
    fun cancellingForgetsTheRegistrationInstant() = runBlocking(Dispatchers.IO) {
        BuiltInGroups.seedIfEmpty(database)
        val saved = repository.saveAlarm(
            Alarm(
                id = 0,
                groupId = BuiltInGroups.UNGROUPED_ID,
                hour = 7,
                minute = 0,
                repeatType = RepeatType.DAILY,
            ),
        )
        repository.setLastTriggerAt(saved.id, fixedNow + 60_000L)
        assertEquals(fixedNow + 60_000L, repository.lastTriggerAt(saved.id))

        scheduler.cancel(saved.id)

        assertNull(
            "a cancelled alarm must not keep claiming a trigger instant (PRD 5.4 compares against it)",
            repository.lastTriggerAt(saved.id),
        )
    }

    /**
     * PRD 5.7's redundant front must not be able to advertise a time five seconds early: the PRE
     * lead is the negative offset applied to the main instant, and nothing else.
     */
    @Test
    fun theRedundantFrontLeadsTheMainTriggerByExactlyFiveSeconds() {
        assertEquals(5_000L, AlarmScheduler.LEAD_MILLIS)
    }

    /** The trigger action is a single string the manifest, the scheduler and the receiver agree on. */
    @Test
    fun theTriggerActionIsStable() {
        assertEquals("com.alarmhub.app.action.ALARM_TRIGGER", AlarmScheduler.ACTION_TRIGGER)
        assertEquals("alarmId", AlarmScheduler.EXTRA_ALARM_ID)
        assertEquals("kind", AlarmScheduler.EXTRA_KIND)
    }
}
