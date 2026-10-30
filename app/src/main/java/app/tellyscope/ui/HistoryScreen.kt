// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.Action
import app.tellyscope.data.HistoryBook
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.resolve

/**
 * Every change Tellyscope made on the TV, newest first, each with its Undo, and Restore everything
 * on top while apps it turned off are still off. Opened from ⋮ and from "See changes".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    state: UiState,
    onUndo: (HistoryEntry) -> Unit,
    onRestoreAll: () -> Unit,
    back: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val device = state.selected ?: return
    val ui = state.device
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(Copy.HISTORY.resolve())
                        Text(name(device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back_to_tv)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        ) {
            item {
                if (ui.history.isEmpty()) EmptyState(HistoryIcons.History, stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_text))
                else HistoryList(ui.history, state.clock(), onUndo, restoring = ui.busy == RESTORING, onRestoreAll = onRestoreAll, busy = ui.busy != null)
            }
        }
    }
}

/**
 * The Restore card while apps Tellyscope turned off are still off, then one row per change. While
 * anything runs on the TV, a restore included, Undo and Restore wait for it.
 */
@Composable
internal fun HistoryList(
    history: List<HistoryEntry>,
    now: Long,
    onUndo: (HistoryEntry) -> Unit,
    restoring: Boolean = false,
    onRestoreAll: () -> Unit = {},
    busy: Boolean = restoring,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val off = HistoryBook.leftDisabled(history).size
    if (confirming) RestoreDialog(off, onRestore = { confirming = false; onRestoreAll() }, onDismiss = { confirming = false })
    var disabling by remember { mutableStateOf<HistoryEntry?>(null) }
    disabling?.let { h ->
        UndoDisableDialog(h, onDisable = { disabling = null; onUndo(h) }, onDismiss = { disabling = null })
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (off > 0) {
            Card(
                Modifier.fillMaxWidth().padding(bottom = 14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Copy.stillOffTitle(off).resolve(), style = MaterialTheme.typography.titleSmall)
                    Text(Copy.STILL_OFF_TEXT.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    FilledTonalButton(onClick = { confirming = true }, enabled = !busy) {
                        if (restoring) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.history_restoring))
                        } else {
                            Text(stringResource(R.string.history_restore_all, off))
                        }
                    }
                }
            }
        }
        history.forEachIndexed { i, h ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = groupShape(i, history.size), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HistoryIcons.of(h.action), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Copy.done(h.action, h.label).resolve(), style = MaterialTheme.typography.bodyLarge)
                        Text(Copy.ago(h.at, now).resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when {
                        // Same button as Undo, switched off: the row keeps its shape and reads as done.
                        h.undoneAt != null -> TextButton(
                            onClick = {},
                            enabled = false,
                            colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.history_undone))
                        }
                        h.undo != null -> TextButton(onClick = { if (h.undo == Action.Disable) disabling = h else onUndo(h) }, enabled = !busy) { Text(Copy.undo(h.undo).resolve()) }
                    }
                }
            }
        }
    }
}

/** A row of a grouped list: the group's ends are rounded, the rows between nearly square. */
internal fun groupShape(i: Int, n: Int) = RoundedCornerShape(
    topStart = if (i == 0) 16.dp else 4.dp,
    topEnd = if (i == 0) 16.dp else 4.dp,
    bottomStart = if (i == n - 1) 16.dp else 4.dp,
    bottomEnd = if (i == n - 1) 16.dp else 4.dp,
)

internal object HistoryIcons {
    // Material's history: a clock with an arrow round it. Absolute commands only, as in TileIcons.
    val History = icon(
        "History",
        "M13 3A9 9 0 0 0 4 12H1L4.89 15.89L4.96 16.03L9 12H6C6 8.13 9.13 5 13 5S20 8.13 20 12S16.87 19 13 19" +
            "C11.07 19 9.32 18.21 8.06 16.94L6.64 18.36A8.954 8.954 0 0 0 13 21A9 9 0 0 0 13 3Z" +
            "M12 8V13L16.28 15.54L17 14.33L13.5 12.25V8Z",
    )
    // Material's block, stop_circle and check_circle outlines: a ring, its inside cut out by even-odd.
    private const val RING = "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2ZM12 4A8 8 0 1 1 12 20A8 8 0 1 1 12 4Z"
    val Block = icon("Block", RING + "M16.9 5.69L18.31 7.1L7.1 18.31L5.69 16.9Z", PathFillType.EvenOdd)
    val Stopped = icon("Stopped", RING + "M8 8H16V16H8Z", PathFillType.EvenOdd)
    val TurnedOn = icon("TurnedOn", RING + "M16.59 7.58L10 14.17L6.41 10.59L5 12L10 17L18 9Z", PathFillType.EvenOdd)

    fun of(action: Action): ImageVector = when (action) {
        Action.Disable -> Block
        Action.Stop -> Stopped
        Action.Enable -> TurnedOn
        Action.Open -> Icons.Default.PlayArrow
        Action.Uninstall, Action.ClearCaches -> Icons.Default.Delete
        Action.Home, Action.HomeBack -> Icons.Default.Home
        Action.Change -> Icons.Default.Settings
        Action.Reset -> History
    }

    private fun icon(name: String, path: String, fill: PathFillType = PathFillType.NonZero) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = fill, fill = SolidColor(Color.Black))
        .build()
}
