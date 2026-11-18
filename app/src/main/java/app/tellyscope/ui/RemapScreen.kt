// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.adb.RemapKeys
import app.tellyscope.adb.Remapper
import app.tellyscope.data.AppInfo
import app.tellyscope.data.RemapAction
import app.tellyscope.data.RemapButton
import app.tellyscope.data.SharedLists
import app.tellyscope.data.StreamingService
import app.tellyscope.data.isFireOs
import app.tellyscope.data.resolve
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What Remap TV remote's screen can ask for; [RemapUi] holds where it stands. */
data class RemapActions(
    val back: () -> Unit = {},
    /** Start remapping, Start again and Try again. */
    val start: () -> Unit = {},
    /** Stop, which keeps the buttons; true for Clear all buttons, which forgets them. */
    val stop: (Boolean) -> Unit = {},
    /** Done on the press-the-buttons frame. */
    val done: () -> Unit = {},
    /** Listen for a press, for an app slot or [RemapButton.NO_SLOT]. */
    val listen: (Int) -> Unit = {},
    val cancelListen: () -> Unit = {},
    /** The dialog or sheet for the last press closed. */
    val heardShown: () -> Unit = {},
    /** Pick action's Save, TV default and Undo. */
    val set: (RemapButton) -> Unit = {},
)

