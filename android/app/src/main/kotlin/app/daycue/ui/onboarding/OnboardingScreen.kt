package app.daycue.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import app.daycue.integrations.location.LocationPermissionStep
import app.daycue.integrations.location.CurrentLocationResult
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.GeoPoint
import app.daycue.domain.config.Place
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Language
import app.daycue.domain.edit.ConfigOp
import app.daycue.engine.ApplyOutcome
import app.daycue.facade.DayCueFacade
import app.daycue.system.ReadinessId
import app.daycue.system.ReadinessReport
import app.daycue.ui.app.UiPrefs
import app.daycue.ui.app.rememberFacade
import app.daycue.ui.app.rememberPermissionActions
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphButton
import app.daycue.ui.components.PermissionCard
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.marks.CueMark
import app.daycue.ui.marks.CueType
import app.daycue.ui.readiness.ReadinessScreen
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

class OnboardingViewModel(private val facade: DayCueFacade) : ViewModel() {
    val readiness: StateFlow<ReadinessReport?> get() = facade.readiness

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> get() = _saving
    private val _saveFailed = MutableStateFlow(false)
    val saveFailed: StateFlow<Boolean> get() = _saveFailed
    private val _testSent = MutableStateFlow(false)
    val testSent: StateFlow<Boolean> get() = _testSent

    fun refresh() { viewModelScope.launch { facade.refreshReadiness() } }

    fun calendarGranted(): Boolean = facade.calendar.hasPermission()

    /** Sets the app language in the config (the Activity locale is switched by the caller after this returns). */
    fun setLanguage(language: Language, then: () -> Unit) {
        viewModelScope.launch {
            facade.setAppLanguage(if (language == Language.he) "he" else "en")
            then()
        }
    }

    /** Applies the chosen templates through the same validated edit path as every other change. */
    fun applyTemplates(chosen: Set<Template>, then: () -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            _saveFailed.value = false
            val config: DayCueConfig? = facade.config.firstOrNull()
            val ops = if (config != null) OnboardingPlan.ops(config, chosen) else emptyList()
            val ok = ops.isEmpty() || facade.apply(ops, source = "onboarding") is ApplyOutcome.Applied
            _saving.value = false
            if (ok) then() else _saveFailed.value = true
        }
    }

    fun sendTest() {
        viewModelScope.launch {
            facade.sendTestReminder()
            _testSent.value = true
        }
    }

    class Factory(private val facade: DayCueFacade) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = OnboardingViewModel(facade) as T
    }
}

private const val STEPS = 6
private const val STEP_PERMISSIONS = 2
private const val STEP_PLACES = 3
private const val STEP_TEST = 4

/**
 * First-run flow (UX 3.1): Language, What to start with, Permissions (one card at a time, each with a benefit and a
 * way to say "Not now"), Places (location, skippable), Test, Done. Battery and unused-app pausing are not asked
 * here: they live in Reminder readiness. Progress and choices are kept, Back goes one step back (the first step
 * exits the app). Nothing is seeded: templates start disabled and the medication list stays empty.
 */
