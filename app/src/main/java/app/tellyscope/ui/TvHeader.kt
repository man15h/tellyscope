// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import app.tellyscope.R
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.Snapshot
import app.tellyscope.data.deviceKind
import app.tellyscope.data.resolve

/*
 * The TV's header, shared by the tabs that lead with the TV: the big hero, which collapses as the
 * page scrolls, and the bar title it collapses into. The TV's cookie and name travel from one to the
 * other as one piece ([HeroMorph]). The TV chips, on Overview, scroll away with the page.
 */

/**
 * How far the hero has scrolled under the bar, 0 (all in view) to 1 (gone), from the list's
 * first visible item and its offset. The hero is item 0, [heroHeight] px tall; 0 before it's measured.
 */
internal fun collapsedFraction(firstIndex: Int, firstOffset: Int, heroHeight: Int): Float = when {
    firstIndex > 0 -> 1f
    heroHeight <= 0 -> 0f
    else -> (firstOffset.toFloat() / heroHeight).coerceIn(0f, 1f)
}

/** The maker line and health pill go first, over the first half of the collapse. */
internal fun detailAlpha(f: Float) = (1f - 2f * f).coerceIn(0f, 1f)

/** The bar title comes in over the last third, as the hero's name passes under the bar. */
internal fun titleAlpha(f: Float) = ((f - 0.67f) / 0.33f).coerceIn(0f, 1f)

/**
 * The blank space a tab needs after its last item so the hero can scroll fully under the bar,
 * 0 when the tab is long enough already. From the list laid out with the hero and the space both
 * in view: [heroSize] and [spacing] are the hero item and the gap after it, [contentEnd] where the
 * space starts measured from the hero's top, [afterPadding] the list's end padding.
 */
internal fun tailSpace(heroSize: Int, spacing: Int, contentEnd: Int, afterPadding: Int, viewport: Int): Int =
    (heroSize + spacing - (contentEnd + afterPadding - viewport)).coerceAtLeast(0)

/**
 * Where the TV's cookie and name sit in the hero and in the bar, in root coordinates, so that
 * [HeroMorphOverlay] can carry them from one to the other. Positions are read while drawing only.
 */
@Stable
class HeroMorph {
    var heroCookie by mutableStateOf<Rect?>(null)
    var heroName by mutableStateOf<Rect?>(null)
    var barCookie by mutableStateOf<Rect?>(null)
    var barName by mutableStateOf<Rect?>(null)
}

/** The node's whole bounds in the root, unlike boundsInRoot, which a scrolling list clips. */
private fun LayoutCoordinates.rectInRoot() = Rect(positionInRoot(), size.toSize())

/** The TV's cookie tile: primary while it's connected, grey when it isn't. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Cookie(device: Device, size: Dp) {
    val kind = deviceKind(device)
    val c = MaterialTheme.colorScheme
    val on = device.state == DeviceState.Online
    Box(
        Modifier.size(size).clip(MaterialShapes.Cookie9Sided.toShape()).background(if (on) c.primaryContainer else c.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(DeviceIcons.of(kind), contentDescription = kind.word(), Modifier.size(size / 2), tint = if (on) c.onPrimaryContainer else c.outline)
    }
}

/**
 * The TV as the hero: an Expressive cookie-shaped tile, its name, how it's doing, and, on
 * Overview ([chips]), the TV chips. [collapse] is read while drawing only, so scrolling redraws
 * without recomposing: the cookie and name shrink and fade, the details faster. [onHeight] gets
 * the height of everything above the chips.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TvHero(state: UiState, live: Snapshot?, actions: DeviceActions, chips: Boolean = true, collapse: () -> Float = { 0f }, morph: HeroMorph = remember { HeroMorph() }, onHeight: (Int) -> Unit = {}) {
    val device = state.selected ?: return
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // No gap between the two parts: the maker line sits right under the name, as before the collapse.
        Column(Modifier.fillMaxWidth().onSizeChanged { onHeight(it.height) }, horizontalAlignment = Alignment.CenterHorizontally) {
            // Once the page scrolls, the overlay carries the cookie and name to the bar; with no bar to go to, they stay.
            Column(
                Modifier.graphicsLayer { alpha = if (collapse() == 0f || morph.barCookie == null) 1f else 0f },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.onGloballyPositioned { morph.heroCookie = it.rectInRoot() }) { Cookie(device, 80.dp) }
                Text(
                    name(device),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp).onGloballyPositioned { morph.heroName = it.rectInRoot() },
                )
            }
            Column(Modifier.graphicsLayer { alpha = detailAlpha(collapse()) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val made = makerLine(device)
                if (made.isNotEmpty()) Text(made, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                val health = live?.let { Copy.health(it, heaviest(it, state.device.apps)) }
                Surface(shape = CircleShape, color = if (health?.ok == false) c.errorContainer else c.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (health != null) {
                            Icon(if (health.ok) Icons.Default.CheckCircle else Icons.Default.Warning, null, Modifier.size(16.dp), tint = if (health.ok) c.primary else c.error)
                        } else StateDot(device.state)
                        Spacer(Modifier.width(8.dp))
                        Text((health?.text ?: Copy.state(device.state)).resolve(), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (chips) TvChips(state, actions)
    }
}

/** "XGIMI · Horizon Pro" under the TV's name; under a model-name title, the model would only say it twice. */
internal fun makerLine(device: Device): String {
    val maker = device.manufacturer ?: device.hit.maker
    return if (name(device) == device.model) maker.orEmpty() else made(maker, device.model)
}

/**
 * The collapsed hero, in the bar: small cookie, the TV's name, and whether it's connected. It's
 * laid out from the start, so the cookie and name have somewhere to travel to, and shows them
 * itself only once the hero has fully folded; the pill comes in over the last third.
 */
