package app.daycue.ui.app

import android.content.Context
import android.content.SharedPreferences

/**
 * UI-only preferences (not part of the synced config): first-run progress and which one-time system prompts were
 * already shown. Reads and writes are wrapped so a storage problem never blocks the UI.
 */
class UiPrefs(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("daycue_ui", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = runCatching { prefs.getBoolean(KEY_DONE, false) }.getOrDefault(false)
        set(v) { runCatching { prefs.edit().putBoolean(KEY_DONE, v).apply() } }

    var onboardingStep: Int
        get() = runCatching { prefs.getInt(KEY_STEP, 0) }.getOrDefault(0)
        set(v) { runCatching { prefs.edit().putInt(KEY_STEP, v).apply() } }

    /** Template ids chosen in the "what to start with" step (kept so Back and a restart keep the choice). */
    var onboardingChoices: Set<String>
        get() = runCatching { prefs.getStringSet(KEY_CHOICES, emptySet()) ?: emptySet() }.getOrDefault(emptySet())
        set(v) { runCatching { prefs.edit().putStringSet(KEY_CHOICES, v).apply() } }

    /** Permissions the owner chose "Not now" for during onboarding (never asked again there). */
    var onboardingSkipped: Set<String>
        get() = runCatching { prefs.getStringSet(KEY_SKIPPED, emptySet()) ?: emptySet() }.getOrDefault(emptySet())
        set(v) { runCatching { prefs.edit().putStringSet(KEY_SKIPPED, v).apply() } }

    /** The notification permission dialog was shown once; after that the fix opens system settings instead. */
    var notificationsAsked: Boolean
        get() = runCatching { prefs.getBoolean(KEY_NOTIF_ASKED, false) }.getOrDefault(false)
        set(v) { runCatching { prefs.edit().putBoolean(KEY_NOTIF_ASKED, v).apply() } }

    private companion object {
        const val KEY_DONE = "onboarding_done"
        const val KEY_STEP = "onboarding_step"
        const val KEY_CHOICES = "onboarding_choices"
        const val KEY_SKIPPED = "onboarding_skipped"
        const val KEY_NOTIF_ASKED = "notifications_asked"
    }
}
