package app.daycue.facade

import app.daycue.AppContainer
import app.daycue.integrations.calendar.CalendarSyncStatus
import app.daycue.integrations.location.LocationGrant
import app.daycue.integrations.location.PlaceDetectionMode
import app.daycue.system.ReadinessStatus
import java.time.Duration
import java.time.Instant

/**
 * Readiness rows for the optional context producers (separate from `ReadinessReport`, whose ids live in
 * `system/`; optional integrations never count as problems for core reminders). [detail] is
 * machine-readable; the UI words it.
 */
enum class ContextReadinessId { PlaceDetection, PreciseLocation, BackgroundLocation, LocationServices, PlayServices, ActivityRecognition, CalendarAccess, CalendarSync }

data class ContextReadinessItem(val id: ContextReadinessId, val status: ReadinessStatus, val detail: String? = null, val fix: ContextFix? = null)

/** What the row's button does: request a runtime permission (UI) or open a system screen ([PlacesFacade.fixIntent]). */
enum class ContextFix { RequestForegroundLocation, RequestPreciseLocation, RequestBackgroundLocation, RequestActivityRecognition, RequestCalendar, LocationSettings, PlayServices, AppSettings, SyncNow }

data class ContextReadinessReport(val items: List<ContextReadinessItem>, val checkedAt: Instant)

internal object ContextReadinessCheck {
    suspend fun check(c: AppContainer): ContextReadinessReport {
        val st = c.location.sync(force = false, reason = "readiness")
        val a = c.location.access.value
        val cfg = c.host.ensureLoaded()
        val hasPlaces = cfg.config.places.any { it.active }
        val items = mutableListOf<ContextReadinessItem>()
        fun nn(s: ReadinessStatus) = if (hasPlaces) s else ReadinessStatus.NotNeeded

        items += ContextReadinessItem(ContextReadinessId.PlaceDetection,
            when { !hasPlaces -> ReadinessStatus.NotNeeded; st.mode == PlaceDetectionMode.Automatic && st.registered > 0 -> ReadinessStatus.Ready; st.mode == PlaceDetectionMode.Paused -> ReadinessStatus.Limited; else -> ReadinessStatus.Off },
            "mode=${st.mode};registered=${st.registered};detail=${st.detail}" + if (st.skippedPlaceIds.isNotEmpty()) ";skipped=${st.skippedPlaceIds.size}" else "")
        items += ContextReadinessItem(ContextReadinessId.PlayServices, if (a.playServices) ReadinessStatus.Ready else nn(ReadinessStatus.Off), fix = ContextFix.PlayServices.takeIf { !a.playServices })
        items += ContextReadinessItem(ContextReadinessId.PreciseLocation,
            when (a.foreground) { LocationGrant.Precise -> ReadinessStatus.Ready; LocationGrant.Approximate -> nn(ReadinessStatus.Limited); LocationGrant.None -> nn(ReadinessStatus.Off) },
            "grant=${a.foreground}", fix = when (a.foreground) { LocationGrant.None -> ContextFix.RequestForegroundLocation; LocationGrant.Approximate -> ContextFix.RequestPreciseLocation; else -> null })
        items += ContextReadinessItem(ContextReadinessId.BackgroundLocation, if (a.background) ReadinessStatus.Ready else nn(ReadinessStatus.Limited),
            fix = ContextFix.RequestBackgroundLocation.takeIf { !a.background && a.foreground == LocationGrant.Precise })
        items += ContextReadinessItem(ContextReadinessId.LocationServices, if (a.locationEnabled) ReadinessStatus.Ready else nn(ReadinessStatus.Limited),
            fix = ContextFix.LocationSettings.takeIf { !a.locationEnabled })
        items += ContextReadinessItem(ContextReadinessId.ActivityRecognition, if (a.activityRecognition) ReadinessStatus.Ready else ReadinessStatus.Limited,
            "awayPolicy=${cfg.config.contextRules.awayEnvironment}", fix = ContextFix.RequestActivityRecognition.takeIf { !a.activityRecognition })

        val calSelected = cfg.config.calendarRules.calendars.isNotEmpty()
        val calPerm = c.calendar.hasPermission()
        items += ContextReadinessItem(ContextReadinessId.CalendarAccess,
            when { calPerm -> ReadinessStatus.Ready; calSelected -> ReadinessStatus.Off; else -> ReadinessStatus.NotNeeded },
            "selected=${cfg.config.calendarRules.calendars.size}", fix = ContextFix.RequestCalendar.takeIf { !calPerm })
        val syncedAt = cfg.state.calendar.syncedAt
        val maxAge = Duration.ofHours(cfg.config.calendarRules.maxCacheAgeHours.toLong())
        val last = c.calendar.sync.last.value
        items += ContextReadinessItem(ContextReadinessId.CalendarSync,
            when {
                !calSelected || !calPerm -> ReadinessStatus.NotNeeded
                syncedAt == null -> ReadinessStatus.Checking
                Duration.between(syncedAt, Instant.now()) > maxAge -> ReadinessStatus.Limited // engine stops calendar cues (maxCacheAge)
                last?.status == CalendarSyncStatus.Failed -> ReadinessStatus.Limited
                else -> ReadinessStatus.Ready
            },
            "syncedAt=$syncedAt;last=${last?.status};error=${last?.error ?: ""}", fix = ContextFix.SyncNow)
        return ContextReadinessReport(items, Instant.now())
    }
}
