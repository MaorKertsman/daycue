package app.daycue.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Room entities for docs/architecture/ANDROID.md §4. All instants are epoch milliseconds UTC (`*_ms`).
 * Config and engine state are JSON documents (cheap migrations); history tables are relational.
 */

/** Single row (id = 1) holding the current `DayCueConfig` JSON document. */
@Entity(tableName = "config_current")
data class ConfigCurrentEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "version") val version: Long,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    companion object { const val SINGLETON_ID = 1 }
}

/** Previous config documents (undo + audit). Bounded: newest [ConfigHistoryEntity.MAX_ROWS] kept. */
@Entity(tableName = "config_history", indices = [Index("replaced_at_ms")])
data class ConfigHistoryEntity(
    /** Version of the document being replaced. */
    @PrimaryKey @ColumnInfo(name = "version") val version: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "replaced_at_ms") val replacedAtMs: Long,
    /** `ui` / `mcp` / `import` / `undo` / `migration` / `engine` / `debug`. */
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "command_id") val commandId: String? = null,
) {
    companion object { const val MAX_ROWS = 100 }
}

/** Single row (id = 1): the full `EngineState` plus a typed copy of the next wake for receivers. */
@Entity(tableName = "engine_state")
data class EngineStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "config_version") val configVersion: Long,
    @ColumnInfo(name = "next_wake_at_ms") val nextWakeAtMs: Long?,
    /** `alarm_clock` / `exact` / `inexact`. */
    @ColumnInfo(name = "next_wake_precision") val nextWakePrecision: String?,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    companion object { const val SINGLETON_ID = 1 }
}

/**
 * GEN-8 history. `rule` and `is_test` are an extension of the ANDROID.md column list so GEN-10
 * (tests never count) and "why" queries don't need JSON parsing.
 */
@Entity(
    tableName = "history_event",
    indices = [Index("subject_type", "subject_id", "occurred_at_ms"), Index("occurred_at_ms"), Index("cue_id")],
)
data class HistoryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "subject_type") val subjectType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "cue_id") val cueId: String?,
    @ColumnInfo(name = "scheduled_for_ms") val scheduledForMs: Long?,
    @ColumnInfo(name = "payload_json") val payloadJson: String?,
    @ColumnInfo(name = "rule") val rule: String,
    @ColumnInfo(name = "is_test") val isTest: Boolean,
)

/** Latest signal per source. Expired rows are treated as Unknown by the domain, not deleted eagerly. */
@Entity(tableName = "signal")
data class SignalEntity(
    @PrimaryKey @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
    @ColumnInfo(name = "observed_at_ms") val observedAtMs: Long,
    @ColumnInfo(name = "expires_at_ms") val expiresAtMs: Long?,
)

/** Calendar instances (on-device only; titles never exported). Filled by the calendar integration. */
@Entity(tableName = "calendar_event_cache", indices = [Index("begin_ms", "end_ms")])
data class CalendarEventCacheEntity(
    @PrimaryKey @ColumnInfo(name = "instance_key") val instanceKey: String,
    @ColumnInfo(name = "calendar_id") val calendarId: Long,
    @ColumnInfo(name = "begin_ms") val beginMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "all_day") val allDay: Boolean,
    @ColumnInfo(name = "busy") val busy: Boolean,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "fetched_at_ms") val fetchedAtMs: Long,
)

/** MCP commands, at-most-once apply. Filled by the relay integration. */
@Entity(tableName = "command_log", indices = [Index("state")])
data class CommandLogEntity(
    @PrimaryKey @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
    @ColumnInfo(name = "origin") val origin: String,
    @ColumnInfo(name = "base_version") val baseVersion: Long,
    @ColumnInfo(name = "ops_json") val opsJson: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "result_json") val resultJson: String?,
    @ColumnInfo(name = "applied_version") val appliedVersion: Long?,
    @ColumnInfo(name = "acked_at_ms") val ackedAtMs: Long?,
)

@Entity(tableName = "audit_log", indices = [Index("at_ms")])
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    /** `user`, `mcp:<client-label>`, `system`. */
    @ColumnInfo(name = "actor") val actor: String,
    /** e.g. `config.apply`, `config.undo`, `config.import`. */
    @ColumnInfo(name = "action") val action: String,
    @ColumnInfo(name = "sensitivity") val sensitivity: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "version_before") val versionBefore: Long?,
    @ColumnInfo(name = "version_after") val versionAfter: Long?,
    @ColumnInfo(name = "command_id") val commandId: String?,
)
