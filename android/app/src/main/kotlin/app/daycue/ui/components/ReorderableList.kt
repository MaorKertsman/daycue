package app.daycue.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.daycue.R
import app.daycue.ui.theme.DayCueSpacing
import app.daycue.ui.theme.DayCueTheme

/**
 * Drag handle (six dots, 48dp) with accessible alternatives: each row exposes "Move up" / "Move down" custom
 * actions and announces "Moved to position 2 of 4". Rows swap in place with no animation, so there is nothing
 * to switch off under reduced motion.
 *
 * [onMove] receives the current and target index; the caller owns the list.
 */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    keyOf: (T) -> Any,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, index: Int, handle: @Composable () -> Unit) -> Unit,
) {
    val heights = remember { mutableStateMapOf<Any, Int>() }
    var draggingKey by remember { mutableStateOf<Any?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val view = LocalView.current
    val moveUp = stringResource(R.string.cd_move_up)
    val moveDown = stringResource(R.string.cd_move_down)
    val movedTemplate = stringResource(R.string.reorder_moved)

    fun announce(position: Int) {
        @Suppress("DEPRECATION")
        view.announceForAccessibility(movedTemplate.format(position, items.size))
    }

    Column(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val itemKey = keyOf(item)
            key(itemKey) {
                val dragging = draggingKey == itemKey
                val actions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index, index - 1); announce(index); true })
                    if (index < items.lastIndex) add(CustomAccessibilityAction(moveDown) { onMove(index, index + 1); announce(index + 2); true })
                }
                Box(
                    Modifier
                        .onSizeChanged { heights[itemKey] = it.height }
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .semantics(mergeDescendants = true) { customActions = actions },
                ) {
                    itemContent(item, index) {
                        val handleDescription = stringResource(R.string.cd_drag_handle)
                        Box(
                            Modifier
                                .size(DayCueSpacing.minTouch)
                                .semantics { contentDescription = handleDescription }
                                .pointerInput(itemKey, items.size) {
                                    detectDragGestures(
                                        onDragStart = { draggingKey = itemKey; dragOffset = 0f },
                                        onDragEnd = { draggingKey = null; dragOffset = 0f },
                                        onDragCancel = { draggingKey = null; dragOffset = 0f },
                                    ) { change, drag ->
                                        change.consume()
                                        dragOffset += drag.y
                                        val current = items.indexOfFirst { keyOf(it) == itemKey }
                                        if (current < 0) return@detectDragGestures
                                        if (dragOffset > 0 && current < items.lastIndex) {
                                            val h = heights[keyOf(items[current + 1])] ?: 0
                                            if (h > 0 && dragOffset > h / 2f) {
                                                onMove(current, current + 1)
                                                dragOffset -= h
                                                announce(current + 2)
                                            }
                                        } else if (dragOffset < 0 && current > 0) {
                                            val h = heights[keyOf(items[current - 1])] ?: 0
                                            if (h > 0 && -dragOffset > h / 2f) {
                                                onMove(current, current - 1)
                                                dragOffset += h
                                                announce(current)
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            GlyphIcon(Glyph.Handle, DayCueTheme.colors.ink2)
                        }
                    }
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") 4.dp
}