/**
 * Remap TV remote: a drawing of a generic remote, its colour keys and four app-button slots, with
 * a line from each mapped button to what it does now. The script on the TV does the work
 * ([Remapper]); this page starts and stops it, learns buttons and picks their actions. Opened from
 * the Remote's ⋮.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemapScreen(state: UiState, actions: RemapActions, snackbar: SnackbarHostState) {
    val device = state.selected ?: return
    val ui = state.device.remap ?: return
    val apps = remember(state.device.apps) { state.device.apps.filter { it.opens }.sortedBy { it.label.lowercase() } }
    val services = SharedLists.state.collectAsState().value.streaming
    val fire = isFireOs(device, state.device.apps)
    val res = LocalResources.current
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    /** The button whose Pick action is open from a tap, not a press; null when none is. */
    var editing by remember { mutableStateOf<Pair<RemapButton, Boolean>?>(null) }
    /** The slot the last listen was for, so Try again listens for the same one. */
    var lastSlot by rememberSaveable { mutableStateOf(RemapButton.NO_SLOT) }
    val listen = { slot: Int -> lastSlot = slot; actions.listen(slot) }

    val phase = when {
        !ui.loaded -> Phase.Loading
        ui.starting -> Phase.Starting
        ui.failed != null -> Phase.Failed
        ui.running && ui.firstRun -> Phase.FirstRun
        ui.running -> Phase.On
        ui.on -> Phase.Restarted
        ui.buttons.isNotEmpty() -> Phase.Stopped
        else -> Phase.NotSetUp
    }
    val live = phase == Phase.On || phase == Phase.FirstRun

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.remap_title))
                        Text(name(device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back_to_tv)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = Copy.MORE.resolve()) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remap_clear_all), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                enabled = ui.loaded && (ui.buttons.isNotEmpty() || ui.running),
                                onClick = { menu = false; confirmClear = true },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(phase, ui.failed, actions, onDetails = { details = true })
            RemoteDrawing(
                buttons = ui.buttons,
                apps = state.device.apps,
                services = services,
                fire = fire,
                live = live,
                firstRun = phase == Phase.FirstRun,
                listening = ui.listening?.slot?.takeIf { phase == Phase.FirstRun },
                onSlot = { slot, b -> if (b == null) listen(slot) else if (phase != Phase.FirstRun) { editing = b to true } },
                onColour = { key -> editing = (ui.buttons.firstOrNull { it.key == key } ?: RemapButton(key)).let { it to (it.action != null) } },
            )
            when (phase) {
                Phase.FirstRun -> FirstRunFooter(ui, onDone = actions.done)
                Phase.Restarted, Phase.Stopped -> Caption(stringResource(if (phase == Phase.Restarted) R.string.remap_restarted_caption else R.string.remap_stopped_caption))
                else -> {
                    if (fire) Caption(stringResource(R.string.remap_fire_no_colours))
                    Caption(stringResource(if (fire) R.string.remap_fire_hint else R.string.remap_hint))
                    if (fire) Caption(stringResource(R.string.remap_fire_untested))
                }
            }
            val others = ui.buttons.filter { !it.colour && it.slot == RemapButton.NO_SLOT }
            if (others.isNotEmpty() && phase != Phase.FirstRun) {
                Text(stringResource(R.string.remap_other_buttons), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.alpha(if (live) 1f else 0.38f)) {
                    for (b in others) OtherRow(b, state.device.apps, services, enabled = live, onClick = { editing = b to true })
                }
            }
            if (phase != Phase.FirstRun && phase != Phase.Restarted && phase != Phase.Stopped) {
                FilledTonalButton(onClick = { listen(RemapButton.NO_SLOT) }, enabled = live) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remap_add_button))
                }
            }
        }
    }

    ui.listening?.takeIf { phase != Phase.FirstRun }?.let { ListenDialog(it.since, state, onCancel = actions.cancelListen) }
    when (val heard = ui.heard) {
        null -> {}
        RemapUi.Heard.NoPress -> AlertDialog(
            onDismissRequest = actions.heardShown,
            icon = { Icon(Icons.Default.Info, null) },
            title = { Text(stringResource(R.string.remap_no_press_title)) },
            text = { Text(stringResource(R.string.remap_no_press_text)) },
            dismissButton = { TextButton(onClick = actions.heardShown) { Text(stringResource(R.string.copy_cancel)) } },
            confirmButton = { TextButton(onClick = { actions.heardShown(); listen(lastSlot) }) { Text(stringResource(R.string.copy_try_again)) } },
        )
        RemapUi.Heard.NotSent -> InfoDialog(RemapIcons.Block, R.string.remap_not_sent_title, R.string.remap_not_sent_text, actions.heardShown)
        RemapUi.Heard.Refused -> InfoDialog(RemapIcons.Block, R.string.remap_refused_title, R.string.remap_refused_text, actions.heardShown)
        is RemapUi.Heard.Button -> PickActionSheet(heard.button, known = heard.known, fresh = !heard.known, apps, services, onSave = actions.set, onDismiss = actions.heardShown)
    }
    editing?.let { (b, known) ->
        PickActionSheet(
            b, known = known, fresh = false, apps, services,
            onSave = { saved ->
                editing = null
                actions.set(saved)
                if (saved.action == null && b.action != null) {
                    scope.launch {
                        val r = snackbar.showSnackbar(
                            res.getString(R.string.remap_back_to_tv, buttonName(b, res)),
                            actionLabel = res.getString(R.string.copy_undo),
                            duration = SnackbarDuration.Short,
                        )
                        if (r == SnackbarResult.ActionPerformed) actions.set(b)
                    }
                }
            },
            onDismiss = { editing = null },
        )
    }
    if (confirmClear) {
        val count = ui.buttons.size
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.remap_clear_title)) },
            text = { Text(pluralStringResource(R.plurals.remap_clear_text, count, count)) },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.copy_cancel)) } },
            confirmButton = { TextButton(onClick = { confirmClear = false; actions.stop(true) }) { Text(stringResource(R.string.remap_clear_confirm)) } },
        )
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(stringResource(R.string.details_title)) },
            text = { Text(ui.failed?.ifBlank { null } ?: stringResource(R.string.remap_failed_no_reason), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.remote_ok)) } },
        )
    }
}

private enum class Phase { Loading, Starting, Failed, NotSetUp, FirstRun, On, Restarted, Stopped }

