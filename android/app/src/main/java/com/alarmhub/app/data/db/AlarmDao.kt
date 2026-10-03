package com.alarmhub.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * DAO for groups and their alarms (PRD §4.1 / §4.2).
 *
 * The suspend functions are the real API; the [Flow] variants exist so a later milestone can push
 * changes to the UI instead of polling. Both go through the same queries, so they can never
 * disagree.
 */
@Dao
interface AlarmDao {

    // ---- groups --------------------------------------------------------------------------

    @Query("SELECT * FROM alarm_groups ORDER BY sort_order ASC, id ASC")
    suspend fun groups(): List<GroupEntity>

    @Query("SELECT * FROM alarm_groups ORDER BY sort_order ASC, id ASC")
    fun groupsFlow(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM alarm_groups WHERE id = :id")
    suspend fun group(id: Long): GroupEntity?

    /** How many alarms a group holds, for the delete confirmation (PRD FR-1.4). */
    @Query("SELECT COUNT(*) FROM alarms WHERE group_id = :groupId")
    suspend fun alarmCountIn(groupId: Long): Int

    @Query("SELECT group_id AS groupId, COUNT(*) AS count FROM alarms GROUP BY group_id")
    suspend fun alarmCountsByGroup(): List<GroupAlarmCount>

    @Query("SELECT * FROM alarm_groups ORDER BY sort_order ASC, id ASC")
    suspend fun groupIdsInOrder(): List<GroupEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroup(group: GroupEntity): Long

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Query("DELETE FROM alarm_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Query("UPDATE alarm_groups SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun setGroupSortOrder(id: Long, sortOrder: Int, updatedAt: Long)

    /**
     * PRD FR-1.4: deleting a group moves its alarms to another group. Both statements run in one
     * transaction so a crash cannot leave alarms pointing at a group that no longer exists.
     */
    @Transaction
    suspend fun moveAlarmsAndDeleteGroup(fromGroupId: Long, toGroupId: Long, updatedAt: Long): Int {
        val moved = reassignGroup(fromGroupId, toGroupId, updatedAt)
        deleteGroup(fromGroupId)
        return moved
    }

    @Query("UPDATE alarms SET group_id = :toGroupId, updated_at = :updatedAt WHERE group_id = :fromGroupId")
    suspend fun reassignGroup(fromGroupId: Long, toGroupId: Long, updatedAt: Long): Int

    @Query("SELECT COUNT(*) FROM alarms WHERE group_id = :groupId AND id != :exceptId")
    suspend fun otherAlarmsIn(groupId: Long, exceptId: Long): Int

    // ---- alarms --------------------------------------------------------------------------

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC, id ASC")
    suspend fun alarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarms ORDER BY hour ASC, minute ASC, id ASC")
    fun alarmsFlow(): Flow<List<AlarmEntity>>

    /** The scheduler's input: only the alarms that can ring at all (PRD §5.1). */
    @Query(
        """
        SELECT * FROM alarms
        WHERE enabled = 1 AND permanently_disabled = 0 AND expired = 0
        ORDER BY hour ASC, minute ASC, id ASC
        """,
    )
    suspend fun schedulableAlarms(): List<AlarmEntity>

    @Query("SELECT * FROM alarms WHERE id = :id")
    suspend fun alarm(id: Long): AlarmEntity?

    /** Scheduling bookkeeping for PRD §5.4; kept off the domain model. */
    @Query("SELECT last_trigger_at FROM alarms WHERE id = :id")
    suspend fun lastTriggerAt(id: Long): Long?

    @Query("SELECT * FROM alarms WHERE id = :id")
    fun alarmFlow(id: Long): Flow<AlarmEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAlarm(alarm: AlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: AlarmEntity)

    @Delete
    suspend fun deleteAlarm(alarm: AlarmEntity)

    @Query("DELETE FROM alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: Long)

    @Query("UPDATE alarms SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE alarms SET expired = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun markExpired(id: Long, updatedAt: Long)

    /**
     * Records (or clears, with `null`) the instant the main trigger is registered for, so PRD §5.4's
     * last check has the 本次触发时间 to compare against. Not part of the domain model: this is
     * scheduling bookkeeping, not user data.
     */
    @Query("UPDATE alarms SET last_trigger_at = :triggerAt WHERE id = :id")
    suspend fun setLastTriggerAt(id: Long, triggerAt: Long?)

    /** PRD FR-4.3.4: the snooze counter, incremented once per 贪睡 press. */
    @Query("SELECT snooze_count FROM alarms WHERE id = :id")
    suspend fun snoozeCount(id: Long): Int?

    @Query("UPDATE alarms SET snooze_count = :count WHERE id = :id")
    suspend fun setSnoozeCount(id: Long, count: Int)

    /** PRD FR-3.6: 响铃后删除 removes the alarm outright. */
    @Query("DELETE FROM alarms WHERE id = :id AND repeat_type = 'ONCE' AND delete_after_ring = 1")
    suspend fun deleteIfMarkedForDeletion(id: Long): Int

    /** PRD FR-3.4: the bulk conversion. Returns how many rows actually changed. */
    @Query(
        """
        UPDATE alarms SET delete_after_ring = 1, updated_at = :updatedAt
        WHERE repeat_type = 'ONCE' AND delete_after_ring = 0
        """,
    )
    suspend fun markAllOnceForDeletion(updatedAt: Long): Int

    @Query("SELECT COUNT(*) FROM alarms WHERE repeat_type = 'ONCE'")
    suspend fun countOnceAlarms(): Int

    @Query("SELECT COUNT(*) FROM alarms WHERE repeat_type = 'ONCE' AND delete_after_ring = 1")
    suspend fun countOnceAlarmsMarkedForDeletion(): Int

    @Query("SELECT * FROM alarms WHERE repeat_type = 'ONCE' AND expired = 1 ORDER BY hour ASC, minute ASC")
    suspend fun expiredAlarms(): List<AlarmEntity>

    // ---- settings ------------------------------------------------------------------------

    @Query("SELECT * FROM settings WHERE id = ${SettingsEntity.SINGLETON_ID}")
    suspend fun settings(): SettingsEntity?

    @Query("SELECT * FROM settings WHERE id = ${SettingsEntity.SINGLETON_ID}")
    fun settingsFlow(): Flow<SettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: SettingsEntity)

    // ---- seed / diagnostics --------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM alarm_groups")
    suspend fun groupCount(): Int
}

/** Projection for `alarmCountsByGroup`. */
data class GroupAlarmCount(
    val groupId: Long,
    val count: Int,
)
