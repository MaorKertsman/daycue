package app.daycue.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.daycue.ui.setup.UiPrefs
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Static Material 3 mapping. Dynamic color is never used (VISUAL.md section 2.5, don't 11). */
private fun DayCueColors.toMaterial(): ColorScheme {
    // Every role that matters is set explicitly, so the light/dark builder defaults never show.
    return lightColorScheme(
        primary = ink,
        onPrimary = paper,
        primaryContainer = sunk,
        onPrimaryContainer = ink,
        secondary = ink2,
        onSecondary = paper,
        secondaryContainer = sunk,
        onSecondaryContainer = ink,
        tertiary = ink2,
        onTertiary = paper,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = sunk,
        onSurfaceVariant = ink2,
        surfaceTint = Color.Transparent,
        inverseSurface = ink,
        inverseOnSurface = paper,
        error = error.ink,
        onError = paper,
        errorContainer = sunk,
        onErrorContainer = error.ink,
        outline = outlineStrong,
        outlineVariant = outline,
        scrim = scrim,
        surfaceBright = paper,
        surfaceDim = sunk,
        surfaceContainerLowest = paper,
        surfaceContainerLow = paper,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = sunk,
    )
}

/**
 * DayCue theme. [reduceMotionSetting] is the in-app "Reduce motion" setting; the system animator
 * scale and battery saver are observed here too.
 */
@Composable
fun DayCueTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    reduceMotionSetting: Boolean? = null,
    content: @Composable () -> Unit,
) {
    // Shell-wide preferences: every Activity that shows DayCue UI gets them without wiring (reduce motion, 12/24 h).
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val prefReduce by remember(appContext) { UiPrefs.load(appContext); UiPrefs.reduceMotion }.collectAsState()
    app.daycue.ui.util.SyncClockFromConfig(appContext)
    val colors = if (darkTheme) DarkDayCueColors else LightDayCueColors
    val type = remember { DayCueType() }
    val reduce = (reduceMotionSetting ?: prefReduce) || rememberSystemReduceMotion()
    CompositionLocalProvider(
        LocalDayCueColors provides colors,
        LocalDayCueType provides type,
        LocalReduceMotion provides reduce,
    ) {
        MaterialTheme(
            colorScheme = remember(colors) { colors.toMaterial() },
            typography = remember(type) { type.toMaterial() },
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}

/** Accessors: `DayCueTheme.colors`, `DayCueTheme.type`. */
object DayCueTheme {
    val colors: DayCueColors
        @Composable get() = LocalDayCueColors.current
    val type: DayCueType
        @Composable get() = LocalDayCueType.current
}
