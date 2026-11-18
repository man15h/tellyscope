// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.adb.TvFile
import app.tellyscope.data.LauncherRelease
import app.tellyscope.data.MediaKind
import app.tellyscope.data.PLAYER
import app.tellyscope.data.PLAYER_DOWNLOADS
import app.tellyscope.data.resolve

data class PlayerActions(
    /** Open on TV in a file's row. */
    val open: (TvFile) -> Unit = {},
    /** Get it from Play Store, or Open Appstore: the TV's store, then a wait for the install. */
    val store: () -> Unit = {},
    /** Download directly: its warning first. */
    val direct: () -> Unit = {},
    /** Download anyway, or Download and install where there's no Play Store. */
    val download: () -> Unit = {},
    /** Open in … anyway. */
    val other: () -> Unit = {},
    val close: () -> Unit = {},
)

/**
 * [PlayerFlow]'s dialog, one step at a time. The prompt and the warning close with a tap outside; the wait and the
 * download only with Cancel, and the install not at all: cutting it off could leave the TV half done.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerDialog(p: PlayerFlow, tv: String, actions: PlayerActions) {
    val name = PLAYER.name
    val cancel = @Composable { TextButton(onClick = actions.close) { Text(stringResource(R.string.transfers_cancel)) } }
    when (p.step) {
        // Fire OS in the Play build: only the Appstore, opened by the user.
        PlayerFlow.Step.Ask -> if (p.store == PlayerStore.Appstore && !p.download) AlertDialog(
            onDismissRequest = actions.close,
            title = { Text(Copy.installPlayer(name).resolve()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(Copy.notOnTv(name, fire = true).resolve())
                    PlayerRow()
                }
            },
            confirmButton = { Button(onClick = actions.store) { Text(Copy.OPEN_APPSTORE.resolve()) } },
            dismissButton = cancel,
        ) else AlertDialog(
            onDismissRequest = actions.close,
            title = { Text(Copy.noPlayer(p.kind == MediaKind.Audio).resolve()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val other = p.otherLabel
                    Text(
                        (if (other != null) Copy.playerOtherText(p.kind == MediaKind.Audio, other, p.file.name, name)
                        else Copy.playerNothingText(p.file.name, name)).resolve(),
                    )
                    PlayerRow()
                    // Stacked full width: the labels don't fit side by side.
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.store == PlayerStore.Play) {
                            IconButtonText(OpenInNew, Copy.GET_FROM_STORE.resolve(), Modifier.fillMaxWidth(), onClick = actions.store)
                            if (p.download) OutlinedButton(onClick = actions.direct, modifier = Modifier.fillMaxWidth()) { Text(Copy.DOWNLOAD_DIRECTLY.resolve()) }
                        } else {
                            IconButtonText(LauncherIcons.Download, Copy.DOWNLOAD_AND_INSTALL.resolve(), Modifier.fillMaxWidth(), onClick = actions.download)
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (other != null) TextButton(onClick = actions.other) { Text(Copy.openAnyway(other).resolve()) } else cancel()
                        }
                    }
                }
            },
            confirmButton = {},
        )
        PlayerFlow.Step.Warn -> {
            val uri = LocalUriHandler.current
            val release = p.release
            AlertDialog(
                onDismissRequest = actions.close,
                text = {
                    // The launcher's Download directly, as it is there: the badge over the title, both to the left.
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        WarningBadge()
                        Text(Copy.downloadDirectly(name).resolve(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(Copy.downloadsFrom(name).resolve())
                        SourceRow(SOURCE, release?.let { LauncherRelease(it.version, it.page, it.apk, it.size) }) {
                            uri.openUri(release?.page ?: "${PLAYER_DOWNLOADS}last/")
                        }
                        Text(Copy.DIRECT_CHECK.resolve())
                        Text(Copy.STORE_SAFER.resolve())
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButtonText(OpenInNew, Copy.GET_FROM_STORE.resolve(), Modifier.fillMaxWidth(), onClick = actions.store)
                            OutlinedButton(onClick = actions.download, enabled = release != null, modifier = Modifier.fillMaxWidth()) { Text(Copy.DOWNLOAD_ANYWAY.resolve()) }
                        }
                    }
                },
                confirmButton = {},
            )
        }
        PlayerFlow.Step.Waiting -> AlertDialog(
            onDismissRequest = {},
            properties = Sticky,
            title = { Text(Copy.installPlayer(name).resolve()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        when {
                            p.store == PlayerStore.Appstore -> Copy.notOnTv(name, fire = true)
                            p.opened -> Copy.playerStoreOpen(name)
                            else -> Copy.noStoreText(tv, name)
                        }.resolve(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(Copy.playerWaiting(name, p.file.name).resolve(), color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            },
            confirmButton = cancel,
        )
        PlayerFlow.Step.Downloading, PlayerFlow.Step.Installing -> {
            val installing = p.step == PlayerFlow.Step.Installing
            AlertDialog(
                onDismissRequest = {},
                properties = Sticky,
                title = { Text((if (installing) Copy.installing(name) else Copy.downloadingPlayer(name)).resolve()) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        p.release?.let { Text("$name ${it.version} · ${it.abi}", style = MaterialTheme.typography.bodyLarge) }
                        val progress = p.progress?.takeIf { !installing }
                        val f = progress?.fraction
                        // The TV says nothing while it installs, so that bar only moves.
                        if (f != null) LinearWavyProgressIndicator(progress = { f }, Modifier.fillMaxWidth())
                        else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            when {
                                installing -> stringResource(R.string.transfers_installing)
                                progress != null -> Copy.playerProgress(progress.done, progress.total ?: progress.done, SOURCE_HOST).resolve()
                                else -> stringResource(R.string.transfers_starting)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                confirmButton = { if (!installing) cancel() },
            )
        }
    }
}

/** The player the prompts offer: its icon, name and who makes it. */
@Composable
private fun PlayerRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(VLC_ORANGE), contentAlignment = Alignment.Center) {
            Icon(Cone, contentDescription = null, Modifier.size(24.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(PLAYER.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(Copy.PLAYER_ABOUT.resolve(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val SOURCE = PLAYER_DOWNLOADS.removePrefix("https://").trimEnd('/')
private const val SOURCE_HOST = "videolan.org"
private val VLC_ORANGE = Color(0xFFFF8800)

/** A traffic cone, VLC's sign: two stripes cut out of it, over its base. Absolute commands only, as in [LauncherIcons]. */
private val Cone = ImageVector.Builder("Cone", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        PathParser().parsePathString(
            "M10.5 3H13.5L17.5 18H6.5Z" +
                "M8.9 9H15.1L15.6 11H8.4Z" +
                "M7.8 13H16.2L16.7 15H7.3Z" +
                "M4 19H20V21H4Z",
        ).toNodes(),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black),
    )
    .build()
