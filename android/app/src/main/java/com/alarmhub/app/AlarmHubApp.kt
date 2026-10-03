package com.alarmhub.app

import android.app.Application
import android.util.Log
import com.alarmhub.app.alarm.AlarmScheduler
import com.alarmhub.app.data.AlarmRepository
import com.alarmhub.app.data.BuiltInGroups
import com.alarmhub.app.data.db.AppDatabase
import com.alarmhub.app.domain.TimeSource
import com.alarmhub.app.domain.calendar.BundledHolidayCalendar
import com.alarmhub.app.domain.calendar.ChainedHolidayCalendar
import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.calendar.HolidayDataParser
import com.alarmhub.app.domain.calendar.WeekdayOnlyCalendar
import com.alarmhub.app.ring.RingController
import com.alarmhub.app.ring.RingNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide wiring: the database, the repository, the holiday calendar and the scheduler.
 *
 * Deliberately a hand-written container rather than a DI framework — there are five objects and they
 * form a straight line, so a library would add a build-time processor and a layer of indirection for
 * nothing (TECH-STACK §4.1 keeps the dependency graph this flat on purpose).
 */
class AlarmHubApp : Application() {

    /** The clock seam. The single place in the app that reads the system time. */
    val timeSource: TimeSource = TimeSource.system

    lateinit var database: AppDatabase
        private set

    lateinit var repository: AlarmRepository
        private set

    lateinit var holidayCalendar: HolidayCalendar
        private set

    /**
     * What `getHolidayDataInfo` reports (PRD FR-6.2 / FR-6.3), captured where the file is parsed.
     *
     * Read back out of the calendar instead of stored here, the "years" would be whatever the
     * *chained* calendar happens to answer, which is not the same question: the chain answers
     * weekday-only for years the file omits. These two fields are the file's own metadata.
     */
    var holidayYears: Set<Int> = emptySet()
        private set

    var holidaySource: String = "内置节假日数据不可用（已降级为周一~周五）"
        private set

    lateinit var scheduler: AlarmScheduler
        private set

    /** Outlives any single receiver, so a recompute started by a broadcast is never cut short. */
    internal val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /*
     * There used to be a `ringInProgress` flag here, for PRD §5.4's "a ring is already on screen"
     * check. It was declared at M3 with a comment saying M4 would set it, M4 never did, so the check
     * silently stayed false -- a dead branch documented as working.
     *
     * `RingController.isRinging` is the real answer and it cannot drift, because it is derived from
     * the state the ring page is actually observed through rather than from a second copy of it.
     */

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.build(this)
        repository = AlarmRepository(database, timeSource)
        holidayCalendar = loadHolidayCalendar()
        scheduler = AlarmScheduler(this, repository, holidayCalendar, timeSource)

        // The ring's notification channel and the ring controller's context. Both want to exist before
        // any trigger can arrive: a channel created late is a channel with downgraded importance, and
        // the controller needs a repository to apply a ring's ending to the stored alarm.
        RingNotifications.ensureChannel(this)
        RingController.attach(this)

        // Register whatever the stored alarms need. On a cold start this is what arms the alarms of a
        // freshly installed — or freshly rebooted — app, so it does not wait for a broadcast.
        appScope.launch {
            BuiltInGroups.seedIfEmpty(database)
            scheduler.recomputeAll()
            Log.i(
                TAG,
                "startup complete: ${repository.groups().size} groups, ${repository.alarms().size} alarms",
            )
        }
    }

    /**
     * Loads `assets/holidays.json`.
     *
     * PRD FR-6.3: if the file is missing or unreadable the app must degrade to "Monday–Friday is a
     * working day" rather than fail to start. A chained calendar makes that degradation per-year, so
     * a file covering 2025–2026 still answers those years correctly while answering 2027 from the
     * weekday fallback.
     */
    private fun loadHolidayCalendar(): HolidayCalendar =
        try {
            val text = assets.open(HOLIDAY_ASSET).bufferedReader().use { it.readText() }
            val parsed = HolidayDataParser.parse(text)
            holidayYears = parsed.holidays.years
            holidaySource = parsed.source
            Log.i(TAG, "holiday data: years=${parsed.holidays.years.sorted()} source=${parsed.source}")
            ChainedHolidayCalendar(BundledHolidayCalendar(parsed.holidays), WeekdayOnlyCalendar)
        } catch (t: Throwable) {
            Log.e(TAG, "could not load $HOLIDAY_ASSET; falling back to Monday–Friday (PRD FR-6.3)", t)
            WeekdayOnlyCalendar
        }

    companion object {
        private const val TAG = "AlarmHub/App"
        private const val HOLIDAY_ASSET = "holidays.json"

        /** The app instance behind any [android.content.Context] a receiver was handed. */
        fun of(context: android.content.Context): AlarmHubApp =
            context.applicationContext as AlarmHubApp

        /**
         * Starts a full recompute on the application scope.
         *
         * Used by the receivers, which have to hand back their main-thread turn immediately but
         * still need the sweep to survive being garbage collected.
         */
        fun recomputeAll(context: android.content.Context) {
            val app = of(context)
            app.appScope.launch {
                runCatching { app.scheduler.recomputeAll() }
                    .onFailure { Log.e(TAG, "recomputeAll failed", it) }
            }
        }
    }
}
