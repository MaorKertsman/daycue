package app.daycue.ui.today

import app.daycue.system.ReadinessId
import app.daycue.system.ReadinessItem
import app.daycue.system.ReadinessReport
import app.daycue.system.ReadinessStatus
import app.daycue.ui.readiness.ReadinessCopy
import app.daycue.ui.readiness.VoiceIssue
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadinessCopyTest {
    private fun report(vararg rows: Pair<ReadinessId, ReadinessStatus>) =
        ReadinessReport(rows.map { ReadinessItem(it.first, it.second, hasFix = true) }, Instant.EPOCH, null, null)

    @Test
    fun voiceDetailIsWordedNotShown() {
        assertEquals(VoiceIssue.HebrewMissing, VoiceIssue.of("engine=com.google.android.tts;en=Available;he=MissingData;enNet=false;heNet=false"))
        assertEquals(VoiceIssue.EnglishMissing, VoiceIssue.of("engine=x;en=MissingData;he=Available;enNet=false;heNet=false"))
        assertEquals(VoiceIssue.BothMissing, VoiceIssue.of("engine=x;en=NotSupported;he=MissingData"))
        assertEquals(VoiceIssue.NoEngine, VoiceIssue.of("engine=null;en=EngineUnavailable;he=EngineUnavailable"))
        assertEquals(VoiceIssue.NeedsNetwork, VoiceIssue.of("engine=x;en=Available;he=Available;enNet=false;heNet=true"))
        assertEquals(VoiceIssue.None, VoiceIssue.of("engine=x;en=Available;he=Available;enNet=false;heNet=false"))
        assertEquals(VoiceIssue.None, VoiceIssue.of(null))
    }

    @Test
    fun todayShowsOnlyTheMostImportantCriticalProblem() {
        val r = report(
            ReadinessId.Notifications to ReadinessStatus.Ready,
            ReadinessId.ExactAlarms to ReadinessStatus.Limited,
            ReadinessId.BatteryOptimization to ReadinessStatus.Limited,
            ReadinessId.Voices to ReadinessStatus.Limited,
        )
        assertEquals(ReadinessId.ExactAlarms, ReadinessCopy.todayProblem(r, alarmEnabled = false)?.id)
        assertNull(ReadinessCopy.todayProblem(report(ReadinessId.Voices to ReadinessStatus.Limited, ReadinessId.SpeechFailure to ReadinessStatus.Limited), false))
        assertNull(ReadinessCopy.todayProblem(null, true))
    }

    @Test
    fun fullScreenRowOnlyMattersWhenAnAlarmExists() {
        val r = report(ReadinessId.FullScreenIntent to ReadinessStatus.Limited)
        assertNull(ReadinessCopy.todayProblem(r, alarmEnabled = false))
        assertEquals(ReadinessId.FullScreenIntent, ReadinessCopy.todayProblem(r, alarmEnabled = true)?.id)
        assertEquals(0, ReadinessCopy.problemCount(r, alarmEnabled = false))
        assertEquals(1, ReadinessCopy.problemCount(r, alarmEnabled = true))
    }

    @Test
    fun optionalRowsNeverCountAsProblems() {
        val r = report(ReadinessId.SpeechFailure to ReadinessStatus.Limited, ReadinessId.Notifications to ReadinessStatus.Off)
        assertEquals(1, ReadinessCopy.problemCount(r, true))
        // A missing optional voice is not "something that may delay reminders" (UX 3.13).
        val voices = report(ReadinessId.Voices to ReadinessStatus.Limited, ReadinessId.SpeechFailure to ReadinessStatus.Limited)
        assertEquals(0, ReadinessCopy.problemCount(voices, true))
    }
}
