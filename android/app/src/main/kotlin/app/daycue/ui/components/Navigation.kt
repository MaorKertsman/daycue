package app.daycue.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.daycue.ui.theme.DayCueTheme

class NavItem(val label: String, val glyph: Glyph)

/**
 * Bottom navigation: 72dp (grows with font scale) on `paper` with a 1dp `outline` top hairline. Three
 * destinations, each a 24dp icon and an always-visible `labelSmall` label. Selected = filled `ink` icon plus a
 * 20x4dp `ink` slab above it, no pill. Unselected = `ink2` outline icon.
 */
@Composable
fun DayCueBottomNav(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = DayCueTheme.colors
    Column(modifier.fillMaxWidth().background(c.paper)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 71.dp)
                        .selectable(selected = selected, role = Role.Tab, interactionSource = null, indication = null) { onSelect(index) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(width = 20.dp, height = 4.dp)
                            .background(if (selected) c.ink else Color.Transparent, RoundedCornerShape(2.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                    GlyphIcon(item.glyph, if (selected) c.ink else c.ink2, filled = selected)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        item.label,
                        style = DayCueTheme.type.labelSmall,
                        color = if (selected) c.ink else c.ink2,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Screen frame with the bottom navigation overlaid at the bottom. [content] gets `bottomPadding` = measured nav
 * height (which already includes the system navigation-bar inset and grows with font scale) + 16dp. A screen puts
 * ALL its content in one vertical scroll with this as the bottom content padding, so nothing ever sits under the
 * nav hairline and the last item can scroll fully clear of it.
 */
@Composable
fun DayCueNavScaffold(
    nav: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (bottomPadding: Dp) -> Unit,
) {
    val density = LocalDensity.current
    var navHeightPx by remember { mutableIntStateOf(0) }
    val bottomPadding = with(density) { navHeightPx.toDp() } + 16.dp
    Box(modifier.fillMaxSize()) {
        content(bottomPadding)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(DayCueTheme.colors.paper)
                .onSizeChanged { navHeightPx = it.height }
                .navigationBarsPadding(),
        ) { nav() }
    }
}
