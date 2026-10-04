package app.daycue.ui.theme

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** Motion tokens (VISUAL.md section 6). Every spec takes `reduce`; reduced motion jumps or crossfades. */
object DayCueMotion {
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val SheetEnter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val SheetExit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val QuickMs = 120
    const val StandardMs = 240
    const val ScreenMs = 300
    const val ProgressMs = 900
    const val EntryMs = 600
    const val AmbientPeriodMs = 28_000
    const val AmbientStopAfterMs = 60_000
    const val ReducedCrossfadeMs = 150

    fun <T> quickEnter(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(QuickMs, easing = LinearOutSlowInEasing)

    fun <T> quickExit(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(QuickMs, easing = FastOutLinearInEasing)

    fun <T> standard(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(StandardMs, easing = Standard)

    fun <T> progress(reduce: Boolean, durationMs: Int = ProgressMs): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(durationMs, easing = LinearOutSlowInEasing)

    fun <T> settle(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) snap() else spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Enter for sheets, dialogs and screens: a 150ms crossfade under reduced motion, otherwise slide + fade. */
    fun overlayEnter(reduce: Boolean, slide: Boolean = true): EnterTransition =
        if (reduce) fadeIn(tween(ReducedCrossfadeMs, easing = LinearOutSlowInEasing))
        else if (slide) slideInVertically(tween(ScreenMs, easing = SheetEnter)) { it / 8 } + fadeIn(tween(StandardMs))
        else fadeIn(tween(StandardMs, easing = Standard))

    fun overlayExit(reduce: Boolean, slide: Boolean = true): ExitTransition =
        if (reduce) fadeOut(tween(ReducedCrossfadeMs, easing = FastOutLinearInEasing))
        else if (slide) slideOutVertically(tween(StandardMs, easing = SheetExit)) { it / 8 } + fadeOut(tween(StandardMs))
        else fadeOut(tween(QuickMs, easing = FastOutLinearInEasing))

    @Suppress("unused")
    val settleSpringStiffness = Spring.StiffnessMediumLow
}

/** True when motion must be minimal: in-app setting, animator scale 0, or battery saver. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Observes the system animator scale and battery saver. */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    var animatorOff by remember { mutableStateOf(readAnimatorOff(context)) }
    var powerSave by remember { mutableStateOf(readPowerSave(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animatorOff = readAnimatorOff(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                powerSave = readPowerSave(context)
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return reduceMotionFrom(setting = false, animatorScale = if (animatorOff) 0f else 1f, powerSave = powerSave)
}

/** The single rule for reduced motion: in-app setting, system animator scale 0, or battery saver. */
fun reduceMotionFrom(setting: Boolean, animatorScale: Float, powerSave: Boolean): Boolean =
    setting || animatorScale == 0f || powerSave

private fun readAnimatorOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

private fun readPowerSave(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
