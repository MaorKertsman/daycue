package app.daycue.ui.components

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.daycue.ui.theme.DayCueMotion
import app.daycue.ui.theme.LocalReduceMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueType
import app.daycue.ui.theme.DayCueElevation
import app.daycue.ui.theme.DayCueShapes
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme

/** Drag handle 32x4dp `outlineStrong`, 12dp from the top, plus the 1dp `outline` top edge of the sheet. */
@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    val c = DayCueTheme.colors
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, bottom = 12.dp)
                .size(width = 32.dp, height = 4.dp)
                .background(c.outlineStrong, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * Sheet content: title (with at most one 28dp mark), body, and at most one primary action at the bottom.
 * 24dp side padding. Scrolls when the font scale makes it taller than the screen.
 */
@Composable
fun SheetBody(
    title: String,
    modifier: Modifier = Modifier,
    mark: CueType? = null,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = DayCueTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (mark != null) {
                CueMark(mark)
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = DayCueTheme.type.title, color = c.ink, modifier = Modifier.semantics { paneTitle = title })
        }
        Spacer(Modifier.height(DayCueSpacing.inRow))
        content()
        if (primaryLabel != null) {
            Spacer(Modifier.height(DayCueSpacing.related))
            PrimaryButton(primaryLabel, onPrimary, Modifier.fillMaxWidth())
        }
    }
}

/** Static sheet look (handle + body) for galleries and previews, without the modal machinery. */
@Composable
fun SheetPreview(
    title: String,
    modifier: Modifier = Modifier,
    mark: CueType? = null,
    primaryLabel: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(DayCueShapes.sheet)
            .background(DayCueTheme.colors.surface),
    ) {
        SheetHandle()
        SheetBody(title, mark = mark, primaryLabel = primaryLabel, scrollable = false, content = content)
    }
}

/**
 * Modal bottom sheet in DayCue style: `surface`, top radius 28, flat (no tonal elevation), scrim from tokens.
 * Edits of a single value belong in sheets. Focus returns to the opener when it closes (Material default).
 * Under reduced motion Material's slide is replaced by a 150ms alpha crossfade of scrim and sheet (REVIEW-1 #43).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayCueBottomSheet(
    onDismiss: () -> Unit,
    title: String,
    mark: CueType? = null,
    primaryLabel: String? = null,
    onPrimary: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val c = DayCueTheme.colors
    if (LocalReduceMotion.current) {
        FadeOverlay(onDismiss = onDismiss, bottom = true) { finish ->
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clip(DayCueShapes.sheet)
                    .background(c.surface)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                SheetHandle()
                SheetBody(title, Modifier.navigationBarsPadding(), mark, primaryLabel, { finish(onPrimary) }, content = content)
            }
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = DayCueShapes.sheet,
        containerColor = c.surface,
        contentColor = c.ink,
        tonalElevation = DayCueElevation.none,
        scrimColor = c.scrim,
        dragHandle = { SheetHandle() },
    ) {
        SheetBody(title, Modifier.navigationBarsPadding(), mark, primaryLabel, onPrimary, content = content)
    }
}

/**
 * Full-window overlay with the `scrim` token (not the platform dim) and our own show/hide: a 150ms crossfade under
 * reduced motion, a short fade otherwise. [content] gets `finish(action)`, which hides the overlay first and runs
 * [action] after, so confirming also fades out. Back and scrim taps dismiss through [onDismiss].
 */
@Composable
private fun FadeOverlay(
    onDismiss: () -> Unit,
    bottom: Boolean,
    content: @Composable (finish: (() -> Unit) -> Unit) -> Unit,
) {
    val c = DayCueTheme.colors
    val reduce = LocalReduceMotion.current
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val finish: (() -> Unit) -> Unit = { action -> pending = action; state.targetState = false }
    LaunchedEffect(state.currentState, state.targetState) {
        if (!state.targetState && !state.currentState) (pending ?: onDismiss).invoke()
    }
    Dialog(
        onDismissRequest = { finish(onDismiss) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
        }
        AnimatedVisibility(
            visibleState = state,
            enter = if (reduce) fadeIn(tween(DayCueMotion.ReducedCrossfadeMs)) else fadeIn(tween(DayCueMotion.StandardMs)),
            exit = if (reduce) fadeOut(tween(DayCueMotion.ReducedCrossfadeMs)) else fadeOut(tween(DayCueMotion.QuickMs)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(c.scrim)
                    .pointerInput(Unit) { detectTapGestures { finish(onDismiss) } }
                    .statusBarsPadding()
                    .then(if (bottom) Modifier else Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp)),
                contentAlignment = if (bottom) Alignment.BottomCenter else Alignment.Center,
            ) {
                content(finish)
            }
        }
    }
}

