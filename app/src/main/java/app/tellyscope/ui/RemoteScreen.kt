// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import app.tellyscope.data.Png
import app.tellyscope.data.SharedLists
import app.tellyscope.data.RemoteButtons
import app.tellyscope.data.StreamingService
import app.tellyscope.adb.RemoteKey
import app.tellyscope.data.AppInfo
import app.tellyscope.data.DeviceState
import app.tellyscope.data.Dock
import app.tellyscope.data.Docked
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import app.tellyscope.data.Device

data class RemoteActions(
    /** Suspends until the TV answers, so a held key never queues up presses. False when it didn't take the key. */
    val press: suspend (RemoteKey) -> Boolean = { true },
    /** Returns why the text can't be typed, or null once it's sent. */
    val type: (String) -> String? = { null },
    val reboot: () -> Unit = {},
    val back: () -> Unit = {},
    /** A restart running late: try the TV again and wait another minute. */
    val retryRestart: () -> Unit = {},
    /** Give up waiting for a restart that never came back. */
    val stopWaiting: () -> Unit = {},
    val launch: (Docked) -> Unit = {},
    val setDock: (List<Docked>) -> Unit = {},
    /** Turns the TV's picture off (true) or back on (false). */
    val screenOff: (Boolean) -> Unit = {},
    /** The first-visit tip on Screen off was shown. */
    val screenOffTipSeen: () -> Unit = {},
)

/** A held key repeats after this, then every [REPEAT_EVERY_MS], or slower if the TV is slower. */
private const val REPEAT_AFTER_MS = 400L

/** Android's own key repeat rate. The key helper answers in about 20 ms, which would skip whole lists. */
internal const val REPEAT_EVERY_MS = 50L

/**
 * One press, then repeats while [held] for keys that repeat. Stops at the first key the TV didn't
 * take (refused, dropped, or no TV selected).
 */
internal suspend fun repeatWhileHeld(
    key: RemoteKey,
    held: () -> Boolean,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    press: suspend (RemoteKey) -> Boolean,
) {
    if (!press(key) || !key.repeats) return
    delay(REPEAT_AFTER_MS)
    var last = nowMs()
    while (held() && press(key)) {
        delay((REPEAT_EVERY_MS - (nowMs() - last)).coerceAtLeast(0))
        last = nowMs()
    }
}

/** The D-pad's comfortable minimum: the hint and the gaps go first, and it only shrinks past this when nothing else is left. */
private val DPAD_MIN = 176.dp
private val DPAD_MAX = 272.dp

/** Shorter than this, mute and volume move beside the D-pad, into room it leaves empty, instead of a row under it. */
private const val SHORT_PHONE_DP = 760

