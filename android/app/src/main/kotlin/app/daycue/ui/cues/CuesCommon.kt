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
import app.daycue.ui.components.DayCueTopBar
import app.daycue.ui.marks.CueMark
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.Stepper
import app.daycue.ui.components.StatusNotch
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.currentLocale
import app.daycue.ui.util.formatTime
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
    fun short(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, locale).removePrefix("יום ").trim()
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
    p.name == "Home" -> stringResource(R.string.cues_place_home)
    p.name == "Office" -> stringResource(R.string.cues_place_office)
    p.name == "Gym" -> stringResource(R.string.cues_place_gym)
    else -> p.name
}

@Composable
internal fun routineName(id: String, name: String): String =
    if (name == "Morning routine") stringResource(R.string.cues_template_morning_routine) else name

@Composable
internal fun stepName(id: String, name: String): String = when {
    name == "Shower" -> stringResource(R.string.cues_step_shower)
    name == "Face cleanser" -> stringResource(R.string.cues_step_face_cleanser)
    name == "Brush teeth" -> stringResource(R.string.cues_step_brush_teeth)
    name == "Get dressed" -> stringResource(R.string.cues_step_get_dressed)
    else -> name
}

@Composable
internal fun alarmName(id: String, name: String): String {
    // A default name in EITHER language (English built-in or the Hebrew first-run text) shows in the UI language (D12).
    val ctx = LocalContext.current
    val text = remember(ctx) { runCatching { (ctx.applicationContext as app.daycue.DayCueApplication).container.text }.getOrNull() }
    val isMorningDefault = remember(name, text) {
        val key = "template.alarm.morning"
        name.trim() == app.daycue.domain.config.Defaults.TEMPLATE_TEXT[key] ||
            app.daycue.domain.config.Language.entries.any { text?.textOrNull(key, it)?.trim() == name.trim() }
    }
    return when {
        isMorningDefault -> stringResource(R.string.cues_template_morning_alarm)
        name == "Alarm" -> stringResource(R.string.cues_new_alarm_name) // the English default name, shown in the UI language
        else -> name
    }
}

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

/** "Bright chime · speaks" for the cue profile an item uses; the row opens Sounds in Setup. */
@Composable
internal fun soundSummary(cfg: app.daycue.domain.config.DayCueConfig, type: app.daycue.domain.config.CueType, explicit: String?): String {
    val p = cfg.profileFor(type, explicit) ?: return stringResource(R.string.cues_sound_voice)
    val sound = if (!p.soundEnabled) stringResource(R.string.su_sound_none) else stringResource(
        when (p.soundId) {
            "soft-bell" -> R.string.su_sound_soft_bell
            "wood-tap" -> R.string.su_sound_wood_tap
            "two-note-rise" -> R.string.su_sound_two_note_rise
            "pop" -> R.string.su_sound_pop
            "bright-chime" -> R.string.su_sound_bright_chime
            "low-marimba" -> R.string.su_sound_low_marimba
            "droplet" -> R.string.su_sound_droplet
            "morning" -> R.string.su_sound_morning
            "alarm-source" -> R.string.su_sound_alarm_source
            else -> R.string.su_sound_none
        },
    )
    val speak = stringResource(if (p.speechEnabled) R.string.su_speaks else R.string.su_silent_speech)
    return "$sound · $speak"
}

// ---- Layout ---------------------------------------------------------------------------------

/**
 * Standard Cues screen: the shared [DayCueTopBar] (serif title, back, optional cue mark and one trailing control),
 * one vertical scroll with the host's bottom padding, content column limited to 560dp (VISUAL.md).
 */
@Composable
internal fun CuesScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    mark: CueType? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DayCueTheme.colors
    Column(modifier.fillMaxSize().background(c.paper)) {
        DayCueTopBar(
            title = title,
            onBack = onBack,
            mark = mark?.let { m -> { CueMark(m, size = 28.dp) } },
            actions = { trailing?.invoke(this) },
        )
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

/** Medium date in the device locale ("Oct 5, 2026" / "5 באוק׳ 2026"); never bidi-isolated, so Hebrew keeps its order. */
@Composable
internal fun dateText(d: LocalDate): String {
    val locale: Locale = currentLocale()
    return d.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
}

/** Day header with weekday from the locale skeleton: "Mon, Oct 5" / "יום ב׳, 5 באוק׳". */
@Composable
internal fun dayHeaderText(d: LocalDate): String = dayHeaderText(d, currentLocale())

internal fun dayHeaderText(d: LocalDate, locale: Locale): String {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM")
    return d.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}

/** Localized display name of a zone ("Israel Time" / "שעון ישראל"), never the raw id. */
internal fun zoneDisplayName(zone: ZoneId, locale: Locale): String =
    zone.getDisplayName(TextStyle.FULL, locale).ifBlank { zone.id }

@Composable
internal fun zoneName(zone: ZoneId): String = zoneDisplayName(zone, currentLocale())

/** "+5 min" with the sign and digits isolated left-to-right so Hebrew never renders "5+". */
@Composable
internal fun plusMinutes(n: Int): String = stringResource(R.string.cues_plus_min, n)
