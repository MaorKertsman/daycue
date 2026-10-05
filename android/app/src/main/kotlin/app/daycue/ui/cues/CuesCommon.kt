package app.daycue.ui.cues

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.domain.config.ContextCondition
import app.daycue.domain.config.Environment
import app.daycue.domain.config.Activity as CtxActivity
import app.daycue.domain.config.Habit
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.Language
import app.daycue.domain.config.LocalizedText
import app.daycue.domain.config.Place
import app.daycue.domain.edit.ValidationError
import app.daycue.ui.components.DayCueTextField
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.Stepper
import app.daycue.ui.components.StatusNotch
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.formatTime
import app.daycue.ui.util.ltr
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Bottom inset the host reserves (the app shell's navigation bar). Set by [CuesRoot]. */
internal val LocalCuesBottomPadding = compositionLocalOf { 24.dp }

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
internal fun uiLanguage(): Language = if (currentLocale().language in setOf("he", "iw")) Language.he else Language.en

internal fun LocalizedText.shown(lang: Language): String = get(lang)

internal val zoneNow: ZoneId get() = ZoneId.systemDefault()

@Composable
internal fun timeText(t: LocalTime): String = formatTime(t.hour, t.minute)

@Composable
internal fun instantTime(i: Instant): String {
    val t = i.atZone(zoneNow).toLocalTime()
    return formatTime(t.hour, t.minute)
}

/** "Sun–Thu" in locale week order when the days are contiguous, otherwise a comma list; "Every day" for all seven. */
@Composable
internal fun daysSummary(days: Set<DayOfWeek>): String {
    val locale = currentLocale()
    val first = WeekFields.of(locale).firstDayOfWeek
    val ordered = List(7) { first.plus(it.toLong()) }
    if (days.size == 7) return stringResource(R.string.cues_every_day)
    if (days.isEmpty()) return stringResource(R.string.cues_no_days)
    val flags = ordered.map { it in days }
    // contiguous run (no wrap) -> "start–end"
    val start = flags.indexOf(true)
    val end = flags.lastIndexOf(true)
    val contiguous = (start..end).all { flags[it] }
    fun short(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, locale)
    return if (contiguous && end - start >= 2) "${short(ordered[start])}–${short(ordered[end])}"
    else ordered.filter { it in days }.joinToString(", ") { short(it) }
}

/** Shown names of template items stay translatable even though the config stores an English name. */
@Composable
internal fun habitName(h: Habit): String = when {
    h.id == "sunscreen" && h.name == "Sunscreen" -> stringResource(R.string.cue_sunscreen)
    h.id == "hydration" && h.name == "Hydration" -> stringResource(R.string.cue_hydration)
    h.id == "water-bottle" && h.name == "Water bottle" -> stringResource(R.string.cue_bottle)
    else -> h.name
}

@Composable
internal fun placeName(p: Place): String = when {
    p.id == "home" && p.name == "Home" -> stringResource(R.string.cues_place_home)
    p.id == "office" && p.name == "Office" -> stringResource(R.string.cues_place_office)
    p.id == "gym" && p.name == "Gym" -> stringResource(R.string.cues_place_gym)
    else -> p.name
}

@Composable
internal fun routineName(id: String, name: String): String =
    if (id == "morning-routine" && name == "Morning routine") stringResource(R.string.cues_template_morning_routine) else name

@Composable
internal fun stepName(id: String, name: String): String = when {
    id == "shower" && name == "Shower" -> stringResource(R.string.cues_step_shower)
    id == "face-cleanser" && name == "Face cleanser" -> stringResource(R.string.cues_step_face_cleanser)
    id == "brush-teeth" && name == "Brush teeth" -> stringResource(R.string.cues_step_brush_teeth)
    id == "get-dressed" && name == "Get dressed" -> stringResource(R.string.cues_step_get_dressed)
    else -> name
}

@Composable
internal fun alarmName(id: String, name: String): String =
    if (id == "morning-alarm" && name == "Morning alarm") stringResource(R.string.cues_template_morning_alarm) else name

internal fun markFor(h: Habit): CueType = when (h) {
    is IntervalHabit -> when (h.kind) {
        IntervalKind.Sunscreen -> CueType.Sunscreen
        else -> CueType.Hydration
    }
    else -> CueType.Bottle
}

@Composable
internal fun conditionText(c: ContextCondition, places: List<Place>): String {
    if (c.isAny) return stringResource(R.string.cues_when_any)
    val parts = mutableListOf<String>()
    c.environments?.let { env ->
        parts += when {
            env == setOf(Environment.Outdoor) -> stringResource(R.string.cues_when_outdoors)
            env == setOf(Environment.Indoor) -> stringResource(R.string.cues_when_indoors)
            else -> stringResource(R.string.cues_when_any)
        }
    }
    c.activities?.let { act ->
        parts += when {
            act == setOf(CtxActivity.Working) -> stringResource(R.string.cues_when_working)
            act == setOf(CtxActivity.Studying) -> stringResource(R.string.cues_when_studying)
            else -> stringResource(R.string.cues_when_active)
        }
    }
    c.places?.let { ids ->
        val names = ids.map { id -> places.firstOrNull { it.id == id }?.let { placeName(it) } ?: id }
        parts += stringResource(R.string.cues_when_at_places, names.joinToString(", "))
    }
    return parts.joinToString(" · ")
}

// ---- Layout ---------------------------------------------------------------------------------

/**
 * Standard Cues screen: back button + title + optional trailing control, one vertical scroll with the host's
 * bottom padding, content column limited to 560dp (VISUAL.md).
 */
@Composable
internal fun CuesScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DayCueTheme.colors
    Column(modifier.fillMaxSize().background(c.paper).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) BackButton(onBack) else Spacer(Modifier.width(16.dp))
            Text(
                title,
                style = DayCueTheme.type.title,
                color = c.ink,
                modifier = Modifier.weight(1f).padding(end = 8.dp).semantics { heading() },
            )
            trailing?.invoke(this)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier
                    .widthIn(max = DayCueSpacing.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = DayCueSpacing.gutter),
            ) {
                content()
                Spacer(Modifier.height(LocalCuesBottomPadding.current))
            }
        }
    }
}

