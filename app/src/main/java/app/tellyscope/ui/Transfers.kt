// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.tellyscope.adb.InstallKind
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.ui.theme.SmallNumber
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.tellyscope.R
import app.tellyscope.data.media
import app.tellyscope.data.resolve

/**
 * Both dialogs here close only through their own buttons: a tap outside or Back would otherwise
 * drop an install halfway through its questions, or look like it cancelled a copy.
 */
internal val Sticky = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

/**
 * The three things people come to a TV's page for, as big tiles under the system widget.
 * Debloat is filled, with a count, while it has something to do; after that it looks like its
 * neighbours and [onDebloat] leads somewhere quieter.
 */
@Composable
fun ActionTiles(actions: DeviceActions, count: Int, todo: Boolean, disabling: Boolean, onDebloat: () -> Unit, loading: Boolean = false) {
    // A label that wraps makes its tile taller, and the others match it.
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(Icons.Default.Add, stringResource(R.string.transfers_install_app), actions.pickApp, Modifier.weight(1f).fillMaxHeight())
        // Greyed while the list loads, so it can't open something else in the meantime.
        Tile(
            Broom, if (disabling) stringResource(R.string.transfers_disabling) else stringResource(R.string.transfers_debloat), onDebloat, Modifier.weight(1f).fillMaxHeight(),
            enabled = !disabling && !loading, primary = todo, badge = count.takeIf { todo && it > 0 },
        )
        // Where the file lands is the user's choice, every time, not whatever folder was last shown.
        var choosing by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Tile(Icons.AutoMirrored.Filled.Send, stringResource(R.string.transfers_send_file), { choosing = true }, Modifier.fillMaxSize())
            DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                TvFolder.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.transfers_to_folder, Copy.folder(f).resolve())) },
                        onClick = { choosing = false; actions.pickFile(f) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    badge: Int? = null,
) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        // Material's disabled colours, so a greyed-out Debloat doesn't still read as the main button.
        color = when {
            !enabled -> c.onSurface.copy(alpha = 0.12f)
            primary -> c.primary
            else -> c.secondaryContainer
        },
        contentColor = when {
            !enabled -> c.onSurface.copy(alpha = 0.38f)
            primary -> c.onPrimary
            else -> c.onSecondaryContainer
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(vertical = 20.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            BadgedBox(badge = { if (badge != null) Badge { Text(badge.toString()) } }) {
                Icon(icon, contentDescription = null, Modifier.size(28.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Material Icons "cleaning_services" (Apache-2.0, see NOTICE); icons-core has no broom. */
internal val Broom = ImageVector.Builder("Broom", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        PathParser().parsePathString(
            "M16 11h-1V3c0-1.1-.9-2-2-2h-2c-1.1 0-2 .9-2 2v8H8c-2.76 0-5 2.24-5 5v7h18v-7c0-2.76-2.24-5-5-5z" +
                "m3 10h-2v-3c0-.55-.45-1-1-1s-1 .45-1 1v3h-2v-3c0-.55-.45-1-1-1s-1 .45-1 1v3H9v-3c0-.55-.45-1-1-1s-1 .45-1 1v3H5v-5" +
                "c0-1.65 1.35-3 3-3h8c1.65 0 3 1.35 3 3v5z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    )
    .build()

/**
 * Body of the Files tab, under its folder tabs: the folder's files, each one tap from the phone. Every file
 * opens a sheet with Save and Delete on TV; a video, a song or a picture also gets Open on TV.
 */
@Composable
fun FilesBlock(files: FilesUi, now: Long, actions: DeviceActions) {
    val list = files.list
    var picked by remember { mutableStateOf<TvFile?>(null) }
    var deleting by remember { mutableStateOf<TvFile?>(null) }
    when {
        list == null -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            @OptIn(ExperimentalMaterial3ExpressiveApi::class)
            LoadingIndicator(Modifier.size(48.dp))
        }
        files.error != null -> Muted(files.error.resolve())
        else -> {
            list.forEach { f ->
                FileRow(f, now) { picked = f }
            }
            // Only with files to tap; an empty folder shows its own page instead.
            if (list.isNotEmpty()) Muted(stringResource(R.string.transfers_tap_to_save))
        }
    }
    picked?.let { f ->
        FileSheet(
            f,
            onOpen = if (media(f.name) != null) ({ picked = null; actions.player.open(f) }) else null,
            onSave = { picked = null; actions.saveFile(f) },
            onDelete = { picked = null; deleting = f },
            onClose = { picked = null },
        )
    }
    deleting?.let { f ->
        DeleteDialog(f, files.folder, onDelete = { deleting = null; actions.deleteFile(f) }, onCancel = { deleting = null })
    }
    files.deleteFailed?.let { DeleteFailedDialog(it, actions) }
}

@Composable
private fun FileRow(f: TvFile, now: Long, onTap: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onTap).padding(vertical = 8.dp)) {
        Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text(
            "${Copy.bytes(f.size).resolve()} · ${Copy.ago(f.modifiedMillis, now).resolve()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A file's name and size, then Open on TV ([onOpen] null: nothing on the TV opens it), Save and Delete on TV. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileSheet(f: TvFile, onOpen: (() -> Unit)?, onSave: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(f.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                Text(Copy.bytes(f.size).resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            onOpen?.let { SheetItem(LauncherIcons.Cast, R.string.copy_open_on_tv, it) }
            SheetItem(LauncherIcons.Download, R.string.transfers_save, onSave)
            // Red, as it can't be taken back: the TV keeps no trash.
            SheetItem(Icons.Default.Delete, R.string.transfers_delete, onDelete, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: Int, onClick: () -> Unit, tint: Color? = null) {
    ListItem(
        headlineContent = { Text(stringResource(label)) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp),
        colors = if (tint == null) ListItemDefaults.colors(containerColor = Color.Transparent)
        else ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = tint, leadingIconColor = tint),
    )
}

/** Delete on TV's confirm: Cancel as a text button, Delete filled in the error colour. */
@Composable
private fun DeleteDialog(f: TvFile, folder: TvFolder, onDelete: () -> Unit, onCancel: () -> Unit) {
    val c = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.transfers_delete_title, f.name)) },
        text = { Text(stringResource(R.string.transfers_delete_text, Copy.folder(folder).resolve()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.onError)) {
                Text(stringResource(R.string.transfers_delete_button))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.transfers_cancel)) } },
    )
}

