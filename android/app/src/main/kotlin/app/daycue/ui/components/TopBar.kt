package app.daycue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme

/**
 * The ONE top bar for every screen that is not Today or playback (REVIEW-2 S1).
 *  - Tab root ([onBack] == null): the serif `headline` title at the gutter, no back arrow.
 *  - Sub-screen: 48dp back icon at the gutter, an optional 28dp cue [mark], then the `headline` title.
 *  - [actions]: one trailing slot (enable switch or one icon button) with end padding equal to the gutter.
 * The bar paints `paper` and pads for the status bar itself (`statusBarsPadding` consumes the inset, so it is
 * harmless when a parent already applied it), so scrolled content never draws under the clock when the bar is the
 * first child of a non-scrolling parent and the body scrolls below it.
 */
@Composable
fun DayCueTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    mark: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val gutter = DayCueSpacing.gutterFor(LocalConfiguration.current.screenWidthDp)
    Row(
        modifier
            .fillMaxWidth()
            .background(DayCueTheme.colors.paper)
            .statusBarsPadding()
            .defaultMinSize(minHeight = 56.dp)
            .padding(start = if (onBack != null) gutter - 12.dp else gutter, end = gutter),
        verticalAlignment = Alignment.Top,
    ) {
        if (onBack != null) {
            // 48dp target, centred on the first title line (36sp line height => 6dp offset).
            GlyphButton(Glyph.Chevron, stringResource(R.string.app_back), onBack, Modifier.rotate(180f).padding(0.dp))
            Spacer(Modifier.width(4.dp))
        }
        if (mark != null) {
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 10.dp, end = 12.dp)) { mark() }
        }
        Text(
            title,
            style = DayCueTheme.type.headline,
            color = DayCueTheme.colors.ink,
            modifier = Modifier
                .weight(1f)
                .padding(top = if (onBack != null) 6.dp else 10.dp, bottom = 6.dp)
                .semantics { heading() },
        )
        actions()
    }
}
