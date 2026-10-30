// SPDX-License-Identifier: GPL-3.0-only
// Icons here use Material Icons path data (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tellyscope.adb.Peripheral
import app.tellyscope.data.AccessClient
import app.tellyscope.data.AccessKey
import app.tellyscope.data.DeviceState
import app.tellyscope.data.TvAccess
import androidx.compose.ui.res.stringResource
import app.tellyscope.R
import app.tellyscope.data.UiText
import app.tellyscope.data.resolve

/** What the Who has access screen and the warning can do. */
data class AccessActions(
    val back: () -> Unit = {},
    val markMine: (String) -> Unit = {},
    /** "It's mine" on a new allowed computer, by its fingerprint. */
    val markKeyMine: (String) -> Unit = {},
    /** Answer on a waiting connection: the TV's prompt, over this screen. */
    val showRequest: () -> Unit = {},
    val answerRequest: (allow: Boolean, always: Boolean) -> Unit = { _, _ -> },
    val dismissRequest: () -> Unit = {},
    val closeAlert: () -> Unit = {},
    val askRemove: () -> Unit = {},
    val close: () -> Unit = {},
    val remove: () -> Unit = {},
    val openDeveloperOptions: () -> Unit = {},
    val revokedByHand: () -> Unit = {},
    val askAgain: () -> Unit = {},
    val retry: () -> Unit = {},
    /** A connection left open: read the TV again, or restart it to end the connection. */
    val checkAgain: () -> Unit = {},
    val restart: () -> Unit = {},
)

