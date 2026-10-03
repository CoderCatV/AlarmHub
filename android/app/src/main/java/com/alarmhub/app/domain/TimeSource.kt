package com.alarmhub.app.domain

/**
 * Where "now" comes from.
 *
 * The domain layer never calls [System.currentTimeMillis] in its logic (docs/DEVELOPMENT-PLAN.md
 * §M2, "关键约束"). Every rule takes the current instant as an explicit parameter instead, which is
 * what makes PRD §5.3's scenario table replayable on a plain JVM with no device, no clock mocking
 * and no Android dependencies.
 *
 * This interface exists for the *callers* that do need a real clock — the repository and the alarm
 * receiver at M3 — so that even there the dependency is visible in a constructor rather than
 * reaching for a static. Tests pass [fixed].
 */
fun interface TimeSource {
    /** Current instant as epoch milliseconds. */
    fun nowMillis(): Long

    companion object {
        /** The production clock. The only place in the codebase that reads the system time. */
        val system: TimeSource = TimeSource { System.currentTimeMillis() }

        /** A clock frozen at [epochMillis]; for tests and for replaying a scenario. */
        fun fixed(epochMillis: Long): TimeSource = TimeSource { epochMillis }
    }
}