/**
 * The remote, as a sheet over the TV's page with the ⋮ sheet's header. It never scrolls, which would
 * fight the drag that closes it: the D-pad takes the height that's left, so a short phone, large text
 * or the keyboard shrink it instead of pushing the buttons off-screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteScreen(
    state: UiState,
    actions: RemoteActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val device = state.selected ?: return
    val restart = state.restarting[device.id]
    val live = device.state == DeviceState.Online && restart == null
    val screenOff = device.id in state.screenOff
    // Android 14 moved the call that turns the picture off out of the shell's reach.
    val screenControl = (device.android?.substringBefore('.')?.toIntOrNull() ?: 0) < 14 || screenOff
    var confirmReboot by rememberSaveable { mutableStateOf(false) }
    if (confirmReboot) RebootDialog(onRestart = { confirmReboot = false; actions.reboot() }, onDismiss = { confirmReboot = false })
    // Hide keeps a restart in the header; the dialog comes back when the wait moves on, or on a tap there.
    val stage = restart?.let { "${it.since}/${it.waitFrom}/${it.phase(state.clock())}" }
    var hidden by rememberSaveable { mutableStateOf<String?>(null) }
    if (restart != null && hidden != stage) {
        RestartDialog(
            restart, state.clock(), name(device),
            onHide = { hidden = stage },
            onRetry = actions.retryRestart,
            onStop = actions.stopWaiting,
        )
    }
    // Set once the D-pad hits its floor, and cleared once there's room for the hint again.
    var tight by remember { mutableStateOf(false) }
    // While typing, the keyboard needs the room: volume, the hint and the buttons step aside for the D-pad,
    // and back, home and menu shrink.
    val typing = WindowInsets.isImeVisible
    // The text box in window coordinates, so a tap on it (or its ⌫ and Send) keeps the keyboard up.
    var field by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val rowGap = if (tight || typing) 12.dp else 20.dp
    val short = LocalConfiguration.current.screenHeightDp < SHORT_PHONE_DP
    val cap = RemoteButtons.cap(LocalConfiguration.current.screenWidthDp)
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) ButtonsSheet(state.device.dock, state.device.apps, cap, actions.setDock, onDismiss = { editing = false })

    ModalBottomSheet(
        onDismissRequest = actions.back,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The sheet is its own window, so the text box's focus lives here, not in the screen's.
        val focus = LocalFocusManager.current
        // A tap on empty space puts the keyboard away once the finger lifts. Keys and buttons consume
        // their lift, so the D-pad can move through results with the keyboard still up. Never consumed here.
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { origin = it.positionInWindow() }
                .pointerInput(typing) {
                    if (!typing) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (field.contains(origin + down.position)) return@awaitEachGesture
                        // Null when something else took the lift (a key, a button, a drag of the sheet): leave the keyboard be.
                        if (waitForUpOrCancellation(PointerEventPass.Final) != null) focus.clearFocus()
                    }
                },
        ) {
            Column(
                Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RemoteHeader(state, device, restart, screenOff, onRestartTap = { hidden = null }) {
                    HeaderMenu(screenControl, screenOff, live, showTip = state.screenOffTip, actions, onRestart = { confirmReboot = true }, onButtons = { editing = true })
                }
                CompositionLocalProvider(LocalKeyHaptics provides state.keyHaptics) { Column(
                    Modifier.weight(1f).alpha(if (live) 1f else 0.38f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (short) RoundKey(RemoteKey.MUTE, "Mute or unmute", live, actions.press, size = 56.dp) { Icon(RemoteIcons.VolumeOff, null) }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val room = minOf(maxWidth, maxHeight)
                            val cramped = room < DPAD_MIN
                            val hintFits = room >= DPAD_MIN + 24.dp
                            LaunchedEffect(cramped, hintFits) { if (cramped) tight = true else if (hintFits) tight = false }
                            // 12 dp clear above and below while there's room; the gap gives way before the pad does,
                            // and the pad never outgrows its box, so it can't overlap the header or the keys.
                            DPad(live, actions.press, (room - 24.dp).coerceIn(DPAD_MIN, DPAD_MAX).coerceAtMost(room))
                        }
                        // Up above down, like the rocker on the side of a real remote.
                        if (short) Column(Modifier.width(56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val (outer, inner) = 28.dp to 4.dp
                            val tall = Modifier.fillMaxWidth().height(72.dp)
                            KeySurface(
                                RemoteKey.VOLUME_UP, "Volume up", live, actions.press, RoundedCornerShape(outer, outer, inner, inner),
                                MaterialTheme.colorScheme.secondaryContainer, tall,
                            ) { Icon(RemoteIcons.VolumeUp, null) }
                            KeySurface(
                                RemoteKey.VOLUME_DOWN, "Volume down", live, actions.press, RoundedCornerShape(inner, inner, outer, outer),
                                MaterialTheme.colorScheme.secondaryContainer, tall,
                            ) { Icon(RemoteIcons.VolumeDown, null) }
                        }
                    }
                    // While typing these shrink so the D-pad keeps its room; 48 dp is still a full touch target.
                    val side = if (typing) 48.dp else 64.dp
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        RoundKey(RemoteKey.BACK, "Back", live, actions.press, Modifier.align(Alignment.CenterStart), size = side) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                        RoundKey(RemoteKey.HOME, "Home", live, actions.press, size = if (typing) 56.dp else 80.dp) {
                            Icon(Icons.Outlined.Home, null, Modifier.size(if (typing) 24.dp else 32.dp))
                        }
                        RoundKey(RemoteKey.MENU, "Menu", live, actions.press, Modifier.align(Alignment.CenterEnd), size = side) { Icon(Icons.Default.Menu, null) }
                    }
                    if (!typing && !short) {
                    Spacer(Modifier.height(rowGap))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundKey(RemoteKey.MUTE, "Mute or unmute", live, actions.press) { Icon(RemoteIcons.VolumeOff, null) }
                        // One split pill: two halves of the same key, so the gap stays narrow.
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val (outer, inner) = 32.dp to 4.dp
                            KeySurface(
                                RemoteKey.VOLUME_DOWN, "Volume down", live, actions.press, RoundedCornerShape(outer, inner, inner, outer),
                                MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f).height(64.dp),
                            ) { Icon(RemoteIcons.VolumeDown, null) }
                            KeySurface(
                                RemoteKey.VOLUME_UP, "Volume up", live, actions.press, RoundedCornerShape(inner, outer, outer, inner),
                                MaterialTheme.colorScheme.secondaryContainer, Modifier.weight(1f).height(64.dp),
                            ) { Icon(RemoteIcons.VolumeUp, null) }
                        }
                    }
                    }
                    // Twice the usual gap, so a thumb aiming at a volume key doesn't land in the text box.
                    Spacer(Modifier.height(if (typing) rowGap else 24.dp))
                    Keyboard(
                        live, actions.type, actions.press, hint = !tight && !typing,
                        modifier = Modifier.onGloballyPositioned { field = it.boundsInWindow() },
                    )
                } }
                // Under the keys, out of a thumb's way.
                if (!typing) {
                    Spacer(Modifier.height(if (tight) 8.dp else 16.dp))
                    CompositionLocalProvider(LocalKeyHaptics provides state.keyHaptics) {
                        ButtonsRow(RemoteButtons.shown(state.device.dock, SharedLists.state.collectAsState().value.streaming, cap), state.device.apps, live, actions, onEdit = { editing = true })
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * The ⋮ sheet's header: the TV, and a second line for its state, a screen that's off or a restart.
 * [menu] sits at its end, centred on the name and its line.
 */