internal object AccessIcons {
    val Shield = icon("Shield", "M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z")
    val ShieldOk = icon("ShieldOk", "M12 2 4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5l-8-3zM10.94 15.54 7.4 12l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41-5.64 5.66z")
    val ShieldNote = icon("ShieldNote", "M12 2 4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5l-8-3zM13 16h-2v-2h2v2zM13 12h-2V7h2v5z")
    val LockReset = icon(
        "LockReset",
        "M13 3a9 9 0 0 0-9 9H1l4 4 4-4H6c0-3.86 3.14-7 7-7s7 3.14 7 7-3.14 7-7 7c-1.9 0-3.62-.76-4.88-1.99L6.7 18.42A8.982 8.982 0 0 0 13 21a9 9 0 0 0 0-18z" +
            "M15 11v-1c0-1.1-.9-2-2-2s-2 .9-2 2v1c-.55 0-1 .45-1 1v3c0 .55.45 1 1 1h4c.55 0 1-.45 1-1v-3c0-.55-.45-1-1-1z" +
            "M14 11h-2v-1c0-.55.45-1 1-1s1 .45 1 1v1z",
    )
    val Phone = icon("Phone", "M17 1.01 7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z")
    val Lan = icon("Lan", "M13 22h8v-7h-3v-4h-5V9h3V2H8v7h3v2H6v4H3v7h8v-7H8v-2h8v2h-3z")
    val Laptop = icon("Laptop", "M20 18c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z")
    val Desktop = icon("Desktop", "M20 3H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h6v2H8v2h8v-2h-2v-2h6c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM20 15H4V5h16v10z")
    val Unknown = icon(
        "Unknown",
        "M11 18h2v-2h-2v2zM12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM12 20c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z" +
            "M12 6c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z",
    )
    val Bluetooth = icon("Bluetooth", "M17.71 7.71 12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.3-4.29 4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zM14.88 16.29 13 18.17v-3.76l1.88 1.88z")
    val Headphones = icon("Headphones", "M12 3a9 9 0 0 0-9 9v7c0 1.1.9 2 2 2h4v-8H5v-1c0-3.87 3.13-7 7-7s7 3.13 7 7v1h-4v8h4c1.1 0 2-.9 2-2v-7a9 9 0 0 0-9-9z")
    val Keyboard = icon(
        "Keyboard",
        "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2z" +
            "M11 8h2v2h-2zM11 11h2v2h-2zM8 8h2v2H8zM8 11h2v2H8zM5 11h2v2H5zM5 8h2v2H5zM8 15h8v2H8zM14 11h2v2h-2zM14 8h2v2h-2zM17 11h2v2h-2zM17 8h2v2h-2z",
    )
    val Mouse = icon("Mouse", "M13 1.07V9h7c0-4.08-3.05-7.44-7-7.93zM4 15c0 4.42 3.58 8 8 8s8-3.58 8-8v-4H4v4zM11 1.07C7.05 1.56 4 4.92 4 9h7V1.07z")
    val Controller = icon(
        "Controller",
        "M21.58 16.09l-1.09-7.66A3.996 3.996 0 0 0 16.53 5H7.47C5.48 5 3.79 6.46 3.51 8.43l-1.09 7.66a2.545 2.545 0 0 0 4.32 2.16L9 16h6l2.25 2.25c.48.48 1.13.75 1.8.75 1.56 0 2.75-1.37 2.53-2.91z" +
            "M11 11H9v2H8v-2H6v-1h2V8h1v2h2v1zM15 8a1 1 0 1 0 0 2 1 1 0 1 0 0-2zM17 11a1 1 0 1 0 0 2 1 1 0 1 0 0-2z",
    )
    val Usb = icon(
        "Usb",
        "M15 7v4h1v2h-3V5h2l-3-4-3 4h2v8H8v-2.07c.7-.37 1.2-1.08 1.2-1.93 0-1.21-.99-2.2-2.2-2.2-1.21 0-2.2.99-2.2 2.2 0 .85.5 1.56 1.2 1.93V13c0 1.11.89 2 2 2h3v3.05c-.71.37-1.2 1.1-1.2 1.95a2.2 2.2 0 0 0 4.4 0c0-.85-.49-1.58-1.2-1.95V15h3c1.11 0 2-.89 2-2v-2h1V7h-4z",
    )
    val Speaker = icon(
        "Speaker",
        "M17 2H7c-1.1 0-2 .9-2 2v16c0 1.1.9 1.99 2 1.99L17 22c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z" +
            "M12 4a2 2 0 1 0 0 4 2 2 0 1 0 0-4zM12 10a5 5 0 1 0 0 10 5 5 0 1 0 0-10zM12 12a3 3 0 1 0 0 6 3 3 0 1 0 0-6z",
    )
    val Camera = icon("Camera", "M15 8v8H5V8h10m1-2H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 4v-11l-4 4V7c0-.55-.45-1-1-1z")
    val Hdmi = icon("Hdmi", "M18 7V4c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v3H5v6l3 6v3h8v-3l3-6V7h-1zM8 4h8v3h-2V5h-1v2h-2V5h-1v2H8V4z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .build()

    fun of(kind: Peripheral.Kind) = when (kind) {
        Peripheral.Kind.Remote -> TileIcons.Remote
        Peripheral.Kind.Headphones -> Headphones
        Peripheral.Kind.Speaker, Peripheral.Kind.Sound -> Speaker
        Peripheral.Kind.Keyboard -> Keyboard
        Peripheral.Kind.Mouse -> Mouse
        Peripheral.Kind.Controller -> Controller
        Peripheral.Kind.Bluetooth -> Bluetooth
        Peripheral.Kind.Usb -> Usb
        Peripheral.Kind.Hdmi -> Hdmi
        Peripheral.Kind.Camera -> Camera
    }
}

/**
 * C1: another computer is connected. A red card, collapsed by default with the whole card as the
 * toggle; open, it says where the connections come from and links to Who has access. "It's mine"
 * marks the address when there is one; with several, it opens Who has access to pick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccessWarning(access: TvAccess, actions: AccessActions, onWho: () -> Unit, startOpen: Boolean = false) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(startOpen) }
    val others = access.others
    Card(
        onClick = { open = !open },
        colors = CardDefaults.cardColors(containerColor = c.errorContainer, contentColor = c.onErrorContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().animateContentSize().testTag("accessWarning"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)) {
            Row {
                Icon(AccessIcons.Shield, contentDescription = null, Modifier.padding(top = 2.dp).size(24.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(Copy.WARNING_TITLE.resolve(), style = MaterialTheme.typography.titleMedium)
                    Text(Copy.WARNING_TEXT.resolve(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                }
                Icon(
                    if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (open) stringResource(R.string.access_show_less) else stringResource(R.string.access_show_more),
                    Modifier.padding(start = 8.dp),
                )
            }
            if (open) {
                Column(Modifier.padding(start = 40.dp, top = 12.dp, end = 4.dp)) {
                    HorizontalDivider(color = c.onErrorContainer.copy(alpha = 0.2f))
                    Text(Copy.CONNECTED_NOW.resolve(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    others.forEach { o ->
                        Text(Copy.connectedFrom(if (o.who == AccessClient.Who.OnTv) Copy.ON_TV.resolve().lowercase() else o.address, o.connections).resolve(), style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(
                        Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onWho).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(Copy.WHO_HAS_ACCESS.resolve(), style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp))
                    }
                }
            }
            FlowRow(Modifier.padding(top = 12.dp, start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val border = BorderStroke(1.dp, c.onErrorContainer)
                val colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onErrorContainer)
                OutlinedButton(onClick = { others.singleOrNull()?.let { actions.markMine(it.address) } ?: onWho() }, border = border, colors = colors) {
                    Text(Copy.ITS_MINE.resolve())
                }
                OutlinedButton(onClick = actions.askRemove, border = border, colors = colors, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(AccessIcons.LockReset, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(Copy.REMOVE_ACCESS.resolve())
                }
            }
        }
    }
}

/**
 * What's plugged in or paired. Three rows and Show all; open, it grows in place into labelled
 * groups with Show less at the end. Teal for in use, grey for paired but off.
 */
@Composable
fun ConnectedDevicesCard(peripherals: List<Peripheral>, startOpen: Boolean = false) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(startOpen) }
    Card(
        colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().animateContentSize().testTag("connectedDevices"),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Text(Copy.DEVICES_TITLE.resolve(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            if (peripherals.isEmpty()) {
                Text(Copy.NO_DEVICES.resolve(), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            } else if (open) {
                peripherals.groupBy { it.group }.forEach { (group, rows) ->
                    Text(Copy.group(group).resolve(), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    rows.forEach { PeripheralRow(it, grouped = true) }
                }
            } else {
                peripherals.take(COLLAPSED_ROWS).forEach { PeripheralRow(it, grouped = false) }
            }
            if (peripherals.size > COLLAPSED_ROWS) {
                TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                    Text(if (open) Copy.SHOW_LESS.resolve() else Copy.showAll(peripherals.size).resolve())
                }
            }
        }
    }
}

private const val COLLAPSED_ROWS = 3

@Composable
private fun PeripheralRow(p: Peripheral, grouped: Boolean) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(AccessIcons.of(p.kind), contentDescription = null, tint = if (p.live) c.primary else c.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(Copy.peripheralStatus(p, grouped).resolve(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/**
 * Who has access (C3) and, once started, Remove all other access (C4–C6). The list is the
 * screen until a removal starts; C4 is a dialog over it, and every later step has the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessScreen(state: UiState, actions: AccessActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    val device = state.selected ?: return
    val ui = state.device
    val removal = ui.removal
    val flow = removal != null && removal.step != Removal.Step.Confirm
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (flow) Copy.REMOVE_TITLE.resolve() else Copy.WHO_HAS_ACCESS.resolve())
                        Text(name(device), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (flow) actions.close else actions.back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (flow) Copy.WHO_HAS_ACCESS.resolve() else stringResource(R.string.access_back_to_tv))
                    }
                },
            )
        },
    ) { pad ->
        val body = Modifier.fillMaxSize().padding(pad)
        when (removal?.step) {
            null, Removal.Step.Confirm -> AccessList(ui.access, state.clock(), device.state == DeviceState.Online, actions, body)
            Removal.Step.Revoking -> Waiting(Copy.REVOKING.resolve(), body)
            Removal.Step.Checking -> Waiting(Copy.CHECKING.resolve(), body)
            Removal.Step.Manual -> ManualGuide(actions, body)
            Removal.Step.Allow -> AllowPhone(removal, actions, body)
            Removal.Step.Done -> Outcome(
                body, Verdict.Done, Copy.DONE_TITLE.resolve(), Copy.DONE_TEXT.resolve(),
                trust = true,
            ) { Button(onClick = { actions.close(); actions.back() }) { Text(Copy.backTo(name(device)).resolve()) } }
            Removal.Step.StillConnected -> if (removal.open) {
                // Only a restart ends an open connection, so it leads; Who has access steps down to a link.
                Outcome(
                    body, Verdict.Still, Copy.STILL_TITLE.resolve(), Copy.stillOpenText((removal.still ?: Copy.UNNAMED_COMPUTER).resolve(), name(device)).resolve(),
                    link = { TextButton(onClick = actions.close) { Text(Copy.WHO_HAS_ACCESS.resolve()) } },
                ) {
                    OutlinedButton(onClick = actions.checkAgain) { Text(Copy.CHECK_AGAIN.resolve()) }
                    Button(onClick = actions.restart) { Text(Copy.RESTART_TV.resolve()) }
                }
            } else {
                Outcome(body, Verdict.Still, Copy.STILL_TITLE.resolve(), Copy.stillText((removal.still ?: Copy.UNNAMED_COMPUTER).resolve(), name(device)).resolve()) {
                    OutlinedButton(onClick = actions.close) { Text(Copy.WHO_HAS_ACCESS.resolve()) }
                    Button(onClick = actions.retry) { Text(Copy.TRY_AGAIN.resolve()) }
                }
            }
            Removal.Step.Restarting -> Waiting(Copy.RESTARTING_TO_CHECK.resolve(), body)
            Removal.Step.Unconfirmed -> Outcome(body, Verdict.Unknown, Copy.UNCONFIRMED_TITLE.resolve(), Copy.UNCONFIRMED_TEXT.resolve()) {
                Button(onClick = actions.close) { Text(Copy.DONE.resolve()) }
            }
        }
    }
    if (removal?.step == Removal.Step.Confirm) ConfirmRemoval(actions)
    // The TV's prompt shows here as on the TV's page, so a computer asking in is answered where it's listed.
    else if (removal == null && device.state == DeviceState.Online) {
        ui.request?.let { RequestDialog(it, ui.busy == ANSWERING, actions.answerRequest, actions.dismissRequest, actions.closeAlert) }
    }
}

/** C4: the one warning before every key on the TV goes, this phone's included. */
@Composable
private fun ConfirmRemoval(actions: AccessActions) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = actions.close,
        icon = { Icon(AccessIcons.LockReset, contentDescription = null, tint = c.error) },
        title = { Text(Copy.CONFIRM_TITLE.resolve(), textAlign = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Copy.CONFIRM_TEXT.resolve())
                Text(Copy.CONFIRM_REMOTE.resolve())
            }
        },
        confirmButton = { Button(onClick = actions.remove, colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError)) { Text(Copy.CONFIRM_BUTTON.resolve()) } },
        dismissButton = { TextButton(onClick = actions.close) { Text(Copy.CANCEL.resolve()) } },
    )
}

