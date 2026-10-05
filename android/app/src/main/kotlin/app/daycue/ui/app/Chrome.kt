package app.daycue.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarHostState
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
import app.daycue.ui.components.DayCueSnackbarHost
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphButton
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text

/** Back button + title row for full-screen pages (the chevron points to the start edge and flips in RTL). */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val gutter = DayCueSpacing.gutterFor(LocalConfiguration.current.screenWidthDp)
    Row(modifier.fillMaxWidth().padding(horizontal = gutter - 12.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Chevron, stringResource(R.string.app_back), onBack, Modifier.rotate(180f))
        Text(
            title,
            style = DayCueTheme.type.title,
            color = DayCueTheme.colors.ink,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { heading() },
        )
    }
}

/**
 * A full-screen page on paper: header, then ONE vertical scroll with gutters, a readable max width and the system
 * bars respected. Nothing is ever clipped at font scale 2.0 because the whole body scrolls.
 */
@Composable
fun ScrollPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DayCueTheme.colors
    val gutter = DayCueSpacing.gutterFor(LocalConfiguration.current.screenWidthDp)
    Box(modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            ScreenHeader(title, onBack)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = DayCueSpacing.contentMaxWidth)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = gutter)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.Top,
                    content = content,
                )
            }
        }
        if (snackbar != null) DayCueSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp))
    }
}
