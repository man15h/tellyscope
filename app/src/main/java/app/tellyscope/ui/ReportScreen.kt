// SPDX-License-Identifier: GPL-3.0-only
// The copy and report icons are the Material Icons "content_copy" and "bug_report" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.adb.Report
import app.tellyscope.adb.Reports
import app.tellyscope.data.resolve

/**
 * Report a problem, or Help add this TV: what the TV said about itself, the form's dropdowns, what
 * the user writes, then exactly what goes to GitHub. Open GitHub opens the pre-filled form; the user
 * posts it from their own account. A report that didn't fit the link comes back here with the rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(r: ReportUi, onOpened: (Report) -> Unit, close: () -> Unit) {
    val browser = LocalUriHandler.current
    val posted = r.posted
    var addingRest by rememberSaveable { mutableStateOf(false) }
    if (posted?.rest != null && addingRest) {
        AddRestScreen(posted, back = { addingRest = false }, done = close)
        return
    }

    var connection by rememberSaveable { mutableStateOf(r.connection) }
    var outcome by rememberSaveable { mutableStateOf<Reports.Outcome?>(null) }
    var stage by rememberSaveable { mutableStateOf<Reports.Stage?>(null) }
    var details by rememberSaveable { mutableStateOf(r.details) }
    val log = r.log?.resolve()
    // Null until the form's required dropdown is picked, and for a new TV, until it's been read.
    val report = remember(r.tv, log, connection, outcome, stage, details) {
        when (r.kind) {
            ReportKind.Problem -> outcome?.let { Reports.problem(r.app, r.tv, connection, it, details, log) }
            ReportKind.NewTv -> if (r.tv == null) null else stage?.let { Reports.newTv(r.app, r.tv, it, details) }
        }
    }
    val newTv = r.kind == ReportKind.NewTv
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(if (newTv) R.string.report_title_new_tv else R.string.report_title_problem))
                        Text(
                            stringResource(if (newTv) R.string.report_subtitle_new_tv else R.string.report_subtitle_problem),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.report_close)) } },
            )
        },
        bottomBar = {
            if (posted == null) Actions(
                note = stringResource(R.string.report_note_own_account) + " " + stringResource(
                    if (report?.rest != null) R.string.report_note_rest else R.string.report_note_nothing_sent,
                ),
                cancel = close,
            ) {
                Button(
                    onClick = { report?.let { runCatching { browser.openUri(it.link) }; onOpened(it) } },
                    enabled = report != null,
                ) { ButtonIcon(OpenInNew); Text(stringResource(R.string.report_open_github)) }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (posted != null) {
                PostedCard(posted, notNow = close, addRest = { addingRest = true })
                return@Column
            }
            // Read from the TV, so shown as facts rather than fields to edit.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                val tv = r.tv
                val reading = stringResource(if (r.reading) R.string.report_reading_tv else R.string.report_no_tv)
                Fact(stringResource(if (newTv) R.string.report_tv_model else R.string.report_tv), tv?.name ?: reading)
                HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                Fact(stringResource(R.string.report_android_build), tv?.android ?: reading)
            }
            if (newTv) {
                Choice(stringResource(R.string.report_how_far), stage, Reports.Stage.entries, { it.option }) { stage = it }
            } else {
                Choice(
                    stringResource(R.string.report_how_connect), connection, Reports.Connection.entries, { it.option },
                    help = stringResource(R.string.report_how_connect_help),
                ) { connection = it }
                Choice(stringResource(R.string.report_what_happened), outcome, Reports.Outcome.entries, { it.option }) { outcome = it }
            }
            OutlinedTextField(
                details, { details = it },
                label = { Text(stringResource(R.string.report_details)) },
                placeholder = { Text(stringResource(if (newTv) R.string.report_details_new_tv else R.string.report_details_problem)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.report_what_gets_sent), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (report == null) {
                Text(
                    when {
                        newTv && r.tv == null -> stringResource(if (r.reading) R.string.report_reading_tv else R.string.report_connect_tv)
                        newTv -> stringResource(R.string.report_pick_stage)
                        else -> stringResource(R.string.report_pick_outcome)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Sent(report.text)
            }
            Removed()
        }
    }
}

/** The cut part of a posted report, as a comment: copied, then the user's newest issue opens to paste it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRestScreen(posted: Report, back: () -> Unit, done: () -> Unit) {
    val browser = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val rest = posted.rest.orEmpty()
    BackHandler(onBack = back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.report_add_rest))
                        Text(
                            stringResource(R.string.report_add_rest_subtitle, posted.repo.substringAfter('/')),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.report_close)) } },
            )
        },
        bottomBar = {
            Actions(
                note = stringResource(R.string.report_add_rest_note, posted.repo),
                cancel = back,
            ) {
                Button(onClick = {
                    clipboard.setText(AnnotatedString(rest))
                    runCatching { browser.openUri(posted.myIssues) }
                    done()
                }) { ButtonIcon(CopyIcon); Text(stringResource(R.string.report_copy_and_open)) }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.report_add_rest_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Sent(rest, tall = true)
            Removed()
        }
    }
}

/** Back from GitHub with part of the report cut: offer to add it. */
@Composable
private fun PostedCard(posted: Report, notNow: () -> Unit, addRest: () -> Unit) {
    val lines = posted.rest.orEmpty().lines().size - 1
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.report_posted_title), style = MaterialTheme.typography.titleMedium)
            Text(
                if (posted.repo == Reports.CLEANYOURTV) pluralStringResource(R.plurals.report_posted_apps_cut, lines, lines)
                else stringResource(R.string.report_posted_part_cut),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = notNow) { Text(stringResource(R.string.report_not_now)) }
                Button(onClick = addRest) { Text(stringResource(R.string.report_add_rest)) }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) = ListItem(
    overlineContent = { Text(label) },
    headlineContent = { Text(value) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
)

/** One of the form's dropdowns, its options word for word. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(label: String, value: T?, options: List<T>, text: (T) -> String, help: String? = null, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value?.let(text) ?: stringResource(R.string.report_choose_one), {},
            readOnly = true,
            label = { Text(label) },
            supportingText = help?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(text(o)) }, onClick = { open = false; onPick(o) }) }
        }
    }
}

/** What goes to GitHub, as it'll read there. */
@Composable
private fun Sent(text: String, tall: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.heightIn(max = if (tall) 420.dp else 280.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        )
    }
}

