package com.alarmhub.app.domain.model

import java.time.LocalTime

/**
 * One alarm reduced to "on which days, at what time, is it currently allowed to ring".
 *
 * [rules] is a set: two alarms that ring at the same minute on the same days behave identically
 * for scheduling and for the pause maths, so keeping both would only make test output noisy.
 */
data class AlarmSchedule(
    val time: LocalTime,
    val rules: Set<AlarmRule>,
) {
    init {
        require(rules.isNotEmpty()) { "an alarm must have at least one repeat rule" }
    }

    companion object {
        /**
         * Collapses a group of alarms into the schedules that matter.
         *
         * [alarms] is filtered first and collapsed second, so the caller's "启用" filter (PRD §5.3
         * step 3) can look at the entity — its switch, its expiry, its group — while the result
         * stays a pure scheduling input. Alarms that share a ring time are merged: two alarms at
         * 07:00 on the same days are one ring day for everybody's purposes.
         */
        fun <T> of(alarms: Iterable<T>, toSchedule: (T) -> AlarmSchedule): List<AlarmSchedule> =
            alarms.map(toSchedule)
                .groupBy { it.time }
                .map { (time, sameTime) -> AlarmSchedule(time, sameTime.flatMap { it.rules }.toSet()) }
                .sortedBy { it.time }
    }
}
