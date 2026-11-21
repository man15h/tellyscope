// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.view.Surface as Rotation
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.runtime.State
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
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.asin
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/** The raised keys' lip, under the face: 3 dp of it shows, and a held key sinks [SINK] onto it. */
private val LIP = 3.dp
private val SINK = 2.dp
/** Room around each key for the held ring: 2 dp of gap, then 2 dp of ring. */
private val RING_GAP = 2.dp
private val RING = 2.dp
private val SHADOW_BLUR = 8.dp
private val SHADOW_DROP = 4.dp
/** The lips from the mockup: under a tonal key, and under A. */
private val LIP_TONAL = Color(0xFFB6D4D4)
private val LIP_PRIMARY = Color(0xFF005457)

/** The shadows slide at most this far toward the edge tilted down, all of it at [TILT_FULL_DEG]. */
private val TILT_SHIFT = 4.dp
private const val TILT_FULL_DEG = 20f
/** Each reading moves the smoothed gravity this share of the way to it. */
private const val TILT_SMOOTHING = 0.2f
/**
 * The level the tilt is measured from follows the hand this slowly, so the angle the phone is held at
 * reads as flat and only a tilt away from it moves the light.
 */
private const val TILT_SETTLE = 0.02f

/** The D-pad's arrow keys: across and along their arrow, and the room between their tips. */
private val ARROW_ACROSS = 56.dp
private val ARROW_ALONG = 64.dp
private val ARROW_GAP = 8.dp
private val FACE_KEY = 56.dp
private val STICK_WELL = 168.dp
private val STICK_KNOB = 72.dp

private enum class Arrow(val key: RemoteKey) { UP(RemoteKey.UP), DOWN(RemoteKey.DOWN), LEFT(RemoteKey.LEFT), RIGHT(RemoteKey.RIGHT) }

/**
 * The game controller: the phone held sideways, over the whole screen, with a D-pad or a stick on the
 * left, Select, Start and Home in the middle, and A, B, X and Y on the right. [virtual] when the TV took
 * the virtual gamepad: the TV then sees a real controller, with a stick. Without it the buttons go as
 * gamepad keys, the stick grays out and a line under Home says what that means. ✕ and Back go back to
 * the Remote.
 */