@Composable
fun OnboardingFlow(onFinished: () -> Unit) {
    val context = LocalContext.current
    val facade = rememberFacade()
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory(facade))
    val prefs = remember { UiPrefs(context) }
    var step by remember { mutableIntStateOf(prefs.onboardingStep.coerceIn(0, STEPS - 1)) }
    var chosen by remember { mutableStateOf(Template.fromIds(prefs.onboardingChoices)) }
    var skipped by remember { mutableStateOf(prefs.onboardingSkipped) }
    var showReadiness by remember { mutableStateOf(false) }
    fun go(to: Int) { step = to.coerceIn(0, STEPS - 1); prefs.onboardingStep = step }

    if (showReadiness) {
        ReadinessScreen(onBack = { showReadiness = false })
        return
    }
    BackHandler(enabled = step > 0) { go(step - 1) }

    val c = DayCueTheme.colors
    val gutter = DayCueSpacing.gutterFor(LocalConfiguration.current.screenWidthDp)
    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = if (step > 0) gutter - 12.dp else gutter, end = gutter).height(DayCueSpacing.minTouch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    GlyphButton(Glyph.Chevron, stringResource(R.string.app_back), { go(step - 1) }, Modifier.rotate(180f))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    stringResource(R.string.app_step_x_of_y, step + 1, STEPS),
                    style = DayCueTheme.type.label, color = c.ink2,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = DayCueSpacing.contentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = gutter).padding(bottom = 24.dp),
                ) {
                    // Plane, then disc, then rule: the picture is built up as the steps go by.
                    OnboardingComposition(stage = when { step <= 1 -> 1; step <= STEP_PLACES -> 2; else -> 3 })
                    when (step) {
                        0 -> LanguageStep(vm, onNext = { go(1) })
                        1 -> TemplatesStep(
                            vm, chosen,
                            onToggle = { t, on ->
                                chosen = if (on) chosen + t else chosen - t
                                prefs.onboardingChoices = chosen.map { it.id }.toSet()
                            },
                            onNext = { go(2) },
                        )
                        STEP_PERMISSIONS -> PermissionsStep(
                            vm, chosen, skipped,
                            onSkip = { kind -> skipped = skipped + kind.id; prefs.onboardingSkipped = skipped },
                            onNext = { go(STEP_PLACES) },
                        )
                        STEP_PLACES -> PlacesStep(onNext = { go(STEP_TEST) })
                        STEP_TEST -> TestStep(vm, onYes = { go(5) }, onNo = { showReadiness = true }, onSkip = { go(5) })
                        else -> DoneStep(onFinished)
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, body: String? = null) {
    val c = DayCueTheme.colors
    Spacer(Modifier.height(8.dp))
    Text(text, style = DayCueTheme.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
    if (body != null) {
        Spacer(Modifier.height(8.dp))
        Text(body, style = DayCueTheme.type.body, color = c.ink2)
    }
    Spacer(Modifier.height(DayCueSpacing.related))
}

@Composable
private fun LanguageStep(vm: OnboardingViewModel, onNext: () -> Unit) {
    val current = LocalConfiguration.current.locales[0]?.language
    val isHebrew = current == "he" || current == "iw"
    // Both languages in the title: the owner has not chosen one yet.
    Title(stringResource(R.string.app_lang_title), stringResource(R.string.app_lang_body))
    fun choose(lang: Language) {
        if ((lang == Language.he) == isHebrew) return
        vm.setLanguage(lang) {}
    }
    SecondaryButton("English", { choose(Language.en) }, Modifier.fillMaxWidth(), selected = !isHebrew)
    Spacer(Modifier.height(8.dp))
    SecondaryButton("עברית", { choose(Language.he) }, Modifier.fillMaxWidth(), selected = isHebrew)
    Spacer(Modifier.height(DayCueSpacing.related))
    PrimaryButton(stringResource(R.string.app_continue), onNext, Modifier.fillMaxWidth())
}

@Composable
private fun TemplatesStep(vm: OnboardingViewModel, chosen: Set<Template>, onToggle: (Template, Boolean) -> Unit, onNext: () -> Unit) {
    val saving by vm.saving.collectAsState()
    val failed by vm.saveFailed.collectAsState()
    Title(stringResource(R.string.app_tpl_title), stringResource(R.string.app_tpl_body))
    Template.entries.forEachIndexed { i, t ->
        SwitchRow(
            label = stringResource(t.title),
            checked = t in chosen,
            onCheckedChange = { onToggle(t, it) },
            secondary = stringResource(t.summary),
            leading = { CueMark(templateMark(t)) },
            divider = true,
        )
    }
    DayCueRow(
        primary = stringResource(R.string.cue_medication),
        secondary = stringResource(R.string.app_tpl_medication),
        leading = { CueMark(CueType.Medication) },
        divider = false,
    )
    if (failed) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.app_tpl_failed), style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.error.ink)
    }
    Spacer(Modifier.height(DayCueSpacing.related))
    PrimaryButton(stringResource(R.string.app_continue), { if (!saving) vm.applyTemplates(chosen, onNext) }, Modifier.fillMaxWidth(), enabled = !saving)
    // Skip keeps whatever is currently enabled (nothing, on a first run).
    DayCueTextButton(stringResource(R.string.app_skip), onNext)
}

private fun templateMark(t: Template): CueType = when (t) {
    Template.Sunscreen -> CueType.Sunscreen
    Template.Hydration -> CueType.Hydration
    Template.Bottle -> CueType.Bottle
    Template.Posture -> CueType.Posture
    Template.Routine -> CueType.Routine
    Template.Alarm -> CueType.Alarm
    Template.Calendar -> CueType.Calendar
}

