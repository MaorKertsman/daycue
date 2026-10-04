package app.daycue.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.components.ContextLine
import app.daycue.ui.components.ContextLineKind
import app.daycue.ui.components.CueAction
import app.daycue.ui.components.CueCard
import app.daycue.ui.components.DayCueBottomNav
import app.daycue.ui.components.DayCueNavScaffold
import app.daycue.ui.components.RunningRow
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.NavItem
import app.daycue.ui.components.NextRow
import app.daycue.ui.components.QuickControls
import app.daycue.ui.components.QuickItem
import app.daycue.ui.components.ReadinessRow
import app.daycue.ui.components.ReadinessStatus
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.TopHeader
import app.daycue.ui.field.Field
import app.daycue.ui.field.FieldActive
import app.daycue.ui.field.FieldContext
import app.daycue.ui.marks.CueState
import app.daycue.ui.marks.CueType
import app.daycue.ui.marks.PostureMode
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme

/**
 * Static mock of the Today screen built only from design-system components, with synthetic data
 * (docs/design/VISUAL.md section 8, UX.md section 3.2). Order: context, active, next, what can I do.
 */
@Composable
fun TodayMock(modifier: Modifier = Modifier) {
    val c = DayCueTheme.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val gutter = DayCueSpacing.gutterFor(LocalConfiguration.current.screenWidthDp)
    val pause = stringResource(R.string.today_menu_pause)
    val why = stringResource(R.string.why_now)
    DayCueNavScaffold(
        modifier = modifier.background(c.paper),
        nav = {
            DayCueBottomNav(
                items = listOf(
                    NavItem(stringResource(R.string.nav_today), Glyph.Today),
                    NavItem(stringResource(R.string.nav_cues), Glyph.Cues),
                    NavItem(stringResource(R.string.nav_setup), Glyph.Setup),
                ),
                selectedIndex = tab,
                onSelect = { tab = it },
            )
        },
    ) { bottomPadding ->
        // One vertical scroll for everything; the bottom padding keeps the last control clear of the nav.
        Box(
            Modifier.fillMaxSize().verticalScroll(rememberGalleryScrollState()).statusBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = DayCueSpacing.contentMaxWidth).padding(bottom = bottomPadding)) {
                TopHeader(
                    dateText = stringResource(R.string.today_date),
                    trailingGlyph = Glyph.Help,
                    trailingDescription = stringResource(R.string.today_help),
                    onTrailingClick = {},
                    modifier = Modifier.padding(horizontal = gutter),
                ) {
                    ContextLine(
                        text = stringResource(R.string.today_context),
                        spoken = stringResource(R.string.today_context_spoken),
                        kind = ContextLineKind.Normal,
                        onClick = {},
                    )
                }
                Field(
                    context = FieldContext.Home,
                    active = FieldActive.Posture(PostureMode.Stand, fill = 0.6f, next = PostureMode.Walk),
                    nextCue = CueType.Sunscreen,
                    remainingMinutes = 40,
                    horizonMinutes = 120,
                    description = stringResource(R.string.today_field_description),
                )
                Column(Modifier.padding(horizontal = gutter).padding(top = 12.dp)) {
                    CueCard(
                        cue = CueType.Hydration,
                        title = stringResource(R.string.today_now_title),
                        statusWord = stringResource(R.string.state_due),
                        statusDetail = stringResource(R.string.today_now_detail),
                        actions = listOf(
                            CueAction(stringResource(R.string.today_action_done), {}, primary = true),
                            CueAction(stringResource(R.string.today_action_snooze), {}),
                        ),
                        moreActions = listOf(CueAction(pause, {}), CueAction(why, {})),
                    )
                    // What is running, in words: the Field's shapes are never the only carrier.
                    RunningRow(
                        CueType.Posture,
                        stringResource(R.string.cue_posture),
                        stringResource(R.string.today_running_detail),
                        postureMode = PostureMode.Stand,
                        divider = false,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    SectionHeader(stringResource(R.string.today_next))
                    NextRow(CueType.Sunscreen, stringResource(R.string.cue_sunscreen), stringResource(R.string.today_next_sunscreen))
                    NextRow(CueType.Medication, stringResource(R.string.sample_medication), stringResource(R.string.today_next_medication))
                    ReadinessRow(
                        name = stringResource(R.string.today_readiness_name),
                        status = ReadinessStatus.Off,
                        consequence = stringResource(R.string.today_readiness_consequence),
                        onFix = {},
                        divider = false,
                    )
                    // Current state: indoors + working, so "Indoors" is hidden and "Start working" reads "End session".
                    QuickControls(
                        items = listOf(
                            QuickItem(stringResource(R.string.today_quick_outdoors), {}),
                            QuickItem(stringResource(R.string.today_quick_end), {}),
                            QuickItem(stringResource(R.string.today_quick_leaving), {}),
                        ),
                        more = QuickItem(stringResource(R.string.today_quick_more), {}),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}
