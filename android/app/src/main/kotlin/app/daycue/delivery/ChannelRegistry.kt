package app.daycue.delivery

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import app.daycue.domain.config.CueType
import app.daycue.domain.engine.Cue

/**
 * Notification channels (ANDROID.md §6). Base channel id = `CueType.channelId` (the id the domain puts
 * on every `Cue`), one group per cue type. Channels are created once; re-creating with the same id never
 * changes importance/sound/vibration the user may have edited, and we never delete + recreate to override
 * user choices. Only names/descriptions are refreshed on locale change.
 *
 * Custom sounds (§6.2): a channel's sound is fixed at creation, so a cue whose profile sound/vibration
 * differs from its type's default is posted on a variant channel `<type>.s.<sound>.v.<vibration>` in the
 * same group, created on first use. Sound ids come from the bundled catalogue, so the count is bounded.
 */
class ChannelRegistry(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    data class Spec(val id: String, val base: String, val importance: Int, val group: String?, val sound: String, val vibration: String)

    private val baseSpecs: List<Spec> = CueType.entries.map { t ->
        val (s, v) = CueMedia.defaults.getValue(t)
        Spec(t.channelId, t.channelId, importanceOf(t), t.channelId, s, v)
    } + listOf(
        Spec(ROUTINE_PLAYBACK, ROUTINE_PLAYBACK, NotificationManager.IMPORTANCE_LOW, CueType.RoutineStep.channelId, CueMedia.NONE, CueMedia.NONE),
        Spec(SYSTEM, SYSTEM, NotificationManager.IMPORTANCE_LOW, null, CueMedia.NONE, CueMedia.NONE),
    )

    fun ensureBase() {
        nm.createNotificationChannelGroups(CueType.entries.map { NotificationChannelGroup(it.channelId, name(it.channelId)) })
        nm.createNotificationChannels(baseSpecs.map(::build))
    }

    /** Channel to post [cue] on, creating its sound variant if needed. */
    fun channelFor(cue: Cue): String {
        if (cue.silent) return cue.channelId // posted with setSilent(true)
        val (defSound, defVib) = CueMedia.defaults[cue.type] ?: (CueMedia.NONE to CueMedia.NONE)
        val sound = CueMedia.knownSound(cue.soundId)
        val vib = CueMedia.knownVibration(cue.vibrationId)
        if (sound == defSound && vib == defVib) return cue.channelId
        val id = "${cue.channelId}.s.$sound.v.$vib"
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(build(Spec(id, cue.channelId, importanceOf(cue.type), cue.channelId, sound, vib)))
        }
        return id
    }

    fun refreshNames() {
        ensureBase()
        nm.notificationChannels.filter { it.id.contains(".s.") }.forEach { ch ->
            val base = ch.id.substringBefore(".s.")
            ch.name = name(base)
            nm.createNotificationChannel(ch)
        }
    }

    /** Channels the user turned off (readiness). */
    fun blockedChannels(): List<String> = nm.notificationChannels.filter { it.importance == NotificationManager.IMPORTANCE_NONE }.map { it.id }

    private fun build(s: Spec): NotificationChannel = NotificationChannel(s.id, name(s.base), s.importance).apply {
        description = string("dc_channel_${s.base}_desc")
        s.group?.let { group = it }
        val uri = CueMedia.soundUri(context, s.sound.takeIf { it != CueMedia.NONE })
        if (uri == null) setSound(null, null) else setSound(uri, NOTIFICATION_AUDIO)
        val pattern = CueMedia.vibrations[s.vibration]
        if (pattern == null) enableVibration(false) else { enableVibration(true); vibrationPattern = pattern }
        lockscreenVisibility = if (s.id.startsWith(CueType.Medication.channelId)) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_PUBLIC
        if (s.id == CueType.Alarm.channelId) setBypassDnd(true) // only honoured with DND access; the FGS rings regardless
    }

    private fun name(base: String) = string("dc_channel_${base}_name")

    @SuppressLint("DiscouragedApi")
    private fun string(resName: String): String {
        val id = context.resources.getIdentifier(resName, "string", context.packageName)
        return if (id == 0) resName else context.getString(id)
    }

    companion object {
        const val ROUTINE_PLAYBACK = "routine_playback"
        const val SYSTEM = "system"

        val NOTIFICATION_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun importanceOf(t: CueType): Int = when (t) {
            CueType.Alarm, CueType.Medication -> NotificationManager.IMPORTANCE_HIGH
            CueType.Notice -> NotificationManager.IMPORTANCE_LOW
            // Posture: PRODUCT §8.1 gives it a sound, so DEFAULT (ANDROID.md proposed LOW; see APP_API.md).
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }
    }
}