/** The card above the drawing: what remapping is doing, and the one thing to do about it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StatusCard(phase: Phase, failed: String?, actions: RemapActions, onDetails: () -> Unit) {
    val c = MaterialTheme.colorScheme
    when (phase) {
        Phase.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        Phase.On -> Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = c.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.remap_on), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.stop(false) }) { Text(stringResource(R.string.copy_stop)) }
            }
        }
        Phase.FirstRun -> InfoCard(c.secondaryContainer, c.onSecondaryContainer, RemapIcons.TouchApp, stringResource(R.string.remap_first_title), stringResource(R.string.remap_first_text))
        Phase.Starting -> InfoCard(c.secondaryContainer, c.onSecondaryContainer, null, stringResource(R.string.remap_starting), stringResource(R.string.remap_starting_text))
        Phase.NotSetUp -> InfoCard(c.secondaryContainer, c.onSecondaryContainer, RemapIcons.SettingsRemote, stringResource(R.string.remap_intro_title), stringResource(R.string.remap_intro_text)) {
            Button(onClick = actions.start) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remap_start))
            }
        }
        Phase.Failed -> InfoCard(c.errorContainer, c.onErrorContainer, Icons.Default.Warning, stringResource(R.string.remap_failed_title), stringResource(R.string.remap_failed_text)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = actions.start) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_try_again))
                }
                if (!failed.isNullOrBlank()) TextButton(onClick = onDetails) { Text(stringResource(R.string.details_title)) }
            }
        }
        Phase.Restarted, Phase.Stopped -> InfoCard(
            c.surfaceContainerHigh, c.onSurface, RemapIcons.Pause,
            stringResource(if (phase == Phase.Restarted) R.string.remap_restarted_title else R.string.remap_stopped_title),
            stringResource(R.string.remap_stopped_text),
        ) {
            Button(onClick = actions.start) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.remap_start_again))
            }
        }
    }
}

/** A tinted card with an icon (a loading cookie when null), a title, a line and an optional action. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InfoCard(container: Color, content: Color, icon: ImageVector?, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Card(colors = CardDefaults.cardColors(containerColor = container, contentColor = content), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Icon(icon, null) else LoadingIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            action?.invoke()
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
}

/** The press-the-buttons frame's foot: how many app buttons are learned, which slot is listening, and Done. */
@Composable
private fun FirstRunFooter(ui: RemapUi, onDone: () -> Unit) {
    val learned = ui.buttons.count { !it.colour && it.slot != RemapButton.NO_SLOT }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Caption(pluralStringResource(R.plurals.remap_learned_count, learned, learned))
            ui.listening?.slot?.let { slot -> SLOT_LISTENING.getOrNull(slot)?.let { Caption(stringResource(it)) } }
        }
        Button(onClick = onDone) { Text(stringResource(R.string.copy_done)) }
    }
}

private val SLOT_NAMES = listOf(R.string.remap_slot_top_left, R.string.remap_slot_top_right, R.string.remap_slot_bottom_left, R.string.remap_slot_bottom_right)
private val SLOT_LISTENING = listOf(R.string.remap_listening_top_left, R.string.remap_listening_top_right, R.string.remap_listening_bottom_left, R.string.remap_listening_bottom_right)

/**
 * The colour keys' own colours: like the brand logos, the one place the drawing doesn't use theme
 * roles, as they're printed that way on every remote.
 */
private val COLOUR_KEYS = mapOf(
    "PROG_RED" to Color(0xFFD93025),
    "PROG_GREEN" to Color(0xFF1E8E3E),
    "PROG_YELLOW" to Color(0xFFF9AB00),
    "PROG_BLUE" to Color(0xFF1A73E8),
)

private val COLOUR_NAMES = mapOf(
    "PROG_RED" to R.string.remap_red,
    "PROG_GREEN" to R.string.remap_green,
    "PROG_YELLOW" to R.string.remap_yellow,
    "PROG_BLUE" to R.string.remap_blue,
)

