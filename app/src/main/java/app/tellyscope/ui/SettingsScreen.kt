// SPDX-License-Identifier: GPL-3.0-only
// The open-in-browser icon is the Material Icons "open_in_new" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.tellyscope.data.RELEASES
import app.tellyscope.data.sourceName
import androidx.compose.ui.res.stringResource
import app.tellyscope.R
import app.tellyscope.data.UiText
import app.tellyscope.data.resolve

/** Where the lists come from, credited in Settings. Its star count is written in, not fetched. */
internal const val CLEANYOURTV = "https://github.com/Gathin23/cleanyourtv"

/** CONTRIBUTING's guide to pointing the app at your own lists. */
internal val OWN_LISTS_GUIDE = RELEASES.removeSuffix("/releases") + "/blob/main/CONTRIBUTING.md#using-your-own-lists"

/** What Share Tellyscope sends: what it is, then the repo, whose page shows it and how to install. */
internal val SHARE_TEXT = UiText(R.string.settings_share_text, REPO)

/** Android's share sheet with [SHARE_TEXT], so any app the user picks can send it on. */
internal fun shareTellyscope(context: Context) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, SHARE_TEXT.resolve(context))
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}

/**
 * App-wide choices, not tied to one TV: update checks, What's new, Report a problem and Share Tellyscope first,
 * then a folded Advanced with where the debloat lists come from and how to use your own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** The user's own debloat-list source; null for tellyscope-devices. */
    listSource: String?,
    onListSource: (String?) -> Unit,
    /** Null hides the switch: this build doesn't check (screenshots). */
    checkUpdates: Boolean?,
    /** Alpha builds check every 5 minutes, release builds every 30 minutes. */
    alphaBuild: Boolean,
    onCheckUpdates: (Boolean) -> Unit,
    back: () -> Unit,
    keyHaptics: Boolean = true,
    onKeyHaptics: (Boolean) -> Unit = {},
    onReport: () -> Unit = {},
    onWhatsNew: () -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val browser = LocalUriHandler.current
    val context = LocalContext.current
    val open = { url: String -> runCatching { browser.openUri(url) }; Unit }
    if (editing) SourceDialog(listSource, onSave = { editing = false; onListSource(it) }, onDismiss = { editing = false })
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back_to_tv)) }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    if (checkUpdates != null) Row(
                        Modifier.fillMaxWidth()
                            .toggleable(checkUpdates, role = Role.Switch, onValueChange = onCheckUpdates)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_check_updates), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(if (alphaBuild) R.string.settings_check_updates_alpha else R.string.settings_check_updates_release),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(checked = checkUpdates, onCheckedChange = null)
                    }
                    if (checkUpdates != null) HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(keyHaptics, role = Role.Switch, onValueChange = onKeyHaptics)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_key_haptics), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_key_haptics_text), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(checked = keyHaptics, onCheckedChange = null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Row(Modifier.fillMaxWidth().clickable(onClick = onWhatsNew).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_whats_new), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_whats_new_text), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Row(Modifier.fillMaxWidth().clickable(onClick = onReport).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_report), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_report_text), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Row(Modifier.fillMaxWidth().clickable { shareTellyscope(context) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(Copy.SHARE.resolve(), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(R.string.settings_share_hint), style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.width(16.dp))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .toggleable(advanced, role = Role.Button, onValueChange = { advanced = it })
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
            if (advanced) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    ListsFrom(listSource, onOpen = { open(CLEANYOURTV) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.settings_change), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.settings_change_text),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(Modifier.clickable { open(OWN_LISTS_GUIDE) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_own_lists_guide), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(OpenInNew, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Row(Modifier.fillMaxWidth().clickable { editing = true }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_own_lists), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

/**
 * Where the lists come from: cleanyourtv with its stars, opening its GitHub page, or the user's own
 * source by name once they've set one.
 */
@Composable
private fun ListsFrom(source: String?, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().then(if (source == null) Modifier.clickable(onClick = onOpen) else Modifier).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_lists_from), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (source == null) "cleanyourtv" else sourceName(source), style = MaterialTheme.typography.titleMedium)
                if (source == null) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Default.Star, contentDescription = null, Modifier.size(16.dp), tint = Color(0xFFE0A100))
                    Text(" 40k+", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
            }
        }
        if (source == null) Icon(OpenInNew, contentDescription = stringResource(R.string.settings_open_cleanyourtv), tint = c.onSurfaceVariant)
    }
}

internal val OpenInNew = ImageVector.Builder("OpenInNew", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        PathParser().parsePathString("M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z").toNodes(),
        pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black),
    )
    .build()

/** Edits the list source: an HTTPS folder holding `index.json`, or back to the default. */
@Composable
private fun SourceDialog(source: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(source.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_source_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_source_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(url, { url = it }, placeholder = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        // Three actions stack full width; two sit side by side.
        confirmButton = {
            val use = @Composable { m: Modifier -> Button(onClick = { onSave(url) }, enabled = url.isNotBlank(), modifier = m) { Text(stringResource(R.string.settings_use_lists)) } }
            if (source == null) use(Modifier) else Column(Modifier.fillMaxWidth()) {
                use(Modifier.fillMaxWidth())
                TextButton(onClick = { onSave(null) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_reset)) }
                TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
        dismissButton = if (source == null) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }) else null,
    )
}