@Composable
fun TvBarTitle(device: Device, collapse: () -> Float, morph: HeroMorph, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val on = device.state == DeviceState.Online
    val folded by remember { derivedStateOf { collapse() >= 1f } }
    DisposableEffect(morph) { onDispose { morph.barCookie = null; morph.barName = null } }
    val landed = Modifier.graphicsLayer { alpha = if (collapse() >= 1f) 1f else 0f }
    Row(if (folded) modifier else modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        Box(landed.onGloballyPositioned { morph.barCookie = it.rectInRoot() }) { Cookie(device, 32.dp) }
        Spacer(Modifier.width(12.dp))
        Text(
            name(device),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).then(landed).onGloballyPositioned { morph.barName = it.rectInRoot() },
        )
        Spacer(Modifier.width(12.dp))
        Surface(modifier = Modifier.graphicsLayer { alpha = titleAlpha(collapse()) }, shape = CircleShape, color = if (on) c.primaryContainer else c.surfaceContainerHighest, contentColor = if (on) c.onPrimaryContainer else c.onSurfaceVariant) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                StateDot(device.state)
                Spacer(Modifier.width(6.dp))
                Text(Copy.state(device.state).resolve(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The TV's cookie and name on their way from the hero to the bar, drawn over the whole screen
 * while the hero is part folded. Each moves and shrinks from where it sits in the hero to where
 * it sits in the bar; at either end the hero or the bar draws its own.
 */
@Composable
internal fun HeroMorphOverlay(device: Device, morph: HeroMorph, collapse: () -> Float) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    fun GraphicsLayerScope.travel(from: Rect?, to: Rect?) {
        val f = collapse()
        if (from == null || to == null || f <= 0f || f >= 1f) { alpha = 0f; return }
        alpha = 1f
        transformOrigin = TransformOrigin(0f, 0f)
        scaleX = lerp(1f, to.height / from.height, f)
        scaleY = scaleX
        translationX = lerp(from.left, to.left, f) - origin.x
        translationY = lerp(from.top, to.top, f) - origin.y
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }.clearAndSetSemantics {}) {
        Box(Modifier.graphicsLayer { travel(morph.heroCookie, morph.barCookie) }) { Cookie(device, 80.dp) }
        Text(
            name(device),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // As wide as the hero's name, so it cuts off where that one does.
            modifier = Modifier
                .layout { m, c ->
                    val w = morph.heroName?.width?.toInt() ?: 0
                    val p = m.measure(if (w > 0) Constraints.fixedWidth(w) else c)
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                .graphicsLayer { travel(morph.heroName, morph.barName) },
        )
    }
}

@Composable
private fun LoggedOutBadge() {
    Box(Modifier.size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error), contentAlignment = Alignment.Center) {
        Icon(DeviceIcons.Logout, contentDescription = Copy.LOGGED_OUT.resolve(), Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onError)
    }
}

/** One chip per TV to switch, offline ones dimmed (logged-out ones badged instead), then "+ Device" as the last chip. */
@Composable
private fun TvChips(state: UiState, actions: DeviceActions) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.devices.size > 1) state.devices.forEach { d ->
            FilterChip(
                selected = d.id == state.selectedId,
                onClick = { actions.select(d.id) },
                label = { Text(name(d), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 104.dp)) },
                leadingIcon = {
                    Box {
                        Icon(DeviceIcons.of(deviceKind(d)), contentDescription = deviceKind(d).word(), Modifier.size(18.dp))
                        Box(Modifier.align(Alignment.BottomEnd).offset(x = 3.dp, y = 3.dp)) {
                            // A logged-out TV keeps its badge, so the logout can still be found from another TV.
                            if (d.loggedOutAt != null) LoggedOutBadge() else StateDot(d.state)
                        }
                    }
                },
                modifier = if (d.state == DeviceState.Online || d.loggedOutAt != null) Modifier else Modifier.alpha(0.6f),
            )
        }
        // Adding a TV sits with the TVs, as the last chip.
        AssistChip(onClick = actions.addDevice, label = { Text(stringResource(R.string.header_add_device_label)) }, leadingIcon = { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.header_add_device_icon), Modifier.size(18.dp)) })
    }
}

/**
 * A tab's own tabs, as connected buttons with a check on the chosen one. The strip runs edge to
 * edge across the list's side [inset], in [color], so it pins cleanly under the bar once the hero has folded.
 * Whatever is [below] the buttons, such as a search field, pins with them.
 */
@Composable
internal fun PinnedSegments(labels: List<String>, selected: Int, color: Color, inset: Dp, below: @Composable () -> Unit = {}, onSelect: (Int) -> Unit) {
    Box(
        Modifier
            .layout { m, c ->
                val side = inset.roundToPx()
                val p = m.measure(c.copy(minWidth = c.maxWidth + 2 * side, maxWidth = c.maxWidth + 2 * side))
                layout(c.maxWidth, p.height) { p.place(-side, 0) }
            }
            .background(color)
            .padding(horizontal = inset, vertical = 8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { i, label ->
                    SegmentedButton(
                        selected = selected == i,
                        onClick = { onSelect(i) },
                        shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                    ) { Text(label) }
                }
            }
            below()
        }
    }
}

/**
 * What a list shows when it has nothing to show: a soft circle with an [icon], a [title], and
 * one [line] under it, in the middle of the page.
 */
@Composable
internal fun EmptyState(icon: ImageVector, title: String, line: String) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, Modifier.size(28.dp), tint = c.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(line, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Where [EmptyState] would sit, while the TV's app list is still on its way. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppsLoading() {
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ContainedLoadingIndicator(Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(Copy.READING_APPS.resolve(), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(Copy.READING_APPS_LINE.resolve(), style
 = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
