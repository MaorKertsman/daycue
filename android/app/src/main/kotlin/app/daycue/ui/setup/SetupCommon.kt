package app.daycue.ui.setup

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import app.daycue.DayCueApplication
import app.daycue.R
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.ValidationError
import app.daycue.engine.ApplyOutcome
import app.daycue.facade.DayCueFacade
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphButton
import app.daycue.ui.components.PolicyChoiceList
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.Stepper
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** A message for the Setup snackbar. Resolved to text in the composition so it follows the app locale. */
class Snack(@StringRes val message: Int, val undo: Boolean = false, val args: List<Any> = emptyList())

/** One bus for the whole Setup area: view models post, [SetupRoot] shows (with Undo for config edits). */
object SetupBus {
    private val _snacks = MutableSharedFlow<Snack>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val snacks: SharedFlow<Snack> = _snacks.asSharedFlow()
    fun post(snack: Snack) { _snacks.tryEmit(snack) }
}

/** Result of one edit through the facade, reduced to what a screen needs. */
sealed interface EditResult {
    data object Applied : EditResult
    data class Invalid(val errors: List<ValidationError>) : EditResult
    data object Conflict : EditResult
}

internal fun ApplyOutcome.toResult(): EditResult = when (this) {
    is ApplyOutcome.Applied -> EditResult.Applied
    is ApplyOutcome.Invalid -> EditResult.Invalid(errors)
    is ApplyOutcome.Conflict -> EditResult.Conflict
    ApplyOutcome.NothingToUndo -> EditResult.Applied
}

/**
 * Base of every Setup view model: the facade (the only way into the app, APP_API.md section 1) and the single
 * edit path. Editors save each committed change as one op (UX 1.3), so there is no Save button.
 */
abstract class SetupViewModel(app: Application) : AndroidViewModel(app) {
    protected val facade: DayCueFacade = (app as DayCueApplication).container.facade

    /** Applies [ops]; reports a snackbar (Undo when [undo]) on success and a plain note on failure. */
    protected suspend fun edit(ops: List<ConfigOp>, @StringRes success: Int? = null, undo: Boolean = true): EditResult {
        val result = facade.apply(ops).toResult()
        when (result) {
            EditResult.Applied -> if (success != null) SetupBus.post(Snack(success, undo))
            is EditResult.Invalid -> SetupBus.post(Snack(R.string.su_err_not_saved))
            EditResult.Conflict -> SetupBus.post(Snack(R.string.su_err_conflict))
        }
        return result
    }

    protected fun post(@StringRes message: Int, vararg args: Any) = SetupBus.post(Snack(message, false, args.toList()))
}

/** Maps a domain validation error to words for an inline field note (never the technical message). */
@StringRes
internal fun friendlyError(error: ValidationError): Int = when (error.code) {
    "out_of_range" -> R.string.su_err_range
    "bad_length" -> R.string.su_err_length
    "empty" -> R.string.su_err_empty
    "duplicate_id" -> R.string.su_err_duplicate
    else -> R.string.su_err_not_saved
}

internal fun Context.startActivitySafely(intent: Intent?): Boolean {
    if (intent == null) return false
    return runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/**
 * Screen frame: a headline title, optional back button, one vertical scroll, content centred up to 560dp.
 * Everything scrolls so nothing is ever cut at font scale 2.0 (UX 5).
 */
@Composable
fun SetupFrame(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DayCueTheme.colors
    Box(
        modifier
            .fillMaxSize()
            .background(c.paper)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = DayCueSpacing.contentMaxWidth)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DayCueSpacing.gutter),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    GlyphButton(Glyph.Chevron, stringResource(R.string.su_back), onBack, modifier = Modifier.rotate(180f))
                }
                Text(
                    title,
                    style = DayCueTheme.type.headline,
                    color = c.ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 8.dp, bottom = 8.dp, start = if (onBack != null) 4.dp else 0.dp)
                        .semantics { heading() },
                )
            }
            content()
            Spacer(Modifier.height(96.dp))
        }
    }
}

/** A short explanatory paragraph in `ink2`. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
fun Para(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DayCueTheme.type.body, color = DayCueTheme.colors.ink, modifier = modifier.padding(vertical = 4.dp))
}

/** Inline validation or status note under a field, in error `ink` (always words, never color alone). */
@Composable
fun FieldNote(text: String, modifier: Modifier = Modifier, error: Boolean = true) {
    val c = DayCueTheme.colors
    Text(text, style = DayCueTheme.type.bodySmall, color = if (error) c.error.ink else c.ink2, modifier = modifier.padding(vertical = 4.dp))
}

/** Label, steppers and one consequence sentence. [valueText] must already be bidi-safe. */
@Composable
fun StepperRow(
    label: String,
    valueText: String,
    valueDescription: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    hint: String? = null,
    error: String? = null,
) {
    val c = DayCueTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = DayCueTheme.type.titleSmall, color = c.ink)
        if (hint != null) Text(hint, style = DayCueTheme.type.bodySmall, color = c.ink2)
        Spacer(Modifier.height(4.dp))
        Stepper(valueText, valueDescription, onDecrease, onIncrease, canDecrease = canDecrease, canIncrease = canIncrease, valueMinWidth = 112.dp)
        if (error != null) FieldNote(error)
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(c.outline))
    }
}

/** Radio sheet with a consequence sentence per option (UX PolicyChoiceSheet). */
@Composable
fun ChoiceSheet(
    title: String,
    options: List<PolicyOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    example: String? = null,
) {
    DayCueBottomSheet(onDismiss = onDismiss, title = title) {
        PolicyChoiceList(options, selectedIndex, { onSelect(it); onDismiss() }, example = example)
    }
}

@Composable
internal fun Gap(dp: Int = 8) = Spacer(Modifier.height(dp.dp))
