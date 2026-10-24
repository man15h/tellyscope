// SPDX-License-Identifier: GPL-3.0-only
// RemoteIcons are the Material Icons "power_settings_new", "volume_off", "volume_up", "remove", "keyboard"
// and "settings_remote" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tellyscope.adb.RemoteKey
import app.tellyscope.data.DeviceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class RemoteActions(
    /** Suspends until the TV answers, so a held key never queues up presses. False when it didn't take the key. */
    val press: suspend (RemoteKey) -> Boolean = { true },
    /** Returns why the text can't be typed, or null once it's sent. */
    val type: (String) -> String? = { null },
    val reboot: () -> Unit = {},
    val reconnect: () -> Unit = {},
    val back: () -> Unit = {},
)

/** A held key repeats after this, then as fast as the TV takes them. */
private const val REPEAT_AFTER_MS = 400L

/**
 * One press, then repeats while [held] for keys that repeat. Stops at the first key the TV didn't
 * take (refused, dropped, or no TV selected), and yields between repeats, so a press that answers
 * at once can't keep the main thread busy.
 */
internal suspend fun repeatWhileHeld(key: RemoteKey, held: () -> Boolean, press: suspend (RemoteKey) -> Boolean) {
    if (!press(key) || !key.repeats) return
    delay(REPEAT_AFTER_MS)
    while (held() && press(key)) yield()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: UiState,
    actions: RemoteActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** Opens with the Type panel up, for screenshots; a tap on Type on the TV also focuses it. */
    startTyping: Boolean = false,
) {
    val device = state.selected ?: return
    val restart = state.restarting[device.id]
    val live = device.state == DeviceState.Online && restart == null
    var confirmReboot by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(startTyping) }
    var focusTyping by remember { mutableStateOf(false) }
    if (confirmReboot) RebootDialog(onRestart = { confirmReboot = false; actions.reboot() }, onDismiss = { confirmReboot = false })
    BackHandler(typing) { typing = false }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name(device), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateDot(device.state)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (restart != null) "Restarting" else Copy.state(device.state),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the TV's page") }
                },
                actions = {
                    val scope = rememberCoroutineScope()
                    FilledTonalIconButton(onClick = { scope.launch { actions.press(RemoteKey.SLEEP) } }, enabled = live) {
                        Icon(RemoteIcons.Power, contentDescription = "Sleep")
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Restart TV") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                enabled = live,
                                onClick = { menu = false; confirmReboot = true },
                            )
                        }
                    }
                },
            )
        },
        // The panel rides on top of the keyboard, and the remote above shrinks to the space left.
        bottomBar = { if (typing) TypePanel(live, actions.type, focus = focusTyping, close = { typing = false }) },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                restart != null -> RestartingCard(restart, state.now)
                device.state != DeviceState.Online -> OfflineCard(device, state.now, actions.reconnect)
            }
            Column(
                Modifier.weight(1f).alpha(if (live) 1f else 0.38f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            ) {
                // As big as the space left allows, up to 320dp, so the keys never scroll or clip.
                BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DPad(minOf(maxWidth, maxHeight, 320.dp), live, actions.press)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundKey(RemoteKey.BACK, "Back", live, actions.press) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                    RoundKey(RemoteKey.HOME, "Home", live, actions.press) { Icon(Icons.Default.Home, null) }
                    RoundKey(RemoteKey.MENU, "Menu", live, actions.press) { Icon(Icons.Default.Menu, null) }
                }
                if (!typing) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundKey(RemoteKey.MUTE, "Mute", live, actions.press, 64.dp) { Icon(RemoteIcons.VolumeOff, null) }
                        VolumeRocker(live, actions.press, Modifier.weight(1f))
                        RoundKey(RemoteKey.PLAY_PAUSE, "Play or pause", live, actions.press, 64.dp) { Icon(Icons.Default.PlayArrow, null) }
                    }
                    OutlinedButton(
                        onClick = { focusTyping = true; typing = true },
                        enabled = live,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(RemoteIcons.Keyboard, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Type on the TV")
                    }
                }
            }
        }
    }
}

