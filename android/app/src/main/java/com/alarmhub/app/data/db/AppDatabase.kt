package com.alarmhub.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.alarmhub.app.domain.model.RepeatType

/** Stores [RepeatType] by name so the column is readable in sqlite3 and reorder-safe. */
class Converters {
    @TypeConverter
    fun repeatTypeToString(value: RepeatType): String = value.name

    @TypeConverter
    fun stringToRepeatType(value: String): RepeatType =
        RepeatType.entries.firstOrNull { it.name == value }
            ?: error("unknown repeat_type '$value' in the database")
}

@Database(
    entities = [GroupEntity::class, AlarmEntity::class, SettingsEntity::class],
    version = AppDatabase.VERSION,
    // Exported to app/schemas and committed: it is what makes a later migration reviewable.
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun alarmDao(): AlarmDao

    companion object {
        /**
         * 1 → the M3 schema (groups, alarms, settings, `last_trigger_at`).
         * 2 → M4 adds `alarms.snooze_count` (PRD FR-4.3.4's snooze limit must survive a process
         *     restart mid-ring).
         * 3 → M8 adds `settings.snooze_enabled`: a global 贪睡 switch, for a user who does not use
         *     snooze at all. `DEFAULT 1` keeps every existing row behaving exactly as before.
         * 4 → adds `settings.pre_alert_notified_at`: the ring instant the "alarm is coming up"
         *     notification was last posted for (PRD FR-7.10.2's "only once"). Nullable on purpose —
         *     `NULL` reads as "nothing has been announced yet", which is the correct state for every
         *     existing install.
         */
        const val VERSION = 4
        const val NAME = "alarmhub.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // NOT NULL with a DEFAULT so existing rows get a valid value in one pass.
                db.execSQL("ALTER TABLE alarms ADD COLUMN snooze_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Same shape as 1→2: NOT NULL plus a DEFAULT, so the single settings row gets a valid
                // value without a second pass, and `1` = snooze on = the behaviour before this column
                // existed. Getting that default wrong would silently disable 贪睡 for everyone on
                // upgrade, which is why MigrationTest asserts the value rather than just the schema.
                db.execSQL("ALTER TABLE settings ADD COLUMN snooze_enabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Nullable, with no DEFAULT: the column records *which* ring instant was announced, and
                // "never announced" has to stay distinguishable from "announced at 0". Adding it as
                // NULL means an upgrading install announces the next alarm normally instead of staying
                // silent forever.
                db.execSQL("ALTER TABLE settings ADD COLUMN pre_alert_notified_at INTEGER")
            }
        }

        /**
         * Every migration the app has ever shipped, applied to an on-disk database.
         *
         * A real migration rather than `fallbackToDestructiveMigration()`: this database holds the
         * user's only copy of their alarms, so a schema change must never silently delete them.
         *
         * Declared *after* the migrations it lists, because a companion object's properties are
         * initialised in source order and referring to one before its declaration is a compile error.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * Opens (and creates) the database.
         *
         * Write-ahead logging is enabled per PRD §M3.1. Room sets a `journal_mode` in its own
         * `RoomOpenHelper`, so `setJournalMode` here is the supported way to ask; it is re-applied
         * on every open rather than once at creation, because a `VACUUM` or a restore can reset it.
         *
         * @param name the file to open. Production always takes the default; the parameter exists so
         *   the migration instrumentation test can run the **same builder** against a scratch file
         *   instead of the live one. Before M6 the test used [NAME] and deleted it in `@After`, which
         *   was merely untidy while nothing but the seeding ever wrote an alarm — and became
         *   "running the instrumented suite deletes the user's alarms" the moment the H5 interface
         *   could really save one.
         */
        fun build(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
