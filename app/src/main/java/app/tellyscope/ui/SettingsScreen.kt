// SPDX-License-Identifier: GPL-3.0-only
// The open-in-browser icon is the Material Icons "open_in_new" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import android.content.Context
import android.content.Intent
import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
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
    /** The own-lists address being checked, or why it was refused; null otherwise. */
    sourceCheck: SourceCheck? = null,
    /** Cancel in the own-lists dialog, to stop a check still running. */
    onCancelSource: () -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val browser = LocalUriHandler.current
    val context = LocalContext.current
    val open = { url: String -> runCatching { browser.openUri(url) }; Unit }
    if (editing) SourceDialog(listSource, sourceCheck, onSave = onListSource, onDone = { editing = false }, onDismiss = { editing = false; onCancelSource() })
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
                AppCard {
                    if (checkUpdates != null) SettingsRow(
                        stringResource(R.string.settings_check_updates),
                        stringResource(if (alphaBuild) R.string.settings_check_updates_alpha else R.string.settings_check_updates_release),
                        Modifier.toggleable(checkUpdates, role = Role.Switch, onValueChange = onCheckUpdates),
                    ) { Switch(checked = checkUpdates, onCheckedChange = null) }
                    if (checkUpdates != null) HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    SettingsRow(
                        stringResource(R.string.settings_key_haptics),
                        stringResource(R.string.settings_key_haptics_text),
                        Modifier.toggleable(keyHaptics, role = Role.Switch, onValueChange = onKeyHaptics),
                    ) { Switch(checked = keyHaptics, onCheckedChange = null) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    SettingsRow(stringResource(R.string.settings_whats_new), stringResource(R.string.settings_whats_new_text), Modifier.clickable(onClick = onWhatsNew), NewReleases)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    SettingsRow(stringResource(R.string.settings_report), stringResource(R.string.settings_report_text), Modifier.clickable(onClick = onReport), BugReport)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    SettingsRow(Copy.SHARE.resolve(), stringResource(R.string.settings_share_hint), Modifier.clickable { shareTellyscope(context) }, Icons.Default.Share)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(advanced, role = Role.Button, onValueChange = { advanced = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(stringResource(R.string.settings_advanced), Modifier.weight(1f))
                    Icon(if (advanced) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
                }
            }
            if (advanced) item {
                AppCard {
                    ListsFrom(listSource, onOpen = { open(CLEANYOURTV) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.settings_change), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(R.string.settings_change_text),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(Modifier.heightIn(min = 48.dp).clickable { open(OWN_LISTS_GUIDE) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_own_lists_guide), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Icon(OpenInNew, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                    ListItem(
                        Modifier.clickable { editing = true },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) { Text(stringResource(R.string.settings_own_lists)) }
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
    ListItem(
        if (source == null) Modifier.clickable(onClick = onOpen) else Modifier,
        trailingContent = if (source == null) ({ Icon(OpenInNew, contentDescription = stringResource(R.string.settings_open_cleanyourtv)) }) else null,
        overlineContent = { Text(stringResource(R.string.settings_lists_from)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (source == null) "cleanyourtv" else sourceName(source))
            if (source == null) {
                // The count in the phone's own short form: 40K, 4万, 40 тыс.
                val locale = LocalConfiguration.current.locales[0]
                val stars = remember(locale) { CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).format(CLEANYOURTV_STARS) }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Star, contentDescription = null, Modifier.size(16.dp), tint = c.onSurfaceVariant)
                Text(" $stars+", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            }
        }
    }
}

/**
 * One row of a Settings card: the M3 list item, with [icon] leading and [trailing] at the end (a
 * chevron unless given). [modifier] carries the whole row's click or toggle.
 */
@Composable
private fun SettingsRow(
    title: String,
    text: String,
    modifier: Modifier,
    icon: ImageVector? = null,
    trailing: @Composable () -> Unit = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
) = ListItem(
    modifier,
    leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
    trailingContent = trailing,
    supportingContent = { Text(text) },
    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
) { Text(title) }

/** About how many stars cleanyourtv has on GitHub, shown as "40K+". */
private const val CLEANYOURTV_STARS = 40_000

internal val OpenInNew = ImageVector.Builder("OpenInNew", 24.dp, 24.dp, 24f, 24f)
    .addPath(
        PathParser().parsePathString("M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z").toNodes(),
        pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black),
    )
    .build()

/**
 * Edits the list source: an HTTPS folder holding `index.json`, or back to the default. It stays
 * open while the address is checked and closes once it's taken; a refusal shows under the address,
 * kept as typed, until it's edited. Cancel stops the check.
 */
@Composable
private fun SourceDialog(source: String?, check: SourceCheck?, onSave: (String?) -> Unit, onDone: () -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(source.orEmpty()) }
    // The address last sent for checking, and whether an answer is still to come.
    var sent by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(check) {
        if (waiting && check == null) onDone()
        if (check is SourceCheck.Refused) waiting = false
    }
    val checking = check == SourceCheck.Checking
    val refused = (check as? SourceCheck.Refused)?.why?.takeIf { sent == url }
    val save = { to: String? -> sent = to.orEmpty(); waiting = true; onSave(to) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_source_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.settings_source_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    url, { url = it },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    isError = refused != null,
                    supportingText = if (refused != null) ({ Text(refused.resolve()) }) else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        // Three actions stack full width; two sit side by side.
        confirmButton = {
            val use = @Composable { m: Modifier ->
                Button(onClick = { save(url) }, enabled = url.isNotBlank() && !checking, modifier = m) {
                    Text(stringResource(if (checking) R.string.settings_checking else R.string.settings_use_lists))
                }
            }
            if (source == null) use(Modifier) else Column(Modifier.fillMaxWidth()) {
                use(Modifier.fillMaxWidth())
                TextButton(onClick = { save(null) }, Modifier.fillMaxWidth(), enabled = !checking) { Text(stringResource(R.string.settings_reset)) }
                TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_cancel)) }
            }
        },
        dismissButton = if (source == null) ({ TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }) else null,
    )
}
