package app.daycue.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** A cue or state hue in its two roles (docs/design/VISUAL.md section 2). */
@Immutable
class Tone(
    /** Fills of abstract shapes. Decorative or redundant, never the only carrier of meaning. */
    val shape: Color,
    /** Text, icons, 1.5dp edges of meaningful shapes. Always 4.5:1 on paper and surface. */
    val ink: Color,
)

/** Every DayCue color token that Material 3's ColorScheme cannot carry (VISUAL.md section 2). */
@Immutable
class DayCueColors(
    val isDark: Boolean,
    // Neutrals
    val paper: Color,
    val surface: Color,
    val sunk: Color,
    val ink: Color,
    val ink2: Color,
    val outline: Color,
    val outlineStrong: Color,
    val scrim: Color,
    // Cue identity
    val sunscreen: Tone,
    val hydration: Tone,
    val posture: Tone,
    val medication: Tone,
    val calendar: Tone,
    val routine: Tone,
    val alarm: Tone,
    // States (due and scheduled reuse the cue tone)
    val snoozed: Tone,
    val paused: Tone,
    /** `shape` is the hatch line color. */
    val unknown: Tone,
    val error: Tone,
    // Context planes (decorative, Field only)
    val planeHome: Color,
    val planeWork: Color,
    val planeOutdoors: Color,
    val planeTransit: Color,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val LightDayCueColors = DayCueColors(
    isDark = false,
    paper = c(0xF4EFE6),
    surface = c(0xFBF8F2),
    sunk = c(0xEAE3D6),
    ink = c(0x1F1D1A),
    ink2 = c(0x57524A),
    outline = c(0xD8D0C2),
    outlineStrong = c(0x8A8276),
    scrim = Color(0x521F1D1A),
    sunscreen = Tone(c(0xE3A72F), c(0x7F5300)),
    hydration = Tone(c(0x5E9EA8), c(0x245F68)),
    posture = Tone(c(0x86A97F), c(0x3B6337)),
    medication = Tone(c(0xA8739A), c(0x7A3B6A)),
    calendar = Tone(c(0x7F86BF), c(0x434B8C)),
    routine = Tone(c(0xCF7652), c(0x963F1E)),
    alarm = Tone(c(0x3F5A8A), c(0x33507F)),
    snoozed = Tone(c(0x9D93B5), c(0x5A5175)),
    paused = Tone(c(0xB3AA9C), c(0x5E574E)),
    unknown = Tone(c(0xC2B9AA), c(0x5E574E)),
    error = Tone(c(0xC4453A), c(0xA3242B)),
    planeHome = c(0xE2D4BA),
    planeWork = c(0xD9DCD6),
    planeOutdoors = c(0xD5DFE4),
    planeTransit = c(0xE4D8D2),
)

val DarkDayCueColors = DayCueColors(
    isDark = true,
    paper = c(0x191816),
    surface = c(0x22201D),
    sunk = c(0x121110),
    ink = c(0xEEE7DB),
    ink2 = c(0xB5AD9F),
    outline = c(0x38342E),
    outlineStrong = c(0x7D7569),
    scrim = Color(0x8F000000),
    sunscreen = Tone(c(0xC99A3E), c(0xE9BC62)),
    hydration = Tone(c(0x5B8F97), c(0x8CC7CF)),
    posture = Tone(c(0x7C9876), c(0xA9CCA2)),
    medication = Tone(c(0x966C8B), c(0xD9A8CC)),
    calendar = Tone(c(0x7277A8), c(0xAEB4E8)),
    routine = Tone(c(0xB56E50), c(0xF0A485)),
    alarm = Tone(c(0x5370A3), c(0xA3BCE8)),
    snoozed = Tone(c(0x8A82A0), c(0xC2B9DC)),
    paused = Tone(c(0x6E675D), c(0xB5AD9F)),
    unknown = Tone(c(0x5E584F), c(0xB5AD9F)),
    error = Tone(c(0xD0574A), c(0xFF9A8C)),
    planeHome = c(0x3A332A),
    planeWork = c(0x2E322E),
    planeOutdoors = c(0x2B3339),
    planeTransit = c(0x382F2C),
)

val LocalDayCueColors = staticCompositionLocalOf { LightDayCueColors }
