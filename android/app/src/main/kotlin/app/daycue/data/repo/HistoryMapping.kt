package app.daycue.data.repo

import app.daycue.data.db.HistoryEventEntity
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.engine.HistoryEntry
import kotlinx.serialization.Serializable
import java.time.ZoneId

/** Subject of a history row, parsed from the engine's item key (`habit:<id>`, `med:<id>|<date>|<time>`, ...). */
data class Subject(val type: String, val id: String)

@Serializable
data class HistoryPayload(val detail: Map<String, String> = emptyMap(), val itemKey: String)

object HistoryMapping {

    fun subjectOf(itemKey: String): Subject {
        val prefix = itemKey.substringBefore(':', "")
        val rest = itemKey.substringAfter(':', itemKey)
        return when (prefix) {
            "habit" -> Subject("habit", rest)
            "med" -> if (rest == "merged" || rest == "policy") Subject("medication", rest) else Subject("medication", rest.substringBefore('|'))
            "routine", "routine-prompt" -> Subject("routine", rest)
            "alarm" -> Subject("alarm", rest)
            "cal" -> Subject("calendar", rest)
            "session" -> Subject("session", rest)
            "preview" -> Subject("test", rest)
            "" -> if (itemKey == "posture") Subject("posture", "posture") else Subject("system", itemKey)
            else -> Subject("system", itemKey)
        }
    }

    fun toEntity(e: HistoryEntry, zone: ZoneId): HistoryEventEntity {
        val subject = subjectOf(e.itemKey)
        return HistoryEventEntity(
            occurredAtMs = e.at.toEpochMilli(),
            zoneId = zone.id,
            kind = e.kind.name,
            subjectType = subject.type,
            subjectId = subject.id,
            cueId = e.cueId,
            scheduledForMs = null,
            payloadJson = DayCueJson.encodeToString(HistoryPayload.serializer(), HistoryPayload(e.detail, e.itemKey)),
            rule = e.rule,
            isTest = e.test,
        )
    }
}
