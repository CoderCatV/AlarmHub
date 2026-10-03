package com.alarmhub.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.alarmhub.app.domain.model.RepeatType

/**
 * Room entities (PRD §4).
 *
 * These are storage shapes, not the domain objects: `domain/model/` stays free of annotations so
 * the rules can be unit-tested without Android (docs/TECH-STACK.md §4.1). [com.alarmhub.app.data.Mappers]
 * is the only place allowed to translate between the two.
 */
@Entity(
    tableName = "alarm_groups",
    indices = [Index(value = ["sort_order"])],
)
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** ARGB packed into an Int, e.g. `0xff4c9dff`. */
    val color: Int,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "is_system") val isSystem: Boolean = false,
    @ColumnInfo(name = "permanently_disabled") val permanentlyDisabled: Boolean = false,
    /** Resume instant of a temporary pause, epoch millis. `null` = not paused (PRD §4.1). */
    @ColumnInfo(name = "pause_until") val pauseUntil: Long? = null,
    @ColumnInfo(name = "paused_at") val pausedAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

@Entity(
    tableName = "alarms",
    /*
     * RESTRICT, not CASCADE. Deleting a group must never delete its alarms (PRD FR-1.4) — they move
     * to 未分组 instead. CASCADE would make an accidental group delete silently destroy alarms, and
     * PRD FR-1.4 explicitly calls for the opposite. RESTRICT fails the transaction loudly.
     */
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["group_id"]),
        Index(value = ["enabled", "permanently_disabled"]),
    ],
)
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "group_id") val groupId: Long,
    val hour: Int,
    val minute: Int,
    val label: String = "",
    /** Stored as its [RepeatType] name (`ONCE` / `DAILY` / `WEEKLY` / `WORKDAY` / `HOLIDAY`). */
    @ColumnInfo(name = "repeat_type") val repeatType: RepeatType,
    /** 7-bit mask, bit0 = Monday … bit6 = Sunday (PRD §4.2). Only meaningful for WEEKLY. */
    @ColumnInfo(name = "repeat_days") val repeatDays: Int = 0,
    /** `yyyy-MM-dd`, only for ONCE. */
    @ColumnInfo(name = "once_date") val onceDate: String? = null,
    @ColumnInfo(name = "ringtone_uri") val ringtoneUri: String? = null,
    @ColumnInfo(name = "ringtone_name") val ringtoneName: String? = null,
    val vibrate: Boolean = true,
    @ColumnInfo(name = "fade_in_seconds") val fadeInSeconds: Int = 5,
    @ColumnInfo(name = "snooze_enabled") val snoozeEnabled: Boolean = true,
    @ColumnInfo(name = "snooze_minutes") val snoozeMinutes: Int = 10,
    @ColumnInfo(name = "snooze_max_count") val snoozeMaxCount: Int = 3,
    @ColumnInfo(name = "auto_stop_minutes") val autoStopMinutes: Int = 10,
    @ColumnInfo(name = "delete_after_ring") val deleteAfterRing: Boolean = false,
    val enabled: Boolean = true,
    @ColumnInfo(name = "permanently_disabled") val permanentlyDisabled: Boolean = false,
    @ColumnInfo(name = "pause_until") val pauseUntil: Long? = null,
    val expired: Boolean = false,
    /**
     * How many times this alarm has been snoozed during the current ring (PRD FR-4.3.4).
     *
     * Persisted rather than kept in memory because the ring survives the process being killed: the
     * alarm rings from a foreground service, and Android may recreate that process mid-ring. A
     * counter held in a singleton would reset to zero and hand the user unlimited snoozes.
     *
     * Reset by [com.alarmhub.app.data.AlarmRepository.clearSnoozeCount] whenever a fresh ring starts
     * or the alarm is rescheduled normally.
     */
    @ColumnInfo(name = "snooze_count") val snoozeCount: Int = 0,
    /**
     * The instant the currently registered main trigger is set for, written when the scheduler
     * registers it and cleared when it cancels.
     *
     * PRD §5.4's last line compares 计算出的本次响铃时间 with 本次触发时间. A `PendingIntent` carries no
     * trigger time and the receiver runs some milliseconds late, so `now` cannot stand in for the
     * scheduled instant — this column is what makes that comparison possible at all. It lives in
     * the database rather than in memory because it has to survive the process being killed (or the
     * device rebooting) between registering the alarm and it firing, which is precisely the case the
     * check exists for.
     */
    @ColumnInfo(name = "last_trigger_at") val lastTriggerAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)

/**
 * PRD §4.3 settings, kept in the database rather than SharedPreferences.
 *
 * They are user data with defaults that must survive a reinstall faithfully, and the bridge reads
 * and writes them as a single object; one row keeps that atomic and keeps the whole app state in
 * one file (PRD NFR「数据只有一个真源」). `timeFormat` / `theme` additionally drive the UI, which
 * can also read them through the same getter.
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    /** Single-row table: always 1. */
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "default_delete_once_after_ring") val defaultDeleteOnceAfterRing: Boolean = true,
    @ColumnInfo(name = "default_pause_days") val defaultPauseDays: Int = 1,
    @ColumnInfo(name = "default_snooze_minutes") val defaultSnoozeMinutes: Int = 10,
    @ColumnInfo(name = "default_snooze_max_count") val defaultSnoozeMaxCount: Int = 3,
    /**
     * Whether the ring page offers 贪睡 **at all**.
     *
     * Added at M8 for a user who does not use snooze: a per-alarm switch already existed in the editor,
     * but turning it off alarm by alarm is not what "I never use it" should require. `true` preserves
     * the old behaviour exactly, which is what makes this column safe to add ahead of the UI that
     * drives it.
     */
    @ColumnInfo(name = "snooze_enabled") val snoozeEnabled: Boolean = true,
    @ColumnInfo(name = "default_auto_stop_minutes") val defaultAutoStopMinutes: Int = 10,
    @ColumnInfo(name = "default_ringtone_uri") val defaultRingtoneUri: String? = null,
    @ColumnInfo(name = "default_ringtone_name") val defaultRingtoneName: String? = null,
    @ColumnInfo(name = "default_fade_in_seconds") val defaultFadeInSeconds: Int = 5,
    /** `"12"` or `"24"`. */
    @ColumnInfo(name = "time_format") val timeFormat: String = "24",
    /** `system` / `dark` / `light`. */
    val theme: String = "system",
    /** `snooze` / `dismiss`. */
    @ColumnInfo(name = "volume_key_action") val volumeKeyAction: String = "snooze",
    @ColumnInfo(name = "permission_check_done") val permissionCheckDone: Boolean = false,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
