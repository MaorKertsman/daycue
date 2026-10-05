package app.daycue.ui.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.components.DayCueBottomNav
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.NavItem
import app.daycue.ui.cues.CuesRoot
import app.daycue.ui.onboarding.OnboardingFlow
import app.daycue.ui.readiness.ReadinessScreen
import app.daycue.ui.setup.SetupRoot
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.today.TodayInitial
import app.daycue.ui.today.TodayNav
import app.daycue.ui.today.TodayScreen

/**
 * The app shell: first-run onboarding, then three tabs (Today, Cues, Setup) with the design-system bottom nav.
 * Each tab keeps its own saved state; reselecting the active tab pops it to its root; Back on a tab root goes to
 * Today and on Today exits. A deep link [route] (with a [routeNonce] so the same link can act twice) selects the
 * tab and hands `startItem` to the Cues or Setup roots, which own their inner navigation.
 */
@Composable
fun AppRoot(route: AppRoute?, routeNonce: Int) {
    val context = LocalContext.current
    val prefs = remember { UiPrefs(context) }
    var onboardingDone by remember { mutableStateOf(prefs.onboardingDone) }
    if (!onboardingDone) {
        OnboardingFlow(onFinished = { prefs.onboardingDone = true; onboardingDone = true })
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(Tab.Today.ordinal) }
    var readinessOpen by rememberSaveable { mutableStateOf(false) }
    var cuesStart by rememberSaveable { mutableStateOf<String?>(null) }
    var setupStart by rememberSaveable { mutableStateOf<String?>(null) }
    var cuesKey by rememberSaveable { mutableIntStateOf(0) }
    var setupKey by rememberSaveable { mutableIntStateOf(0) }
    var todayInitial by remember { mutableStateOf(TodayInitial()) }
    var handledNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(routeNonce) {
        val r = route
        if (r != null && routeNonce != handledNonce) {
            handledNonce = routeNonce
            readinessOpen = r is AppRoute.Readiness
            tab = r.tab().ordinal
            when (r) {
                is AppRoute.Cues -> { cuesStart = r.startItem; cuesKey++ }
                is AppRoute.Setup -> { setupStart = r.startItem; setupKey++ }
                is AppRoute.ItemDetail -> todayInitial = TodayInitial(detailKey = r.itemKey, nonce = routeNonce)
                AppRoute.ContextSheet -> todayInitial = TodayInitial(openContext = true, nonce = routeNonce)
                else -> todayInitial = TodayInitial(nonce = routeNonce)
            }
        }
    }

    if (readinessOpen) {
        ReadinessScreen(onBack = { readinessOpen = false })
        return
    }

    // Back from Cues or Setup goes to Today (their own inner handlers, composed deeper, win first).
    BackHandler(enabled = tab != Tab.Today.ordinal) { tab = Tab.Today.ordinal }

    val nav = remember {
        TodayNav(
            openCues = { item -> cuesStart = item; cuesKey++; tab = Tab.Cues.ordinal },
            openSetup = { item -> setupStart = item; setupKey++; tab = Tab.Setup.ordinal },
            openReadiness = { readinessOpen = true },
        )
    }

    val holder = rememberSaveableStateHolder()
    val c = DayCueTheme.colors
    Column(Modifier.fillMaxSize().background(c.paper)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val current = Tab.entries[tab.coerceIn(0, Tab.entries.lastIndex)]
            holder.SaveableStateProvider(current.name) {
                when (current) {
                    Tab.Today -> TodayScreen(nav, todayInitial, bottomPadding = 16.dp)
                    Tab.Cues -> androidx.compose.runtime.key(cuesKey) { Box(Modifier.fillMaxSize().statusBarsPadding()) { CuesRoot(cuesStart) } }
                    Tab.Setup -> androidx.compose.runtime.key(setupKey) { Box(Modifier.fillMaxSize().statusBarsPadding()) { SetupRoot(setupStart) } }
                }
            }
        }
        Box(Modifier.fillMaxWidth().background(c.paper).navigationBarsPadding()) {
            DayCueBottomNav(
                items = listOf(
                    NavItem(stringResource(R.string.app_nav_today), Glyph.Today),
                    NavItem(stringResource(R.string.app_nav_cues), Glyph.Cues),
                    NavItem(stringResource(R.string.app_nav_setup), Glyph.Setup),
                ),
                selectedIndex = tab,
                onSelect = { i ->
                    if (i == tab) {
                        // Reselecting the active tab pops it to its root.
                        when (Tab.entries[i]) {
                            Tab.Cues -> { cuesStart = null; cuesKey++ }
                            Tab.Setup -> { setupStart = null; setupKey++ }
                            Tab.Today -> todayInitial = TodayInitial(nonce = todayInitial.nonce + 1)
                        }
                    } else tab = i
                },
            )
        }
    }
}