/** Laid out like a failed install: what didn't happen, then the TV's own words. */
@Composable
private fun DeleteFailedDialog(d: DeleteFailed, actions: DeviceActions) {
    AlertDialog(
        onDismissRequest = actions.closeDeleteFailed,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text(stringResource(R.string.transfers_delete_failed, d.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.transfers_delete_still_there), style = MaterialTheme.typography.bodyMedium)
                d.why?.let { Text(it.resolve(), style = MaterialTheme.typography.bodyMedium) }
                d.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(stringResource(R.string.transfers_tv_said, it), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = { Button(onClick = actions.closeDeleteFailed) { Text(stringResource(R.string.transfers_ok)) } },
        dismissButton = { TextButton(onClick = { actions.reportDelete(d) }) { Text(stringResource(R.string.transfers_report)) } },
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SideloadDialog(s: SideloadUi, actions: DeviceActions) {
    val app = s.app
    val kind = s.kind
    AlertDialog(
        onDismissRequest = {},
        properties = Sticky,
        icon = when (s.step) {
            SideloadUi.Step.Installed -> ({ Icon(Icons.Default.CheckCircle, null) })
            SideloadUi.Step.Failed -> ({ Icon(Icons.Default.Warning, null) })
            else -> null
        },
        title = {
            Text(
                when (s.step) {
                    SideloadUi.Step.Reading -> stringResource(R.string.transfers_reading)
                    SideloadUi.Step.Failed -> app?.let { stringResource(R.string.transfers_install_failed, it.label) } ?: stringResource(R.string.transfers_cant_install)
                    SideloadUi.Step.Installed -> Copy.installed(kind ?: InstallKind.NEW, app?.label.orEmpty()).resolve()
                    else -> app?.label.orEmpty()
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.step == SideloadUi.Step.Reading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { LoadingIndicator() }
                }
                if (app != null && s.step != SideloadUi.Step.Failed) {
                    Column {
                        Text(app.info.packageName, style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            listOfNotNull(
                                stringResource(R.string.transfers_version, app.info.versionName ?: app.info.versionCode),
                                Copy.bytes(app.sizeBytes).resolve(),
                                if (app.files.size > 1) pluralStringResource(R.plurals.transfers_parts, app.files.size, app.files.size) else null,
                            ).joinToString(" · "),
                            style = SmallNumber,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (s.step) {
                    SideloadUi.Step.Ready -> if (kind != null) {
                        Text(Copy.installKind(kind, s.installed?.versionName, app?.info?.versionName).resolve(), style = MaterialTheme.typography.bodyMedium)
                        if (kind == InstallKind.DOWNGRADE) {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { actions.allowDowngrade(!s.allowDowngrade) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(s.allowDowngrade, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Text(Copy.ALLOW_DOWNGRADE.resolve(), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    SideloadUi.Step.Installing -> {
                        val f = s.progress?.fraction
                        // Past the last byte the TV is still unpacking: say so rather than sit at 100%.
                        val sending = f != null && f < 1f
                        if (sending) LinearWavyProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                        else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            if (sending) stringResource(R.string.transfers_sending_percent, (f * 100).toInt()) else stringResource(R.string.transfers_installing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Muted(Copy.INSTALL_KEEP_OPEN.resolve())
                    }
                    SideloadUi.Step.Failed -> {
                        Text(s.error?.resolve().orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        s.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.transfers_tv_said, it), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (s.step) {
                SideloadUi.Step.Ready -> if (kind != null) Button(
                    onClick = actions.install,
                    enabled = kind != InstallKind.DOWNGRADE || s.allowDowngrade,
                ) { Text(Copy.installButton(kind).resolve()) }
                SideloadUi.Step.Installed -> Button(onClick = actions.openInstalled) { Text(stringResource(R.string.transfers_open_on_tv)) }
                SideloadUi.Step.Failed -> Button(onClick = actions.closeSideload) { Text(stringResource(R.string.transfers_ok)) }
                else -> Unit
            }
        },
        dismissButton = {
            when (s.step) {
                SideloadUi.Step.Reading, SideloadUi.Step.Ready -> TextButton(onClick = actions.closeSideload) { Text(stringResource(R.string.transfers_cancel)) }
                SideloadUi.Step.Installed -> TextButton(onClick = actions.closeSideload) { Text(stringResource(R.string.transfers_done)) }
                SideloadUi.Step.Failed -> TextButton(onClick = { actions.reportInstall(s) }) { Text(stringResource(R.string.transfers_report)) }
                else -> Unit
            }
        },
    )
}

/** A copy in either direction. Cancel is the only way out before it's done. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TransferDialog(t: TransferUi, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = Sticky,
        title = { Text(if (t.toTv) stringResource(R.string.transfers_sending_to, Copy.folder(t.folder).resolve()) else stringResource(R.string.transfers_saving)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                val f = t.progress?.fraction
                if (f != null) LinearWavyProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    t.progress?.let { p -> p.total?.let { stringResource(R.string.transfers_progress, Copy.bytes(p.done).resolve(), Copy.bytes(it).resolve()) } ?: Copy.bytes(p.done).resolve() } ?: stringResource(R.string.transfers_starting),
                    style = SmallNumber,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.transfers_cancel)) } },
    )
}