/** The four arrows around a big OK, shaped like the remote in the user's other hand. */
@Composable
private fun DPad(size: Dp, enabled: Boolean, press: suspend (RemoteKey) -> Boolean) {
    Surface(Modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Box {
            val arrow = Modifier.size(size / 4)
            val tint = MaterialTheme.colorScheme.onSurface
            KeySurface(RemoteKey.UP, "Up", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.TopCenter)) {
                Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(40.dp), tint = tint)
            }
            KeySurface(RemoteKey.DOWN, "Down", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.BottomCenter)) {
                Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(40.dp), tint = tint)
            }
            KeySurface(RemoteKey.LEFT, "Left", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.CenterStart)) {
                Icon(Icons.Default.KeyboardArrowLeft, null, Modifier.size(40.dp), tint = tint)
            }
            KeySurface(RemoteKey.RIGHT, "Right", enabled, press, CircleShape, Color.Transparent, arrow.align(Alignment.CenterEnd)) {
                Icon(Icons.Default.KeyboardArrowRight, null, Modifier.size(40.dp), tint = tint)
            }
            KeySurface(
                RemoteKey.OK, "OK", enabled, press, CircleShape, MaterialTheme.colorScheme.primary,
                Modifier.size(size * 3 / 8).align(Alignment.Center),
            ) {
                Text("OK", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

/** A round key with only its icon, as on the TV's own remote; [label] is what TalkBack reads. */
@Composable
private fun RoundKey(
    key: RemoteKey,
    label: String,
    enabled: Boolean,
    press: suspend (RemoteKey) -> Boolean,
    size: Dp = 72.dp,
    icon: @Composable () -> Unit,
) = KeySurface(key, label, enabled, press, CircleShape, MaterialTheme.colorScheme.secondaryContainer, Modifier.size(size), icon)

/** Volume down and up as the two ends of one pill. */
@Composable
private fun VolumeRocker(enabled: Boolean, press: suspend (RemoteKey) -> Boolean, modifier: Modifier = Modifier) {
    Surface(modifier.height(64.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeySurface(RemoteKey.VOLUME_DOWN, "Volume down", enabled, press, CircleShape, Color.Transparent, Modifier.size(64.dp)) {
                Icon(RemoteIcons.Remove, null)
            }
            Icon(RemoteIcons.VolumeUp, null, Modifier.weight(1f).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            KeySurface(RemoteKey.VOLUME_UP, "Volume up", enabled, press, CircleShape, Color.Transparent, Modifier.size(64.dp)) {
                Icon(Icons.Default.Add, null)
            }
        }
    }
}
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
            .pointerInput(key, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val p = PressInteraction.Press(down.position)
                    interactions.tryEmit(p)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    var held = true
                    scope.launch { repeatWhileHeld(key, { held }, press) }
                    waitForUpOrCancellation()
                    held = false
                    interactions.tryEmit(PressInteraction.Release(p))
                }
            },
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * Types into the TV's selected text box. Sits in the bottom bar, padded by the keyboard, so it stays
 * right on top of it and the remote above gets the space that is left.
 */
@Composable
private fun TypePanel(enabled: Boolean, type: (String) -> String?, focus: Boolean, close: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun send() {
        error = type(text)
        if (error == null) text = ""
    }
    val field = remember { FocusRequester() }
    LaunchedEffect(focus) { if (focus) field.requestFocus() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Type on the TV", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = close) { Icon(Icons.Default.Close, contentDescription = "Close typing") }
            }
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    enabled = enabled,
                    placeholder = { Text("Text to send") },
                    supportingText = { Text(error ?: Copy.TYPE_HINT) },
                    isError = error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.weight(1f).focusRequester(field),
                )
                FilledIconButton(onClick = ::send, enabled = enabled && text.isNotEmpty(), modifier = Modifier.padding(top = 4.dp).size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to the TV")
                }
            }
        }
    }
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

/** Material icons the core set lacks. Absolute moves only: Compose's path parser misplaces a relative move after a close. */
internal object RemoteIcons {
    val Power = icon(
        "Power",
        "M13 3h-2v10h2V3zM17.83 5.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42" +
            "L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z",
    )
    val VolumeUp = icon(
        "VolumeUp",
        "M3 9v6h4l5 5V4L7 9H3zM16.5 12c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02z" +
            "M14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z",
    )
    val VolumeOff = icon(
        "VolumeOff",
        "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63z" +
            "M19 12c0 .94-.2 1.82-.54 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71z" +
            "M4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3z" +
            "M12 4L9.91 6.09 12 8.18V4z",
    )
    val Remove = icon("Remove", "M19 13H5v-2h14v2z")
    val Keyboard = icon(
        "Keyboard",
        "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2z" +
            "M11 8h2v2h-2V8zM11 11h2v2h-2v-2zM8 8h2v2H8V8zM8 11h2v2H8v-2zM7 13H5v-2h2v2zM7 10H5V8h2v2z" +
            "M16 17H8v-2h8v2zM16 13h-2v-2h2v2zM16 10h-2V8h2v2zM19 13h-2v-2h2v2zM19 10h-2V8h2v2z",
    )
    val Remote = icon(
        "Remote",
        "M15 9H9c-.55 0-1 .45-1 1v12c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V10c0-.55-.45-1-1-1z" +
            "M12 15c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z" +
            "M7.05 6.05l1.41 1.41C9.37 6.56 10.62 6 12 6s2.63.56 3.54 1.46l1.41-1.41C15.68 4.78 13.93 4 12 4s-3.68.78-4.95 2.05z" +
            "M12 0C8.96 0 6.21 1.23 4.22 3.22l1.41 1.41C7.26 3.01 9.51 2 12 2s4.74 1.01 6.36 2.64l1.41-1.41C17.79 1.23 15.04 0 12 0z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
        .build()
}
