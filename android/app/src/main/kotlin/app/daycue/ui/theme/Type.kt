package app.daycue.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.daycue.R

// Bundled variable fonts (SIL OFL 1.1, see assets/licenses/). Weights are instanced at load time
// through variation settings (API 26+), which gives the same glyphs as static TTFs.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Rubik = FontFamily(
    Font(R.font.rubik, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.rubik, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.rubik, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val FrankRuhlLibre = FontFamily(
    Font(
        R.font.frank_ruhl_libre,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)

private fun ui(weight: FontWeight, size: Int, line: Int) = TextStyle(
    fontFamily = Rubik,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp, // never track Hebrew
)

private fun serif(size: Int, line: Int) = TextStyle(
    fontFamily = FrankRuhlLibre,
    fontWeight = FontWeight.Medium,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = 0.sp,
)

/** Type scale from VISUAL.md section 3. Nothing below 12sp, no caps, no tracking. */
@Immutable
class DayCueType(
    val display: TextStyle = serif(34, 42),
    val headline: TextStyle = serif(28, 36),
    val title: TextStyle = ui(FontWeight.Medium, 20, 28),
    val titleSmall: TextStyle = ui(FontWeight.Medium, 16, 24),
    val body: TextStyle = ui(FontWeight.Normal, 16, 24),
    val bodySmall: TextStyle = ui(FontWeight.Normal, 14, 20),
    val label: TextStyle = ui(FontWeight.Medium, 14, 20),
    val button: TextStyle = ui(FontWeight.Medium, 16, 24),
    val labelSmall: TextStyle = ui(FontWeight.Medium, 12, 16),
    /** Countdowns and times. `tnum` is requested; if Rubik lacks it the feature is ignored (open issue 1). */
    val numeric: TextStyle = ui(FontWeight.Medium, 16, 24).copy(fontFeatureSettings = "tnum"),
)

val LocalDayCueType = staticCompositionLocalOf { DayCueType() }

internal fun DayCueType.toMaterial() = Typography(
    displayLarge = display, displayMedium = display, displaySmall = display,
    headlineLarge = headline, headlineMedium = headline, headlineSmall = headline,
    titleLarge = title, titleMedium = titleSmall, titleSmall = titleSmall,
    bodyLarge = body, bodyMedium = body, bodySmall = bodySmall,
    labelLarge = label, labelMedium = label, labelSmall = labelSmall,
)
