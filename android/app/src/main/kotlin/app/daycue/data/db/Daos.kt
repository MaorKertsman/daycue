package app.daycue.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM config_current WHERE id = 1")
    suspend fun current(): ConfigCurrentEntity?

    @Query("SELECT * FROM config_current WHERE id = 1")
    fun observe(): Flow<ConfigCurrentEntity?>

    @Upsert
    suspend fun put(entity: ConfigCurrentEntity)

    @Insert
    suspend fun insertHistory(entity: ConfigHistoryEntity)

    @Query("SELECT * FROM config_history ORDER BY version DESC LIMIT 1")
    suspend fun latestHistory(): ConfigHistoryEntity?

    @Query("SELECT * FROM config_history ORDER BY version DESC LIMIT 1")
    fun observeLatestHistory(): Flow<ConfigHistoryEntity?>

    @Query("SELECT * FROM config_history ORDER BY version DESC LIMIT :limit")
    suspend fun history(limit: Int): List<ConfigHistoryEntity>

    @Query("DELETE FROM config_history WHERE version = :version")
    suspend fun deleteHistory(version: Long)

    @Query("DELETE FROM config_history WHERE version NOT IN (SELECT version FROM config_history ORDER BY version DESC LIMIT :keep)")
    suspend fun trimHistory(keep: Int)

    @Query("SELECT COUNT(*) FROM config_history")
    suspend fun historyCount(): Int
}

@Dao
interface EngineStateDao {
    @Query("SELECT * FROM engine_state WHERE id = 1")
    suspend fun current(): EngineStateEntity?

    @Query("SELECT * FROM engine_state WHERE id = 1")
    fun observe(): Flow<EngineStateEntity?>

    @Upsert
    suspend fun put(entity: EngineStateEntity)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertAll(rows: List<HistoryEventEntity>)

    @Query("SELECT * FROM history_event ORDER BY occurred_at_ms DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<HistoryEventEntity>>

    @Query("SELECT * FROM history_event WHERE occurred_at_ms >= :fromMs AND occurred_at_ms < :toMs ORDER BY occurred_at_ms ASC, id ASC")
    suspend fun between(fromMs: Long, toMs: Long): List<HistoryEventEntity>

    @Query("SELECT * FROM history_event WHERE subject_type = :type AND subject_id = :id ORDER BY occurred_at_ms DESC, id DESC LIMIT :limit")
    fun observeFor(type: String, id: String, limit: Int): Flow<List<HistoryEventEntity>>

    @Query("SELECT * FROM history_event ORDER BY occurred_at_ms ASC, id ASC")
    suspend fun all(): List<HistoryEventEntity>

    @Query("DELETE FROM history_event WHERE subject_type = :type AND occurred_at_ms < :beforeMs")
    suspend fun deleteOlder(type: String, beforeMs: Long): Int

    @Query("DELETE FROM history_event WHERE subject_type != 'medication' AND occurred_at_ms < :beforeMs")
    suspend fun deleteOlderExceptMedication(beforeMs: Long): Int
}

@Dao
interface SignalDao {
    @Upsert
    suspend fun put(entity: SignalEntity)

    @Query("SELECT * FROM signal")
    suspend fun all(): List<SignalEntity>
}

@Dao
interface CalendarCacheDao {
    @Upsert
    suspend fun putAll(rows: List<CalendarEventCacheEntity>)

    @Query("SELECT * FROM calendar_event_cache WHERE end_ms >= :fromMs AND begin_ms < :toMs ORDER BY begin_ms")
    suspend fun between(fromMs: Long, toMs: Long): List<CalendarEventCacheEntity>

    @Query("DELETE FROM calendar_event_cache")
    suspend fun clear()
}

@Dao
interface CommandLogDao {
    @Upsert
    suspend fun put(entity: CommandLogEntity)

    @Query("SELECT * FROM command_log WHERE command_id = :id")
    suspend fun get(id: String): CommandLogEntity?
}

@Dao
interface AuditDao {
    @Insert
    suspend fun insert(entity: AuditLogEntity)

    @Query("SELECT * FROM audit_log ORDER BY at_ms DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditLogEntity>>

    @Query("SELECT * FROM audit_log ORDER BY at_ms DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditLogEntity>
}
