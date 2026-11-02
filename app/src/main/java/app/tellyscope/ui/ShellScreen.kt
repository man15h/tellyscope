// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GenericShape
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tellyscope.R
import app.tellyscope.data.Device
import app.tellyscope.data.resolve
import app.tellyscope.ui.theme.TellyscopeTheme
import kotlin.math.hypot

/**
 * Advanced's Shell: a black terminal that grows out of the Shell button ([from], in the root) and
 * shrinks back into it on ✕, Back or `exit`. Commands go to [terminal]; it's always dark, whatever
 * the app's theme.
 */
@Composable
fun ShellOverlay(open: Boolean, from: Offset, device: Device, online: Boolean, terminal: ShellTerminal, onClose: () -> Unit) {
    val reveal = remember { Animatable(if (open) 1f else 0f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(open) {
        if (open) terminal.opened()
        reveal.animateTo(if (open) 1f else 0f, spec)
    }
    if (!open && reveal.value == 0f) return
    BackHandler(open, onClose)
    val state by terminal.state.collectAsState()
    LaunchedEffect(state.exited) { if (state.exited) onClose() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val center = from - origin
    val circle = GenericShape { size, _ ->
        val r = maxOf(hypot(center.x, center.y), hypot(size.width - center.x, center.y), hypot(center.x, size.height - center.y), hypot(size.width - center.x, size.height - center.y))
        addOval(Rect(center, r * reveal.value))
    }
    TellyscopeTheme(dark = true) {
        Box(
            Modifier.fillMaxSize()
                .onGloballyPositioned { origin = it.positionInRoot() }
                .clip(circle)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Terminal(device, online, terminal, state, onClose)
        }
    }
}

@Composable
private fun Terminal(device: Device, online: Boolean, terminal: ShellTerminal, state: TerminalState, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = c.onSurface)
    val address = stringResource(R.string.advanced_address, device.hit.ip, device.hit.port)
    var input by remember { mutableStateOf(TextFieldValue()) }
    // Steps back through Recent with ↑; -1 is the line being typed.
    var back by remember { mutableIntStateOf(-1) }
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val clipboard = LocalClipboardManager.current

    fun set(text: String) { input = TextFieldValue(text, TextRange(text.length)) }
    fun type(s: String) {
        val t = input.text.replaceRange(input.selection.min, input.selection.max, s)
        input = TextFieldValue(t, TextRange(input.selection.min + s.length))
    }
    fun enter() {
        terminal.submit(input.text, online)
        // Offline, Enter does nothing, so the line stays to run once the TV is back.
        if (online || state.confirming != null) set("")
        back = -1
    }

    LaunchedEffect(state.lines.size, state.running, state.confirming) { list.scrollToItem(maxOf(0, list.layoutInfo.totalItemsCount - 1)) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.shell_close), tint = c.onSurface) }
            Text(stringResource(R.string.shell_title, name(device), address), style = mono.copy(color = c.onSurfaceVariant), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            item { Text(stringResource(R.string.shell_banner, name(device), address), style = mono.copy(color = c.onSurfaceVariant)) }
            if (state.trimmed) item { Text(stringResource(R.string.shell_trimmed, ShellTerminal.MAX_LINES), style = mono.copy(color = c.onSurfaceVariant)) }
            items(state.lines.size) { i ->
                when (val l = state.lines[i]) {
                    is TermLine.Typed -> Row {
                        Text(stringResource(R.string.shell_prompt, l.cwd), style = mono.copy(color = c.primary))
                        Text(" " + l.command, style = mono)
                    }
                    is TermLine.Out -> Text(l.text, style = mono)
                    is TermLine.Err -> Text(l.text, style = mono.copy(color = c.error))
                    is TermLine.Note -> Text(l.text.resolve(), style = mono.copy(color = c.onSurfaceVariant))
                    is TermLine.Warn -> Text(l.text.resolve(), style = mono.copy(color = c.error))
                }
            }
            // The line being typed sits under the output, as in a terminal; there's none while a command runs.
            if (!state.running) item {
                Row(verticalAlignment = Alignment.Top) {
                    when {
                        state.confirming != null -> Text(stringResource(R.string.shell_confirm) + " ", style = mono.copy(color = c.error))
                        !online -> Text(stringResource(R.string.shell_offline_prompt) + " ", style = mono.copy(color = c.onSurfaceVariant))
                        else -> Text(stringResource(R.string.shell_prompt, state.cwd) + " ", style = mono.copy(color = c.primary))
                    }
                    BasicTextField(
                        input,
                        { input = it },
                        Modifier.weight(1f).focusRequester(focus),
                        textStyle = mono,
                        cursorBrush = SolidColor(c.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Go,
                        ),
                        keyboardActions = KeyboardActions(onGo = { enter() }),
                    )
                }
            }
            if (!online && !state.running) item { Text(stringResource(R.string.shell_offline_hint), style = mono.copy(color = c.onSurfaceVariant)) }
        }
        // Termux-style keys for what a phone keyboard hides on its symbols page.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key(stringResource(R.string.shell_key_paste)) { clipboard.getText()?.text?.let { type(it.trim().replace("\n", "; ")) } }
            Key(stringResource(R.string.shell_key_tab)) { terminal.complete(input.text) { set(it) } }
            Key(stringResource(R.string.shell_key_ctrl_c), stop = state.running) {
                if (state.running || state.confirming != null) terminal.interrupt() else set("")
            }
            Key(icon = Icons.Default.KeyboardArrowUp, label = stringResource(R.string.shell_key_up)) {
                if (back + 1 < state.recent.size) { back++; set(state.recent[back]) }
            }
            Key(icon = Icons.Default.KeyboardArrowDown, label = stringResource(R.string.shell_key_down)) {
                if (back > 0) { back--; set(state.recent[back]) } else { back = -1; set("") }
            }
            listOf("|", "-", "/").forEach { s -> Key(s) { type(s) } }
        }
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.confirming != null) {
                item { SuggestionChip(onClick = { set("y"); enter() }, label = { Text(stringResource(R.string.shell_answer_yes), style = mono) }) }
                item { SuggestionChip(onClick = { set(""); enter() }, label = { Text(stringResource(R.string.shell_answer_no), style = mono) }) }
            } else {
                items(state.recent) { r ->
                    SuggestionChip(onClick = { set(r); back = -1 }, label = { Text(r, style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }
}

@Composable
private fun Key(text: String = "", icon: ImageVector? = null, label: String = text, stop: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    AssistChip(
        onClick = onClick,
        label = { if (icon == null) Text(text, fontFamily = FontFamily.Monospace) },
        leadingIcon = icon?.let { { Icon(it, label) } },
        colors = if (stop) AssistChipDefaults.assistChipColors(containerColor = c.error, labelColor = c.onError) else AssistChipDefaults.assistChipColors(),
        border = if (stop) null else AssistChipDefaults.assistChipBorder(enabled = true),
    )
}

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
    .build()

internal val TerminalIcon = icon(
    "Terminal",
    "M20 4H4c-1.11 0-2 .9-2 2v12c0 1.1.89 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.89-2-2-2zm0 14H4V8h16v10zm-2-1h-6v-2h6v2zM7.5 17l-1.41-1.41L8.67 13l-2.59-2.59L7.5 9l4 4-4 4z",
)
