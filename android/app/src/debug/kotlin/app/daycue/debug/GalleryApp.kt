package app.daycue.debug

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import app.daycue.R
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme

enum class GalleryPage(val key: String, @StringRes val title: Int) {
    Today("today", R.string.gal_page_today),
    Marks("marks", R.string.gal_page_marks),
    Field("field", R.string.gal_page_field),
    Controls("controls", R.string.gal_page_controls),
    Rows("rows", R.string.gal_page_rows),
    Pickers("pickers", R.string.gal_page_pickers),
    Overlays("overlays", R.string.gal_page_overlays),
    States("states", R.string.gal_page_states);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: Marks
    }
}

@Composable
fun GalleryApp(initial: GalleryPage, chrome: Boolean, openSheet: Boolean, openDialog: Boolean) {
    var page by rememberSaveable { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().background(DayCueTheme.colors.paper)) {
        if (chrome) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GalleryPage.entries.forEach { p ->
                    DayCueTextButton(
                        stringResource(p.title),
                        { page = p },
                        color = if (p == page) DayCueTheme.colors.ink else DayCueTheme.colors.ink2,
                    )
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                GalleryPage.Today -> TodayMock()
                GalleryPage.Marks -> GalleryScroll(statusBar = !chrome) { MarksPage() }
                GalleryPage.Field -> GalleryScroll(statusBar = !chrome, gutter = false) { FieldPage() }
                GalleryPage.Controls -> GalleryScroll(statusBar = !chrome) { ControlsPage() }
                GalleryPage.Rows -> GalleryScroll(statusBar = !chrome) { RowsPage() }
                GalleryPage.Pickers -> GalleryScroll(statusBar = !chrome) { PickersPage() }
                GalleryPage.Overlays -> GalleryScroll(statusBar = !chrome) { OverlaysPage(openSheet, openDialog) }
                GalleryPage.States -> GalleryScroll(statusBar = !chrome) { StatesPage() }
            }
        }
    }
}

/** Debug capture aid: initial scroll offset in px (`-e scroll 4000`, or `end`), so screenshots can be taken in segments. */
val LocalInitialScroll = androidx.compose.runtime.compositionLocalOf { 0 }

@Composable
fun rememberGalleryScrollState(): androidx.compose.foundation.ScrollState {
    val state = rememberScrollState()
    val px = LocalInitialScroll.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { state.maxValue }.collect { android.util.Log.i("GalleryScroll", "max=$it") }
    }
    androidx.compose.runtime.LaunchedEffect(px) {
        if (px > 0) {
            androidx.compose.runtime.snapshotFlow { state.maxValue }.first { it > 0 }
            state.scrollTo(px)
        }
    }
    return state
}

@Composable
private fun GalleryScroll(statusBar: Boolean, gutter: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val g = DayCueSpacing.gutterFor(LocalConfiguration.current.screenWidthDp)
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberGalleryScrollState())
            .then(if (statusBar) Modifier.statusBarsPadding() else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = DayCueSpacing.contentMaxWidth)
                .fillMaxWidth()
                .then(if (gutter) Modifier.padding(horizontal = g) else Modifier)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            content = content,
        )
    }
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DayCueTheme.type.bodySmall, color = DayCueTheme.colors.ink2, modifier = modifier.padding(vertical = 4.dp))
}
