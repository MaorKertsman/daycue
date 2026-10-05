package app.daycue.data.repo

import app.daycue.domain.engine.HistoryEntry
import app.daycue.domain.engine.HistoryKind
import java.time.Instant
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class HistoryMappingTest {
    private val recorded = Instant.parse("2026-10-05T10:05:00Z")
    private val taken = Instant.parse("2026-10-05T08:05:00Z")

    @Test
    fun `Taken row shows the entered taken-at and keeps recorded-at separately`() {
        val t = takenTimes("Taken", recorded.toEpochMilli(), mapOf("takenAt" to taken.toString()))!!
        assertEquals(taken, t.takenAt)
        assertEquals(recorded, t.recordedAt)
        assertTrue(t.differs)
    }

    @Test
    fun `old Taken rows without takenAt fall back to the row instant`() {
        val t = takenTimes("Taken", recorded.toEpochMilli(), emptyMap())!!
        assertEquals(recorded, t.takenAt)
        assertFalse(t.differs)
    }

    @Test
    fun `only taken outcomes have taken times`() {
        assertNull(takenTimes("Delivered", recorded.toEpochMilli(), mapOf("takenAt" to taken.toString())))
        assertNull(takenTimes("Corrected", recorded.toEpochMilli(), mapOf("to" to "Skipped")))
        assertEquals(taken, takenTimes("Corrected", recorded.toEpochMilli(), mapOf("to" to "Taken", "takenAt" to taken.toString()))!!.takenAt)
    }

    @Test
    fun `engine history entry detail survives the Room payload round trip`() {
        val e = HistoryEntry(at = recorded, itemKey = "med:m|2026-10-05|08:00", kind = HistoryKind.Taken, rule = "MED-2", detail = mapOf("takenAt" to taken.toString()))
        val row = HistoryMapping.toEntity(e, ZoneId.of("UTC"))
        assertEquals(recorded.toEpochMilli(), row.occurredAtMs)
        assertEquals(taken, takenTimes(row.kind, row.occurredAtMs, historyDetail(row.payloadJson))!!.takenAt)
    }
}
