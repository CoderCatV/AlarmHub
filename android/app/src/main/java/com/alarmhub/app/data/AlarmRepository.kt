package com.alarmhub.app.data

import androidx.room.withTransaction
import com.alarmhub.app.data.db.AlarmDao
import com.alarmhub.app.data.db.AppDatabase
import com.alarmhub.app.data.db.SettingsEntity
import com.alarmhub.app.domain.TimeSource
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.Group
import com.alarmhub.app.domain.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** An alarm together with the group it belongs to — what both the scheduler and the list need. */
data class AlarmWithGroup(val alarm: Alarm, val group: Group?)

/**
 * The single entry point to stored state (PRD §M3.3).
 *
 * Every write that the scheduler has to react to returns the affected alarm ids so the caller can
 * re-register without re-reading the whole table. Cross-table invariants (moving alarms out of a
 * group before deleting it, PRD FR-1.4) are wrapped in a Room transaction so a crash cannot leave
 * alarms pointing at a group that no longer exists.
 */
class AlarmRepository(
    private val database: AppDatabase,
    private val timeSource: TimeSource = TimeSource.system,
) {

    private val dao: AlarmDao = database.alarmDao()

    // ---- reads ---------------------------------------------------------------------------

    suspend fun groups(): List<Group> {
        val counts = dao.alarmCountsByGroup().associate { it.groupId to it.count }
        return dao.groups().map { it.toDomain(counts[it.id] ?: 0) }
    }

    fun groupsFlow(): Flow<List<Group>> = dao.groupsFlow().map { list -> list.map { it.toDomain() } }

    suspend fun group(id: Long): Group? = dao.group(id)?.toDomain(dao.alarmCountIn(id))

    suspend fun alarms(): List<Alarm> = dao.alarms().map { it.toDomain() }

    fun alarmsFlow(): Flow<List<Alarm>> = dao.alarmsFlow().map { list -> list.map { it.toDomain() } }

    suspend fun alarm(id: Long): Alarm? = dao.alarm(id)?.toDomain()

    /** Each alarm with its group, for the scheduler and for building list rows. */
    suspend fun alarmsWithGroups(): List<AlarmWithGroup> {
        val groups = dao.groups().associate { it.id to it.toDomain() }
        return dao.alarms().map { AlarmWithGroup(it.toDomain(), groups[it.groupId]) }
    }

    /**
     * What the scheduler registers: only alarms that can ring at all (PRD §5.1).
     *
     * Expired and switched-off alarms are excluded here rather than inside the rules, because this
     * is also what PRD §5.3 means by 「启用闹钟」 when it counts ring days.
     */
    suspend fun schedulableAlarms(): List<AlarmWithGroup> {
        val groups = dao.groups().associate { it.id to it.toDomain() }
        return dao.schedulableAlarms().map { AlarmWithGroup(it.toDomain(), groups[it.groupId]) }
    }

    suspend fun settings(): Settings = (dao.settings() ?: SettingsEntity()).toDomain()

    fun settingsFlow(): Flow<Settings> = dao.settingsFlow().map { (it ?: SettingsEntity()).toDomain() }

    // ---- writes --------------------------------------------------------------------------

    /** Creates or updates a group. Returns the id, which for an insert is newly generated. */
    suspend fun saveGroup(group: Group): Long {
        val entity = group.toEntity().copy(updatedAt = timeSource.nowMillis())
        return if (entity.id == 0L) {
            dao.insertGroup(entity)
        } else {
            dao.updateGroup(entity)
            entity.id
        }
    }

    /**
     * PRD FR-1.4: deleting a group never deletes its alarms — they move to [moveAlarmsTo].
     *
     * Returns how many alarms were moved, so the caller can report the number it promised the user
     * during the confirmation prompt.
     */
    suspend fun deleteGroup(id: Long, moveAlarmsTo: Long): Int {
        require(id != moveAlarmsTo) { "cannot move a group's alarms into the group being deleted" }
        val target = dao.group(moveAlarmsTo) ?: error("target group $moveAlarmsTo does not exist")
        return database.withTransaction {
            // The destination must exist before the source disappears, or RESTRICT would reject the
            // update and the whole delete would fail.
            check(target.id == moveAlarmsTo)
            dao.moveAlarmsAndDeleteGroup(id, moveAlarmsTo, timeSource.nowMillis())
        }
    }

    /** PRD FR-1.1: reorder. Ids not present are ignored rather than failing the whole reorder. */
    suspend fun reorderGroups(orderedIds: List<Long>) {
        val now = timeSource.nowMillis()
        database.withTransaction {
            orderedIds.forEachIndexed { index, id -> dao.setGroupSortOrder(id, index, now) }
        }
    }

    /** Creates or updates an alarm. Returns the stored alarm so the caller sees generated ids. */
    suspend fun saveAlarm(alarm: Alarm): Alarm {
        val now = timeSource.nowMillis()
        val existing = alarm.id.takeIf { it != 0L }
        val entity = alarm.toEntity().copy(
            updatedAt = now,
            createdAt = if (existing == null) now else alarm.createdAt,
        )
        return if (existing == null) {
            val id = dao.insertAlarm(entity)
            dao.alarm(id)!!.toDomain()
        } else {
            dao.updateAlarm(entity)
            dao.alarm(existing)!!.toDomain()
        }
    }

    suspend fun deleteAlarm(id: Long) = dao.deleteAlarmById(id)

    suspend fun setAlarmEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled, timeSource.nowMillis())

    suspend fun markExpired(id: Long) = dao.markExpired(id, timeSource.nowMillis())

    /**
     * PRD FR-3.6: 响铃后删除. Returns true when a row was actually removed.
     *
     * The SQL carries its own guard (`repeat_type = 'ONCE' AND delete_after_ring = 1`) rather than
     * trusting the caller, because this is the one place in the app that destroys user data: a bug
     * that called it for a repeating alarm, or for a one-off that was not marked, would delete an
     * alarm the user still expects to ring.
     */
    suspend fun deleteIfMarkedForDeletion(id: Long): Boolean = dao.deleteIfMarkedForDeletion(id) > 0

    /** PRD §5.4 bookkeeping: what the currently registered main trigger is set for. */
    suspend fun lastTriggerAt(id: Long): Long? = dao.lastTriggerAt(id)

    suspend fun setLastTriggerAt(id: Long, triggerAt: Long?) = dao.setLastTriggerAt(id, triggerAt)

    /**
     * PRD FR-4.3.4's snooze counter.
     *
     * [recordSnooze] returns the count *after* the increment, so the caller can compare it with the
     * alarm's `snoozeMaxCount` without a second read.
     */
    suspend fun snoozeCount(id: Long): Int = dao.snoozeCount(id) ?: 0

    suspend fun recordSnooze(id: Long): Int {
        val next = snoozeCount(id) + 1
        dao.setSnoozeCount(id, next)
        return next
    }

    /**
     * Clears the snooze counter.
     *
     * Called when a ring starts from a real trigger and whenever the alarm is rescheduled for a
     * later day, so the next morning gets its full allowance again (PRD FR-4.3.4 caps a single
     * ring's snoozes, not the alarm's lifetime).
     */
    suspend fun clearSnoozeCount(id: Long) = dao.setSnoozeCount(id, 0)

    /** PRD FR-3.4: bulk-convert every one-off alarm to 响铃后删除. Returns the affected count. */
    suspend fun bulkSetDeleteAfterRing(): Int = dao.markAllOnceForDeletion(timeSource.nowMillis())

    suspend fun countOnceAlarms(): Pair<Int, Int> =
        dao.countOnceAlarms() to dao.countOnceAlarmsMarkedForDeletion()

    suspend fun updateSettings(patch: (Settings) -> Settings): Settings {
        val updated = patch(settings())
        dao.upsertSettings(updated.toEntity())
        return updated
    }

    /**
     * PRD §5.3 step 4: persist the conversion result. `pauseUntil` is an absolute instant, so the
     * only thing the runtime ever has to do is compare it with "now".
     */
    suspend fun pauseGroup(id: Long, pauseUntil: Long, pausedAt: Long) {
        val group = dao.group(id)?.copy(
            pauseUntil = pauseUntil,
            pausedAt = pausedAt,
            // Mutually exclusive with a permanent disable (PRD FR-2.7).
            permanentlyDisabled = false,
            updatedAt = timeSource.nowMillis(),
        ) ?: error("group $id does not exist")
        dao.updateGroup(group)
    }

    suspend fun pauseAlarm(id: Long, pauseUntil: Long) {
        val alarm = dao.alarm(id)?.copy(
            pauseUntil = pauseUntil,
            // Mutually exclusive with a permanent disable (PRD FR-2.7).
            permanentlyDisabled = false,
            updatedAt = timeSource.nowMillis(),
        ) ?: error("alarm $id does not exist")
        dao.updateAlarm(alarm)
    }

    /** PRD FR-2.6: cancel a pause early, by hand. */
    suspend fun resumeGroup(id: Long) {
        val group = dao.group(id)?.copy(pauseUntil = null, pausedAt = null, updatedAt = timeSource.nowMillis())
            ?: error("group $id does not exist")
        dao.updateGroup(group)
    }

    suspend fun resumeAlarm(id: Long) {
        val alarm = dao.alarm(id)?.copy(pauseUntil = null, updatedAt = timeSource.nowMillis())
            ?: error("alarm $id does not exist")
        dao.updateAlarm(alarm)
    }

    /** PRD FR-2.7: indefinite off, restored only by hand. */
    suspend fun setGroupPermanentDisabled(id: Long, disabled: Boolean) {
        val updated = dao.group(id)?.copy(
            permanentlyDisabled = disabled,
            // PRD FR-2.7 makes the two mutually exclusive: switching a group off indefinitely ends
            // any temporary pause, and switching it back on does not silently restore one.
            pauseUntil = null,
            pausedAt = null,
            updatedAt = timeSource.nowMillis(),
        ) ?: error("group $id does not exist")
        dao.updateGroup(updated)
    }

    suspend fun setAlarmPermanentDisabled(id: Long, disabled: Boolean) {
        val current = dao.alarm(id) ?: error("alarm $id does not exist")
        val updated = current.copy(
            permanentlyDisabled = disabled,
            pauseUntil = if (disabled) null else current.pauseUntil,
            updatedAt = timeSource.nowMillis(),
        )
        dao.updateAlarm(updated)
    }
}