/** "Green", "Netflix", or the key's own name (`BUTTON_15`) where the remote isn't known. */
private fun buttonName(b: RemapButton, res: android.content.res.Resources): String =
    COLOUR_NAMES[b.key]?.let(res::getString) ?: b.brand ?: b.key

@Composable
private fun buttonName(b: RemapButton): String = COLOUR_NAMES[b.key]?.let { stringResource(it) } ?: b.brand ?: b.key

@Composable
private fun actionName(a: RemapAction?): String = when (a) {
    null -> stringResource(R.string.remap_tv_default)
    RemapAction.DoNothing -> stringResource(R.string.remap_does_nothing)
    is RemapAction.Open -> stringResource(R.string.remap_opens, a.label)
}

/*
 * The drawing, in dp from the top of the remote's body and its centre line. A phone 360 dp wide
 * leaves 328: the 200 dp body, then 64 dp a side for a line and a 40 dp action button. Every drawn
 * button has a 48 dp touch target.
 */
private val BODY_W = 200.dp
private val DPAD_Y = 64.dp
private val DPAD_R = 40.dp
private val KEYS_Y = 128.dp
private val SLOT_W = 72.dp
private val SLOT_H = 36.dp
private val SLOT_X = 44.dp
private val SLOT_Y = listOf(172.dp, 224.dp)
private val COLOUR_Y = 272.dp
private val COLOUR_W = 36.dp
private val COLOUR_H = 18.dp
private val COLOUR_X = listOf((-72).dp, (-24).dp, 24.dp, 72.dp)
/** Red and blue's lines turn out shallow, green and yellow's deep, so none of them cross. */
private val COLOUR_LINE_Y = listOf(320.dp, 368.dp, 368.dp, 320.dp)
private val CHIP = 40.dp
private val TARGET = 48.dp

/**
 * The generic remote: D-pad, back/home/menu, four app slots and (not on Fire TV) the colour row.
 * Each mapped button has a dot and an elbow line out to its action on its own side. Grayed out
 * unless [live]. A dashed slot learns a new button; on the [firstRun] frame the slot [listening]
 * shows Press, and the buttons found on their own get a ring.
 */
