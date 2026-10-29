// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.tellyscope.adb.InstallKind
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.ui.theme.SmallNumber

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
fun ActionTiles(actions: DeviceActions, count: Int, todo: Boolean, disabling: Boolean, onDebloat: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile(Icons.Default.Add, "Install app", actions.pickApp, Modifier.weight(1f))
        Tile(
            Broom, if (disabling) "Disabling…" else "Debloat", onDebloat, Modifier.weight(1f),
            enabled = !disabling, primary = todo, badge = count.takeIf { todo && it > 0 },
        )
        // Where the file lands is the user's choice, every time, not whatever folder was last shown.
        var choosing by remember { mutableStateOf(false) }
        Box(Modifier.weight(1f)) {
            @Suppress("DEPRECATION") // the AutoMirrored set isn't in icons-core
            Tile(Icons.Default.Send, "Send file", { choosing = true }, Modifier.fillMaxWidth())
            DropdownMenu(choosing, onDismissRequest = { choosing = false }) {
                TvFolder.entries.forEach { f ->
                    DropdownMenuItem(
                        text = { Text("To ${Copy.folder(f)}") },
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
            Modifier.padding(vertical = 20.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BadgedBox(badge = { if (badge != null) Badge { Text(badge.toString()) } }) {
                Icon(icon, contentDescription = null, Modifier.size(28.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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

/** Body of the Files tab, under its folder tabs: the folder's files, each one tap from the phone. */
@Composable
fun FilesBlock(files: FilesUi, now: Long, actions: DeviceActions) {
    val list = files.list
    when {
        list == null -> Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
            @OptIn(ExperimentalMaterial3ExpressiveApi::class)
            LoadingIndicator(Modifier.size(32.dp))
        }
        files.error != null -> Muted(files.error)
        else -> {
            list.forEach { f -> FileRow(f, now) { actions.saveFile(f) } }
            // Only with files to tap; an empty folder shows its own page instead.
            if (list.isNotEmpty()) Muted("Tap a file to save a copy on your phone.")
        }
    }
}

@Composable
private fun FileRow(f: TvFile, now: Long, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSave).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(
                "${Copy.bytes(f.size)} · ${Copy.ago(f.modifiedMillis, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onSave) { Text("Save") }
    }
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
                    SideloadUi.Step.Reading -> "Reading the app"
                    SideloadUi.Step.Failed -> app?.let { "Couldn't install ${it.label}" } ?: "Can't install that"
                    SideloadUi.Step.Installed -> Copy.installed(kind ?: InstallKind.NEW, app?.label.orEmpty())
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
                                "Version ${app.info.versionName ?: app.info.versionCode}",
                                Copy.bytes(app.sizeBytes),
                                if (app.files.size > 1) "${app.files.size} parts" else null,
                            ).joinToString(" · "),
                            style = SmallNumber,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                when (s.step) {
                    SideloadUi.Step.Ready -> if (kind != null) {
                        Text(Copy.installKind(kind, s.installed?.versionName, app?.info?.versionName), style = MaterialTheme.typography.bodyMedium)
                        if (kind == InstallKind.DOWNGRADE) {
                            Row(
                                Modifier.fillMaxWidth().clickable { actions.allowDowngrade(!s.allowDowngrade) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(s.allowDowngrade, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Text(Copy.ALLOW_DOWNGRADE, style = MaterialTheme.typography.bodyMedium)
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
                            if (sending) "Sending to the TV · ${(f * 100).toInt()}%" else "Installing on the TV…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Muted(Copy.INSTALL_KEEP_OPEN)
                    }
                    SideloadUi.Step.Failed -> {
                        Text(s.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        s.detail?.takeIf { it.isNotBlank() }?.let {
                            Text("The TV said: $it", style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
                ) { Text(Copy.installButton(kind)) }
                SideloadUi.Step.Installed -> Button(onClick = actions.openInstalled) { Text("Open on TV") }
                SideloadUi.Step.Failed -> Button(onClick = actions.closeSideload) { Text("OK") }
                else -> Unit
            }
        },
        dismissButton = {
            when (s.step) {
                SideloadUi.Step.Reading, SideloadUi.Step.Ready -> TextButton(onClick = actions.closeSideload) { Text("Cancel") }
                SideloadUi.Step.Installed -> TextButton(onClick = actions.closeSideload) { Text("Done") }
                SideloadUi.Step.Failed -> TextButton(onClick = { actions.reportInstall(s) }) { Text("Report a problem") }
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
        title = { Text(if (t.toTv) "Sending to ${Copy.folder(t.folder)}" else "Saving to your phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                val f = t.progress?.fraction
                if (f != null) LinearWavyProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    t.progress?.let { p -> p.total?.let { "${Copy.bytes(p.done)} of ${Copy.bytes(it)}" } ?: Copy.bytes(p.done) } ?: "Starting…",
                    style = SmallNumber,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