/** One permission at a time: the current card carries the only primary (Allow), "Not now" moves to the next. */
@Composable
private fun PermissionsStep(vm: OnboardingViewModel, chosen: Set<Template>, skipped: Set<String>, onSkip: (PermissionKind) -> Unit, onNext: () -> Unit) {
    val facade = rememberFacade()
    val report by vm.readiness.collectAsState()
    val actions = rememberPermissionActions(facade) { vm.refresh() }
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    // Recomputed on every readiness refresh (after returning from a system screen the granted card disappears).
    val cards = OnboardingPlan.cards(chosen, report, vm.calendarGranted(), skipped)
    val current = cards.firstOrNull()
    val c = DayCueTheme.colors
    if (current == null) {
        Title(stringResource(R.string.app_perm_title), stringResource(R.string.app_perm_body))
        Text(
            if (report == null) stringResource(R.string.readiness_checking) else stringResource(R.string.app_perm_none),
            style = DayCueTheme.type.body, color = c.ink,
        )
        Spacer(Modifier.height(DayCueSpacing.related))
        PrimaryButton(stringResource(R.string.app_continue), onNext, Modifier.fillMaxWidth(), enabled = report != null)
        return
    }
    val copy = permissionCopy(current)
    Title(stringResource(R.string.app_perm_title))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(DayCueSpacing.markSlot), contentAlignment = Alignment.Center) { AppMark() }
        Spacer(Modifier.width(DayCueSpacing.inRow))
        Text(stringResource(copy.title), style = DayCueTheme.type.title, color = c.ink, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(copy.why), style = DayCueTheme.type.body, color = c.ink)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(copy.without), style = DayCueTheme.type.bodySmall, color = c.ink2)
    Spacer(Modifier.height(DayCueSpacing.inRow))
    PrimaryButton(
        stringResource(R.string.app_allow),
        {
            when (current) {
                PermissionKind.Notifications -> actions.requestNotifications()
                PermissionKind.ExactAlarms -> actions.openFix(ReadinessId.ExactAlarms)
                PermissionKind.FullScreen -> actions.openFix(ReadinessId.FullScreenIntent)
                PermissionKind.Calendar -> actions.requestCalendar()
            }
        },
        Modifier.fillMaxWidth(),
    )
    DayCueTextButton(stringResource(R.string.app_not_now), { onSkip(current) })
    Text(stringResource(R.string.app_perm_later), style = DayCueTheme.type.bodySmall, color = c.ink2)
}

private class PermissionCopy(val title: Int, val why: Int, val without: Int)

private fun permissionCopy(kind: PermissionKind): PermissionCopy = when (kind) {
    PermissionKind.Notifications -> PermissionCopy(R.string.app_rd_notifications, R.string.app_perm_notif_why, R.string.app_perm_notif_without)
    PermissionKind.ExactAlarms -> PermissionCopy(R.string.app_rd_exact, R.string.app_perm_exact_why, R.string.app_perm_exact_without)
    PermissionKind.FullScreen -> PermissionCopy(R.string.app_rd_fullscreen, R.string.app_perm_fs_why, R.string.app_perm_fs_without)
    PermissionKind.Calendar -> PermissionCopy(R.string.cue_calendar, R.string.app_perm_cal_why, R.string.app_perm_cal_without)
}

/**
 * Places (UX 3.1): location is what lets DayCue notice arriving and leaving. Skippable; foreground first, then the
 * background step as its own explained request (Android rejects a combined one). The saved places themselves are
 * added later in Setup.
 */