/** C3: who is connected now and which computers are allowed, never tied together. */
@Composable
private fun AccessList(access: TvAccess?, now: Long, online: Boolean, actions: AccessActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
        item { Text(Copy.ACCESS_INTRO.resolve(), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp)) }
        section(Copy.CONNECTED_NOW)
        when (val list = access?.connected) {
            null -> item { Muted(if (access == null) stringResource(R.string.access_checking) else Copy.CANT_CHECK_CONNECTED.resolve()) }
            else -> segmented(list) { ConnectedRow(it, actions) }
        }
        item { Spacer(Modifier.height(24.dp)) }
        section(Copy.ALLOWED_COMPUTERS)
        when (val list = access?.allowed) {
            null -> item { Muted(if (access == null) stringResource(R.string.access_checking) else Copy.CANT_CHECK_ALLOWED.resolve()) }
            emptyList<AccessKey>() -> item { Muted(Copy.NOBODY_ALLOWED.resolve()) }
            else -> segmented(list) { AllowedRow(it, now, actions) }
        }
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Copy.ACCESS_FOOT_LINK.resolve(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                Text(Copy.ACCESS_FOOT_ALL.resolve(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                OutlinedButton(
                    onClick = actions.askRemove,
                    enabled = online,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = c.error),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(AccessIcons.LockReset, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(Copy.REMOVE_ACCESS.resolve())
                }
            }
        }
    }
}