@Composable
private fun RemoteHeader(state: UiState, device: Device, restart: Restart?, screenOff: Boolean, onRestartTap: () -> Unit, menu: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Cookie(device, 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name(device), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                restart != null -> RestartStatus(late = restart.phase(state.clock()) != Restart.Phase.Waiting, onClick = onRestartTap)
                screenOff -> StateLine(Copy.SCREEN_OFF, Copy.SCREEN_OFF_WAKE)
                device.state != DeviceState.Online -> StateLine(Copy.state(device.state), Copy.REMOTE_OFFLINE_WAKE)
                else -> {
                    val health = state.device.snapshot?.let { Copy.health(it, heaviest(it, state.device.apps)).text }
                    val line = listOf(makerLine(device), health.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Out by the button's own padding, so the glyph's edge lines up with the keys and buttons below.
        Box(Modifier.offset(x = 12.dp)) { menu() }
    }
}

/** "**Screen off** · tap any key to wake": the state, then what to do about it. */
@Composable
private fun StateLine(what: String, then: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)) { append(what) }
            append(" · $then")
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The four arrows around a big OK, shaped like the remote in the user's other hand; [size] across. */
@Composable
private fun DPad(enabled: Boolean, press: suspend (RemoteKey) -> Boolean, size: Dp = DPAD_MAX) {
    // Every part keeps its share of the full-size pad, so a smaller pad is the same pad, scaled.
    val scale = size / DPAD_MAX
    Surface(Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box {
            val arrow = Modifier.size(80.dp * scale)
            val glyph = Modifier.size(40.dp * scale)
            val tint = MaterialTheme.colorScheme.onSurface
            KeySurface(RemoteKey.UP, "Up", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.TopCenter)) {
                Icon(Icons.Default.KeyboardArrowUp, null, glyph, tint = tint)
            }
            KeySurface(RemoteKey.DOWN, "Down", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.BottomCenter)) {
                Icon(Icons.Default.KeyboardArrowDown, null, glyph, tint = tint)
            }
            KeySurface(RemoteKey.LEFT, "Left", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.CenterStart)) {
                Icon(Icons.Default.KeyboardArrowLeft, null, glyph, tint = tint)
            }
            KeySurface(RemoteKey.RIGHT, "Right", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.CenterEnd)) {
                Icon(Icons.Default.KeyboardArrowRight, null, glyph, tint = tint)
            }
            KeySurface(
                RemoteKey.OK, "OK", enabled, press, CircleShape, MaterialTheme.colorScheme.primary,
                Modifier.size(112.dp * scale).align(Alignment.Center),
            ) {}
        }
    }
}

