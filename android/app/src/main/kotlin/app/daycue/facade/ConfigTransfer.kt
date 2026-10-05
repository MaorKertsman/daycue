package app.daycue.facade

import app.daycue.data.db.HistoryEventEntity
import app.daycue.domain.config.ConfigCodec
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.Preview
import app.daycue.engine.ConfigDiff
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/** One exported history row (only with "Include history"). */
@Serializable
data class HistoryExport(
    val at: String, val zone: String, val kind: String, val subjectType: String, val subjectId: String,
    val cueId: String? = null, val rule: String, val test: Boolean, val payload: String? = null,
)

/** `*.daycue-backup.json` (ANDROID.md §4 backups, UX §3.14). */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAt: String,
    val config: JsonElement,
    val history: List<HistoryExport>? = null,
) {
    companion object { const val FORMAT = "daycue-backup"; const val CURRENT_FORMAT_VERSION = 1 }
}

sealed interface ImportParse {
    data class Ok(val config: DayCueConfig, val hadHistory: Boolean) : ImportParse
    /**
     * UX §3.16 "This file isn't a DayCue setup". [reason] is a code: `not_json_object`, `missing_config`, `unknown_format`,
     * `missing_schema_version`, `unknown_format_version` (backup wrapper without a valid `formatVersion`),
     * `unsupported_content` (written by a newer version: a type this build does not know), `invalid_document`.
     */
    data class NotDayCue(val reason: String) : ImportParse
    /**
     * Written by a newer DayCue: `config.schemaVersion` newer than this build, or (D4) the backup `formatVersion` newer
     * than [BackupFile.CURRENT_FORMAT_VERSION] ([formatVersion] set). Nothing is imported in either case.
     */
    data class UnsupportedSchema(val schemaVersion: Int, val formatVersion: Int? = null) : ImportParse
}

/**
 * Import never writes directly: it is turned into ops ([ConfigDiff]) that go through `applyOps` +
 * `ConfigValidator` like any edit, with a [Preview] (diff + sensitivity) for the review screen.
 * Imported history is ignored (history is device-local and append-only).
 */
data class ImportPlan(
    val ops: List<ConfigOp>,
    val preview: Preview,
    val baseVersion: Long,
    val containsMedication: Boolean,
) {
    val valid: Boolean get() = preview.valid
    val noChanges: Boolean get() = ops.isEmpty()
}

object ConfigTransfer {
    private val pretty = Json(DayCueJson) { prettyPrint = true; explicitNulls = false }

    /** Export the setup; history only when asked. The result contains medication labels if any exist. */
    fun export(config: DayCueConfig, history: List<HistoryEventEntity>?, now: Instant): String {
        val file = BackupFile(
            exportedAt = now.toString(),
            config = DayCueJson.encodeToJsonElement(DayCueConfig.serializer(), config),
            history = history?.map {
                HistoryExport(Instant.ofEpochMilli(it.occurredAtMs).toString(), it.zoneId, it.kind, it.subjectType, it.subjectId, it.cueId, it.rule, it.isTest, it.payloadJson)
            },
        )
        return pretty.encodeToString(BackupFile.serializer(), file)
    }

    fun parse(text: String): ImportParse {
        val root = runCatching { DayCueJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ImportParse.NotDayCue("not_json_object")
        val wrapped = root["format"]?.jsonPrimitive?.contentOrNull == BackupFile.FORMAT
        val (doc, hadHistory) = when {
            wrapped -> ((root["config"] as? JsonObject) ?: return ImportParse.NotDayCue("missing_config")) to (root["history"] != null)
            root.containsKey("schemaVersion") && root.containsKey("settings") -> root to false
            else -> return ImportParse.NotDayCue("unknown_format")
        }
        val schema = doc["schemaVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return ImportParse.NotDayCue("missing_schema_version")
        if (schema > DayCueConfig.CURRENT_SCHEMA_VERSION) return ImportParse.UnsupportedSchema(schema)
        // D4: the backup wrapper has its own version. Missing / not a positive integer = not a file we understand;
        // newer than this build = written by a newer DayCue (same typed result as a newer schema, with formatVersion set).
        if (wrapped) {
            val fv = runCatching { root["formatVersion"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull()
            if (fv == null || fv < 1) return ImportParse.NotDayCue("unknown_format_version")
            if (fv > BackupFile.CURRENT_FORMAT_VERSION) return ImportParse.UnsupportedSchema(schema, formatVersion = fv)
        }
        // A whole-document decode can still throw (an unknown sealed subtype from a newer app version, or a malformed
        // value): kotlinx closed polymorphism, DOMAIN.md section 4. Report it cleanly; never echo file content.
        val config = try {
            ConfigCodec.decode(doc.toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            return ImportParse.NotDayCue(if ("polymorphic" in m || "subclass" in m || "discriminator" in m) "unsupported_content" else "invalid_document")
        }
        return ImportParse.Ok(config, hadHistory)
    }

    fun plan(current: DayCueConfig, imported: DayCueConfig): ImportPlan {
        val ops = ConfigDiff.ops(current, imported)
        return ImportPlan(ops, ConfigEditor.preview(current, ops), current.version, imported.medications.isNotEmpty())
    }
}
