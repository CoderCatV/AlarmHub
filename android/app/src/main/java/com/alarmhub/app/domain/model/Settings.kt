package com.alarmhub.app.domain.model

/** 12- or 24-hour clock (PRD FR-4.4.6). */
enum class TimeFormat { H12, H24 }

/** PRD FR-4.4.7. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** PRD FR-4.3.6. */
enum class VolumeKeyAction { SNOOZE, DISMISS }

/**
 * Application settings (PRD §4.3), with the factory defaults from the PRD as literal defaults so
 * there is exactly one place that states them.
 */
data class Settings(
    /** PRD FR-3.1 / D6 — the switch this whole app was requested for. Factory default: ON. */
    val defaultDeleteOnceAfterRing: Boolean = true,
    /** PRD FR-2.3 — how many ring days "pause" skips by default. Factory default: 1. */
    val defaultPauseDays: Int = 1,
    val defaultSnoozeMinutes: Int = 10,
    val defaultSnoozeMaxCount: Int = 3,
    /**
     * Whether 贪睡 is offered at all, on every alarm.
     *
     * A global switch, not a default: a user who does not use snooze should not have to turn it off
     * alarm by alarm, and the per-alarm switch in the editor stays as the finer-grained control.
     * `true` keeps the pre-M8 behaviour.
     */
    val snoozeEnabled: Boolean = true,
    val defaultAutoStopMinutes: Int = 10,
    val defaultRingtoneUri: String? = null,
    val defaultRingtoneName: String? = null,
    val defaultFadeInSeconds: Int = 5,
    val timeFormat: TimeFormat = TimeFormat.H24,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val volumeKeyAction: VolumeKeyAction = VolumeKeyAction.SNOOZE,
    val permissionCheckDone: Boolean = false,
) {
    init {
        require(defaultPauseDays >= 1) { "defaultPauseDays must be at least 1, was $defaultPauseDays" }
    }
}
