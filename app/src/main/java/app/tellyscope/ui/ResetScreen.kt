// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.Device
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetFailure
import app.tellyscope.data.ResetPart
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetProgress
import app.tellyscope.data.UiText
import app.tellyscope.data.resolve

/** What Reset TV's screen can do, for MainActivity to wire to the view model. */
data class ResetActions(
    val reset: () -> Unit = {},
    val retry: () -> Unit = {},
    val reload: () -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * Reset TV to how it shipped: what it found on the TV first, with nothing changed yet, then its
 * progress, then what changed or what didn't work. Opened from ⋮.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ResetScreen(state: UiState, actions: ResetActions) {
    val device = state.selected ?: return
    val ui = state.device.reset ?: return
    val plan = ui.plan
    val maker = makerOf(device)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(Copy.RESET_TV.resolve())
                        Text(name(device), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.screen_back_to_tv)) }
                },
            )
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when {
                    plan == null || (plan.changes == 0 && !ui.ran) -> Unit
                    ui.running != null -> Button(onClick = {}, enabled = false) { Text(Copy.RESETTING.resolve()) }
                    ui.ran -> {
                        if (ui.failed.isEmpty()) Button(onClick = actions.back) { Text(stringResource(R.string.screen_done)) }
                        else {
                            TextButton(onClick = actions.back) { Text(stringResource(R.string.screen_done)) }
                            Button(onClick = actions.retry) { Text(Copy.tryAgain(ui.failed.singleOrNull()?.app?.label).resolve()) }
                        }
                    }
                    else -> {
                        TextButton(onClick = actions.back) { Text(stringResource(R.string.screen_cancel)) }
                        Button(onClick = actions.reset) { Text(Copy.RESET_BUTTON.resolve()) }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                ui.error != null -> Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Default.Warning, stringResource(R.string.reset_read_failed), ui.error.resolve())
                    TextButton(onClick = actions.reload) { Text(stringResource(R.string.screen_try_again)) }
                }
                plan == null -> Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator(Modifier.size(48.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.reset_reading), style = MaterialTheme.typography.titleSmall)
                }
                // Try again runs on this screen too, so a run in progress comes before the last run's result.
                ui.running == null && ui.ran -> Result(plan, ui.failed)
                plan.changes == 0 -> Box(Modifier.padding(horizontal = 24.dp)) {
                    EmptyState(Icons.Default.Check, Copy.NOTHING_TO_RESET.resolve(), Copy.nothingToReset(plan.ownHome, maker).resolve())
                }
                else -> Found(plan, ui.running, ui.progress, maker)
            }
        }
    }
}

/** What the TV has, before and while the reset runs: one section per part, then what stays. */
@Composable
private fun Found(plan: ResetPlan, running: ResetPlan?, progress: ResetProgress?, maker: String?) {
    val c = MaterialTheme.colorScheme
    val parts = ResetPart.entries.filter { count(plan, it) > 0 }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            if (running == null) {
                Surface(color = c.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Copy.resetFound(plan.changes).resolve(), style = MaterialTheme.typography.titleSmall, color = c.onPrimaryContainer)
                        Text(Copy.RESET_FOUND_TEXT.resolve(), style = MaterialTheme.typography.bodyMedium, color = c.onPrimaryContainer)
                    }
                }
            } else {
                val total = running.changes.coerceAtLeast(1)
                val done = progress?.let { p -> ResetPart.entries.filter { it < p.part }.sumOf { count(running, it) } + p.done } ?: 0
                LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(
                    progress?.let { Copy.resetting(it, plan.home?.toLabel).resolve() } ?: Copy.RESETTING.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                )
            }
        }
        itemsIndexed(parts) { i, part ->
            val status = running?.let { r -> status(part, r, progress) }
            Surface(color = c.surfaceContainerLow, shape = groupShape(i, parts.size), modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                Section(plan, part, status)
            }
        }
        item {
            Text(
                Copy.resetLeaves(plan.shippedOff, maker).resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
    }
}

private enum class Status { Waiting, Running, Done }

/** Where [part] is in a run of [running]: a part with nothing to do this time counts as done. */
private fun status(part: ResetPart, running: ResetPlan, progress: ResetProgress?): Status {
    if (count(running, part) == 0) return Status.Done
    val now = progress?.part ?: ResetPart.entries.first { count(running, it) > 0 }
    return when {
        part < now -> Status.Done
        part == now -> Status.Running
        else -> Status.Waiting
    }
}

private fun count(plan: ResetPlan, part: ResetPart) = when (part) {
    ResetPart.TurnOn -> plan.turnOn.size
    ResetPart.BringBack -> plan.bringBack.size
    ResetPart.Home -> if (plan.home != null) 1 else 0
}

