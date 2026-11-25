// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tellyscope.R
import app.tellyscope.adb.GamepadButton
import app.tellyscope.adb.GamepadReport
import app.tellyscope.adb.HidDevice
import app.tellyscope.adb.RemoteKey
import app.tellyscope.data.RemotePad
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * The pad's sizes at full size, on a phone with room for them, such as a Pixel 2 XL held sideways. On a
 * shorter screen every one shrinks by the same share, so the pad keeps its shape. [END] is the round
 * ends of the pad's body, and [DIMPLE] the dimple and bowl set in them.
 */
private val END = 280.dp
private val DIMPLE = 208.dp
/** The D-pad: one cross, each of its four arms its own key. */
private val CROSS = 160.dp
private val ARM = 52.dp
/** The face keys, and how far each sits from the middle of the bowl. */
private val FACE_KEY = 52.dp
private val FACE_STEP = 54.dp
private val HOME_KEY = 52.dp
private val PILL = DpSize(64.dp, 28.dp)
private val STICK_WELL = 168.dp
private val STICK_KNOB = 72.dp
/** Room around each key for the held ring: 2 dp of gap, then 2 dp of ring. */
private val RING_GAP = 2.dp
private val RING = 2.dp

private enum class Arrow(val key: RemoteKey) { UP(RemoteKey.UP), DOWN(RemoteKey.DOWN), LEFT(RemoteKey.LEFT), RIGHT(RemoteKey.RIGHT) }

/**
 * The game controller: the phone held sideways, over the whole screen, with a D-pad or a stick on the
 * left, Home, Select and Start in the middle, and X, Y, A and B on the right, on a pad shaped like an
 * old Nintendo one. [virtual] when the TV took
 * the virtual gamepad: the TV then sees a real controller, with a stick. Without it the buttons go as
 * gamepad keys, the stick grays out and a line under Home says what that means. ✕ and Back go back to
 * the Remote.
 */
@Composable
internal fun GameController(tvName: String, live: Boolean, virtual: Boolean, pointer: Boolean, keyHaptics: Boolean, actions: RemoteActions) {
    Dialog(
        onDismissRequest = actions.closeController,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Sideways()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Controller(tvName, live, virtual, pointer, keyHaptics, actions)
        }
    }
}

/**
 * Turns the screen sideways while on screen, and back as it was after, unless the screen is only being rebuilt.
 * How it was is saved: the rebuilt screen already reads sideways, and going back to that kept the app there.
 */
@Composable
private fun Sideways() {
    val activity = LocalActivity.current ?: return
    val before = rememberSaveable { activity.requestedOrientation }
    DisposableEffect(activity) {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (!activity.isChangingConfigurations) activity.requestedOrientation = before }
    }
}