@Composable
internal fun BackButton(onBack: () -> Unit) {
    val description = stringResource(R.string.cues_back)
    Box(
        Modifier
            .size(48.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onBack)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Chevron, DayCueTheme.colors.ink, Modifier.graphicsLayer { scaleX = -1f })
    }
}

// ---- Editing helpers ---------------------------------------------------------------------------

/** Localized, code-based message for a validation error (the domain message is English and technical). */
@Composable
internal fun errorText(e: ValidationError): String = when (e.code) {
    "out_of_range" -> stringResource(R.string.cues_err_range)
    "bad_length" -> stringResource(R.string.cues_err_length)
    "empty", "none_enabled" -> stringResource(R.string.cues_err_empty)
    "duplicate", "duplicate_id" -> stringResource(R.string.cues_err_duplicate)
    "unknown_ref" -> stringResource(R.string.cues_err_unknown_ref)
    "bad_range" -> stringResource(R.string.cues_err_bad_range)
    else -> stringResource(R.string.cues_err_generic)
}

/** Inline errors of the last rejected edit whose field path starts with [prefix] (exact or nested). */
@Composable
internal fun FieldErrors(errors: List<ValidationError>, prefix: String, modifier: Modifier = Modifier) {
    val mine = errors.filter { it.path == prefix || it.path.startsWith("$prefix.") || it.path.startsWith("$prefix[") }
    if (mine.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        mine.distinctBy { it.code }.forEach { e ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                StatusNotch(markSize = 20.dp)
                Text(errorText(e), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.error.ink)
            }
        }
    }
}

/**
 * Text field that commits on a short pause and when the screen goes away, so autosave never loses typing
 * and does not write one op per keystroke.
 */
@Composable
internal fun CommitTextField(
    value: String,
    onCommit: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(value) }
    val latest by rememberUpdatedState(text)
    val committed by rememberUpdatedState(value)
    val commit by rememberUpdatedState(onCommit)
    LaunchedEffect(text) {
        if (text != value) {
            kotlinx.coroutines.delay(700)
            commit(text)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (latest != committed) commit(latest) }
    }
    DayCueTextField(text, { text = it }, label, modifier, helper, error, singleLine = singleLine)
}

/** "Label  [-] value [+]" row for small counts and minute values with explicit range. */
@Composable
internal fun NumberRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    valueText: String,
    valueDescription: String = valueText,
    step: Int = 1,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
        Stepper(
            valueText = valueText,
            valueDescription = valueDescription,
            onDecrease = { onChange((value - step).coerceAtLeast(min)) },
            onIncrease = { onChange((value + step).coerceAtMost(max)) },
            canDecrease = value > min,
            canIncrease = value < max,
        )
    }
}

internal fun LocalDate.isoText(): String = toString()

@Composable
internal fun dateText(d: LocalDate): String {
    val locale: Locale = currentLocale()
    val f = java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)
    return d.format(f).ltrIfLatin()
}

/** Dates mix digits and month names; isolate them only when they would otherwise reorder inside Hebrew text. */
private fun String.ltrIfLatin(): String = if (any { it in '0'..'9' }) ltr() else this