/** One part of the reset; tapping it, before a run, lists the apps it changes. */
@Composable
private fun Section(plan: ResetPlan, part: ResetPart, status: Status?) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable(part) { mutableStateOf(false) }
    val (icon, title, text) = when (part) {
        ResetPart.TurnOn -> Triple(HistoryIcons.TurnedOn, Copy.turnOnApps(plan.turnOn.size).resolve(), Copy.TURN_ON_TEXT.resolve())
        ResetPart.BringBack -> Triple(ResetIcons.BringBack, Copy.bringBackApps(plan.bringBack.size).resolve(), Copy.BRING_BACK_TEXT.resolve())
        ResetPart.Home -> plan.home!!.let { Triple(Icons.Default.Home, Copy.resetHome(it.toLabel).resolve(), Copy.resetHomeText(it.fromLabel, it.service).resolve()) }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = status == null) { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = c.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            when (status) {
                null -> Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = stringResource(if (open) R.string.reset_hide_list else R.string.reset_show_list))
                Status.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Status.Waiting -> Icon(ResetIcons.Waiting, contentDescription = stringResource(R.string.screen_waiting), tint = c.onSurfaceVariant)
                Status.Done -> Icon(HistoryIcons.TurnedOn, contentDescription = stringResource(R.string.screen_done), tint = c.primary)
            }
        }
        if (open && status == null) {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = c.outlineVariant)
            when (part) {
                ResetPart.TurnOn -> AppList(plan.turnOn)
                ResetPart.BringBack -> AppList(plan.bringBack)
                ResetPart.Home -> plan.home!!.let { AppList(listOf(ResetApp(it.from, it.fromLabel), ResetApp(it.to, it.toLabel))) }
            }
        }
    }
}

/** Name over package, as the confirms show them: any app can call itself anything, the package is what changes. */
@Composable
private fun AppList(apps: List<ResetApp>) {
    var all by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(start = 56.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        (if (all) apps else apps.take(SHOWN)).forEach { a ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(a.label, style = MaterialTheme.typography.bodyLarge)
                Text(a.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!all && apps.size > SHOWN) TextButton(onClick = { all = true }) { Text(Copy.showAll(apps.size).resolve()) }
    }
}

private const val SHOWN = 7

/** After a run: a cookie badge, what changed, and per part what worked, with the first failure's reason. */
@Composable
private fun Result(plan: ResetPlan, failed: List<ResetFailure>) {
    val ok = failed.isEmpty()
    fun failures(part: ResetPart) = failed.filter { it.part == part }
    val on = plan.turnOn.size - failures(ResetPart.TurnOn).size
    val back = plan.bringBack.size - failures(ResetPart.BringBack).size
    val home = plan.home?.takeIf { failures(ResetPart.Home).isEmpty() }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            ResultHead(ok, if (ok) Copy.RESET_DONE.resolve() else Copy.RESET_PARTLY.resolve(), if (ok) Copy.resetDoneText(on + back, home?.toLabel).resolve() else Copy.resetPartlyText(failed.size).resolve())
        }
        val rows = ResetPart.entries.filter { count(plan, it) > 0 }
        itemsIndexed(rows) { i, part ->
            val miss = failures(part)
            val line = when (part) {
                ResetPart.TurnOn -> Copy.turnedOn(on, plan.turnOn.size).resolve()
                ResetPart.BringBack -> Copy.broughtBack(back, plan.bringBack.size).resolve()
                // A miss with an app is only the service: Home did move.
                ResetPart.Home -> plan.home!!.let { if (miss.all { m -> m.app != null }) Copy.isHome(it.toLabel).resolve() else Copy.stillHome(it.fromLabel).resolve() }
            }
            val why = miss.firstOrNull()?.let { f ->
                val reason = UiText.Capitalized(f.reason).resolve().trimEnd('.')
                f.app?.takeIf { f.part != ResetPart.Home }?.let { stringResource(R.string.result_reason_app, it.label, reason) }
                    ?: stringResource(R.string.result_reason, reason)
            }
            ResultRow(line, why, groupShape(i, rows.size))
        }
    }
}

/** The top of a result, shared with Debloat's: a cookie badge, a check or a warning, the title and what happened. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ResultHead(ok: Boolean, title: String, text: String) {
    val c = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(if (ok) c.primaryContainer else c.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (ok) Icons.Default.Check else Icons.Default.Warning, contentDescription = null, Modifier.size(44.dp), tint = if (ok) c.onPrimaryContainer else c.onErrorContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 24.dp),
        )
    }
}

/** One grouped row of a result: what worked, or what didn't with [why] in red under it. */
@Composable
internal fun ResultRow(line: String, why: String?, shape: Shape) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceContainerLow, shape = shape, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (why == null) Icon(HistoryIcons.TurnedOn, contentDescription = null, tint = c.primary)
            else Icon(Icons.Default.Warning, contentDescription = null, tint = c.error)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(line, style = MaterialTheme.typography.bodyLarge)
                if (why != null) Text(why, style = MaterialTheme.typography.bodyMedium, color = c.error)
            }
        }
    }
}

/** The TV's brand as the footnotes name it, when known. */
private fun makerOf(device: Device) = device.manufacturer ?: device.hit.maker

internal object ResetIcons {
    // Material's download and schedule (a ring cut out by even-odd, hands inside). Absolute commands only, as in TileIcons.
    val BringBack = icon("BringBack", "M5 20H19V18H5ZM19 9H15V3H9V9H5L12 16Z")
    val Waiting = icon(
        "Waiting",
        "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2ZM12 4A8 8 0 1 1 12 20A8 8 0 1 1 12 4Z" +
            "M12.5 7H11V13L16.25 16.15L17 14.92L12.5 12.25Z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            PathParser().parsePathString(path).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black),
        )
        .build()
}
