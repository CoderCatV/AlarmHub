package com.alarmhub.app.data

import androidx.room.withTransaction
import com.alarmhub.app.data.db.AlarmDao
import com.alarmhub.app.data.db.AppDatabase
import com.alarmhub.app.data.db.GroupEntity
import com.alarmhub.app.data.db.SettingsEntity
import com.alarmhub.app.domain.TimeSource
import com.alarmhub.app.domain.model.Alarm
import com.alarmhub.app.domain.model.Group
import com.alarmhub.app.domain.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The built-in groups of PRD FR-1.2.
 *
 * Deliberately constants, not instances: the ids are load-bearing. `未分组` is the group FR-1.4
 * moves alarms into when another group is deleted and the one FR-1.3 defaults new alarms to, so
 * the repository and the M6 bridge both need to name it without a lookup.
 */
object BuiltInGroups {

    /** 未分组 — the default group; 不可删除. */
    const val UNGROUPED_ID = 1L

    /** 工作日. */
    const val WORKDAY_ID = 2L

    /** 节假日. */
    const val HOLIDAY_ID = 3L

    val all: List<GroupEntity> = listOf(
        GroupEntity(
            id = UNGROUPED_ID,
            name = "未分组",
            color = 0xff8b97a8.toInt(),
            sortOrder = 0,
            isSystem = true,
        ),
        GroupEntity(
            id = WORKDAY_ID,
            name = "工作日",
            color = 0xff4c9dff.toInt(),
            sortOrder = 1,
            isSystem = true,
        ),
        GroupEntity(
            id = HOLIDAY_ID,
            name = "节假日",
            color = 0xffff8a5c.toInt(),
            sortOrder = 2,
            isSystem = true,
        ),
    )

    /**
     * Creates the three built-ins and the default settings row, exactly once.
     *
     * Idempotent on purpose: it runs from `Application.onCreate` on every process start, and a
     * second process (or a crash-restart) must not create a duplicate 未分组. The guard is "any
     * group at all exists" — a user who deleted every group they were allowed to still has the
     * three built-ins.
     */
    suspend fun seedIfEmpty(database: AppDatabase) {
        val dao = database.alarmDao()
        if (dao.groupCount() > 0) {
            // Still make sure a settings row exists: an install that predates it must not crash
            // on the first getSettings().
            if (dao.settings() == null) dao.upsertSettings(SettingsEntity())
            return
        }

        database.withTransaction {
            for (group in all) dao.insertGroup(group)
            dao.upsertSettings(SettingsEntity())
        }
    }
}