@Composable
private fun RemoteDrawing(
    buttons: List<RemapButton>,
    apps: List<AppInfo>,
    services: List<StreamingService>,
    fire: Boolean,
    live: Boolean,
    firstRun: Boolean,
    listening: Int?,
    onSlot: (Int, RemapButton?) -> Unit,
    onColour: (String) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val bodyH = if (fire) 252.dp else 300.dp
    val height = if (fire) 268.dp else COLOUR_LINE_Y.max() + TARGET / 2
    val slots = (0 until RemapButton.SLOTS).map { s -> buttons.firstOrNull { it.slot == s && !it.colour } }
    val colours = RemapKeys.COLOURS.map { k -> buttons.firstOrNull { it.key == k } }
    val line = c.primary
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).alpha(if (live) 1f else 0.38f)) {
        val w = maxWidth
        val cx = w / 2
        val left = CHIP / 2
        val right = w - CHIP / 2
        fun slotX(s: Int) = cx + if (s % 2 == 0) -SLOT_X else SLOT_X
        fun slotY(s: Int) = SLOT_Y[s / 2]
        Canvas(Modifier.fillMaxSize()) {
            val body = Offset((cx - BODY_W / 2).toPx(), 0f)
            drawRoundRect(c.surfaceContainerHighest, body, Size(BODY_W.toPx(), bodyH.toPx()), CornerRadius(80.dp.toPx()))
            drawRoundRect(c.outlineVariant, body, Size(BODY_W.toPx(), bodyH.toPx()), CornerRadius(80.dp.toPx()), style = Stroke(1.dp.toPx()))
            drawCircle(c.outline, 4.dp.toPx(), Offset(cx.toPx(), 14.dp.toPx()))
            drawCircle(c.surfaceContainerHigh, DPAD_R.toPx(), Offset(cx.toPx(), DPAD_Y.toPx()))
            drawCircle(c.outlineVariant, DPAD_R.toPx(), Offset(cx.toPx(), DPAD_Y.toPx()), style = Stroke(1.dp.toPx()))
            drawCircle(c.outline, 14.dp.toPx(), Offset(cx.toPx(), DPAD_Y.toPx()))
            for (dx in listOf(-48, 0, 48)) {
                drawCircle(c.outlineVariant, 12.dp.toPx(), Offset((cx + dx.dp).toPx(), KEYS_Y.toPx()), style = Stroke(1.dp.toPx()))
            }
            if (firstRun) {
                // Found on their own: the standard keys' codes are fixed.
                drawCircle(line, DPAD_R.toPx() + 3.dp.toPx(), Offset(cx.toPx(), DPAD_Y.toPx()), style = Stroke(2.dp.toPx()))
                for (dx in listOf(-48, 0, 48)) drawCircle(line, 15.dp.toPx(), Offset((cx + dx.dp).toPx(), KEYS_Y.toPx()), style = Stroke(2.dp.toPx()))
            }
            for (s in 0 until RemapButton.SLOTS) {
                if (slots[s] != null && listening != s) continue
                val tl = Offset((slotX(s) - SLOT_W / 2).toPx(), (slotY(s) - SLOT_H / 2).toPx())
                drawRoundRect(
                    if (listening == s) line else c.outline, tl, Size(SLOT_W.toPx(), SLOT_H.toPx()), CornerRadius(SLOT_H.toPx() / 2),
                    style = Stroke(if (listening == s) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (listening == s) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            // Lines: an app slot straight out to its side; a colour down, then out.
            for (s in 0 until RemapButton.SLOTS) {
                if (slots[s]?.action == null) continue
                val y = slotY(s).toPx()
                val from = if (s % 2 == 0) slotX(s) - SLOT_W / 2 else slotX(s) + SLOT_W / 2
                val to = if (s % 2 == 0) left + CHIP / 2 else right - CHIP / 2
                drawLine(line, Offset(from.toPx(), y), Offset(to.toPx(), y), 2.dp.toPx())
                drawCircle(line, 4.dp.toPx(), Offset(from.toPx(), y))
            }
            if (!fire) for (i in 0..3) {
                if (colours[i]?.action == null) continue
                val x = (cx + COLOUR_X[i]).toPx()
                val top = (COLOUR_Y + COLOUR_H / 2).toPx()
                val y = COLOUR_LINE_Y[i].toPx()
                val to = if (i < 2) left + CHIP / 2 else right - CHIP / 2
                drawLine(line, Offset(x, top), Offset(x, y), 2.dp.toPx())
                drawLine(line, Offset(x, y), Offset(to.toPx(), y), 2.dp.toPx())
                drawCircle(line, 4.dp.toPx(), Offset(x, top))
            }
        }
        for (s in 0 until RemapButton.SLOTS) {
            val b = slots[s]
            val label = stringResource(SLOT_NAMES[s]) + (b?.let { ": " + buttonName(it) + ", " + actionName(it.action) } ?: "")
            Target(slotX(s), slotY(s), SLOT_W, TARGET, label, enabled = live) { onSlot(s, b) }
            when {
                listening == s -> Placed(slotX(s), slotY(s), SLOT_W, SLOT_H) {
                    Text(stringResource(R.string.remap_press), style = MaterialTheme.typography.labelLarge, color = line)
                }
                b != null -> Placed(slotX(s), slotY(s), SLOT_W, SLOT_H) {
                    Glyph(b, services, Modifier.size(SLOT_W, SLOT_H), ring = firstRun)
                }
            }
            if (b?.action != null) ActionChip(b.action, apps, if (s % 2 == 0) left else right, slotY(s), enabled = live) { onSlot(s, b) }
        }
        if (!fire) for (i in 0..3) {
            val key = RemapKeys.COLOURS[i]
            val b = colours[i]
            val x = cx + COLOUR_X[i]
            val label = stringResource(COLOUR_NAMES.getValue(key)) + (b?.let { ", " + actionName(it.action) } ?: "")
            Target(x, COLOUR_Y, TARGET, TARGET, label, enabled = live && !firstRun) { onColour(key) }
            Placed(x, COLOUR_Y, COLOUR_W, COLOUR_H) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(COLOUR_KEYS.getValue(key))
                        .then(if (firstRun) Modifier.border(2.dp, line, CircleShape) else Modifier),
                )
            }
            if (b?.action != null) ActionChip(b.action, apps, if (i < 2) left else right, COLOUR_LINE_Y[i], enabled = live) { onColour(key) }
        }
    }
}

