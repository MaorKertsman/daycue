package app.daycue.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * Scaffold-level Room database: only `config_current` exists so the Room + KSP pipeline
 * is exercised by the build. The remaining tables from docs/architecture/ANDROID.md
 * are added by the owning engineers. Schema version stays 1 until the first install
 * that holds real data; after that every change needs a Migration + exported schema.
 */
@Database(
    entities = [ConfigCurrentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DayCueDatabase : RoomDatabase() {
    abstract fun configDao(): ConfigDao
}

/** Single row (id = 1) holding the current DayCueConfig JSON document. */
@Entity(tableName = "config_current")
data class ConfigCurrentEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "version") val version: Long,
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM config_current WHERE id = 1")
    suspend fun current(): ConfigCurrentEntity?

    @Upsert
    suspend fun put(entity: ConfigCurrentEntity)
}
