package app.daycue.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * `daycue.db` (docs/architecture/ANDROID.md §4). Schema version stays 1 until the first install that
 * holds real data; after that every change ships a Migration + the exported schema in `app/schemas/`,
 * and destructive fallback is never enabled.
 */
@Database(
    entities = [
        ConfigCurrentEntity::class,
        ConfigHistoryEntity::class,
        EngineStateEntity::class,
        HistoryEventEntity::class,
        SignalEntity::class,
        CalendarEventCacheEntity::class,
        CommandLogEntity::class,
        AuditLogEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DayCueDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
    abstract fun engineStateDao(): EngineStateDao
    abstract fun historyDao(): HistoryDao
    abstract fun signalDao(): SignalDao
    abstract fun calendarCacheDao(): CalendarCacheDao
    abstract fun commandLogDao(): CommandLogDao
    abstract fun auditDao(): AuditDao

    companion object {
        const val NAME = "daycue.db"

        fun build(context: Context): DayCueDatabase =
            Room.databaseBuilder(context.applicationContext, DayCueDatabase::class.java, NAME)
                // Receivers, services and the UI all share one process-wide instance (AppContainer).
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()

        fun inMemory(context: Context): DayCueDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, DayCueDatabase::class.java).build()
    }
}