private fun LazyListScope.section(title: UiText) = item {
    Text(title.resolve(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
}

/** An MD3 segmented list: 2 dp apart, 16 dp outer corners and 4 dp inner ones. */
private fun <T> LazyListScope.segmented(rows: List<T>, row: @Composable (T) -> Unit) {
    rows.forEachIndexed { i, r ->
        item {
            val top = if (i == 0) 16.dp else 4.dp
            val bottom = if (i == rows.lastIndex) 16.dp else 4.dp
            Surface(
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            ) { row(r) }
        }
    }
}

@Composable
private fun ConnectedRow(client: AccessClient, actions: AccessActions) {
    val c = MaterialTheme.colorScheme
    val phone = client.who == AccessClient.Who.Phone
    AccessRow(
        icon = if (phone) AccessIcons.Phone else AccessIcons.Lan,
        title = when (client.who) {
            AccessClient.Who.Phone -> Copy.THIS_PHONE.resolve()
            AccessClient.Who.OnTv -> Copy.ON_TV.resolve()
            else -> client.address
        },
        chip = when (client.who) {
            AccessClient.Who.Phone -> Chip.Tellyscope
            AccessClient.Who.Waiting -> Chip.Waiting
            else -> null
        },
    ) {
        if (phone || client.who == AccessClient.Who.OnTv) Sub(client.address)
        // Not in yet, so no connection count: it isn't using the TV.
        if (client.who != AccessClient.Who.Waiting) Sub(Copy.connections(client.connections).resolve())
        when (client.who) {
            AccessClient.Who.Waiting -> {
                Text(Copy.WAITING_LINE.resolve(), style = MaterialTheme.typography.bodyMedium)
                Sub(Copy.WAITING_TEXT.resolve())
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    FilledTonalButton(onClick = actions.showRequest) { Text(Copy.ANSWER.resolve()) }
                }
            }
            AccessClient.Who.Other, AccessClient.Who.OnTv -> {
                Text(Copy.NOT_THIS_PHONE.resolve(), style = MaterialTheme.typography.bodyMedium, color = c.error)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    FilledTonalButton(onClick = { actions.markMine(client.address) }) { Text(Copy.ITS_MINE.resolve()) }
                }
            }
            AccessClient.Who.Mine -> Sub(Copy.YOURS.resolve())
            AccessClient.Who.Phone -> {}
        }
    }
}