@Composable
private fun Controller(tvName: String, live: Boolean, virtual: Boolean, pointer: Boolean, keyHaptics: Boolean, actions: RemoteActions) {
    val scope = rememberCoroutineScope()
    var stick by rememberSaveable { mutableStateOf(false) }
    val useStick = stick && virtual
    // With the virtual gamepad, every change of what's held goes as one report; a newer one replaces any
    // still waiting. Without it, each press and release goes as a key, in order.
    var report by remember { mutableStateOf(GamepadReport()) }
    val held = remember { mutableStateMapOf<Any, Boolean>() }
    val keys = remember { Channel<Pair<Int, Boolean>>(Channel.UNLIMITED) }
    DisposableEffect(virtual, live) {
        if (virtual && live) actions.hid(HidDevice.GAMEPAD)
        onDispose { if (virtual && live) actions.hid(null) }
    }
    LaunchedEffect(virtual, live) {
        if (!live) return@LaunchedEffect
        if (virtual) snapshotFlow { report }.conflate().collect { actions.gamepad(it) }
        else for ((code, down) in keys) actions.gamepadKey(code, down)
    }
    fun update() {
        val buttons = GamepadButton.entries.filter { held[it] == true }.sumOf { 1 shl it.bit }
        val hat = if (useStick) GamepadReport.HAT_CENTRE
        else GamepadReport.hat(held[Arrow.UP] == true, held[Arrow.DOWN] == true, held[Arrow.LEFT] == true, held[Arrow.RIGHT] == true)
        report = report.copy(buttons = buttons, hat = hat)
    }
    fun button(b: GamepadButton, down: Boolean) {
        held[b] = down
        if (virtual) update() else keys.trySend(b.code to down)
    }
    fun arrow(a: Arrow, down: Boolean) {
        held[a] = down
        if (virtual) update() else keys.trySend(a.key.code to down)
    }
    val c = MaterialTheme.colorScheme
    val tonal = KeyColors(c.secondaryContainer, c.onSecondaryContainer)
    val primary = KeyColors(c.primary, c.onPrimary)
    val pad = PadKeys(keyHaptics, live)

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.closeController) { Icon(Icons.Default.Close, stringResource(R.string.remote_controller_close)) }
            Text(
                "$tvName · ${stringResource(R.string.remote_controller)}",
                Modifier.weight(1f).padding(start = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StickSwitch(
                stick = useStick,
                stickAllowed = virtual,
                onStick = {
                    // An arrow held as the pad goes would stay held.
                    Arrow.entries.forEach { held[it] = false }
                    stick = true
                    update()
                },
                onDpad = { stick = false; report = report.copy(x = 0, y = 0) },
            )
            // The Remote's mode button: picking another mode goes back to the Remote in that mode.
            Spacer(Modifier.width(8.dp))
            ModeButton(RemotePad.CONTROLLER, pointer, onPick = actions.pad)
        }
        // Clear of the edges a back swipe starts from, so a thumb that slides off a key doesn't go Back.
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .alpha(if (live) 1f else 0.38f),
            contentAlignment = Alignment.Center,
        ) {
            val scale = minOf(1f, maxHeight / END, maxWidth / (END * 2.6f))
            fun Dp.s() = this * scale
            Body(END.s(), Modifier.fillMaxWidth().height(END.s()))
            Row(Modifier.fillMaxWidth().height(END.s()), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(END.s()), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(DIMPLE.s()).background(c.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
                        if (useStick) Stick(live, STICK_WELL.s(), STICK_KNOB.s()) { x, y -> report = report.copy(x = x, y = y) }
                        else Cross(pad, tonal, CROSS.s(), ARM.s(), ::arrow)
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Home leaves the game on the TV, as the Remote's Home does.
                    PadKey(pad, stringResource(R.string.remote_home), CircleShape, tonal, DpSize(HOME_KEY.s(), HOME_KEY.s()), onKey = { down ->
                        if (down) scope.launch { actions.press(RemoteKey.HOME) }
                    }) { Icon(Icons.Outlined.Home, null) }
                    Spacer(Modifier.height(24.dp.s()))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp.s())) {
                        for ((b, label) in listOf(GamepadButton.SELECT to R.string.remote_select, GamepadButton.START to R.string.remote_start)) {
                            val text = stringResource(label)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // Slanted, as on the old Nintendo pad.
                                PadKey(pad, text, RoundedCornerShape(50), tonal, DpSize(PILL.width.s(), PILL.height.s()), Modifier.rotate(-25f), onKey = { button(b, it) }) {}
                                Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                            }
                        }
                    }
                    if (!virtual) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.remote_controller_buttons_only),
                            Modifier.widthIn(max = 240.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Box(Modifier.size(END.s()), contentAlignment = Alignment.Center) {
                    FaceButtons(pad, tonal, primary, DIMPLE.s(), FACE_KEY.s(), FACE_STEP.s(), ::button)
                }
            }
        }
    }
}

/**
 * The pad's body behind the keys: a round end on each side, as wide as the pad is tall, joined by a
 * narrower band.
 */
@Composable
private fun Body(end: Dp, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = end / 2).height(end * 0.72f).background(color))
        Box(Modifier.align(Alignment.CenterStart).size(end).background(color, CircleShape))
        Box(Modifier.align(Alignment.CenterEnd).size(end).background(color, CircleShape))
    }
}