/** An icon-only key; [label] is what TalkBack reads. */
@Composable
private fun RoundKey(
    key: RemoteKey,
    label: String,
    enabled: Boolean,
    press: suspend (RemoteKey) -> Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    icon: @Composable () -> Unit,
) = KeySurface(key, label, enabled, press, CircleShape, MaterialTheme.colorScheme.secondaryContainer, modifier.size(size), icon)

/** Off in Settings, a key press or a button tap doesn't vibrate. */
private val LocalKeyHaptics = staticCompositionLocalOf { true }

/**
 * One remote key. Not a Button: a held [RemoteKey.repeats] key has to keep sending until the
 * finger lifts, and each repeat waits for the TV to take the last one so nothing piles up.
 */
@Composable
private fun KeySurface(
    key: RemoteKey,
    label: String,
    enabled: Boolean,
    press: suspend (RemoteKey) -> Boolean,
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val haptics = LocalKeyHaptics.current
    val interactions = remember { MutableInteractionSource() }
    Surface(
        shape = shape,
        color = color,
        modifier = modifier
            .clip(shape)
            .indication(interactions, ripple())
            .semantics {
                role = Role.Button
                contentDescription = label
                if (enabled) onClick { scope.launch { press(key) }; true }
            }
            .pointerInput(key, enabled, haptics) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = PressInteraction.Press(down.position)
                    interactions.tryEmit(p)
                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                    var held = true
                    scope.launch { repeatWhileHeld(key, { held }, press = press) }
                    // Consumed so the Remote knows a key took the tap and keeps the keyboard up.
                    waitForUpOrCancellation()?.consume()
                    held = false
                    interactions.tryEmit(PressInteraction.Release(p))
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** [hint] off on a screen too short for it; an error still shows. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun Keyboard(
    enabled: Boolean,
    type: (String) -> String?,
    press: suspend (RemoteKey) -> Boolean,
    hint: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun send() {
        error = type(text)
        if (error == null) text = ""
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = null },
        enabled = enabled,
        label = { Text("Type on the TV") },
        supportingText = if (error != null || hint) ({ Text(error ?: Copy.TYPE_HINT) }) else null,
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            Row {
                // A key, not a button: holding it repeats like the D-pad. It deletes on the TV only;
                // the field above is the unsent draft, which the phone's keyboard edits.
                KeySurface(
                    RemoteKey.DEL, Copy.BACKSPACE, enabled, press,
                    CircleShape, Color.Transparent, Modifier.size(48.dp),
                ) { Icon(RemoteIcons.Backspace, null, tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f)) }
                IconButton(onClick = ::send, enabled = enabled && text.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to the TV")
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun RebootDialog(onRestart: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Copy.REBOOT_TITLE) },
        text = { Text(Copy.REBOOT_TEXT) },
        confirmButton = { Button(onClick = onRestart) { Text("Restart") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown instead of the offline card while a TV restarts: it's expected, not an error. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RestartingCard(restart: Restart, now: Long) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            LoadingIndicator(Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Restarting", style = MaterialTheme.typography.titleSmall)
                Text(Copy.restarting((now - restart.since) / 60_000), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * A restart from the Remote: waiting for a minute, then late, then, after a Retry's minute,
 * lost. Hide and the first Close leave it waiting in the top bar; the last Close stops waiting.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RestartDialog(restart: Restart, now: Long, tvName: String, onHide: () -> Unit, onRetry: () -> Unit, onStop: () -> Unit) {
    val c = MaterialTheme.colorScheme
    when (restart.phase(now)) {
        Restart.Phase.Waiting -> AlertDialog(
            onDismissRequest = onHide,
            // Wavy, not the loading indicator: that one is for waits under a few seconds.
            icon = { CircularWavyProgressIndicator(Modifier.size(48.dp)) },
            title = { Text(Copy.RESTARTING_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Copy.RESTARTING_TEXT)
                    Text(Copy.waitingFor(tvName, now - restart.since), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = onHide) { Text("Hide") } },
        )
        Restart.Phase.Late -> AlertDialog(
            onDismissRequest = onHide,
            icon = { StatusShape(RemoteIcons.HourglassTop, c.secondaryContainer, c.onSecondaryContainer) },
            title = { Text(Copy.RESTART_LATE_TITLE) },
            text = { Text(Copy.RESTART_LATE_TEXT) },
            confirmButton = { Button(onClick = onRetry) { Text("Retry") } },
            dismissButton = { TextButton(onClick = onHide) { Text("Close") } },
        )
        Restart.Phase.Lost -> AlertDialog(
            onDismissRequest = onStop,
            icon = { StatusShape(RemoteIcons.LinkOff, c.errorContainer, c.onErrorContainer) },
            title = { Text(Copy.restartLost(tvName)) },
            text = { Text(Copy.RESTART_LOST_TEXT) },
            confirmButton = { Button(onClick = onRetry) { Text("Retry") } },
            dismissButton = { TextButton(onClick = onStop) { Text("Close") } },
        )
    }
}

/** A filled glyph in the cookie the TV header uses, for a state that needs a look rather than a spinner. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusShape(icon: ImageVector, container: Color, content: Color) {
    Box(Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
    }
}

/**
 * The header's ⋮: Screen off (or on again), Restart TV, then Remote buttons. It drops below the ⋮,
 * so the TV's name stays readable. Explains Screen off on the first visit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderMenu(screenControl: Boolean, off: Boolean, live: Boolean, showTip: Boolean, actions: RemoteActions, onRestart: () -> Unit, onButtons: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val tip = rememberTooltipState(isPersistent = true)
    LaunchedEffect(showTip, screenControl) {
        if (showTip && screenControl) {
            tip.show()
            actions.screenOffTipSeen()
        }
    }
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = {
                PlainTooltip {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(Copy.SCREEN_OFF, style = MaterialTheme.typography.labelLarge)
                        Text(Copy.SCREEN_OFF_TIP)
                    }
                }
            },
            state = tip,
            enableUserInput = false,
        ) {
            IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = Copy.MORE) }
        }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            if (screenControl) {
                // While the picture is off, the item turns it back on.
                if (off) DropdownMenuItem(
                    text = { Text(Copy.SCREEN_ON) },
                    leadingIcon = { Icon(RemoteIcons.Tv, null) },
                    enabled = live,
                    onClick = { open = false; actions.screenOff(false) },
                )
                else DropdownMenuItem(
                    text = { Text(Copy.SCREEN_OFF) },
                    leadingIcon = { Icon(RemoteIcons.TvOff, null) },
                    enabled = live,
                    onClick = { open = false; actions.screenOff(true) },
                )
            }
            DropdownMenuItem(
                text = { Text(Copy.REMOTE_RESTART) },
                leadingIcon = { Icon(RemoteIcons.RestartAlt, null) },
                enabled = live,
                onClick = { open = false; onRestart() },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text(Copy.REMOTE_BUTTONS) },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { open = false; onButtons() },
            )
        }
    }
}

/** The header's second line while a restart runs, overdue once [late]; a tap brings its dialog back. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RestartStatus(late: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(MaterialTheme.shapes.small).clickable(onClickLabel = "Show the restart", onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (late) {
            Icon(RemoteIcons.HourglassTop, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
        } else {
            LoadingIndicator(Modifier.size(18.dp))
        }
        Text(if (late) Copy.RESTART_LATE_STATUS else Copy.RESTARTING_TITLE, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * The row under the keys: up to [cap] streaming buttons, sharing it equally, or one pill to pick some.
 * A tap opens the service on the TV; holding one opens the picker, as ⋮ › Remote buttons does.
 */
@Composable
private fun ButtonsRow(buttons: List<StreamingService>, apps: List<AppInfo>, enabled: Boolean, actions: RemoteActions, onEdit: () -> Unit) {
    val installed = remember(apps) { apps.mapTo(HashSet()) { it.packageName } }
    Row(Modifier.fillMaxWidth().height(48.dp).alpha(if (enabled) 1f else 0.38f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (buttons.isEmpty()) AddButtons(enabled, onEdit, Modifier.weight(1f))
        buttons.forEach { s ->
            key(s.name) {
                ServiceButton(s, enabled, onOpen = { actions.launch(Docked(s.packageOn(installed), s.name)) }, onHold = onEdit, Modifier.weight(1f))
            }
        }
    }
}

/**
 * One streaming button: its pill, 40 dp tall in a 48 dp tall target. Its name is for TalkBack.
 * The ripple is drawn on the pill, inside its shape, not across the whole target.
 */
@Composable
private fun ServiceButton(s: StreamingService, enabled: Boolean, onOpen: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val haptics = LocalKeyHaptics.current
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxHeight()
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = Copy.OPEN_ON_TV,
                onLongClickLabel = Copy.REMOTE_BUTTONS,
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onHold() },
                onClick = { if (haptics) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey); onOpen() },
            )
            .semantics { contentDescription = s.name },
        contentAlignment = Alignment.Center,
    ) {
        ServicePill(s, Modifier.fillMaxWidth().height(40.dp), interactions)
    }
}

/** Nothing picked yet: an outlined pill the size of a button, the way into the picker. */
@Composable
private fun AddButtons(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, c.outline),
        modifier = modifier.fillMaxHeight(),
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(Copy.REMOTE_BUTTONS, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
        }
    }
}

/**
 * A service's brand pill, the same colour in both themes like a button on a real remote, drawn by
 * [RemoteButtons.look] against the sheet behind it.
 */
@Composable
private fun ServicePill(s: StreamingService, modifier: Modifier = Modifier, interactions: InteractionSource? = null) {
    val c = MaterialTheme.colorScheme
    val dark = c.surface.luminance() < 0.5f
    val look = RemoteButtons.look(s, c.surfaceContainerLow.toArgb().toLong() and 0xFFFFFFFF, dark)
    // The name stands in until the logo is downloaded, or for good when it can't be.
    val logo = produceState<ImageBitmap?>(null, s.logo) {
        value = SharedLists.logo(s.logo)?.let(Png::decode)?.asImageBitmap()
    }.value
    val fill = look.fill2?.let { Brush.linearGradient(listOf(Color(look.fill), Color(it))) } ?: SolidColor(Color(look.fill))
    Box(
        modifier
            .clip(CircleShape)
            .background(fill)
            .then(if (look.outline) Modifier.border(1.dp, c.outline, CircleShape) else Modifier)
            .then(if (interactions != null) Modifier.indication(interactions, ripple()) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            // Drawn at four times its size in dp.
            Image(
                logo, contentDescription = null,
                modifier = Modifier.size((logo.width / 4f).dp, (logo.height / 4f).dp),
                colorFilter = look.ink?.let { ColorFilter.tint(Color(it)) },
            )
        } else {
            Text(s.name, style = MaterialTheme.typography.labelLarge, color = Color(look.ink ?: RemoteButtons.WHITE), maxLines = 1)
        }
    }
}

/**
 * Which services get a button, and in what order: ticked ones first, dragged by their handle to
 * reorder, then the rest by group. Past [cap] the others wait for one to be unticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ButtonsSheet(dock: List<Docked>, apps: List<AppInfo>, cap: Int, setDock: (List<Docked>) -> Unit, onDismiss: () -> Unit) {
    val services = SharedLists.state.collectAsState().value.streaming
    val ticked = dock.mapNotNull { RemoteButtons.serviceOf(it, services) }.distinct()
    val rest = RemoteButtons.choices(services, apps) - ticked.toSet()
    val toggle = { s: StreamingService -> setDock(RemoteButtons.toggle(dock, s, services, cap, apps)) }
    // One entry per ticked service, in the same order, so a ticked row's index moves its entry.
    val mine = dock.filter { RemoteButtons.serviceOf(it, services) != null }.distinctBy { RemoteButtons.serviceOf(it, services) }
    // The ticked row being dragged, and how far.
    var lifted by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { SHEET_ROW.toPx() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 32.dp).padding(bottom = 8.dp)) {
            Text(Copy.REMOTE_BUTTONS, style = MaterialTheme.typography.titleLarge)
            Text(Copy.buttonsHint(cap), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            ticked.forEachIndexed { i, s ->
                key(s.name) {
                    val up = lifted == s.name
                    val moves = listOfNotNull(
                        CustomAccessibilityAction("Move up") { setDock(Dock.move(mine, i, i - 1)); true }.takeIf { i > 0 },
                        CustomAccessibilityAction("Move down") { setDock(Dock.move(mine, i, i + 1)); true }.takeIf { i < ticked.lastIndex },
                    )
                    ServiceRow(
                        s, checked = true, enabled = true, onToggle = { toggle(s) },
                        modifier = Modifier
                            .offset { IntOffset(0, if (up) dragY.roundToInt() else 0) }
                            .zIndex(if (up) 1f else 0f)
                            .background(if (up) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                            .semantics { customActions = moves },
                    ) {
                        Icon(
                            RemoteIcons.DragHandle, contentDescription = null,
                            modifier = Modifier.size(48.dp).padding(12.dp).pointerInput(s.name, ticked.size) {
                                detectDragGestures(
                                    onDragStart = { lifted = s.name; dragY = 0f },
                                    onDragEnd = {
                                        val to = (i + (dragY / rowPx).roundToInt()).coerceIn(0, ticked.lastIndex)
                                        if (to != i) setDock(Dock.move(mine, i, to))
                                        lifted = null
                                        dragY = 0f
                                    },
                                    onDragCancel = { lifted = null; dragY = 0f },
                                ) { change, amount -> change.consume(); dragY += amount.y }
                            },
                        )
                    }
                }
            }
            rest.forEachIndexed { i, s ->
                if (i == 0 || rest[i - 1].group != s.group) {
                    Text(
                        s.group,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                key(s.name) { ServiceRow(s, checked = false, enabled = ticked.size < cap, onToggle = { toggle(s) }) }
            }
        }
    }
}

private val SHEET_ROW = 56.dp

/** A row of the picker: its tick, its pill and its name, and [trailing] for a ticked one's drag handle. */
@Composable
private fun ServiceRow(
    s: StreamingService,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(SHEET_ROW)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .alpha(if (enabled) 1f else 0.38f)
            .padding(start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(48.dp))
        ServicePill(s, Modifier.width(80.dp).height(40.dp))
        Spacer(Modifier.width(16.dp))
        Text(s.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

private object RemoteIcons {
    // Absolute commands only: Compose's path parser misplaces a relative move after a close.
    val TvOff = icon(
        "TvOff",
        "M1 3.54L2.53 5.07C1.65 5.28 1 6.06 1 7L1 19C1 20.1 1.9 21 3 21L18.46 21L20.46 23L21.72 21.73L2.27 2.27L1 3.54Z" +
            "M3 19L3 7L4.46 7L16.46 19L3 19Z" +
            "M21 5L13.42 5L16.71 1.7L16 1L12 5L8 1L7.3 1.7L10.58 5L7.52 5L9.52 7L21 7L21 18.48L22.65 20.13C22.87 19.81 23 19.42 23 19L23 7C23 5.89 22.11 5 21 5Z",
    )
    val Tv = icon(
        "Tv",
        "M21 3L3 3C1.9 3 1 3.9 1 5L1 17C1 18.1 1.9 19 3 19L8 19L8 21L16 21L16 19L21 19C22.1 19 22.99 18.1 22.99 17L23 5C23 3.9 22.1 3 21 3Z" +
            "M21 17L3 17L3 5L21 5L21 17Z",
    )
    val Backspace = icon(
        "Backspace",
        "M22 3L7 3C6.31 3 5.77 3.35 5.41 3.88L0 12L5.41 20.11C5.77 20.64 6.31 21 7 21L22 21C23.1 21 24 20.1 24 19L24 5C24 3.9 23.1 3 22 3Z" +
            "M19 15.59L17.59 17L14 13.41L10.41 17L9 15.59L12.59 12L9 8.41L10.41 7L14 10.59L17.59 7L19 8.41L15.41 12L19 15.59Z",
    )
    val RestartAlt = icon(
        "RestartAlt",
        "M12 5L12 2L8 6L12 10L12 7C15.31 7 18 9.69 18 13C18 15.97 15.83 18.43 13 18.91L13 20.93C16.95 20.44 20 17.08 20 13C20 8.58 16.42 5 12 5Z" +
            "M6 13C6 11.35 6.67 9.85 7.76 8.76L6.34 7.34C4.9 8.79 4 10.79 4 13C4 17.08 7.05 20.44 11 20.93L11 18.91C8.17 18.43 6 15.97 6 13Z",
    )
    val HourglassTop = icon(
        "HourglassTop",
        "M6 2L6.01 8L10 12L6.01 16.01L6 22L18 22L18 16L14 12L18 8.01L18 2L6 2Z M16 16.5L16 20L8 20L8 16.5L12 12.5L16 16.5Z",
    )
    val LinkOff = icon(
        "LinkOff",
        "M17 7L13 7L13 8.9L17 8.9C18.71 8.9 20.1 10.29 20.1 12C20.1 13.43 19.12 14.63 17.79 14.98L19.25 16.44C20.88 15.61 22 13.95 22 12C22 9.24 19.76 7 17 7Z" +
            "M16 11L13.81 11L15.81 13L16 13Z" +
            "M2 4.27L5.11 7.38C3.29 8.12 2 9.91 2 12C2 14.76 4.24 17 7 17L11 17L11 15.1L7 15.1C5.29 15.1 3.9 13.71 3.9 12C3.9 10.41 5.11 9.1 6.66 8.93" +
            "L8.73 11L8 11L8 13L10.73 13L13 15.27L13 17L14.73 17L18.74 21L20 19.74L3.27 3L2 4.27Z",
    )
    val VolumeUp = icon(
        "VolumeUp",
        "M3 9L3 15L7 15L12 20L12 4L7 9Z M16.5 12C16.5 10.23 15.48 8.71 14 7.97L14 16.02C15.48 15.29 16.5 13.77 16.5 12Z" +
            "M14 3.23L14 5.29C16.89 6.15 19 8.83 19 12C19 15.17 16.89 17.85 14 18.71L14 20.77C18.01 19.86 21 16.28 21 12C21 7.72 18.01 4.14 14 3.23Z",
    )
    val VolumeDown = icon(
        "VolumeDown",
        "M18.5 12C18.5 10.23 17.48 8.71 16 7.97L16 16.02C17.48 15.29 18.5 13.77 18.5 12Z M5 9L5 15L9 15L14 20L14 4L9 9Z",
    )
    val VolumeOff = icon(
        "VolumeOff",
        "M16.5 12C16.5 10.23 15.48 8.71 14 7.97L14 10.18L16.45 12.63C16.48 12.43 16.5 12.22 16.5 12Z" +
            "M19 12C19 12.94 18.8 13.82 18.46 14.64L19.97 16.15C20.63 14.91 21 13.5 21 12C21 7.72 18.01 4.14 14 3.23L14 5.29C16.89 6.15 19 8.83 19 12Z" +
            "M4.27 3L3 4.27L7.73 9L3 9L3 15L7 15L12 20L12 13.27L16.25 17.52C15.58 18.04 14.83 18.45 14 18.7L14 20.76C15.38 20.45 16.63 19.81 17.69 18.95" +
            "L19.73 21L21 19.73L12 10.73L4.27 3Z M12 4L9.91 6.09L12 8.18L12 4Z",
    )
    val DragHandle = icon("DragHandle", "M20 9L4 9L4 11L20 11L20 9Z M4 15L20 15L20 13L4 13L4 15Z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
        .build()
}
