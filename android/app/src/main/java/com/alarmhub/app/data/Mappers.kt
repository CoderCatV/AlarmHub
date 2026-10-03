package com.alarmhub.app.data

import com.alarmhub.app.data.db.AlarmEntity
import com.alarmhub.app.data.db.GroupEntity
import com.alarmhub.app.data.db.SettingsEntity
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.Group
import com.alarmhub.app.domain.model.Settings
import com.alarmhub.app.domain.model.ThemeMode
import com.alarmhub.app.domain.model.TimeFormat
import com.alarmhub.app.domain.model.VolumeKeyAction

/**
 * The only place that translates between storage rows and domain objects.
 *
 * Kept in one file, as top-level extensions, so the "domain has no Android dependencies" invariant
 * is checkable by reading one file (docs/TECH-STACK.md §4.1): if an `@Entity` ever leaks into
 * `domain/`, it has to come through here first.
 *
 * Note the one naming asymmetry between the two layers: the domain says `permanentDisabled` and the
 * `alarms` table column says `permanently_disabled` (PRD §4.2 spells it both ways; the domain kept
 * the PRD §5.1 spelling because that is the section the rules quote). These functions are where that
 * translation happens, once.
 */

fun GroupEntity.toDomain(alarmCount: Int = 0): Group = Group(
    id = id,
    name = name,
    color = color,
    sortOrder = sortOrder,
    isSystem = isSystem,
    permanentDisabled = permanentlyDisabled,
    pauseUntil = pauseUntil,
    pausedAt = pausedAt,
    updatedAt = updatedAt,
    alarmCount = alarmCount,
)

fun Group.toEntity(): GroupEntity = GroupEntity(
    id = id,
    name = name,
    color = color,
    sortOrder = sortOrder,
    isSystem = isSystem,
    permanentlyDisabled = permanentDisabled,
    pauseUntil = pauseUntil,
    pausedAt = pausedAt,
    updatedAt = updatedAt,
)

fun AlarmEntity.toDomain(): Alarm = Alarm(
    id = id,
    groupId = groupId,
    hour = hour,
    minute = minute,
    label = label,
    repeatType = repeatType,
    repeatDays = repeatDays,
    onceDate = onceDate,
    ringtoneUri = ringtoneUri,
    ringtoneName = ringtoneName,
    vibrate = vibrate,
    fadeInSeconds = fadeInSeconds,
    snoozeEnabled = snoozeEnabled,
    snoozeMinutes = snoozeMinutes,
    snoozeMaxCount = snoozeMaxCount,
    autoStopMinutes = autoStopMinutes,
    deleteAfterRing = deleteAfterRing,
    enabled = enabled,
    permanentDisabled = permanentlyDisabled,
    pauseUntil = pauseUntil,
    expired = expired,
    snoozeCount = snoozeCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * `lastTriggerAt` is deliberately not mapped: it is scheduling bookkeeping (PRD §5.4's 本次触发时间),
 * not part of the alarm the user sees, so it stays a column the scheduler reads through its own DAO
 * query rather than something that rides along on every domain object.
 */
fun Alarm.toEntity(): AlarmEntity = AlarmEntity(
    id = id,
    groupId = groupId,
    hour = hour,
    minute = minute,
    label = label,
    repeatType = repeatType,
    repeatDays = repeatDays,
    onceDate = onceDate,
    ringtoneUri = ringtoneUri,
    ringtoneName = ringtoneName,
    vibrate = vibrate,
    fadeInSeconds = fadeInSeconds,
    snoozeEnabled = snoozeEnabled,
    snoozeMinutes = snoozeMinutes,
    snoozeMaxCount = snoozeMaxCount,
    autoStopMinutes = autoStopMinutes,
    deleteAfterRing = deleteAfterRing,
    enabled = enabled,
    permanentlyDisabled = permanentDisabled,
    pauseUntil = pauseUntil,
    expired = expired,
    snoozeCount = snoozeCount,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun SettingsEntity.toDomain(): Settings = Settings(
    defaultDeleteOnceAfterRing = defaultDeleteOnceAfterRing,
    defaultPauseDays = defaultPauseDays,
    defaultSnoozeMinutes = defaultSnoozeMinutes,
    defaultSnoozeMaxCount = defaultSnoozeMaxCount,
    snoozeEnabled = snoozeEnabled,
    defaultAutoStopMinutes = defaultAutoStopMinutes,
    defaultRingtoneUri = defaultRingtoneUri,
    defaultRingtoneName = defaultRingtoneName,
    defaultFadeInSeconds = defaultFadeInSeconds,
    timeFormat = if (timeFormat == "12") TimeFormat.H12 else TimeFormat.H24,
    theme = when (theme) {
        "dark" -> ThemeMode.DARK
        "light" -> ThemeMode.LIGHT
        else -> ThemeMode.SYSTEM
    },
    volumeKeyAction = if (volumeKeyAction == "dismiss") VolumeKeyAction.DISMISS else VolumeKeyAction.SNOOZE,
    permissionCheckDone = permissionCheckDone,
)

fun Settings.toEntity(): SettingsEntity = SettingsEntity(
    defaultDeleteOnceAfterRing = defaultDeleteOnceAfterRing,
    defaultPauseDays = defaultPauseDays,
    defaultSnoozeMinutes = defaultSnoozeMinutes,
    defaultSnoozeMaxCount = defaultSnoozeMaxCount,
    snoozeEnabled = snoozeEnabled,
    defaultAutoStopMinutes = defaultAutoStopMinutes,
    defaultRingtoneUri = defaultRingtoneUri,
    defaultRingtoneName = defaultRingtoneName,
    defaultFadeInSeconds = defaultFadeInSeconds,
    timeFormat = if (timeFormat == TimeFormat.H12) "12" else "24",
    theme = when (theme) {
        ThemeMode.DARK -> "dark"
        ThemeMode.LIGHT -> "light"
        ThemeMode.SYSTEM -> "system"
    },
    volumeKeyAction = if (volumeKeyAction == VolumeKeyAction.DISMISS) "dismiss" else "snooze",
    permissionCheckDone = permissionCheckDone,
)
