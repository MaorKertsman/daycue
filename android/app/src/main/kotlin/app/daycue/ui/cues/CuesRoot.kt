package app.daycue.ui.cues

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.domain.config.DayCueConfig
import app.daycue.ui.components.DayCueSnackbarHost
import app.daycue.ui.components.rememberTalkBackOn
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.collectLatest

/**
 * Root of the Cues tab. Hosts its own inner navigation (a saveable route stack) and handles [startItem] deep
 * links: `habit:<id>`, `med:<id>[|date|time]`, `routine:<id>`, `routine-prompt:<id>`, `alarm:<id>`, `posture`,
 * or a bare habit / medication / routine / alarm id.
 *
 * @param onOpenCueProfile opens the cue customization of an item in Setup (profile id, or null for the type default).
 *   Default: the `daycue://open/setup?item=cueprofile:<id>` deep link, which the app shell routes to Setup.
 * @param onOpenCalendar opens Calendar cues (owned by Setup). Default: the `daycue://open/calendar` deep link.
 * @param bottomPadding space the host reserves at the bottom (its navigation bar).
 */
@Composable
fun CuesRoot(
    startItem: String? = null,
    onOpenCueProfile: ((profileId: String?) -> Unit)? = null,
    onOpenCalendar: (() -> Unit)? = null,
    bottomPadding: Dp = 16.dp,
) {
    val vm: CuesViewModel = viewModel()
    val appContext = LocalContext.current
    val openProfile: (String?) -> Unit = onOpenCueProfile ?: { id ->
        appContext.startActivity(app.daycue.delivery.DeepLinks.intent(appContext, "setup", id?.let { "cueprofile:$it" } ?: "sounds"))
    }
    val openCalendar: () -> Unit = onOpenCalendar ?: {
        appContext.startActivity(app.daycue.delivery.DeepLinks.intent(appContext, "calendar"))
    }
    val config by vm.config.collectAsState()
    val stack = rememberSaveable(saver = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf("home")
    }
    val route = stack.last()
    fun push(r: String) { if (stack.last() != r) stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Deep link: resolved once the config is known, then consumed.
    var handled by rememberSaveable(startItem) { androidx.compose.runtime.mutableStateOf(startItem == null) }
    LaunchedEffect(startItem, config != null) {
        val cfg = config
        if (!handled && cfg != null) {
            handled = true
            resolveStartItem(startItem.orEmpty(), cfg)?.let { target ->
                stack.clear(); stack.add("home"); target.forEach { stack.add(it) }
            }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val talkBack = rememberTalkBackOn()
    val undoLabel = stringResource(R.string.cues_undo)
    LaunchedEffect(Unit) {
        vm.messages.collectLatest { m ->
            val text = context.getString(m.textRes, *m.args.toTypedArray())
            val result = snackbar.showSnackbar(
                message = text,
                actionLabel = if (m.undo) undoLabel else null,
                duration = if (talkBack && m.undo) SnackbarDuration.Indefinite else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undo()
        }
    }

    val reduce = LocalReduceMotion.current
    CompositionLocalProvider(LocalCuesBottomPadding provides bottomPadding) {
        Box(Modifier.fillMaxSize().background(DayCueTheme.colors.paper)) {
            Crossfade(targetState = route, animationSpec = tween(if (reduce) 0 else 150), label = "cues-route") { r ->
                CuesRoute(
                    route = r, vm = vm, push = ::push, pop = ::pop,
                    onOpenCueProfile = openProfile, onOpenCalendar = openCalendar,
                )
            }
            DayCueSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding))
        }
    }
}

@Composable
private fun CuesRoute(
    route: String,
    vm: CuesViewModel,
    push: (String) -> Unit,
    pop: () -> Unit,
    onOpenCueProfile: (String?) -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val parts = route.split("/")
    when (parts[0]) {
        "home" -> CuesHome(vm, push, onOpenCalendar)
        "habit" -> HabitEditorScreen(vm, parts[1], pop, onOpenCueProfile)
        "posture" -> if (parts.getOrNull(1) == "live") PostureLiveScreen(vm, pop, push) else PostureEditorScreen(vm, pop, push, onOpenCueProfile)
        "meds" -> MedicationListScreen(vm, pop, push)
        "med" -> when (parts[1]) {
            "history" -> MedicationHistoryScreen(vm, pop)
            else -> MedicationEditorScreen(vm, parts[1], pop, onOpenCueProfile)
        }
        "routines" -> RoutineListScreen(vm, pop, push)
        "routine" -> if (parts.getOrNull(2) == "play") RoutinePlaybackScreen(vm, parts[1], pop) else RoutineEditorScreen(vm, parts[1], pop, push, onOpenCueProfile)
        "alarms" -> AlarmListScreen(vm, pop, push)
        "alarm" -> AlarmEditorScreen(vm, parts[1], pop, push)
        else -> CuesHome(vm, push, onOpenCalendar)
    }
}

/** Maps a deep-link item key to the route stack above "home", or null when nothing matches. */
internal fun resolveStartItem(raw: String, cfg: DayCueConfig): List<String>? {
    val key = raw.trim()
    if (key.isEmpty()) return null
    if (key.startsWith("route:")) return key.removePrefix("route:").split(',').filter { it.isNotBlank() } // debug / screenshots
    val prefix = key.substringBefore(':', "")
    val rest = key.substringAfter(':', key)
    return when (prefix) {
        "habit" -> cfg.habit(rest)?.let { listOf("habit/${it.id}") }
        "med" -> {
            val id = rest.substringBefore('|')
            if (cfg.medication(id) != null) listOf("meds") else if (rest == "merged" || rest == "policy") listOf("meds") else null
        }
        "routine", "routine-prompt" -> cfg.routine(rest)?.let { listOf("routines", "routine/${it.id}/play") }
        "alarm" -> cfg.alarm(rest)?.let { listOf("alarms", "alarm/${it.id}") }
        "posture" -> listOf("posture", "posture/live")
        else -> when {
            key == "posture" -> listOf("posture", "posture/live")
            cfg.habit(key) != null -> listOf("habit/$key")
            cfg.medication(key) != null -> listOf("meds", "med/$key")
            cfg.routine(key) != null -> listOf("routines", "routine/$key")
            cfg.alarm(key) != null -> listOf("alarms", "alarm/$key")
            else -> null
        }
    }
}