@Composable
private fun AllowedRow(key: AccessKey, now: Long, actions: AccessActions) {
    val name = key.name
    AccessRow(
        icon = when {
            key.phone -> AccessIcons.Phone
            name == null -> AccessIcons.Unknown
            "laptop" in name.lowercase() || "book" in name.lowercase() -> AccessIcons.Laptop
            else -> AccessIcons.Desktop
        },
        title = if (key.phone) Copy.THIS_PHONE.resolve() else name ?: Copy.UNNAMED_COMPUTER.resolve(),
        chip = if (key.phone) Chip.Tellyscope else if (key.new) Chip.New else null,
    ) {
        Text(Copy.shortFingerprint(key.fingerprint), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Sub(Copy.lastSeen(key.lastSeen, now).resolve())
        if (key.new) {
            Text(Copy.NEW_KEY_TEXT.resolve(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                FilledTonalButton(onClick = { actions.markKeyMine(key.fingerprint) }) { Text(Copy.ITS_MINE.resolve()) }
            }
        }
    }
}

/** Beside a row's title: this phone is Tellyscope, filled; Waiting and New are neutral outlines, a state and not an alarm. */
private enum class Chip(val text: UiText) { Tellyscope(Copy.TELLYSCOPE), Waiting(Copy.WAITING_CHIP), New(Copy.NEW_CHIP) }

@Composable
private fun AccessRow(icon: ImageVector, title: String, chip: Chip?, lines: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.padding(16.dp)) {
        Icon(icon, contentDescription = null, tint = c.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (chip != null) {
                    Spacer(Modifier.width(8.dp))
                    val filled = chip == Chip.Tellyscope
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (filled) c.secondaryContainer else Color.Transparent,
                        contentColor = if (filled) c.onSecondaryContainer else c.onSurfaceVariant,
                        border = if (filled) null else BorderStroke(1.dp, c.outline),
                    ) {
                        Text(chip.text.resolve(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            lines()
        }
    }
}

@Composable
private fun Sub(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Waiting(text: String, modifier: Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LoadingIndicator(Modifier.size(96.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
    }
}

/** C5: the TV is asking about this phone. Its fingerprint wraps at the colons, to compare pair by pair. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AllowPhone(removal: Removal, actions: AccessActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoadingIndicator(Modifier.size(96.dp))
        Text(Copy.ALLOW_TITLE.resolve(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 32.dp))
        Text(
            buildAnnotatedString {
                append(Copy.ALLOW_TEXT_A.resolve())
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(Copy.ALLOW_ALWAYS.resolve()) }
                append(Copy.ALLOW_TEXT_B.resolve())
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(Copy.ALLOW_BUTTON.resolve()) }
                append(".")
            },
            style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (removal.refused) {
            Text(Copy.REFUSED.resolve(), style = MaterialTheme.typography.bodyLarge, color = c.error, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            Button(onClick = actions.askAgain, Modifier.padding(top = 8.dp)) { Text(Copy.ASK_AGAIN.resolve()) }
        }
        removal.fingerprint?.let { fp ->
            Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(Copy.FINGERPRINT_LABEL.resolve(), style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
                    Text(
                        fp.replace(":", ":​"),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Text(Copy.FINGERPRINT_HINT.resolve(), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
            }
        }
        TextButton(onClick = actions.close, Modifier.padding(top = 16.dp)) { Text(Copy.STOP_WAITING.resolve()) }
    }
}

/** The fallback when the TV won't take the call: revoke in Developer options, then allow again. */
@Composable
private fun ManualGuide(actions: AccessActions, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(Copy.MANUAL_INTRO.resolve(), style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
        Step(1, Copy.MANUAL_STEP_1.resolve(), Copy.MANUAL_STEP_1_TEXT.resolve()) {
            Button(onClick = actions.openDeveloperOptions, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(OpenInNew, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(Copy.OPEN_ON_TV.resolve())
            }
        }
        Step(2, Copy.MANUAL_STEP_2.resolve(), Copy.MANUAL_STEP_2_TEXT.resolve()) {
            OutlinedButton(onClick = actions.revokedByHand) { Text(Copy.MANUAL_DONE.resolve()) }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, text: String, button: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.padding(top = 24.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(c.primary), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleSmall, color = c.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            button()
        }
    }
}

private enum class Verdict { Done, Still, Unknown }

/** C6, C6b and C6c: a cookie badge, what Tellyscope found, and where to go next. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Outcome(
    modifier: Modifier,
    kind: Verdict,
    title: String,
    text: String,
    trust: Boolean = false,
    link: (@Composable () -> Unit)? = null,
    buttons: @Composable () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        Verdict.Done -> c.primaryContainer to c.onPrimaryContainer
        Verdict.Still -> c.surfaceContainerHighest to c.error
        Verdict.Unknown -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(bg), contentAlignment = Alignment.Center) {
            Icon(if (kind == Verdict.Done) AccessIcons.ShieldOk else AccessIcons.Shield, contentDescription = null, Modifier.size(44.dp), tint = fg)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 32.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        if (trust) {
            Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Icon(AccessIcons.ShieldNote, contentDescription = null, tint = c.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(Copy.TRUST_WARNING.resolve(), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
        if (link != null) Box(Modifier.padding(top = 8.dp)) { link() }
    }
}
