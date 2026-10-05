package app.daycue.ui.readiness

import androidx.annotation.StringRes
import app.daycue.R
import app.daycue.facade.ContextReadinessId
import app.daycue.system.ReadinessId
import app.daycue.system.ReadinessItem
import app.daycue.system.ReadinessReport
import app.daycue.system.ReadinessStatus

/** Pure mapping from the facade's machine-readable readiness rows to words (the UI words `detail`, APP_API section 7). */
object ReadinessCopy {

    /** Display order on the Readiness screen: what matters most to on-time reminders first. */
    val order: List<ReadinessId> = listOf(
        ReadinessId.Notifications, ReadinessId.BlockedChannels, ReadinessId.ExactAlarms, ReadinessId.FullScreenIntent,
        ReadinessId.BatteryOptimization, ReadinessId.Hibernation, ReadinessId.BackgroundRestricted, ReadinessId.ForceStopped,
        ReadinessId.Voices, ReadinessId.SpeechFailure,
    )

    /** Rows that can make core reminders late or invisible: only these can appear on Today (UX 3.2). */
    private val critical = setOf(
        ReadinessId.Notifications, ReadinessId.BlockedChannels, ReadinessId.ExactAlarms, ReadinessId.BackgroundRestricted,
        ReadinessId.BatteryOptimization, ReadinessId.Hibernation, ReadinessId.FullScreenIntent, ReadinessId.ForceStopped,
    )

    @StringRes fun name(id: ReadinessId): Int = when (id) {
        ReadinessId.Notifications -> R.string.app_rd_notifications
        ReadinessId.ExactAlarms -> R.string.app_rd_exact
        ReadinessId.FullScreenIntent -> R.string.app_rd_fullscreen
        ReadinessId.BatteryOptimization -> R.string.app_rd_battery
        ReadinessId.BackgroundRestricted -> R.string.app_rd_background
        ReadinessId.Hibernation -> R.string.app_rd_hibernation
        ReadinessId.Voices -> R.string.app_rd_voices
        ReadinessId.BlockedChannels -> R.string.app_rd_channels
        ReadinessId.SpeechFailure -> R.string.app_rd_speech
        ReadinessId.ForceStopped -> R.string.app_rd_forcestop
    }

    @StringRes fun consequence(item: ReadinessItem): Int = when (item.id) {
        ReadinessId.Notifications -> R.string.app_rd_notifications_why
        ReadinessId.ExactAlarms -> R.string.app_rd_exact_why
        ReadinessId.FullScreenIntent -> R.string.app_rd_fullscreen_why
        ReadinessId.BatteryOptimization -> R.string.app_rd_battery_why
        ReadinessId.BackgroundRestricted -> R.string.app_rd_background_why
        ReadinessId.Hibernation -> R.string.app_rd_hibernation_why
        ReadinessId.BlockedChannels -> R.string.app_rd_channels_why
        ReadinessId.SpeechFailure -> R.string.app_rd_speech_why
        ReadinessId.ForceStopped -> R.string.app_rd_forcestop_why
        ReadinessId.Voices -> when (VoiceIssue.of(item.detail)) {
            VoiceIssue.HebrewMissing -> R.string.app_rd_voice_he_missing
            VoiceIssue.EnglishMissing -> R.string.app_rd_voice_en_missing
            VoiceIssue.BothMissing -> R.string.app_rd_voice_both_missing
            VoiceIssue.NoEngine -> R.string.app_rd_voice_no_engine
            VoiceIssue.NeedsNetwork -> R.string.app_rd_voice_network
            VoiceIssue.None -> R.string.app_rd_voice_other
        }
    }

    @StringRes fun fixLabel(item: ReadinessItem): Int = when {
        item.id == ReadinessId.Voices && VoiceIssue.of(item.detail) != VoiceIssue.NoEngine -> R.string.app_rd_install
        else -> R.string.action_fix
    }

    /**
     * The one problem Today may show as its last Next row: the most important critical row that is not Ready.
     * [alarmEnabled] gates the full-screen row (it only matters when an alarm exists).
     */
    fun todayProblem(report: ReadinessReport?, alarmEnabled: Boolean): ReadinessItem? {
        if (report == null) return null
        return order.firstNotNullOfOrNull { id ->
            report.items.firstOrNull { it.id == id }?.takeIf {
                it.id in critical && it.status != ReadinessStatus.Ready && it.status != ReadinessStatus.NotNeeded && it.status != ReadinessStatus.Checking &&
                    (it.id != ReadinessId.FullScreenIntent || alarmEnabled)
            }
        }
    }

    /** Rows for the screen in display order; rows the platform does not have are simply absent. */
    fun sorted(report: ReadinessReport): List<ReadinessItem> =
        report.items.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }

    /** Rows that can make core reminders late or invisible; everything else (voices, speech) is optional. */
    fun isCritical(id: ReadinessId): Boolean = id in critical

    /** How many rows may delay reminders: only critical rows count, optional ones (voices, speech) never do. */
    fun problemCount(report: ReadinessReport, alarmEnabled: Boolean): Int = report.items.count {
        it.id in critical && (it.status == ReadinessStatus.Limited || it.status == ReadinessStatus.Off) &&
            (it.id != ReadinessId.FullScreenIntent || alarmEnabled)
    }

    @StringRes fun contextName(id: ContextReadinessId): Int = when (id) {
        ContextReadinessId.PlaceDetection -> R.string.app_rd_place_detection
        ContextReadinessId.PreciseLocation -> R.string.app_rd_precise
        ContextReadinessId.BackgroundLocation -> R.string.app_rd_bg_location
        ContextReadinessId.LocationServices -> R.string.app_rd_location_services
        ContextReadinessId.PlayServices -> R.string.app_rd_play_services
        ContextReadinessId.ActivityRecognition -> R.string.app_rd_activity
        ContextReadinessId.CalendarAccess -> R.string.app_rd_calendar_access
        ContextReadinessId.CalendarSync -> R.string.app_rd_calendar_sync
    }

    @StringRes fun contextWhy(id: ContextReadinessId): Int = when (id) {
        ContextReadinessId.PlaceDetection -> R.string.app_rd_place_detection_why
        ContextReadinessId.PreciseLocation -> R.string.app_rd_precise_why
        ContextReadinessId.BackgroundLocation -> R.string.app_rd_bg_location_why
        ContextReadinessId.LocationServices -> R.string.app_rd_location_services_why
        ContextReadinessId.PlayServices -> R.string.app_rd_play_services_why
        ContextReadinessId.ActivityRecognition -> R.string.app_rd_activity_why
        ContextReadinessId.CalendarAccess -> R.string.app_rd_calendar_access_why
        ContextReadinessId.CalendarSync -> R.string.app_rd_calendar_sync_why
    }
}

/** Voice problems, read from the machine-readable `detail` of the Voices row (`engine=..;en=..;he=..;enNet=..;heNet=..`). */
enum class VoiceIssue {
    None, HebrewMissing, EnglishMissing, BothMissing, NoEngine, NeedsNetwork;

    companion object {
        fun of(detail: String?): VoiceIssue {
            if (detail == null) return None
            val parts = detail.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val en = parts["en"]
            val he = parts["he"]
            val bad = setOf("MissingData", "NotSupported")
            return when {
                en == "EngineUnavailable" || he == "EngineUnavailable" -> NoEngine
                en in bad && he in bad -> BothMissing
                he in bad -> HebrewMissing
                en in bad -> EnglishMissing
                parts["enNet"] == "true" || parts["heNet"] == "true" -> NeedsNetwork
                else -> None
            }
        }
    }
}