/** Something [w] × [h] centred on ([x], [y]) in the drawing. */
@Composable
private fun Placed(x: Dp, y: Dp, w: Dp, h: Dp, content: @Composable () -> Unit) {
    Box(Modifier.offset(x - w / 2, y - h / 2).size(w, h), contentAlignment = Alignment.Center) { content() }
}

/** A drawn button's touch target, centred on it, named for TalkBack. */
@Composable
private fun Target(x: Dp, y: Dp, w: Dp, h: Dp, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.offset(x - w / 2, y - h / 2).size(w, h).clip(RoundedCornerShape(h / 2))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    )
}

/** A mapped button's action at the end of its line: the app's icon, or ⃠ for Do nothing; 40 dp in a 48 dp target. */
@Composable
private fun ActionChip(action: RemapAction, apps: List<AppInfo>, x: Dp, y: Dp, enabled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Placed(x, y, TARGET, TARGET) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = c.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.outlineVariant),
            modifier = Modifier.size(CHIP),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (action) {
                    RemapAction.DoNothing -> Icon(RemapIcons.Block, stringResource(R.string.remap_does_nothing), tint = c.onSurfaceVariant)
                    is RemapAction.Open -> AppGlyph(action, apps, Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
private fun AppGlyph(action: RemapAction.Open, apps: List<AppInfo>, modifier: Modifier) {
    val app = apps.firstOrNull { it.packageName == action.packageName }
        ?: AppInfo(action.packageName, action.label, iconColor = null, system = false, enabled = true, protectedReason = null, version = "")
    Box(Modifier.semantics { contentDescription = action.label }) { AppIcon(app, modifier) }
}

/**
 * A learned button as the remote shows it: a colour, its service's brand pill, or a keyboard tile
 * for a key with no known service.
 */
@Composable
private fun Glyph(b: RemapButton, services: List<StreamingService>, modifier: Modifier, ring: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val service = b.brand?.let { name -> services.firstOrNull { it.name.equals(name, ignoreCase = true) } }
    val ringed = if (ring) Modifier.border(2.dp, c.primary, CircleShape) else Modifier
    when {
        b.colour -> Box(modifier.clip(CircleShape).background(COLOUR_KEYS.getValue(b.key)).then(ringed))
        service != null -> ServicePill(service, modifier.then(ringed))
        else -> Box(modifier.clip(CircleShape).background(c.secondaryContainer).then(ringed), contentAlignment = Alignment.Center) {
            Icon(RemapIcons.Keyboard, null, tint = c.onSecondaryContainer, modifier = Modifier.size(20.dp))
        }
    }
}

/** An Other buttons row: a learned button the drawing has no slot for, and what it does. */
@Composable
private fun OtherRow(b: RemapButton, apps: List<AppInfo>, services: List<StreamingService>, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(b, services, Modifier.size(56.dp, 32.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(buttonName(b), style = MaterialTheme.typography.bodyLarge)
            Text(actionName(b.action), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) Icon(Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Listening: "Press the button on your TV remote now", with a ring that runs down the 15 s. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListenDialog(since: Long, state: UiState, onCancel: () -> Unit) {
    val total = Remapper.LEARN_SECONDS * 1000L
    var now by remember { mutableLongStateOf(state.clock()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val left = ((total - (now - since)).coerceIn(0, total))
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.remap_listen_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularWavyProgressIndicator(progress = { left / total.toFloat() }, modifier = Modifier.size(48.dp))
                val seconds = ((left + 999) / 1000).toInt()
                Text(pluralStringResource(R.plurals.remap_listen_text, seconds, seconds))
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.copy_cancel)) } },
    )
}

@Composable
private fun InfoDialog(icon: ImageVector, title: Int, text: Int, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onOk,
        icon = { Icon(icon, null) },
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(text)) },
        confirmButton = { TextButton(onClick = onOk) { Text(stringResource(R.string.remote_ok)) } },
    )
}

