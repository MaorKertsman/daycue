package app.daycue.system

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.daycue.delivery.ChannelRegistry
import app.daycue.delivery.VoiceAvailability
import app.daycue.delivery.VoiceStatus
import app.daycue.domain.engine.EngineState
import app.daycue.engine.ArmResult
import app.daycue.scheduling.ExactAlarmAccess
import java.time.Instant

enum class ReadinessStatus { Ready, Limited, Off, NotNeeded, Checking }

enum class ReadinessId { Notifications, ExactAlarms, FullScreenIntent, BatteryOptimization, BackgroundRestricted, Hibernation, Voices, BlockedChannels, SpeechFailure, ForceStopped }

/**
 * One readiness row (UX §3.13). [detail] is machine-readable (the UI words it): e.g. blocked channel ids,
 * `he=MissingData`, the speech failure reason.
 */
data class ReadinessItem(val id: ReadinessId, val status: ReadinessStatus, val detail: String? = null, val hasFix: Boolean = false)

data class ReadinessReport(
    val items: List<ReadinessItem>,
    val checkedAt: Instant,
    /** How the current next wake is armed (degraded = exact access missing). */
    val lastArm: ArmResult?,
    /** `UsageStatsManager.appStandbyBucket` (10 active ... 45 restricted), API 28+. */
    val standbyBucket: Int?,
) {
    /** Problems that may delay reminders (optional integrations never count). */
    val problemCount: Int get() = items.count { it.status == ReadinessStatus.Limited || it.status == ReadinessStatus.Off }
}

/** Live permission / platform state for the readiness screen, plus the exact system screen to fix each row. */
class Readiness(private val context: Context, private val channels: ChannelRegistry) {

    @SuppressLint("NewApi")
    fun check(voices: VoiceStatus, state: EngineState?, lastArm: ArmResult?, speechWanted: Boolean): ReadinessReport {
        val items = mutableListOf<ReadinessItem>()
        val nm = context.getSystemService(NotificationManager::class.java)
        val notifOk = nm.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        items += ReadinessItem(ReadinessId.Notifications, if (notifOk) ReadinessStatus.Ready else ReadinessStatus.Off, hasFix = true)

        val exact = ExactAlarmAccess.canScheduleExact(context)
        items += ReadinessItem(ReadinessId.ExactAlarms, if (exact) ReadinessStatus.Ready else ReadinessStatus.Limited, hasFix = !exact && Build.VERSION.SDK_INT >= 31)

        val fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        items += ReadinessItem(ReadinessId.FullScreenIntent, if (fsi) ReadinessStatus.Ready else ReadinessStatus.Limited, hasFix = !fsi)

        val ignoringBattery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        items += ReadinessItem(ReadinessId.BatteryOptimization, if (ignoringBattery) ReadinessStatus.Ready else ReadinessStatus.Limited, hasFix = !ignoringBattery)

        val am = context.getSystemService(ActivityManager::class.java)
        if (Build.VERSION.SDK_INT >= 28 && am.isBackgroundRestricted) items += ReadinessItem(ReadinessId.BackgroundRestricted, ReadinessStatus.Off, hasFix = true)

        val hibernationExempt = Build.VERSION.SDK_INT < 30 || context.packageManager.isAutoRevokeWhitelisted
        items += ReadinessItem(ReadinessId.Hibernation, if (hibernationExempt) ReadinessStatus.Ready else ReadinessStatus.Limited, hasFix = !hibernationExempt)

        val voiceRow = when {
            !speechWanted -> ReadinessStatus.NotNeeded
            !voices.initialized && voices.en == VoiceAvailability.Unknown -> ReadinessStatus.Checking
            voices.en == VoiceAvailability.EngineUnavailable -> ReadinessStatus.Off
            voices.en == VoiceAvailability.Available && voices.he == VoiceAvailability.Available -> ReadinessStatus.Ready
            else -> ReadinessStatus.Limited
        }
        items += ReadinessItem(ReadinessId.Voices, voiceRow,
            "engine=${voices.engine};en=${voices.en};he=${voices.he};enNet=${voices.enNeedsNetwork};heNet=${voices.heNeedsNetwork}", hasFix = voiceRow == ReadinessStatus.Limited)

        val blocked = channels.blockedChannels()
        items += ReadinessItem(ReadinessId.BlockedChannels, if (blocked.isEmpty()) ReadinessStatus.Ready else ReadinessStatus.Limited, blocked.joinToString(","), hasFix = blocked.isNotEmpty())

        state?.readiness?.speechFailedAt?.let { at ->
            items += ReadinessItem(ReadinessId.SpeechFailure, ReadinessStatus.Limited, "${state.readiness.speechFailureReason}@$at")
        }

        if (Build.VERSION.SDK_INT >= 35) {
            val forceStopped = runCatching { am.getHistoricalProcessStartReasons(1).firstOrNull()?.wasForceStopped() == true }.getOrDefault(false)
            if (forceStopped) items += ReadinessItem(ReadinessId.ForceStopped, ReadinessStatus.Limited, "last start followed a force stop")
        }

        val bucket = if (Build.VERSION.SDK_INT >= 28) runCatching { context.getSystemService(UsageStatsManager::class.java).appStandbyBucket }.getOrNull() else null
        return ReadinessReport(items, Instant.now(), lastArm, bucket)
    }

    /** The system screen that fixes [id], or null. Start it from an Activity. */
    @SuppressLint("BatteryLife", "InlinedApi")
    fun fixIntent(id: ReadinessId): Intent? {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (id) {
            ReadinessId.Notifications, ReadinessId.BlockedChannels ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            ReadinessId.ExactAlarms -> if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
            ReadinessId.FullScreenIntent -> if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null
            ReadinessId.BatteryOptimization -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            ReadinessId.BackgroundRestricted, ReadinessId.ForceStopped -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
            ReadinessId.Hibernation -> IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
            ReadinessId.Voices, ReadinessId.SpeechFailure -> Intent("android.speech.tts.engine.INSTALL_TTS_DATA")
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