@Composable
internal fun GameController(tvName: String, live: Boolean, virtual: Boolean, keyHaptics: Boolean, actions: RemoteActions) {
    Dialog(
        onDismissRequest = actions.closeController,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Sideways()
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Controller(tvName, live, virtual, keyHaptics, actions)
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
private fun Controller(tvName: String, live: Boolean, virtual: Boolean, keyHaptics: Boolean, actions: RemoteActions) {
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
    val tilt = rememberTilt()
    val c = MaterialTheme.colorScheme
    val dark = c.surface.luminance() < 0.5f
    // The mockup's lips are for the light theme; in the dark one each is its face, darkened.
    val tonal = KeyColors(c.secondaryContainer, c.onSecondaryContainer, if (dark) lerp(c.secondaryContainer, Color.Black, 0.4f) else LIP_TONAL)
    val primary = KeyColors(c.primary, c.onPrimary, if (dark) lerp(c.primary, Color.Black, 0.4f) else LIP_PRIMARY)
    val raised = Raised(tilt, keyHaptics, live)

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
        }
        Row(
            Modifier.weight(1f).fillMaxWidth().alpha(if (live) 1f else 0.38f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (useStick) Stick(live, tilt) { x, y -> report = report.copy(x = x, y = y) }
                else ArrowPad(raised, tonal, ::arrow)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for ((b, label) in listOf(GamepadButton.SELECT to R.string.remote_select, GamepadButton.START to R.string.remote_start)) {
                        val text = stringResource(label)
                        RaisedKey(raised, text, RoundedCornerShape(50), tonal, DpSize(80.dp, 36.dp), onKey = { button(b, it) }) {
                            Text(text, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                // Home leaves the game on the TV, as the Remote's Home does.
                RaisedKey(raised, stringResource(R.string.remote_home), CircleShape, tonal, DpSize(52.dp, 52.dp), onKey = { down ->
                    if (down) scope.launch { actions.press(RemoteKey.HOME) }
                }) { Icon(Icons.Outlined.Home, null) }
                if (!virtual) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.remote_controller_buttons_only),
                        Modifier.widthIn(max = 260.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                FaceButtons(raised, tonal, primary, ::button)
            }
        }
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

/** A raised key's face, label and lip colours. */
private data class KeyColors(val face: Color, val content: Color, val lip: Color)

/** What every raised key shares: the tilt its shadow follows, whether a press vibrates, and whether the TV is there. */
private class Raised(val tilt: State<Offset>, val haptics: Boolean, val enabled: Boolean)

/** Y on top, X and B either side, A at the bottom in the primary colour, as on most pads. */
@Composable
private fun FaceButtons(raised: Raised, tonal: KeyColors, primary: KeyColors, button: (GamepadButton, Boolean) -> Unit) {
    val step = FACE_KEY + 4.dp
    Box(Modifier.size(step * 3)) {
        for ((b, at) in listOf(
            GamepadButton.Y to Alignment.TopCenter,
            GamepadButton.X to Alignment.CenterStart,
            GamepadButton.B to Alignment.CenterEnd,
            GamepadButton.A to Alignment.BottomCenter,
        )) {
            RaisedKey(raised, b.name, CircleShape, if (b == GamepadButton.A) primary else tonal, DpSize(FACE_KEY, FACE_KEY), Modifier.align(at), onKey = { button(b, it) }) {
                Text(b.name, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Four arrow-tip keys pointing at the middle, with nothing behind them. */
@Composable
private fun ArrowPad(raised: Raised, colors: KeyColors, arrow: (Arrow, Boolean) -> Unit) {
    Box(Modifier.size(ARROW_ALONG * 2 + ARROW_GAP + (RING_GAP + RING) * 2)) {
        for ((a, at) in listOf(
            Arrow.UP to Alignment.TopCenter,
            Arrow.DOWN to Alignment.BottomCenter,
            Arrow.LEFT to Alignment.CenterStart,
            Arrow.RIGHT to Alignment.CenterEnd,
        )) {
            val upright = a == Arrow.UP || a == Arrow.DOWN
            val size = if (upright) DpSize(ARROW_ACROSS, ARROW_ALONG) else DpSize(ARROW_ALONG, ARROW_ACROSS)
            val label = stringResource(
                when (a) {
                    Arrow.UP -> R.string.remote_up
                    Arrow.DOWN -> R.string.remote_down
                    Arrow.LEFT -> R.string.remote_left
                    Arrow.RIGHT -> R.string.remote_right
                },
            )
            RaisedKey(raised, label, arrowShape(a), colors, size, Modifier.align(at), onKey = { arrow(a, it) }) {
                val glyph = when (a) {
                    Arrow.UP -> Icons.Default.KeyboardArrowUp
                    Arrow.DOWN -> Icons.Default.KeyboardArrowDown
                    Arrow.LEFT -> Icons.Default.KeyboardArrowLeft
                    Arrow.RIGHT -> Icons.Default.KeyboardArrowRight
                }
                // Toward the key's outer end, clear of the tip.
                val nudge = 6.dp
                val outward = when (a) {
                    Arrow.UP -> Modifier.offset(y = -nudge)
                    Arrow.DOWN -> Modifier.offset(y = nudge)
                    Arrow.LEFT -> Modifier.offset(x = -nudge)
                    Arrow.RIGHT -> Modifier.offset(x = nudge)
                }
                Icon(glyph, null, outward)
            }
        }
    }
}

/**
 * A key square at its outer end, with small rounded corners, and coming to a right-angled point at the
 * end that faces the middle of the pad.
 */
private fun arrowShape(a: Arrow): Shape = GenericShape { size, _ ->
    val upright = a == Arrow.UP || a == Arrow.DOWN
    val across = if (upright) size.width else size.height
    val along = if (upright) size.height else size.width
    val r = across * 0.12f
    // u runs across the key, v from its outer end to the tip.
    fun at(u: Float, v: Float): Offset = when (a) {
        Arrow.UP -> Offset(u, v)
        Arrow.DOWN -> Offset(u, along - v)
        Arrow.LEFT -> Offset(v, u)
        Arrow.RIGHT -> Offset(along - v, u)
    }
    fun line(u: Float, v: Float) = at(u, v).let { lineTo(it.x, it.y) }
    fun corner(cu: Float, cv: Float, u: Float, v: Float) {
        val c = at(cu, cv)
        val p = at(u, v)
        quadraticTo(c.x, c.y, p.x, p.y)
    }
    at(0f, r).let { moveTo(it.x, it.y) }
    corner(0f, 0f, r, 0f)
    line(across - r, 0f)
    corner(across, 0f, across, r)
    line(across, along - across / 2)
    line(across / 2, along)
    line(0f, along - across / 2)
    close()
}

/**
 * One raised key: a face on a darker lip, with a soft shadow that follows the phone's tilt and a light
 * fading down the top of the face. Held, it sinks onto its lip and a ring shows around it. [size] is the
 * face; the key takes room for the ring around it and the lip under it. [onKey] gets true as a finger
 * goes down and false as it lifts, so holding a key holds the button on the TV.
 */
@Composable
private fun RaisedKey(
    raised: Raised,
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
            .size(size.width + room * 2, size.height + room * 2 + LIP)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (raised.enabled) onClick { onKey(true); onKey(false); true }
            }
            .pointerInput(raised.enabled, raised.haptics) {
                if (!raised.enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown().consume()
                    down = true
                    if (raised.haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    onKey(true)
                    waitForUpOrCancellation()?.consume()
                    down = false
                    onKey(false)
                }
            },
    ) {
        val face = Modifier.size(size.width, size.height)
        // The shadow, cast by the key at rest; drawn on its own so the sink doesn't move it.
        Box(
            face.offset(room, room).dropShadow(shape) {
                val t = raised.tilt.value
                radius = SHADOW_BLUR.toPx()
                color = Color.Black.copy(alpha = 0.10f)
                offset = Offset(t.x, SHADOW_DROP.toPx() + t.y)
            },
        )
        Box(face.offset(room, room + LIP).background(colors.lip, shape))
        val sunk = if (down) SINK else 0.dp
        // The ring is the key's own shape at the ring's size, so it keeps [RING_GAP] clear of the face.
        if (down) Box(Modifier.offset(0.dp, sunk).size(size.width + room * 2, size.height + room * 2).border(RING, ringColor, shape))
        Box(
            face.offset(room, room + sunk)
                .background(colors.face, shape)
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.15f), 0.5f to Color.Transparent), shape),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.content) { content() }
        }
    }
}

/**
 * The stick: a round well with a knob that follows the thumb, up to the well's edge, and springs back
 * to the middle on lift. [onMove] gets X and Y from -127 to 127, down and right positive.
 */
@Composable
private fun Stick(enabled: Boolean, tilt: State<Offset>, onMove: (Int, Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    var knob by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val reach = with(density) { ((STICK_WELL - STICK_KNOB) / 2).toPx() }
    Box(
        Modifier.size(STICK_WELL)
            .background(c.surfaceContainerHighest, CircleShape)
            .pointerInput(enabled) {
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
                .size(STICK_KNOB)
                .dropShadow(CircleShape) {
                    val t = tilt.value
                    radius = SHADOW_BLUR.toPx()
                    color = Color.Black.copy(alpha = 0.10f)
                    offset = Offset(t.x, SHADOW_DROP.toPx() + t.y)
                }
                .background(c.primary, CircleShape)
                .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.15f), 0.5f to Color.Transparent), CircleShape),
        )
    }
}

/**
 * Where the keys' shadows sit beyond their drop, in px, from how the phone is tilted: toward the edge
 * tilted down, [TILT_SHIFT] at most. Listens to the gravity sensor, or the accelerometer where there is
 * none, only while on screen. Stays still with Remove animations on, or with neither sensor.
 */
@Composable
private fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    val view = LocalView.current
    val shift = with(LocalDensity.current) { TILT_SHIFT.toPx() }
    val tilt = remember { mutableStateOf(Offset.Zero) }
    val still = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    DisposableEffect(still, shift) {
        val sensors = context.getSystemService(SensorManager::class.java)
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (still || sensors == null || sensor == null) return@DisposableEffect onDispose {}
        var now: Offset? = null
        var level: Offset? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val g = screenTilt(e.values[0], e.values[1], e.values[2], view.display?.rotation ?: Rotation.ROTATION_0)
                val n = now?.let { it + (g - it) * TILT_SMOOTHING } ?: g
                val l = level?.let { it + (n - it) * TILT_SETTLE } ?: n
                now = n
                level = l
                val d = n - l
                tilt.value = Offset(
                    (d.x / TILT_FULL_DEG).coerceIn(-1f, 1f) * shift,
                    (d.y / TILT_FULL_DEG).coerceIn(-1f, 1f) * shift,
                )
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
    return tilt
}

/**
 * How far, in degrees, the screen's right edge (x) and its bottom edge (y) are tilted down, from a
 * gravity reading [x], [y], [z] in the phone's own axes and the screen's [rotation]. The reading points
 * up, away from the ground, so the edge tilted down is the one it points away from.
 */
internal fun screenTilt(x: Float, y: Float, z: Float, rotation: Int): Offset {
    // The reading on the screen's axes, x right and y up, as the screen is turned.
    val (sx, sy) = when (rotation) {
        Rotation.ROTATION_90 -> -y to x
        Rotation.ROTATION_180 -> -x to -y
        Rotation.ROTATION_270 -> y to -x
        else -> x to y
    }
    val g = sqrt(x * x + y * y + z * z).takeIf { it > 0f } ?: return Offset.Zero
    fun deg(v: Float) = Math.toDegrees(asin((v / g).coerceIn(-1f, 1f).toDouble())).toFloat()
    // Pointing right means the left edge is down; pointing up (toward the top) means the bottom is down.
    return Offset(-deg(sx), deg(sy))
}
