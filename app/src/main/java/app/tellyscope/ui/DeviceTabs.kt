// SPDX-License-Identifier: GPL-3.0-only
// Apps, Files and Tweaks icons are the Material Icons "apps", "folder" and "tune", Upload is
// "upload" and Movie is "movie" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tellyscope.adb.TvFolder
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.DeviceState
import app.tellyscope.data.Snapshot
import app.tellyscope.data.Suggestions

/** The TV page's destinations, one job each, in the bottom bar. */
enum class TvTab(val label: String) { Overview("Overview"), Apps("Apps"), Files("Files"), Tweaks("Tweaks") }

private object TabIcons {
    val Apps = icon(
        "Apps",
        "M4 8h4V4H4v4zm6 12h4v-4h-4v4zm-6 0h4v-4H4v4zm0-6h4v-4H4v4zm6 0h4v-4h-4v4zm6-10v4h4V4h-4zm-6 4h4V4h-4v4zm6 6h4v-4h-4v4zm0 6h4v-4h-4v4z",
    )
    val Folder = icon("Folder", "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z")
    val Tune = icon(
        "Tune",
        "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
        .build()
}

private fun TvTab.icon() = when (this) {
    TvTab.Overview -> Icons.Default.Home
    TvTab.Apps -> TabIcons.Apps
    TvTab.Files -> TabIcons.Folder
    TvTab.Tweaks -> TabIcons.Tune
}

/**
 * The TV page as four tabs under a bottom bar, in place of one long scroll of folded sections.
 * Every tab leads with the TV itself, big and centred, Overview with a chip per TV to switch; scrolling
 * folds it into the app bar, with the overflow on the right. On Apps, "Search apps" pins under the
 * three buttons; opening it takes the top of the screen and hides the TV until it closes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun DeviceTabsScreen(
    state: UiState,
    actions: DeviceActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    startTab: TvTab = TvTab.Overview,
) {
    val device = state.selected ?: return
    val ui = state.device
    var tab by rememberSaveable { mutableStateOf(startTab) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var appsTab by rememberSaveable { mutableStateOf(AppsTab.Running) }
    val q = state.query.trim().lowercase()
    val offline = device.state != DeviceState.Online
    val list = rememberLazyListState()
    val closeSearch = { searching = false; actions.query("") }
    BackHandler(enabled = searching, onBack = closeSearch)
    // The hero tops every tab and collapses into the bar as the tab scrolls.
    var heroHeight by remember { mutableIntStateOf(0) }
    val collapse by remember { derivedStateOf { collapsedFraction(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, heroHeight) } }
    val morph = remember { HeroMorph() }
    // A tab shorter than the screen gets blank space at its end, so its hero can still fold fully into the bar.
    // Measured only while the hero and the space are both in view, and kept while scrolled; a different list
    // (another tab, sub-tab or folder, or items coming and going) starts again from none.
    var tail by remember { mutableIntStateOf(0) }
    var measuredFor by remember { mutableStateOf<List<Any>>(emptyList()) }
    val folder by rememberUpdatedState(ui.files.folder)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo }.collect { info ->
            val content = listOf(tab, appsTab, folder, searching, info.totalItemsCount)
            if (content != measuredFor) { measuredFor = content; tail = 0 }
            val items = info.visibleItemsInfo
            val hero = items.firstOrNull { it.key == "hero" } ?: return@collect
            val end = items.firstOrNull { it.key == "tail" } ?: return@collect
            tail = tailSpace(hero.size, info.mainAxisItemSpacing, end.offset - hero.offset, info.afterContentPadding, info.viewportSize.height)
        }
    }
    // The tabs share one list, so each opens at the top with its hero, not where the last one was left.
    // "See changes" lands on Tweaks scrolled to Recent changes, the list's third item, after the hero.
    var toChanges by remember { mutableStateOf(false) }
    var shown by rememberSaveable { mutableStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab == shown) return@LaunchedEffect
        shown = tab
        list.scrollToItem(if (tab == TvTab.Tweaks && toChanges) 2 else 0)
        toChanges = false
    }
    // Opening search starts the results from the top; closing it brings the TV back into view.
    var wasSearching by rememberSaveable { mutableStateOf(searching) }
    LaunchedEffect(searching) {
        if (searching == wasSearching) return@LaunchedEffect
        wasSearching = searching
        list.scrollToItem(0)
    }

    Box {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            // Each tab's main action as Material's extended FAB: install on Apps, upload on Files.
            floatingActionButton = {
                if (!offline) when (tab) {
                    TvTab.Apps -> ExtendedFloatingActionButton(onClick = actions.pickApp, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Install app") })
                    TvTab.Files -> ExtendedFloatingActionButton(onClick = { actions.pickFile(ui.files.folder) }, icon = { Icon(TileIcons.Upload, null) }, text = { Text("Upload files") })
                    else -> {}
                }
            },
            topBar = {
                if (searching) {
                    SearchHeader(state.query, actions.query, onClose = closeSearch)
                } else {
                    TopAppBar(
                        // Each tab shows the TV big below, and in the bar once it has scrolled under it.
                        title = { TvBarTitle(device, collapse = { collapse }, morph = morph) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer, collapse),
                        ),
                        actions = {
                            if (REMOTE_ENABLED) TextButton(onClick = actions.remote, enabled = !offline) { Text("Remote") }
                            AppMenu(state, actions)
                        },
                    )
                }
            },
            bottomBar = {
                ShortNavigationBar {
                    TvTab.entries.forEach { t ->
                        ShortNavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                // Leaving Apps closes the search, so the list isn't still filtered on return.
                                if (t != TvTab.Apps && searching) closeSearch()
                                tab = t
                            },
                            icon = { Icon(t.icon(), contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { pad ->
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                state = list,
                // No top padding: the Apps tabs pin at the list's top edge, flush under the bar; the hero carries the 4dp.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val snap = ui.snapshot
                val live = snap?.takeIf { !offline }
                if (!searching) item(key = "hero") { Box(Modifier.padding(top = 4.dp)) { TvHero(state, live, actions, chips = tab == TvTab.Overview, collapse = { collapse }, morph = morph, onHeight = { heroHeight = it }) } }
                when (tab) {
                    TvTab.Overview -> {
                        val restart = state.restarting[device.id]
                        if (restart != null) {
                            item { RestartingCard(restart, state.now) }
                        } else if (device.state == DeviceState.NotAllowed) {
                            item { NotAllowedCard(actions.reconnect) }
                        } else if (offline) {
                            item { OfflineCard(device, state.now, actions.reconnect) }
                        }
                        // Away, storage is the last one seen, this session's or the saved one after a restart.
                        val used = snap?.storageUsedGb ?: device.lastKnown?.storageUsedGb
                        val total = snap?.storageTotalGb ?: device.lastKnown?.storageTotalGb
                        if (live != null) {
                            item { SystemWidget(live) }
                        } else if (offline && used != null && total != null) {
                            item { SystemWidget(used, total, null, asOf = device.lastSeen?.let { Copy.ago(it, state.now) } ?: "the last visit") }
                        } else if (offline) {
                            item { Muted(Copy.noStats(device.state)) }
                        }
                        val debloat = debloatState(ui)
                        if (!offline) {
                            item {
                                ActionTiles(
                                    actions,
                                    count = debloat.count,
                                    todo = debloat.todo,
                                    disabling = ui.busy == DISABLING,
                                    // With nothing left on the list, Debloat leads to the apps for one-off changes.
                                    onDebloat = if (debloat.todo) actions.debloat else ({ tab = TvTab.Apps }),
                                )
                            }
                        }
                        item { SectionTitle("Suggested for this TV") }
                        item { SuggestedCard(ui.lists, debloat, offline, onChanges = { toChanges = true; tab = TvTab.Tweaks }) }
                        item { AndroidCard(state, snap, offline, onUpdates = actions.checkUpdates) }
                    }
                    TvTab.Apps -> {
                        val procs = snap?.procs.orEmpty()
                        fun rows(apps: List<AppInfo>) = items(apps, key = { it.packageName }) { app ->
                            AppRow(app, procs, ui.pending, ui.busy == app.packageName, actions, homeLabel(ui.apps, app), locked = ui.busy != null)
                        }
                        if (searching) {
                            // One flat list of every matching app, whichever button it sits under; each row says
                            // whether it's running, off or protected.
                            val found = ui.apps
                                .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }
                                .sortedWith(compareBy({ !it.enabled }, { it.system }, { it.label }))
                            if (found.isEmpty()) item { EmptyLine(q) }
                            rows(found)
                        } else {
                            stickyHeader(key = "appsTabs") {
                                PinnedSegments(
                                    AppsTab.entries.map { it.label }, appsTab.ordinal,
                                    lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer, collapse), inset = 16.dp,
                                    below = { SearchPill(enabled = !offline) { searching = true } },
                                ) { appsTab = AppsTab.entries[it] }
                            }
                            val split = splitApps(ui.apps, procs, appsTab)
                            if (split.apps.isEmpty() && split.system.isEmpty()) item {
                                // Away, the TV can't say what's running, rather than nothing is.
                                if (appsTab == AppsTab.Running && live == null) EmptyState(DeviceIcons.Tv, Copy.TV_OFFLINE, Copy.RUNNING_OFFLINE) else EmptyLine(q)
                            }
                            rows(split.apps)
                            if (split.system.isNotEmpty()) {
                                val folded = appsTab in state.foldedSystem
                                item(key = "system") { SystemHeader(split.system.size, folded) { actions.foldSystem(appsTab, it) } }
                                if (!folded) rows(split.system)
                            }
                        }
                    }
                    TvTab.Files -> {
                        // Pinned under the bar once the hero has folded into it, like the Apps tabs.
                        stickyHeader(key = "folders") {
                            PinnedSegments(TvFolder.entries.map(Copy::folder), ui.files.folder.ordinal, lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surfaceContainer, collapse), inset = 16.dp) {
                                if (!offline) actions.folder(TvFolder.entries[it])
                            }
                        }
                        val folder = ui.files.folder
                        when {
                            offline -> item { EmptyState(DeviceIcons.Tv, Copy.TV_OFFLINE, "Connect to the TV to see its files.") }
                            ui.files.error == null && ui.files.list?.isEmpty() == true -> item {
                                EmptyState(
                                    if (folder == TvFolder.MOVIES) TileIcons.Movie else TabIcons.Folder,
                                    "Nothing in ${Copy.folder(folder)} yet",
                                    "Files you send land here.",
                                )
                            }
                            else -> item { FilesBlock(ui.files, state.now, actions) }
                        }
                    }
                    TvTab.Tweaks -> {
                        if (offline) item { Muted("Connect to the TV to change its settings.") }
                        else item { Panel(Copy.TWEAKS) { TweaksBlock(ui, actions) } }
                        item {
                            Panel("Recent changes") {
                                HistoryList(ui.history, state.now, actions.undo, restoring = ui.busy == RESTORING, onRestoreAll = actions.restoreAll, busy = ui.busy != null)
                            }
                        }
                    }
                }
                item(key = "tail") { Spacer(Modifier.height(with(LocalDensity.current) { tail.toDp() }).testTag("tail")) }
            }
            if (!offline) ui.request?.let { RequestDialog(it, ui.busy == ANSWERING, actions.answerRequest, actions.dismissRequest) }
            ui.sideload?.let { SideloadDialog(it, actions) }
            ui.transfer?.let { TransferDialog(it, actions.cancelTransfer) }
        }
        // Over everything, so the cookie and name can pass from the list into the bar.
        if (!searching) HeroMorphOverlay(device, morph) { collapse }
    }
}

/** A titled card for a tab's block, not folded: each tab has room for it. */
@Composable
private fun Panel(title: String, body: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            body()
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The overflow: the TV on top, so it's clear which TV its items touch, then two blocks, this TV's
 * items and then the app-wide ones.
 */
@Composable
private fun AppMenu(state: UiState, actions: DeviceActions) {
    var open by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val device = state.selected ?: return
    if (about) AboutDialog(
        when (val l = state.device.lists) {
            is Suggestions.NoList -> l.commit
            is Suggestions.Ready -> l.commit
            else -> null
        },
        onDismiss = { about = false },
    )
    if (forgetting) ForgetDialog(name(device), onForget = { forgetting = false; actions.forget(device.id) }, onDismiss = { forgetting = false })
    if (renaming) RenameDialog(device.nickname.orEmpty(), device.model ?: networkName(device), onSave = { renaming = false; actions.rename(device.id, it) }, onDismiss = { renaming = false })
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More options") }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.large) {
            Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cookie(device, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(name(device), style = MaterialTheme.typography.titleSmall)
                    val made = makerLine(device)
                    if (made.isNotEmpty()) Text(made, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            MenuBlock {
                DropdownMenuItem(text = { Text("Rename this TV") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { open = false; renaming = true })
                DropdownMenuItem(text = { Text("Forget this TV") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { open = false; forgetting = true })
                DropdownMenuItem(text = { Text("Advanced") }, leadingIcon = { Icon(Icons.Default.List, null) }, onClick = { open = false; actions.advanced() })
            }
            Spacer(Modifier.height(4.dp))
            MenuBlock {
                DropdownMenuItem(text = { Text("Settings") }, leadingIcon = { Icon(Icons.Default.Settings, null) }, onClick = { open = false; actions.settings() })
                DropdownMenuItem(text = { Text("About & licences") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { open = false; about = true })
            }
        }
    }
}

/** One group of the menu, as a slightly darker rounded block inside it. */
@Composable
private fun MenuBlock(items: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Column { items() } }
}

/** "Search apps" under the Apps buttons, pinned with them; a tap opens the search at the top. */
@Composable
private fun SearchPill(enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text("Search apps", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Search, open: the field on top with ← to close and ✕ to clear, and the results get the rest of
 * the screen. The keyboard comes up with it.
 */
@Composable
private fun SearchHeader(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
                TextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
            }
        }
    }
}

/** Where the suggested list stands on this TV, worked out from the live app list. */
internal data class DebloatState(
    /** Apps on the list that are installed here and Tellyscope may turn off, on or off. */
    val total: Int,
    /** Of those, the ones still on. */
    val on: Int,
    /** Apps Tellyscope turned off that are on again, often after a TV update. */
    val back: Int,
    /** Apps on the list still on, plus any that came back: the number on the Debloat tile. */
    val count: Int,
    /** Debloat has something to show: apps to turn off, or that this TV has no list. */
    val todo: Boolean,
)

internal fun debloatState(ui: DeviceUi): DebloatState {
    // History lives on this phone, so it only knows what this phone turned off.
    val ours = ui.history.filter { it.action == Action.Disable && it.undoneAt == null }.map { it.packageName }.toSet()
    val back = ui.apps.filter { it.enabled && it.packageName in ours }.map { it.packageName }.toSet()
    val ready = ui.lists as? Suggestions.Ready
    val listed = ready?.list?.let { l -> l.groups.flatMap { g -> g.packages.map { it.pkg } }.filter { it !in l.keep }.toSet() }.orEmpty()
    val candidates = ui.apps.filter { it.packageName in listed && (!it.enabled || it.protectedReason == null) }
    val on = candidates.filter { it.enabled }.map { it.packageName }.toSet()
    val count = (on + back).size
    return DebloatState(candidates.size, on.size, back.size, count, count > 0 || ui.lists is Suggestions.NoList)
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.titleMedium)
}

/**
 * The list in one line of plain text, with See changes beside it. When apps Tellyscope turned off
 * are on again, one red line says so; the Debloat tile above carries the count and the fix.
 */
@Composable
private fun SuggestedCard(lists: Suggestions?, d: DebloatState, offline: Boolean, onChanges: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val apps = { n: Int -> if (n == 1) "1 app" else "$n apps" }
    Card(colors = CardDefaults.cardColors(containerColor = c.surfaceContainerLow), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (lists) {
                        is Suggestions.Ready -> when {
                            d.total == 0 -> "Nothing from the ${lists.list.name} list is on this TV"
                            d.on == 0 -> if (d.total == 1) "The suggested app is off" else "All ${d.total} suggested apps are off"
                            else -> "${d.total - d.on} of ${d.total} suggested apps are off"
                        }
                        is Suggestions.NoList -> "No list for this TV yet"
                        Suggestions.Unavailable -> "Couldn't load the device lists"
                        null -> if (offline) "Connect to the TV to see its suggestions" else "Looking up this TV's list\u2026"
                    },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                TextButton(onClick = onChanges) { Text("See changes") }
            }
            if (d.back > 0) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Action required: ") }
                        append("${apps(d.back)} came back on")
                    },
                    Modifier.padding(end = 8.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.bodyLarge, color = c.error,
                )
            }
            val note = when {
                lists is Suggestions.Unavailable -> "Check the phone's internet and open this TV again."
                lists is Suggestions.NoList -> "Tap Debloat to pick the apps to turn off by hand."
                lists is Suggestions.Ready && lists.byMaker -> "No list for this exact model, so these come from ${lists.list.name}'s other TVs."
                lists is Suggestions.Ready && !d.todo -> "Debloat now opens Apps for one-off changes."
                else -> null
            }
            note?.let { Text(it, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant) }
            if (lists is Suggestions.Ready) {
                Text("Lists adapted from cleanyourtv (MIT).", Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
    }
}

/** Which Android and when it last updated, with a way to make the TV look for a newer one. */
@Composable
private fun AndroidCard(state: UiState, snap: Snapshot?, offline: Boolean, onUpdates: () -> Unit) {
    val device = state.selected ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.large) {
        StatusRow(
            Icons.Default.Info,
            device.android?.let { "Android $it" } ?: "Android",
            (snap?.softwareMonth ?: device.lastKnown?.softwareMonth)?.let { "Updated ${Copy.month(it)}" },
            "Check for updates", !offline, onUpdates,
        )
    }
}

@Composable
private fun StatusRow(icon: ImageVector, title: String, sub: String?, button: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = sub?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { TextButton(onClick = onClick, enabled = enabled) { Text(button) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}


private object TileIcons {
    // Absolute commands only: Compose's path parser misplaces a relative move after a close.
    val Upload = icon("Upload", "M9 16H15V10H19L12 3L5 10H9ZM5 18H19V20H5Z")
    val Movie = icon("Movie", "M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
        .build()
}
