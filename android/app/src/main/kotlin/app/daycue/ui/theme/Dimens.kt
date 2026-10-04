package app.daycue.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4dp grid (VISUAL.md section 7). */
object DayCueSpacing {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val inRow = 16.dp
    val gutter = 20.dp
    val gutterNarrow = 16.dp
    val related = 24.dp
    val section = 32.dp
    val contentMaxWidth = 560.dp
    val minTouch = 48.dp
    val buttonHeight = 52.dp
    val rowMin = 64.dp
    val rowMinOneLine = 56.dp
    val markSlot = 40.dp
    val markSize = 28.dp

    fun gutterFor(widthDp: Int): Dp = if (widthDp < 360) gutterNarrow else gutter
}

/** Corner radii. Nothing is fully rounded except discs and the switch. */
object DayCueShapes {
    val plane = RoundedCornerShape(6.dp)
    val chip = RoundedCornerShape(8.dp)
    val button = RoundedCornerShape(14.dp)
    val dialog = RoundedCornerShape(14.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
    val textField = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
    val snackbar = RoundedCornerShape(8.dp)
    val slab = RoundedCornerShape(2.dp)
}

/** Elevation policy: flat. No shadows on rows, buttons or bars; tonal elevation is always 0. */
object DayCueElevation {
    val none = 0.dp
}