/**
 * The Stick | D-pad switch: one outlined pill, the side in use filled and ticked. Built from a Surface
 * rather than M3's segmented buttons, which nothing else in the app uses and which kept the APK over its size gate.
 */
@Composable
private fun StickSwitch(stick: Boolean, stickAllowed: Boolean, onStick: () -> Unit, onDpad: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(50), color = Color.Transparent, border = BorderStroke(1.dp, c.outline)) {
        Row(Modifier.height(40.dp).selectableGroup()) {
            for ((on, label, allowed, pick) in listOf(
                Choice(stick, R.string.remote_controller_stick, stickAllowed, onStick),
                Choice(!stick, R.string.remote_dpad, true, onDpad),
            )) {
                Row(
                    Modifier.fillMaxHeight()
                        .background(if (on) c.secondaryContainer else Color.Transparent)
                        .selectable(selected = on, enabled = allowed, role = Role.RadioButton, onClick = pick)
                        .padding(horizontal = 16.dp)
                        .alpha(if (allowed) 1f else 0.38f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (on) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = if (on) c.onSecondaryContainer else c.onSurface)
                }
            }
        }
    }
}

private data class Choice(val on: Boolean, val label: Int, val allowed: Boolean, val pick: () -> Unit)

/** A key's face and label colours. */
private data class KeyColors(val face: Color, val content: Color)

/** What every key on the pad shares: whether a press vibrates, and whether the TV is there. */
private class PadKeys(val haptics: Boolean, val enabled: Boolean)

/**
 * X on top, Y on the left, A on the right in the primary colour and B at the bottom, in a round bowl, as
 * on the old Nintendo pad. Y and X sit on one slanted rail, B and A on another.
 */
