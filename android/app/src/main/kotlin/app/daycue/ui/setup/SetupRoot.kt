package app.daycue.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import app.daycue.DayCueApplication
import app.daycue.ui.components.DayCueSnackbarHost
import app.daycue.ui.components.rememberTalkBackOn
import app.daycue.ui.components.showUndo
import kotlinx.coroutines.launch

/** Routes of the Setup area, kept as strings so the back stack survives rotation and process death. */
internal object SetupRoutes {
    const val HOME = "home"
    const val PLACES = "places"
    const val PLACE = "place:" // + id, or NEW
    const val NEW = "new"
    const val CONTEXT = "context"
    const val CALENDAR = "calendar"
    const val CALENDAR_PREVIEW = "calendar-preview"
    const val CALENDAR_RULE = "calendar-rule:" // + id, or NEW
    const val SOUNDS = "sounds"
    const val PROFILE = "profile:" // + id
    const val SPEECH = "speech"
    const val QUIET = "quiet"
    const val INTEGRATIONS = "integrations"
    const val COMPANION = "companion"
    const val REMOTE = "remote"
    const val SPOTIFY = "spotify"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"
    const val READINESS = "readiness"
}

/**
 * The back stack a deep link builds (UX 1.4: the parents come first, so Back walks up the tree). Accepts a place
 * id, `places`, `calendar`, `remote` (optionally `remote:<command or grant id>`), `cueprofile:<id>`, `sounds`,
 * `integrations`, `settings`, `readiness`. Anything else is treated as a place id.
 */
internal fun stackFor(startItem: String?): List<String> {
    val item = startItem?.trim().orEmpty()
    val home = SetupRoutes.HOME
    return when {
        item.isEmpty() || item == "setup" -> listOf(home)
        item == "places" -> listOf(home, SetupRoutes.PLACES)
        item == "context" -> listOf(home, SetupRoutes.PLACES, SetupRoutes.CONTEXT)
        item == "calendar" -> listOf(home, SetupRoutes.CALENDAR)
        item == "calendar-preview" || item.startsWith("cal:") -> listOf(home, SetupRoutes.CALENDAR, SetupRoutes.CALENDAR_PREVIEW)
        item == "sounds" -> listOf(home, SetupRoutes.SOUNDS)
        item.startsWith("cueprofile:") -> listOf(home, SetupRoutes.SOUNDS, SetupRoutes.PROFILE + item.removePrefix("cueprofile:"))
        item == "integrations" -> listOf(home, SetupRoutes.INTEGRATIONS)
        item == "companion" -> listOf(home, SetupRoutes.INTEGRATIONS, SetupRoutes.COMPANION)
        item == "spotify" -> listOf(home, SetupRoutes.INTEGRATIONS, SetupRoutes.SPOTIFY)
        item == "remote" || item.startsWith("remote:") -> listOf(home, SetupRoutes.INTEGRATIONS, SetupRoutes.REMOTE)
        item == "settings" -> listOf(home, SetupRoutes.SETTINGS)
        item == "speech" -> listOf(home, SetupRoutes.SOUNDS, SetupRoutes.SPEECH)
        item == "quiet" -> listOf(home, SetupRoutes.QUIET)
        item == "about" -> listOf(home, SetupRoutes.SETTINGS, SetupRoutes.ABOUT)
        item == "privacy" -> listOf(home, SetupRoutes.SETTINGS, SetupRoutes.PRIVACY)
        item.startsWith("calendar-rule:") -> listOf(home, SetupRoutes.CALENDAR, SetupRoutes.CALENDAR_RULE + item.removePrefix("calendar-rule:"))
        item == "readiness" -> listOf(home, SetupRoutes.READINESS)
        else -> listOf(home, SetupRoutes.PLACES, SetupRoutes.PLACE + item.removePrefix("place:"))
    }
}

/**
 * Root of the Setup tab. Hosts its own inner navigation (a small back stack of route strings; system back pops it,
 * and on the root it falls through to the app shell). [startItem] deep-links: a place id, `calendar`, `remote`
 * (+ command or grant id), `cueprofile:<id>`.
 *
 * [readiness] renders the Reminder readiness screen owned by the app shell engineer. The default is the real
 * `ReadinessScreen`; [ReadinessPlaceholder] remains only for previews and tests.
 */
@Composable
fun SetupRoot(
    startItem: String? = null,
    readiness: @Composable (onBack: () -> Unit) -> Unit = { onBack -> app.daycue.ui.readiness.ReadinessScreen(onBack) },
) {
    val stack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { stackFor(startItem).toMutableStateList() }
    var handledStart by rememberSaveable { mutableStateOf(startItem) }
    LaunchedEffect(startItem) {
        if (startItem != handledStart) {
            handledStart = startItem
            stack.clear()
            stack.addAll(stackFor(startItem))
        }
    }
    val push: (String) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    val route = stack.lastOrNull() ?: SetupRoutes.HOME
    val remoteFocus = startItem?.takeIf { it.startsWith("remote:") }?.removePrefix("remote:")

    val context = LocalContext.current
    val resources by rememberUpdatedState(LocalResources.current)
    val host = remember { SnackbarHostState() }
    val talkBack = rememberTalkBackOn()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        SetupBus.snacks.collect { snack ->
            val text = resources.getString(snack.message, *snack.args.toTypedArray())
            if (snack.undo) {
                val undoLabel = resources.getString(app.daycue.R.string.su_undo)
                val result = host.showUndo(text, undoLabel, talkBack)
                if (result == SnackbarResult.ActionPerformed) {
                    val facade = (context.applicationContext as DayCueApplication).container.facade
                    scope.launch { facade.undo() }
                }
            } else {
                host.showSnackbar(text)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            route == SetupRoutes.HOME -> SetupHomeScreen(push)
            route == SetupRoutes.PLACES -> PlacesScreen(pop, push)
            route.startsWith(SetupRoutes.PLACE) -> PlaceEditorScreen(route.removePrefix(SetupRoutes.PLACE), pop)
            route == SetupRoutes.CONTEXT -> ContextSettingsScreen(pop)
            route == SetupRoutes.CALENDAR -> CalendarScreen(pop, push)
            route == SetupRoutes.CALENDAR_PREVIEW -> CalendarPreviewScreen(pop)
            route.startsWith(SetupRoutes.CALENDAR_RULE) -> CalendarRuleScreen(route.removePrefix(SetupRoutes.CALENDAR_RULE), pop)
            route == SetupRoutes.SOUNDS -> SoundsScreen(pop, push)
            route.startsWith(SetupRoutes.PROFILE) -> ProfileScreen(route.removePrefix(SetupRoutes.PROFILE), pop)
            route == SetupRoutes.SPEECH -> SpeechScreen(pop)
            route == SetupRoutes.QUIET -> QuietScreen(pop)
            route == SetupRoutes.INTEGRATIONS -> IntegrationsScreen(pop, push)
            route == SetupRoutes.COMPANION -> CompanionScreen(pop)
            route == SetupRoutes.REMOTE -> RemoteScreen(pop, remoteFocus)
            route == SetupRoutes.SPOTIFY -> SpotifyScreen(pop)
            route == SetupRoutes.SETTINGS -> SettingsScreen(pop, push)
            route == SetupRoutes.IMPORT -> ImportScreen(pop)
            route == SetupRoutes.ABOUT -> AboutScreen(pop)
            route == SetupRoutes.PRIVACY -> PrivacyScreen(pop)
            route == SetupRoutes.READINESS -> readiness(pop)
            else -> SetupHomeScreen(push)
        }
        DayCueSnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }
}
