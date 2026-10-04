package com.alarmhub.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
/**
 * The schema-2 migration is the one code path that touches every existing alarm row, and this database
 * holds the user's only copy of them. A destructive fallback would have been one line and no test.
 *
 * **Why this does not use `MigrationTestHelper`.** Room's helper needs `room-testing`, which drags in
 * `room-migration` and therefore `kotlinx-serialization-json`, and on this project's dependency graph
 * that family cannot be kept version-consistent: the app's classpath resolves
 * `kotlinx-serialization-core` to a different version than `room-migration` was compiled against, so
 * the helper dies with
 *
 * ```
 * AbstractMethodError: abstract method "KSerializer[] GeneratedSerializer.typeParametersSerializers()"
 *     on receiver ... FieldBundle$$serializer
 * ```
 *
 * rather than reporting anything about the migration. Dropping the dependency and building the v1
 * database with the same SQL the helper would have used tests the *production* path more directly:
 * a real on-disk file written at version 1, opened by the real [AppDatabase.build] with the real
 * [AppDatabase.MIGRATIONS]. What it does not give is Room's automatic "does the migrated schema match
 * the entities" comparison, so the read-back below asserts the moved columns by hand.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbFile: File get() = context.getDatabasePath(TEST_DB_NAME)

    private companion object {
        /**
         * A scratch file, deliberately **not** [AppDatabase.NAME].
         *
         * This test deletes its database in `@After`. While it pointed at the production file that
         * was harmless only because nothing but the seeding ever wrote a row; from M6 the H5
         * interface saves real alarms, so the same `@After` would delete the user's data every time
         * the instrumented suite ran.
         */
        const val TEST_DB_NAME = "migration-test.db"
    }

    @After
    fun tearDown() {
        // Each test starts from a clean file; a leftover from a previous run would silently change
        // what "migrating" means.
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { it.delete() }
    }

    /**
     * 1 → 2 keeps the rows and gives them the new column's default.
     *
     * The v1 rows are inserted with raw SQL against the committed v1 schema, because the v1 entities
     * no longer exist in this source tree — which is the point: this exercises the schema as it
     * actually was on a user's device.
     */
    @Test
    fun migratingFrom1To2KeepsAlarmsAndDefaultsTheSnoozeCounter() {
        writeVersion1Database()

        // Opening through the real builder is what runs MIGRATION_1_2 for real.
        val database = AppDatabase.build(context, TEST_DB_NAME)
        try {
            val dao = database.alarmDao()
            val groups = runBlocking(Dispatchers.IO) { dao.groups() }
            assertEquals("the group must survive", 1, groups.size)
            assertEquals("未分组", groups.single().name)

            val alarm = runBlocking(Dispatchers.IO) { dao.alarm(7L) }
            assertNotNull("the alarm must survive the migration", alarm)
            with(alarm!!) {
                assertEquals(7, hour)
                assertEquals(5, minute)
                assertEquals("起床上班", label)
                assertEquals(123_456_789L, lastTriggerAt)
                // The whole point of migration 1 → 2.
                assertEquals("the new column takes its default", 0, snoozeCount)
                assertEquals(3, snoozeMaxCount)
            }
        } finally {
            database.close()
        }
    }

    /**
     * 3 → 4 adds `settings.pre_alert_notified_at` (PRD FR-7.10's "only announce once") and must leave
     * everything else exactly as it was.
     *
     * The interesting assertion is `null`: the column is nullable *on purpose*, because "never
     * announced" has to stay distinguishable from "announced at 0". If the migration had given it a
     * `DEFAULT 0`, every upgrading install would read `0` as a previously announced instant and stay
     * silent until the user's next alarm fired — a bug that looks like "the feature does not work" and
     * would be traced from the wrong end.
     */
    @Test
    fun migratingFrom3To4AddsThePreAlertColumnWithoutClaimingAnythingWasAnnounced() {
        writeVersion3Database()

        val database = AppDatabase.build(context, TEST_DB_NAME)
        try {
            val sqlite = database.openHelper.readableDatabase
            sqlite.query("SELECT pre_alert_notified_at FROM settings LIMIT 1").use { cursor ->
                assertEquals(
                    "the v4 settings column must be selectable",
                    0,
                    cursor.getColumnIndexOrThrow("pre_alert_notified_at"),
                )
                assertTrue("the migrated row must exist", cursor.moveToFirst())
                assertTrue(
                    "nothing has been announced yet, so the value must be NULL rather than 0",
                    cursor.isNull(0),
                )
            }

            // And the row's other values survived: a migration that reset the settings would be a much
            // quieter disaster than one that lost alarms.
            val settings = runBlocking(Dispatchers.IO) { database.alarmDao().settings() }
            assertNotNull("the settings row must survive", settings)
            assertEquals("贪睡 must keep the value the user had", false, settings!!.snoozeEnabled)
            assertEquals("the pause default must survive", 3, settings.defaultPauseDays)
        } finally {
            database.close()
        }
    }

    /**
     * A database created fresh at the current version must be usable, i.e. the v2 schema includes the
     * snooze counter.
     *
     * **What this asserts, and what its first version wrongly asserted.** Instrumentation runs inside
     * the app's own process, so `AlarmHubApp.onCreate` has already seeded the built-in groups. M4
     * noticed that and stopped asserting "no groups exist" — but left `assertNull(dao.alarm(1L))` in
     * place, on the implicit assumption that nothing ever creates an alarm except a test. That
     * assumption died at M6: the H5 interface now writes real alarms, so this test failed the first
     * time an alarm saved from the UI happened to have id 1. It was never a product defect; it was an
     * assertion about the user's data dressed up as an assertion about the schema.
     *
     * The version below asserts the schema only, and is therefore independent of what is in the
     * table: preparing `SELECT snooze_count` at all requires the v2 column to exist, and the column
     * index lookup proves the statement resolved it. It also runs against [TEST_DB_NAME] rather than
     * the live file — see [AppDatabase.build].
     *
     * The settings half is schema-only for the same reason, and it is the stricter reading: this is a
     * scratch file, so the app's startup seeding has *not* touched it and there genuinely is no
     * settings row. Asserting "a row exists" here would be re-asserting the seeding, not the schema.
     */
    @Test
    fun aFreshDatabaseHasTheSnoozeColumnAndSettings() {
        val database = AppDatabase.build(context, TEST_DB_NAME)
        try {
            val sqlite = database.openHelper.readableDatabase
            // A v1 schema would fail to prepare either statement: "no such column".
            sqlite.query("SELECT snooze_count FROM alarms LIMIT 1").use { cursor ->
                assertEquals(
                    "the v2 alarms column must be selectable",
                    0,
                    cursor.getColumnIndexOrThrow("snooze_count"),
                )
            }
            sqlite.query("SELECT permission_check_done FROM settings LIMIT 1").use { cursor ->
                assertEquals(
                    "the settings table must be selectable",
                    0,
                    cursor.getColumnIndexOrThrow("permission_check_done"),
                )
            }
            // Added at v4 (PRD FR-7.10).
            sqlite.query("SELECT pre_alert_notified_at FROM settings LIMIT 1").use { cursor ->
                assertEquals(
                    "the v4 settings column must be selectable",
                    0,
                    cursor.getColumnIndexOrThrow("pre_alert_notified_at"),
                )
            }
        } finally {
            database.close()
        }
    }
    /**
     * Writes a v1 database using the schema exactly as `app/schemas/…/1.json` describes it.
     *
     * Kept as literal SQL rather than derived from the JSON: the point of the test is that this SQL
     * is independent of the current entities, so a change to an entity cannot quietly change what
     * "v1" means.
     */
    private fun writeVersion1Database() {
        dbFile.parentFile?.mkdirs()
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { it.delete() }

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // The v1 schema, verbatim from app/schemas/.../1.json.
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alarm_groups` (
                            `id` INTEGER NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL,
                            `sort_order` INTEGER NOT NULL, `is_system` INTEGER NOT NULL,
                            `permanently_disabled` INTEGER NOT NULL, `pause_until` INTEGER,
                            `paused_at` INTEGER, `updated_at` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_groups_sort_order` ON `alarm_groups` (`sort_order`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alarms` (
                            `id` INTEGER NOT NULL, `group_id` INTEGER NOT NULL, `hour` INTEGER NOT NULL,
                            `minute` INTEGER NOT NULL, `label` TEXT NOT NULL, `repeat_type` TEXT NOT NULL,
                            `repeat_days` INTEGER NOT NULL, `once_date` TEXT, `ringtone_uri` TEXT,
                            `ringtone_name` TEXT, `vibrate` INTEGER NOT NULL, `fade_in_seconds` INTEGER NOT NULL,
                            `snooze_enabled` INTEGER NOT NULL, `snooze_minutes` INTEGER NOT NULL,
                            `snooze_max_count` INTEGER NOT NULL, `auto_stop_minutes` INTEGER NOT NULL,
                            `delete_after_ring` INTEGER NOT NULL, `enabled` INTEGER NOT NULL,
                            `permanently_disabled` INTEGER NOT NULL, `pause_until` INTEGER,
                            `expired` INTEGER NOT NULL, `last_trigger_at` INTEGER,
                            `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`group_id`) REFERENCES `alarm_groups`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarms_group_id` ON `alarms` (`group_id`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_alarms_enabled_permanently_disabled` " +
                            "ON `alarms` (`enabled`, `permanently_disabled`)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `settings` (
                            `id` INTEGER NOT NULL, `default_delete_once_after_ring` INTEGER NOT NULL,
                            `default_pause_days` INTEGER NOT NULL, `default_snooze_minutes` INTEGER NOT NULL,
                            `default_snooze_max_count` INTEGER NOT NULL, `default_auto_stop_minutes` INTEGER NOT NULL,
                            `default_ringtone_uri` TEXT, `default_ringtone_name` TEXT,
                            `default_fade_in_seconds` INTEGER NOT NULL, `time_format` TEXT NOT NULL,
                            `theme` TEXT NOT NULL, `volume_key_action` TEXT NOT NULL,
                            `permission_check_done` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helper.use { h ->
            val db = h.writableDatabase
            db.execSQL(
                """
                INSERT INTO alarm_groups (id, name, color, sort_order, is_system, permanently_disabled,
                                          pause_until, paused_at, updated_at)
                VALUES (1, '未分组', 4287334312, 0, 1, 0, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO alarms (id, group_id, hour, minute, label, repeat_type, repeat_days, once_date,
                                    ringtone_uri, ringtone_name, vibrate, fade_in_seconds, snooze_enabled,
                                    snooze_minutes, snooze_max_count, auto_stop_minutes, delete_after_ring,
                                    enabled, permanently_disabled, pause_until, expired, last_trigger_at,
                                    created_at, updated_at)
                VALUES (7, 1, 7, 5, '起床上班', 'WORKDAY', 0, NULL,
                        NULL, NULL, 1, 5, 1,
                        10, 3, 10, 0,
                        1, 0, NULL, 0, 123456789,
                        111, 222)
                """.trimIndent(),
            )
        }
    }

    /**
     * Writes a v3 database: the v1 schema plus the two columns that shipped after it
     * (`alarms.snooze_count` from M4, `settings.snooze_enabled` from M8).
     *
     * Same reasoning as [writeVersion1Database] — literal SQL for the schema as it actually was on a
     * device, not derived from today's entities. The settings row is inserted with `snooze_enabled = 0`
     * so the test can prove the migration preserves a value the user chose, which a "reset to defaults"
     * migration would quietly destroy.
     */
    private fun writeVersion3Database() {
        dbFile.parentFile?.mkdirs()
        listOf(dbFile, File("${dbFile.path}-wal"), File("${dbFile.path}-shm")).forEach { it.delete() }

        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // The v3 schema, verbatim from app/schemas/.../3.json.
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alarm_groups` (
                            `id` INTEGER NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL,
                            `sort_order` INTEGER NOT NULL, `is_system` INTEGER NOT NULL,
                            `permanently_disabled` INTEGER NOT NULL, `pause_until` INTEGER,
                            `paused_at` INTEGER, `updated_at` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_groups_sort_order` ON `alarm_groups` (`sort_order`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alarms` (
                            `id` INTEGER NOT NULL, `group_id` INTEGER NOT NULL, `hour` INTEGER NOT NULL,
                            `minute` INTEGER NOT NULL, `label` TEXT NOT NULL, `repeat_type` TEXT NOT NULL,
                            `repeat_days` INTEGER NOT NULL, `once_date` TEXT, `ringtone_uri` TEXT,
                            `ringtone_name` TEXT, `vibrate` INTEGER NOT NULL, `fade_in_seconds` INTEGER NOT NULL,
                            `snooze_enabled` INTEGER NOT NULL, `snooze_minutes` INTEGER NOT NULL,
                            `snooze_max_count` INTEGER NOT NULL, `auto_stop_minutes` INTEGER NOT NULL,
                            `delete_after_ring` INTEGER NOT NULL, `enabled` INTEGER NOT NULL,
                            `permanently_disabled` INTEGER NOT NULL, `pause_until` INTEGER,
                            `expired` INTEGER NOT NULL, `snooze_count` INTEGER NOT NULL DEFAULT 0,
                            `last_trigger_at` INTEGER,
                            `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`group_id`) REFERENCES `alarm_groups`(`id`)
                                ON UPDATE NO ACTION ON DELETE RESTRICT
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alarms_group_id` ON `alarms` (`group_id`)")
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_alarms_enabled_permanently_disabled` " +
                            "ON `alarms` (`enabled`, `permanently_disabled`)",
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `settings` (
                            `id` INTEGER NOT NULL, `default_delete_once_after_ring` INTEGER NOT NULL,
                            `default_pause_days` INTEGER NOT NULL, `default_snooze_minutes` INTEGER NOT NULL,
                            `default_snooze_max_count` INTEGER NOT NULL, `snooze_enabled` INTEGER NOT NULL DEFAULT 1,
                            `default_auto_stop_minutes` INTEGER NOT NULL,
                            `default_ringtone_uri` TEXT, `default_ringtone_name` TEXT,
                            `default_fade_in_seconds` INTEGER NOT NULL, `time_format` TEXT NOT NULL,
                            `theme` TEXT NOT NULL, `volume_key_action` TEXT NOT NULL,
                            `permission_check_done` INTEGER NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        helper.use { h ->
            val db = h.writableDatabase
            db.execSQL(
                """
                INSERT INTO alarm_groups (id, name, color, sort_order, is_system, permanently_disabled,
                                          pause_until, paused_at, updated_at)
                VALUES (1, '未分组', 4287334312, 0, 1, 0, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO alarms (id, group_id, hour, minute, label, repeat_type, repeat_days, once_date,
                                    ringtone_uri, ringtone_name, vibrate, fade_in_seconds, snooze_enabled,
                                    snooze_minutes, snooze_max_count, auto_stop_minutes, delete_after_ring,
                                    enabled, permanently_disabled, pause_until, expired, snooze_count,
                                    last_trigger_at, created_at, updated_at)
                VALUES (7, 1, 7, 5, '起床上班', 'WORKDAY', 0, NULL,
                        NULL, NULL, 1, 5, 1,
                        10, 3, 10, 0,
                        1, 0, NULL, 0, 0,
                        123456789,
                        111, 222)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO settings (id, default_delete_once_after_ring, default_pause_days,
                                      default_snooze_minutes, default_snooze_max_count, snooze_enabled,
                                      default_auto_stop_minutes, default_ringtone_uri, default_ringtone_name,
                                      default_fade_in_seconds, time_format, theme, volume_key_action,
                                      permission_check_done)
                VALUES (1, 1, 3, 10, 3, 0, 10, NULL, NULL, 5, '24', 'system', 'dismiss', 1)
                """.trimIndent(),
            )
        }
    }
}