@Composable
private fun FaceButtons(pad: PadKeys, tonal: KeyColors, primary: KeyColors, bowl: Dp, key: Dp, step: Dp, button: (GamepadButton, Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(bowl).background(c.surfaceContainerHigh, CircleShape), contentAlignment = Alignment.Center) {
        // Each rail runs between the middles of its two keys, rising to the right.
        val rail = Modifier.size(step * 1.414f + key, key + 8.dp).rotate(-45f).background(c.surfaceContainerHighest, RoundedCornerShape(50))
        Box(Modifier.offset(-step / 2, -step / 2).then(rail))
        Box(Modifier.offset(step / 2, step / 2).then(rail))
        for ((b, at) in listOf(
            GamepadButton.X to Offset(0f, -1f),
            GamepadButton.Y to Offset(-1f, 0f),
            GamepadButton.A to Offset(1f, 0f),
            GamepadButton.B to Offset(0f, 1f),
        )) {
            PadKey(
                pad, b.name, CircleShape, if (b == GamepadButton.A) primary else tonal, DpSize(key, key),
                Modifier.offset(step * at.x, step * at.y), onKey = { button(b, it) },
            ) {
                Text(b.name, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The D-pad: a cross whose four arms are each a key, around a middle that isn't one. */
@Composable
private fun Cross(pad: PadKeys, colors: KeyColors, cross: Dp, arm: Dp, arrow: (Arrow, Boolean) -> Unit) {
    val reach = (cross - arm) / 2
    val corner = arm * 0.16f
    Box(Modifier.size(cross + (RING_GAP + RING) * 2), contentAlignment = Alignment.Center) {
        Box(Modifier.size(arm).background(colors.face)) {
            Box(Modifier.align(Alignment.Center).size(arm * 0.2f).background(colors.content.copy(alpha = 0.12f), CircleShape))
        }
        for ((a, at) in listOf(
            Arrow.UP to Offset(0f, -1f),
            Arrow.DOWN to Offset(0f, 1f),
            Arrow.LEFT to Offset(-1f, 0f),
            Arrow.RIGHT to Offset(1f, 0f),
        )) {
            val upright = a == Arrow.UP || a == Arrow.DOWN
            val size = if (upright) DpSize(arm, reach) else DpSize(reach, arm)
            // Only the arm's outer end is rounded; its inner end meets the middle square.
            val shape = when (a) {
                Arrow.UP -> RoundedCornerShape(topStart = corner, topEnd = corner)
                Arrow.DOWN -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
                Arrow.LEFT -> RoundedCornerShape(topStart = corner, bottomStart = corner)
                Arrow.RIGHT -> RoundedCornerShape(topEnd = corner, bottomEnd = corner)
            }
            val label = stringResource(
                when (a) {
                    Arrow.UP -> R.string.remote_up
                    Arrow.DOWN -> R.string.remote_down
                    Arrow.LEFT -> R.string.remote_left
                    Arrow.RIGHT -> R.string.remote_right
                },
            )
            val shift = (arm + reach) / 2
            PadKey(pad, label, shape, colors, size, Modifier.offset(shift * at.x, shift * at.y), onKey = { arrow(a, it) }) {
                val glyph = when (a) {
                    Arrow.UP -> Icons.Default.KeyboardArrowUp
                    Arrow.DOWN -> Icons.Default.KeyboardArrowDown
                    Arrow.LEFT -> Icons.Default.KeyboardArrowLeft
                    Arrow.RIGHT -> Icons.Default.KeyboardArrowRight
                }
                Icon(glyph, null)
            }
        }
    }
}

/**
 * One key on the pad, flat in the Remote's colours. Held, a ring shows around it. [size] is the face;
 * the key takes room for the ring around it. [onKey] gets true as a finger goes down and false as it
 * lifts, so holding a key holds the button on the TV.
 */
@Composable
private fun PadKey(
    pad: PadKeys,
    label: String,
    shape: Shape,
    colors: KeyColors,
    size: DpSize,
    modifier: Modifier = Modifier,
    onKey: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var down by remember { mutableStateOf(false) }
    val room = RING_GAP + RING
    val ringColor = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(size.width + room * 2, size.height + room * 2)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (pad.enabled) onClick { onKey(true); onKey(false); true }
            }
            .pointerInput(pad.enabled, pad.haptics) {
                if (!pad.enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    down = true
                    if (pad.haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onKey(true)
                    waitForUpOrCancellation()?.consume()
                    down = false
                    onKey(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The ring is the key's own shape at the ring's size, so it keeps [RING_GAP] clear of the face.
        if (down) Box(Modifier.fillMaxSize().border(RING, ringColor, shape))
        Box(Modifier.size(size.width, size.height).background(colors.face, shape), contentAlignment = Alignment.Center) {
            CompositionLocalProvider(LocalContentColor provides colors.content) { content() }
        }
    }
}

/**
 * The stick: a round well with a knob that follows the thumb, up to the well's edge, and springs back
 * to the middle on lift. [onMove] gets X and Y from -127 to 127, down and right positive.
 */
@Composable
private fun Stick(enabled: Boolean, well: Dp, knobSize: Dp, onMove: (Int, Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    var knob by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val reach = with(density) { ((well - knobSize) / 2).toPx() }
    Box(
        Modifier.size(well)
            .background(c.surfaceContainerHighest, CircleShape)
            .pointerInput(enabled, reach) {
                if (!enabled) return@pointerInput
                val centre = Offset(size.width / 2f, size.height / 2f)
                awaitEachGesture {
                    val first = awaitFirstDown()
                    first.consume()
                    fun follow(p: Offset) {
                        val d = p - centre
                        val len = d.getDistance()
                        knob = if (len > reach) d * (reach / len) else d
                        onMove((knob.x / reach * 127).roundToInt(), (knob.y / reach * 127).roundToInt())
                    }
                    follow(first.position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == first.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        follow(change.position)
                    }
                    knob = Offset.Zero
                    onMove(0, 0)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.offset { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) }
                .size(knobSize)
                .background(c.primary, CircleShape),
        )
    }
}
