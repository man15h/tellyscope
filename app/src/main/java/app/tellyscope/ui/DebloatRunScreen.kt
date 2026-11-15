// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.UiText
import app.tellyscope.data.resolve

/**
 * Debloat after "Yes, disable them": the ticked apps, each marked done, running, waiting or
 * refused with the TV's reason, under a bar pinned to the top. Then Reset TV's result layout.
 * While it runs there's no way back, only Stop: leaving would cut the run off partway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebloatRunScreen(
    run: DebloatRun,
    back: () -> Unit,
    retry: () -> Unit,
    stop: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    RunBackHandler(!run.ran, snackbar, Copy.DEBLOAT_STILL_RUNNING, back)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debloat_title)) },
                navigationIcon = {
                    if (run.ran) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back)) }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when {
                    !run.ran -> {
                        if (!run.stopping) TextButton(onClick = stop) { Text(stringResource(R.string.copy_stop)) }
                        Button(onClick = {}, enabled = false) { Text(Copy.DISABLING.resolve()) }
                    }
                    run.failed.isEmpty() && !run.stopped -> Button(onClick = back) { Text(stringResource(R.string.screen_done)) }
                    else -> {
                        TextButton(onClick = back) { Text(stringResource(R.string.screen_done)) }
                        val one = run.failed.keys.singleOrNull()?.let { pkg -> run.apps.first { it.packageName == pkg }.label }
                        val again = if (run.stopped) Copy.disableTheRest(run.failed.size + run.left.size) else Copy.tryAgain(one)
                        Button(onClick = retry) { Text(again.resolve()) }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (run.ran) Result(run) else Progress(run)
        }
    }
}

private enum class Mark { Done, Running, Waiting, Failed }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Progress(run: DebloatRun) {
    val c = MaterialTheme.colorScheme
    // Pinned: the bar and the count stay in view while the list scrolls.
    LinearWavyProgressIndicator(
        progress = { run.at.toFloat() / run.apps.size.coerceAtLeast(1) },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
    // After Stop, the line names the app it ends on, in place of the count.
    val running = run.apps.getOrNull(run.at)
    Text(
        (if (run.stopping && running != null) Copy.stoppingAfter(running.label) else Copy.disablingApps(run.at, run.apps.size)).resolve(),
        style = MaterialTheme.typography.bodyMedium,
        color = c.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    )
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        itemsIndexed(run.apps, key = { _, a -> a.packageName }) { i, a ->
            val why = run.failed[a.packageName]
            val mark = when {
                why != null -> Mark.Failed
                i < run.at -> Mark.Done
                i == run.at -> Mark.Running
                else -> Mark.Waiting
            }
            Surface(color = c.surfaceContainerLow, shape = groupShape(i, run.apps.size), modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Name over package, as the confirm shows them.
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.bodyLarge)
                        Text(a.packageName, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                        if (why != null) Text(reason(why), style = MaterialTheme.typography.bodyMedium, color = c.error)
                    }
                    Spacer(Modifier.width(16.dp))
                    when (mark) {
                        Mark.Done -> Icon(HistoryIcons.TurnedOn, contentDescription = stringResource(R.string.screen_done), tint = c.primary)
                        Mark.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Mark.Waiting -> Icon(ResetIcons.Waiting, contentDescription = stringResource(R.string.screen_waiting), tint = c.onSurfaceVariant)
                        Mark.Failed -> Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.debloat_failed), tint = c.error)
                    }
                }
            }
        }
        item {
            Text(
                Copy.DISABLING_NOTE.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
    }
}

/** After the run: what turned off, then one row per app still on, with the TV's reason, then the apps Stop left. */
@Composable
private fun Result(run: DebloatRun) {
    val ok = run.failed.isEmpty() && !run.stopped
    val still = run.apps.filter { it.packageName in run.failed }
    val left = run.left
    val off = run.apps.size - still.size - left.size
    // No "Turned off 0 of n" row with a tick when the TV refused every one.
    val top = if (off > 0) 1 else 0
    val rows = top + still.size + if (left.isEmpty()) 0 else 1
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            when {
                run.stopped -> ResultHead(false, Copy.DEBLOAT_STOPPED.resolve(), Copy.debloatStoppedText(run.apps.getOrNull(run.at - 1)?.label).resolve(), stopped = true)
                else -> ResultHead(ok, if (ok) Copy.DEBLOAT_DONE.resolve() else Copy.DEBLOAT_PARTLY.resolve(), Copy.DEBLOAT_RESULT_TEXT.resolve())
            }
        }
        if (off > 0) item { ResultRow(Copy.turnedOff(off, run.apps.size).resolve(), null, groupShape(0, rows)) }
        itemsIndexed(still) { i, a -> ResultRow(Copy.stillOn(a.label).resolve(), reason(run.failed.getValue(a.packageName)), groupShape(i + top, rows)) }
        if (left.isNotEmpty()) item {
            val names = Copy.firstFew(left.map { UiText.Raw(it.label) }).map { it.resolve() }.joinToString(", ")
            ResultRow(Copy.notChanged(left.size).resolve(), names, groupShape(rows - 1, rows), left = true)
        }
    }
}

/** The TV's reason as a sentence. */
@Composable
private fun reason(why: UiText) = stringResource(R.string.result_reason, UiText.Capitalized(why).resolve().trimEnd('.'))