@Composable
private fun Removed() = Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.width(8.dp))
    Text(
        stringResource(R.string.report_removed_first),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The bottom bar: what happens next, then Cancel and the one action. */
@Composable
private fun Actions(note: String, cancel: () -> Unit, action: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = cancel) { Text(stringResource(R.string.report_cancel)) }

                action()
            }
        }
    }
}

@Composable
private fun ButtonIcon(icon: ImageVector) {
    Icon(icon, null, Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
}

private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
    .build()

private val CopyIcon = icon(
    "ContentCopy",
    "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z",
)

internal val BugReport = icon(
    "BugReport",
    "M20 8h-2.81c-.45-.78-1.07-1.45-1.82-1.96L17 4.41 15.59 3l-2.17 2.17C12.96 5.06 12.49 5 12 5c-.49 0-.96.06-1.41.17L8.41 3 7 4.41l1.62 1.63C7.88 6.55 7.26 7.22 6.81 8H4v2h2.09c-.05.33-.09.66-.09 1v1H4v2h2v1c0 .34.04.67.09 1H4v2h2.81c1.04 1.79 2.97 3 5.19 3s4.15-1.21 5.19-3H20v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1H20V8zm-6 8h-4v-2h4v2zm0-4h-4v-2h4v2z",
)

internal val AddToQueue = icon(
    "AddToQueue",
    "M21 3H3c-1.11 0-2 .89-2 2v12c0 1.1.89 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.11-.9-2-2-2zm0 14H3V5h18v12zm-5-7v2h-3v3h-2v-3H8v-2h3V7h2v3h3z",
)