/**
 * Pick action: open an app, do nothing, or (for a button already in the list, [known]) TV default.
 * [fresh] for a button learned just now. Save is off until an app is picked for Open an app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickActionSheet(
    button: RemapButton,
    known: Boolean,
    fresh: Boolean,
    apps: List<AppInfo>,
    services: List<StreamingService>,
    onSave: (RemapButton) -> Unit,
    onDismiss: () -> Unit,
) {
    var choice by remember(button) { mutableStateOf(button.action?.let { if (it is RemapAction.Open) 0 else 1 } ?: if (known) 2 else 0) }
    var app by remember(button) { mutableStateOf(button.action as? RemapAction.Open) }
    var picking by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(button, services, Modifier.size(if (button.colour) 24.dp else 56.dp, if (button.colour) 24.dp else 32.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(stringResource(R.string.remap_button_title, buttonName(button)), style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            fresh -> stringResource(R.string.remap_learned_now, button.key)
                            known && button.action != null -> stringResource(R.string.remap_in_list, button.key)
                            else -> button.key
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ActionChoice(choice == 0, stringResource(R.string.copy_open_an_app), null, onClick = { choice = 0 }) {
                val picked = app
                AssistChip(
                    onClick = { choice = 0; picking = true },
                    label = { Text(picked?.label ?: stringResource(R.string.remap_pick_app), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (picked == null) null else { { AppGlyph(picked, apps, Modifier.size(18.dp)) } },
                    trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
            }
            ActionChoice(choice == 1, stringResource(R.string.remap_do_nothing), stringResource(R.string.remap_do_nothing_text), onClick = { choice = 1 })
            if (known) ActionChoice(choice == 2, stringResource(R.string.remap_tv_default), stringResource(R.string.remap_tv_default_text), onClick = { choice = 2 })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.copy_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = choice != 0 || app != null,
                    onClick = {
                        onSave(button.copy(action = when (choice) { 0 -> app; 1 -> RemapAction.DoNothing; else -> null }))
                    },
                ) { Text(stringResource(R.string.device_save)) }
            }
        }
    }
    if (picking) AppPickerSheet(apps, app?.packageName, onPick = { a -> app = RemapAction.Open(a.packageName, a.label); choice = 0; picking = false }, onDismiss = { picking = false })
}

@Composable
private fun ActionChoice(selected: Boolean, title: String, text: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** Open an app: the TV's apps that open, A to Z, with a search box; the current pick has a tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(apps: List<AppInfo>, current: String?, onPick: (AppInfo) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val shown = remember(apps, q) { apps.filter { q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.copy_open_an_app), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp))
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.remap_search_apps)) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            if (shown.isEmpty()) item { Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) { EmptyLine(q) } }
            items(shown, key = { it.packageName }) { a ->
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clickable { onPick(a) }.padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(a)
                    Spacer(Modifier.width(16.dp))
                    Text(a.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (a.packageName == current) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

internal object RemapIcons {
    // Absolute commands only: Compose's path parser misplaces a relative move after a close.
    val SettingsRemote = icon(
        "SettingsRemote",
        "M15 9L9 9C8.45 9 8 9.45 8 10L8 22C8 22.55 8.45 23 9 23L15 23C15.55 23 16 22.55 16 22L16 10C16 9.45 15.55 9 15 9Z" +
            "M12 15C10.9 15 10 14.1 10 13C10 11.9 10.9 11 12 11C13.1 11 14 11.9 14 13C14 14.1 13.1 15 12 15Z" +
            "M7.05 6.05L8.46 7.46C9.37 6.56 10.62 6 12 6C13.38 6 14.63 6.56 15.54 7.46L16.95 6.05C15.68 4.78 13.93 4 12 4C10.07 4 8.32 4.78 7.05 6.05Z" +
            "M12 0C8.96 0 6.21 1.23 4.22 3.22L5.63 4.63C7.26 3.01 9.51 2 12 2C14.49 2 16.74 3.01 18.36 4.64L19.77 3.23C17.79 1.23 15.04 0 12 0Z",
    )
    val Block = icon(
        "Block",
        "M12 2C6.48 2 2 6.48 2 12C2 17.52 6.48 22 12 22C17.52 22 22 17.52 22 12C22 6.48 17.52 2 12 2Z" +
            "M4 12C4 7.58 7.58 4 12 4C13.85 4 15.55 4.63 16.9 5.69L5.69 16.9C4.63 15.55 4 13.85 4 12Z" +
            "M12 20C10.15 20 8.45 19.37 7.1 18.31L18.31 7.1C19.37 8.45 20 10.15 20 12C20 16.42 16.42 20 12 20Z",
    )
    val Keyboard = icon("Keyboard", "M20 5L4 5C2.9 5 2.01 5.9 2.01 7L2 17C2 18.1 2.9 19 4 19L20 19C21.1 19 22 18.1 22 17L22 7C22 5.9 21.1 5 20 5ZM11 8L13 8L13 10L11 10L11 8ZM11 11L13 11L13 13L11 13L11 11ZM8 8L10 8L10 10L8 10L8 8ZM8 11L10 11L10 13L8 13L8 11ZM7 13L5 13L5 11L7 11L7 13ZM7 10L5 10L5 8L7 8L7 10ZM16 17L8 17L8 15L16 15L16 17ZM16 13L14 13L14 11L16 11L16 13ZM16 10L14 10L14 8L16 8L16 10ZM19 13L17 13L17 11L19 11L19 13ZM19 10L17 10L17 8L19 8L19 10Z")
    val Pause = icon("Pause", "M6 19L10 19L10 5L6 5L6 19Z M14 5L14 19L18 19L18 5L14 5Z")
    val TouchApp = icon(
        "TouchApp",
        "M9 11.24L9 7.5C9 6.12 10.12 5 11.5 5C12.88 5 14 6.12 14 7.5L14 11.24C15.21 10.43 16 9.06 16 7.5C16 5.01 13.99 3 11.5 3C9.01 3 7 5.01 7 7.5C7 9.06 7.79 10.43 9 11.24Z" +
            "M18.84 15.87L14.3 13.61C14.13 13.54 13.95 13.5 13.76 13.5L13 13.5L13 7.5C13 6.67 12.33 6 11.5 6C10.67 6 10 6.67 10 7.5L10 18.24L6.57 17.52C6.49 17.51 6.42 17.49 6.33 17.49C6.02 17.49 5.74 17.62 5.54 17.82L4.75 18.62L9.69 23.56C9.96 23.83 10.34 24 10.75 24L17.54 24C18.29 24 18.87 23.45 18.98 22.72L19.73 17.45C19.74 17.38 19.75 17.31 19.75 17.25C19.75 16.63 19.37 16.09 18.84 15.87Z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
        .build()
}