class PolicyOption(val label: String, val consequence: String)

/**
 * Radio list where every option states its consequence in one sentence, with a worked example footer.
 * Use inside [DayCueBottomSheet] / [SheetPreview].
 */
@Composable
fun PolicyChoiceList(
    options: List<PolicyOption>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    example: String? = null,
) {
    val c = DayCueTheme.colors
    Column(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            ChoiceRow(option.label, option.consequence, index == selectedIndex, { onSelect(index) })
        }
        if (example != null) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
            Spacer(Modifier.height(8.dp))
            Text(example, style = DayCueTheme.type.bodySmall, color = c.ink2)
        }
    }
}

/**
 * Dialog body: `surface`, radius 14, title in the `title` token (no serif), end-aligned buttons. When the
 * buttons would not fit side by side (large font) both go full width, the dismissive one on top.
 */
@Composable
fun DialogContent(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    val c = DayCueTheme.colors
    val stack = LocalDensity.current.fontScale >= 1.3f
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(DayCueShapes.dialog)
            .background(c.surface)
            .padding(24.dp),
    ) {
        Text(title, style = DayCueTheme.type.title, color = c.ink)
        Spacer(Modifier.height(12.dp))
        Text(text, style = DayCueTheme.type.body, color = c.ink)
        Spacer(Modifier.height(DayCueSpacing.related))
        if (stack) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(dismissLabel, onDismiss, Modifier.fillMaxWidth())
                if (destructive) DestructiveButton(confirmLabel, onConfirm, Modifier.fillMaxWidth())
                else PrimaryButton(confirmLabel, onConfirm, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SecondaryButton(dismissLabel, onDismiss)
                if (destructive) DestructiveButton(confirmLabel, onConfirm) else PrimaryButton(confirmLabel, onConfirm)
            }
        }
    }
}

/** Only for destructive or sensitive confirmations (for example medication changes). Scrim token, own fade. */
@Composable
fun DayCueDialog(
    title: String,
    text: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    FadeOverlay(onDismiss = onDismiss, bottom = false) { finish ->
        DialogContent(
            title, text, confirmLabel, dismissLabel,
            onConfirm = { finish(onConfirm) },
            onDismiss = { finish(onDismiss) },
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            destructive = destructive,
        )
    }
}

/** `ink` fill, `paper` text, radius 8, with an "Undo" text action. */
@Composable
fun DayCueSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = DayCueTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(DayCueShapes.snackbar)
            .background(c.ink)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = DayCueTheme.type.bodySmall, color = c.paper, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (actionLabel != null) {
            DayCueTextButton(actionLabel, onAction, color = c.paper)
        }
    }
}

@Composable
fun DayCueSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier.padding(horizontal = 16.dp)) { data ->
        DayCueSnackbar(
            message = data.visuals.message,
            actionLabel = data.visuals.actionLabel,
            onAction = { data.performAction() },
        )
    }
}

/** True while a touch-exploration service (TalkBack) is running. */
@Composable
fun rememberTalkBackOn(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var on by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return on
}

/** Undo snackbar: timed (Long, about 10s) normally, sticky until dismissed while TalkBack is on. */
suspend fun SnackbarHostState.showUndo(message: String, undoLabel: String, talkBackOn: Boolean): SnackbarResult =
    showSnackbar(
        message = message,
        actionLabel = undoLabel,
        duration = if (talkBackOn) SnackbarDuration.Indefinite else SnackbarDuration.Long,
    )