@Composable
private fun PlacesStep(onNext: () -> Unit) {
    val facade = rememberFacade()
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(facade.places.nextPermissionStep()) }
    var homeState by remember { mutableStateOf(HomeState.Idle) }
    var homeSkipped by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch {
            facade.places.onPermissionsChanged()
            step = facade.places.nextPermissionStep()
        }
    }
    LifecycleResumeEffect(Unit) {
        scope.launch { facade.places.refreshAccess(); step = facade.places.nextPermissionStep() }
        onPauseOrDispose { }
    }
    val homeName = stringResource(R.string.app_places_home_name)
    fun useHere() {
        if (homeState == HomeState.Locating) return
        homeState = HomeState.Locating
        scope.launch {
            homeState = when (val r = facade.places.currentLocation()) {
                is CurrentLocationResult.Ok -> if (!r.precise) HomeState.Coarse else {
                    val point = GeoPoint(r.fix.lat, r.fix.lng)
                    val existing = facade.config.firstOrNull()?.places?.firstOrNull { it.id == Defaults.HOME }
                    if (existing == null) facade.places.upsertPlace(Place(Defaults.HOME, homeName))
                    if (facade.places.setPlaceLocation(Defaults.HOME, point) is ApplyOutcome.Applied) HomeState.Saved else HomeState.Failed
                }
                else -> HomeState.Failed
            }
        }
    }
    val c = DayCueTheme.colors
    Title(stringResource(R.string.app_places_title), stringResource(R.string.app_places_body))
    when (step) {
        LocationPermissionStep.Foreground, LocationPermissionStep.Precise -> {
            Text(stringResource(R.string.app_places_without), style = DayCueTheme.type.bodySmall, color = c.ink2)
            Spacer(Modifier.height(DayCueSpacing.inRow))
            PrimaryButton(stringResource(R.string.app_places_allow), { launcher.launch(step.permissions.toTypedArray()) }, Modifier.fillMaxWidth())
            DayCueTextButton(stringResource(R.string.app_not_now), onNext)
        }
        else -> {
            // Foreground location is granted. First the optional "set Home from here", then the separate background step.
            if (homeState != HomeState.Saved && !homeSkipped) {
                Text(stringResource(R.string.app_places_home_body), style = DayCueTheme.type.body, color = c.ink)
                when (homeState) {
                    HomeState.Coarse -> Text(stringResource(R.string.app_places_home_coarse), style = DayCueTheme.type.bodySmall, color = c.ink2)
                    HomeState.Failed -> Text(stringResource(R.string.app_places_home_failed), style = DayCueTheme.type.bodySmall, color = c.ink2)
                    HomeState.Locating -> Text(stringResource(R.string.app_places_home_locating), style = DayCueTheme.type.bodySmall, color = c.ink2)
                    else -> {}
                }
                Spacer(Modifier.height(DayCueSpacing.inRow))
                PrimaryButton(stringResource(R.string.app_places_home_use), { useHere() }, Modifier.fillMaxWidth(), enabled = homeState != HomeState.Locating)
                DayCueTextButton(stringResource(R.string.app_not_now), { homeSkipped = true })
            } else if (step == LocationPermissionStep.Background) {
                if (homeState == HomeState.Saved) Text(stringResource(R.string.app_places_home_saved), style = DayCueTheme.type.bodySmall, color = c.ink2)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.app_places_bg_why), style = DayCueTheme.type.body, color = c.ink)
                Spacer(Modifier.height(DayCueSpacing.inRow))
                PrimaryButton(stringResource(R.string.app_places_bg_allow), { launcher.launch(step.permissions.toTypedArray()) }, Modifier.fillMaxWidth())
                DayCueTextButton(stringResource(R.string.app_skip), onNext)
            } else {
                Text(
                    stringResource(if (homeState == HomeState.Saved) R.string.app_places_home_saved else R.string.app_places_done),
                    style = DayCueTheme.type.body, color = c.ink,
                )
                Spacer(Modifier.height(DayCueSpacing.inRow))
                PrimaryButton(stringResource(R.string.app_continue), onNext, Modifier.fillMaxWidth())
            }
        }
    }
}

private enum class HomeState { Idle, Locating, Saved, Coarse, Failed }

@Composable
private fun TestStep(vm: OnboardingViewModel, onYes: () -> Unit, onNo: () -> Unit, onSkip: () -> Unit) {
    val sent by vm.testSent.collectAsState()
    Title(stringResource(R.string.app_test_title), stringResource(R.string.app_test_body))
    if (!sent) {
        PrimaryButton(stringResource(R.string.app_rd_send_test), { vm.sendTest() }, Modifier.fillMaxWidth())
    } else {
        Text(stringResource(R.string.app_test_question), style = DayCueTheme.type.title, color = DayCueTheme.colors.ink)
        Spacer(Modifier.height(DayCueSpacing.inRow))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(stringResource(R.string.app_yes), onYes, Modifier.weight(1f))
            SecondaryButton(stringResource(R.string.app_no), onNo, Modifier.weight(1f))
        }
        DayCueTextButton(stringResource(R.string.app_test_again), { vm.sendTest() })
    }
    DayCueTextButton(stringResource(R.string.app_skip), onSkip)
}

@Composable
private fun DoneStep(onFinished: () -> Unit) {
    Title(stringResource(R.string.app_done_title), stringResource(R.string.app_done_body))
    PrimaryButton(stringResource(R.string.app_done_button), onFinished, Modifier.fillMaxWidth())
}
