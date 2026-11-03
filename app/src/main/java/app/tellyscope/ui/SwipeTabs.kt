// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** A sideways swipe past this moves one tab; a shorter one does nothing. */
private val SWIPE_TAB = 48.dp

/** The tab a sideways swipe of [dx] lands on from [index] of [count]: the next one going left, the previous one going right, none past either end. */
internal fun swipedTab(index: Int, count: Int, dx: Float, rtl: Boolean, far: Float): Int? {
    if (abs(dx) < far) return null
    return (index + if ((dx < 0) != rtl) 1 else -1).takeIf { it in 0 until count }
}

/**
 * Swiping sideways over a tab's content moves to the next or previous tab, the same as tapping it.
 * Only a drag that goes sideways before it goes down is taken, so the list under it still scrolls.
 */
internal fun Modifier.swipeTabs(index: Int, count: Int, onSelect: (Int) -> Unit): Modifier = composed {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val current by rememberUpdatedState(index)
    val select by rememberUpdatedState(onSelect)
    pointerInput(count, rtl) {
        val far = SWIPE_TAB.toPx()
        var dx = 0f
        detectHorizontalDragGestures(
            onDragStart = { dx = 0f },
            onDragEnd = { swipedTab(current, count, dx, rtl, far)?.let(select) },
        ) { change, amount ->
            change.consume()
            dx += amount
        }
    }
}
