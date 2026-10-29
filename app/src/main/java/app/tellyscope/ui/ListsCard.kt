// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceKind
import app.tellyscope.data.DeviceState
import app.tellyscope.data.ListEntry
import app.tellyscope.data.RELEASES
import app.tellyscope.data.ResetApp
import app.tellyscope.data.Suggested
import app.tellyscope.data.SuggestedGroup
import app.tellyscope.data.Suggestions
import app.tellyscope.data.deviceKind
import app.tellyscope.data.listKind
import app.tellyscope.data.listMakers
import app.tellyscope.data.sourceName
import app.tellyscope.data.squash

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Debloat opens on the apps to turn off, under a card naming what they came from: this TV's model
 * list, else its maker's lists, unticked, else the TV's own apps to pick by hand. "Not this TV?"
 * searches every list instead. The confirm names each app before anything runs; once it's
 * confirmed, MainActivity shows [DebloatRunScreen] in its place.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DebloatScreen(
    lists: Suggestions?,
    onPick: (String) -> Unit,
    /** The ticked apps, name and package, in the order the confirm lists them. */
    onDisable: (List<ResetApp>) -> Unit,
    back: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    /** The user's own list source, null for the default; set in Settings. */
    source: String? = null,
    /** The TV's brand, then its manufacturer; the first that names a list starts the search. */
    tvMakers: List<String> = emptyList(),
    /** The TV, for the card when no list fits it. */
    tv: Device? = null,
    /** The TV's apps, to pick from by hand when no list fits it. */
    tvApps: List<AppInfo> = emptyList(),
    /** Something is running on this TV: Disable waits for it, like the Apps tab does. */
    busy: Boolean = false,
    /** Help add this TV to cleanyourtv, offered when a search finds no list. */
    onHelpAdd: () -> Unit = {},
) {
    val ready = lists as? Suggestions.Ready
    val devices = when (lists) {
        is Suggestions.NoList -> lists.devices
        is Suggestions.Ready -> lists.devices
        else -> emptyList()
    }
    // A list picked in the search; until then, the one Tellyscope found.
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var marked by rememberSaveable { mutableStateOf<String?>(null) }
    val makers = remember(devices) { listMakers(devices) }
    fun finds(term: String) = devices.filter { it.name.contains(term, ignoreCase = true) || makers.getValue(it.id).contains(term, ignoreCase = true) }
    // Nobody typed the prefill, so a maker no list names shows the full list, not "No results".
    val prefill = remember(devices) { tvMakers.map { it.trim() }.firstOrNull { it.isNotEmpty() && finds(it).isNotEmpty() } }
    var typed by rememberSaveable { mutableStateOf<String?>(null) }
    val q = typed ?: prefill.orEmpty()
    // Ticks start from the list and reset whenever it reloads.
    var ticked by remember(lists) {
        mutableStateOf(ready?.groups.orEmpty().flatMap { it.packages }.filter { it.ticked }.map { it.pkg }.toSet())
    }
    var confirming by remember { mutableStateOf(false) }
    val showing = ready?.takeIf { chosen == null || it.list.id == chosen }
    // With no list, every app that's on and not protected, by name.
    val groups = when {
        showing != null -> showing.groups
        lists is Suggestions.NoList -> listOf(
            SuggestedGroup(
                "Apps on this TV",
                tvApps.filter { it.enabled && it.protectedReason == null && !it.builtInLauncher }.sortedBy { it.label.lowercase() }
                    .map { Suggested(it.packageName, it.label, note = null, optional = false, ticked = false) },
            ),
        )
        else -> null
    }
    if (confirming && groups != null) {
        // The dialog's list is exactly what gets disabled.
        val apps = groups.flatMap { it.packages }.filter { it.pkg in ticked }
        DisableDialog(apps, onDisable = { confirming = false; onDisable(apps.map { ResetApp(it.pkg, it.label) }) }, onDismiss = { confirming = false }, enabled = !busy)
    }
    val use = { marked?.let { if (it != ready?.list?.id) { chosen = it; onPick(it) } }; searching = false }
    val keyboard = LocalSoftwareKeyboardController.current
    val goBack = { if (searching) searching = false else back() }
    BackHandler(onBack = goBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (searching) "Which TV is this?" else "Debloat") },
                navigationIcon = {
                    IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            // Above the keyboard while the search is typed into.
            Surface(tonalElevation = 3.dp, modifier = Modifier.imePadding()) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (searching) {
                        Button(onClick = use, enabled = marked != null, modifier = Modifier.fillMaxWidth()) { Text("Use this list") }
                    } else {
                        Button(
                            onClick = { confirming = true },
                            enabled = groups != null && ticked.isNotEmpty() && !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Disable ${ticked.size} ${if (ticked.size == 1) "app" else "apps"}") }
                    }
                }
            }
        },
    ) { pad ->
        if (searching) {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    OutlinedTextField(
                        q, { typed = it },
                        placeholder = { Text("Brand or model") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (q.isNotEmpty()) IconButton(onClick = { typed = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                        },
                        singleLine = true,
                        // The keyboard's action key closes it, and moves on once a TV is picked.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); if (marked != null) use() }),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                val term = q.trim()
                val shown = if (term.isEmpty()) emptyList() else finds(term).sortedBy { it.name.lowercase() }
                // What found nothing: their search, or else the TV's own maker.
                val missed = term.ifEmpty { if (typed == null) tvMakers.firstOrNull()?.trim()?.ifEmpty { null } else null }
                if (shown.isNotEmpty()) {
                    items(shown, key = { it.id }) { d -> ListRow(d, makers.getValue(d.id), d.id == marked) { marked = d.id } }
                    return@LazyColumn
                }
                if (missed != null) item { Muted("No list for \u201c$missed\u201d. Pick the closest model.") }
                // Only a connected TV can say which apps it came with.
                if (missed != null && tv?.state == DeviceState.Online) item { HelpAddCard(onHelpAdd) }
                // Every list, by maker, with the maker in view while scrolling.
                devices.groupBy { makers.getValue(it.id).lowercase().filter(Char::isLetterOrDigit) }.toSortedMap().forEach { (key, group) ->
                    stickyHeader(key = "m:$key") {
                        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                            Text(makers.getValue(group.first().id), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                    }
                    items(group.sortedBy { it.name.lowercase() }, key = { it.id }) { d -> ListRow(d, makers.getValue(d.id), d.id == marked) { marked = d.id } }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (groups == null) {
                    item {
                        Muted(
                            if (lists == Suggestions.Unavailable) "Couldn't load that list. Check the phone's internet and try again."
                            else "Loading the list…",
                        )
                    }
                    return@LazyColumn
                }
                item {
                    // A list's own kind: its model's entry, or for a maker's lists, the first of them.
                    val entry = showing?.let { r -> devices.firstOrNull { it.id == r.list.id } ?: devices.firstOrNull { squash(makers.getValue(it.id)) == squash(r.list.name) } }
                    val kind = entry?.let { listKind(it, makers.getValue(it.id)) } ?: tv?.let(::deviceKind) ?: DeviceKind.Tv
                    when {
                        showing == null -> MatchCard(kind, tv?.let(::name) ?: "This TV", "No list for this TV or its maker yet", Match.None) { marked = null; searching = true }
                        showing.byMaker -> MatchCard(kind, showing.list.name, "No list for this model, so these come from ${showing.list.name}'s other models", Match.Loose) { marked = null; searching = true }
                        showing.sdkMatches -> MatchCard(kind, showing.list.name, "This model's list · tested on ${showing.list.testedOs ?: showing.sdk?.let(::androidName)}", Match.Exact) { marked = null; searching = true }
                        else -> MatchCard(
                            kind, showing.list.name,
                            showing.list.testedOs?.let { "This model's list, tested on $it, not this TV's Android" }
                                ?: "This model's list, with no Android it was tested on",
                            Match.Loose,
                        ) { marked = null; searching = true }
                    }
                    Text(
                        when {
                            showing == null -> "Tick the apps to turn off. Apps the TV needs to run aren't shown."
                            showing.sdkMatches && !showing.byMaker -> "These apps will be turned off. Untick any you use."
                            else -> "Nothing is ticked: tick only the apps you know you don't use."
                        } + " Nothing is deleted, and History can turn each one back on.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    // Nobody should debloat from a list they didn't mean to use.
                    if (source != null && showing != null) Text(
                        "This list comes from ${sourceName(source)}, not tellyscope-devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (groups.all { it.packages.isEmpty() }) {
                        Muted(if (showing == null) "Every app left on is one the TV needs." else "Nothing on the list is still on here.")
                    }
                }
                // A list's groups are a tree: each heading ticks its whole group. The TV's own apps, with no list, stay flat.
                val tree = showing != null
                groups.filter { it.packages.isNotEmpty() }.forEach { g ->
                    val pkgs = g.packages.map { it.pkg }
                    item(key = "g:" + g.title) {
                        if (tree) GroupRow(g.title, pkgs.count { it in ticked }, pkgs.size) {
                            ticked = if (pkgs.all { it in ticked }) ticked - pkgs.toSet() else ticked + pkgs
                        } else Text(g.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                    }
                    itemsIndexed(g.packages, key = { _, p -> p.pkg }) { i, p ->
                        Row(
                            Modifier.fillMaxWidth()
                                .then(if (tree) Modifier.treeLine(MaterialTheme.colorScheme.outlineVariant, last = i == g.packages.lastIndex).padding(start = Indent) else Modifier)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { ticked = if (p.pkg in ticked) ticked - p.pkg else ticked + p.pkg },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(p.pkg in ticked, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(p.pkg, p.note, "optional: tick it if you don't use it".takeIf { p.optional }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How far a group's apps sit in from its heading: a checkbox and its gap, so theirs line up under its title. */
private val Indent = 32.dp

/** The gap between rows, which a group's line bridges. Matches the list's spacedBy. */
private val RowGap = 4.dp

/**
 * A group's heading: its checkbox ticks every app in it (optional ones too), or unticks them all
 * once they all are, with a dash while only some are.
 */
@Composable
private fun GroupRow(title: String, on: Int, of: Int, onToggle: () -> Unit) {
    val state = when (on) {
        of -> ToggleableState.On
        0 -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }
    Row(
        Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(min = 48.dp)
            .treeLine(MaterialTheme.colorScheme.outlineVariant, head = true)
            .clip(MaterialTheme.shapes.medium)
            .triStateToggleable(state, role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text("$on of $of", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * The line down a group, under the heading's checkbox. A [head]ing's starts just below its
 * checkbox; an app's bridges the gap above it, runs to its foot (to its middle on the
 * [last] one) and turns across to its checkbox.
 */
private fun Modifier.treeLine(color: Color, last: Boolean = false, head: Boolean = false) = drawBehind {
    // From the start edge, which is the right one in a right-to-left language.
    fun at(dx: Dp) = if (layoutDirection == LayoutDirection.Ltr) dx.toPx() else size.width - dx.toPx()
    val x = at(12.dp)
    val mid = size.height / 2
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(x, if (head) mid + 12.dp.toPx() else -RowGap.toPx()), Offset(x, if (last) mid else size.height), stroke)
    if (!head) drawLine(color, Offset(x, mid), Offset(at(Indent - 4.dp), mid), stroke)
}

/** No list for this TV: send its model and stock apps to cleanyourtv, where the lists come from. */
@Composable
private fun HelpAddCard(onHelpAdd: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(AddToQueue, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Not in the list? Help add this TV", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Send its model and stock apps to cleanyourtv, where Tellyscope\u2019s lists come from. You see the text first.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FilledTonalButton(onClick = onHelpAdd, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Help add this TV") }
            }
        }
    }
}

/** How well the list fits the TV: its model on its Android, near enough, or not at all. */
private enum class Match { Exact, Loose, None }

/** What the apps below came from, on top of the page, with the way out to every list. */
@Composable
private fun MatchCard(kind: DeviceKind, name: String, detail: String, match: Match, onNotThis: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (match) {
        Match.Exact -> c.secondaryContainer to c.onSecondaryContainer
        Match.Loose -> c.tertiaryContainer to c.onTertiaryContainer
        Match.None -> c.surfaceContainerHigh to c.onSurface
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(DeviceIcons.of(kind), contentDescription = kind.word())
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onNotThis) { Text("Not this TV?") }
        }
    }
}

@Composable
internal fun DisableDialog(apps: List<Suggested>, onDisable: () -> Unit, onDismiss: () -> Unit, enabled: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disable ${apps.size} ${if (apps.size == 1) "app" else "apps"}?") },
        text = {
            // Every name, so a list can't slip something in behind a count.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Each one is hidden and stops running. Nothing is deleted; every one shows in History, where you can turn it back on.")
                LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(apps, key = { it.pkg }) { a ->
                        Column {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDisable, enabled = enabled) { Text("Yes, disable them") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal val REPO = RELEASES.removeSuffix("/releases")

/** The project's GitHub Pages site: `https://OWNER.github.io/tellyscope/` for `github.com/OWNER/tellyscope`. */
internal val SITE = REPO.removePrefix("https://github.com/").split('/').let { (owner, repo) -> "https://$owner.github.io/$repo/" }

/** The demo video, the same one the README and the site play. */
internal const val DEMO_VIDEO = "https://youtu.be/N_PeDw5GiCc"

/**
 * About and licences: the installed version, links to the site, the repo and the demo video,
 * then the licences.
 * [commit] is the cleanyourtv commit the downloaded lists were built from; [version] is the
 * installed versionName, alpha suffix included.
 */
@Composable
fun AboutDialog(commit: String?, onDismiss: () -> Unit, version: String = installedVersion()) {
    val browser = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About Tellyscope") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $version", style = MaterialTheme.typography.titleSmall)
                AboutLink("Website", SITE, browser::openUri)
                AboutLink("GitHub", REPO, browser::openUri)
                AboutLink("Watch the demo on YouTube", DEMO_VIDEO, browser::openUri)
                Text("Free software under the GNU GPL v3.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Device lists are adapted from cleanyourtv by Gathin (MIT licence), " +
                        (commit?.let { "commit ${it.take(12)}." } ?: "once they have downloaded."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Uses Kadb (Apache 2.0) and spake2-java (GPL v3). Full licence texts are in the NOTICE file.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AboutLink(label: String, url: String, open: (String) -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { runCatching { open(url) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        Icon(OpenInNew, contentDescription = null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun installedVersion(): String {
    val context = LocalContext.current
    @Suppress("DEPRECATION") // the flags overload is API 33+
    return remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
}

/** "Android 11" for SDK 30; just the SDK where the release name isn't known. */
internal fun androidName(sdk: Int): String = when (sdk) {
    21 -> "Android 5.0"
    22 -> "Android 5.1"
    23 -> "Android 6"
    24 -> "Android 7"
    25 -> "Android 7.1"
    26 -> "Android 8"
    27 -> "Android 8.1"
    28 -> "Android 9"
    32 -> "Android 12L"
    in 29..31 -> "Android ${sdk - 19}"
    in 33..36 -> "Android ${sdk - 20}"
    else -> "API $sdk"
}

/** One list in the picker: the kind of device, the model and the Android it was tested on. The chosen one is tinted and ticked. */
@Composable
private fun ListRow(e: ListEntry, maker: String, selected: Boolean, onClick: (() -> Unit)?) {
    val kind = listKind(e, maker)
    val pick = if (onClick == null) Modifier else Modifier.selectable(selected, role = Role.RadioButton, onClick = onClick)
    ListItem(
        headlineContent = { Text(e.name) },
        // The maker is already in the name; say what the list was tested on instead.
        supportingContent = { Text(listOfNotNull(kind.word(), e.sdk?.let(::androidName)).joinToString(" · ")) },
        leadingContent = { Icon(DeviceIcons.of(kind), contentDescription = kind.word()) },
        trailingContent = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
        colors = if (selected) ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
            supportingColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) else ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).then(pick),
    )
}
