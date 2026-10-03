package com.alarmhub.app.domain

import com.alarmhub.app.domain.calendar.HolidayCalendar
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.Group
import com.alarmhub.app.domain.schedule.NextRingCalculator
import com.alarmhub.app.domain.schedule.PauseResolver
import com.alarmhub.app.domain.schedule.PauseResolution
import java.time.ZoneId

/**
 * M3-facing conveniences: the same rules, reading "now" from an injected [TimeSource].
 *
 * The primary API of [NextRingCalculator] / [PauseResolver] takes `now` as a `Long`, because that
 * is what the tests, the repository and the bridge all have and it keeps those classes free of any
 * clock at all. These extensions exist so the *production* call sites still have to name their
 * clock — a `TimeSource` in a constructor — instead of silently reaching for
 * `System.currentTimeMillis()` somewhere inside the domain.
 */
object DomainServices {

    /** The next ring instant as of `timeSource.nowMillis()`, or `null` when it will not ring. */
    fun nextRing(
        calculator: NextRingCalculator,
        alarm: Alarm,
        group: Group?,
        zone: ZoneId,
        timeSource: TimeSource = TimeSource.system,
    ): Long? = calculator.nextRing(alarm, group, timeSource.nowMillis(), zone)

    /** The row status as of `timeSource.nowMillis()`. */
    fun statusOf(
        calculator: NextRingCalculator,
        alarm: Alarm,
        group: Group?,
        zone: ZoneId,
        timeSource: TimeSource = TimeSource.system,
    ) = calculator.statusOf(alarm, group, timeSource.nowMillis(), zone)

    /**
     * PRD §5.3 as of `timeSource.nowMillis()` — the pure conversion behind the contract's
     * `previewPause`, and the same call the confirm action uses before writing `pauseUntil`.
     */
    fun previewPause(
        resolver: PauseResolver,
        alarms: List<Alarm>,
        group: Group?,
        days: Int,
        zone: ZoneId,
        timeSource: TimeSource = TimeSource.system,
    ): PauseResolution = resolver.previewForGroup(alarms, group, days, timeSource.nowMillis(), zone)

    /** A calculator wired to [calendar], for callers that do not want to construct it themselves. */
    fun calculator(calendar: HolidayCalendar): NextRingCalculator = NextRingCalculator(calendar)

    /** A resolver wired to [calendar]. */
    fun resolver(calendar: HolidayCalendar): PauseResolver = PauseResolver(calendar)
}
