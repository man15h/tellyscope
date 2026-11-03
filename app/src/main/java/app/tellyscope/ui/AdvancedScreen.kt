// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.AppInfo
import app.tellyscope.data.AppNames
import app.tellyscope.data.AppTraffic
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.NetworkInfo
import app.tellyscope.data.Proc
import app.tellyscope.data.Snapshot
import app.tellyscope.data.Socket
import app.tellyscope.data.resolve

/**
 * Everything with a number in it, kept off the TV's page, on four tabs: This TV (the stats and the
 * TV's facts), then CPU, RAM and Network, each a summary card over its apps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(
    state: UiState,
    back: () -> Unit,
    terminal: ShellTerminal? = null,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val device = state.selected ?: return
    val ui = state.device
    val snap = ui.snapshot
    val offline = device.state != DeviceState.Online
    val androidSystem = Copy.ANDROID_SYSTEM.resolve()
    val rows = remember(ui.apps, snap?.procs, ui.network, androidSystem) { appRows(ui.apps, snap?.procs.orEmpty(), ui.network, androidSystem) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var shellOpen by rememberSaveable { mutableStateOf(false) }
    var shellFrom by remember { mutableStateOf(Offset.Zero) }
    // Offline: the last stats this phone saw, greyed and dated, rather than blanks.
    val staleSince = if (offline) state.clock() else null

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(Copy.ADVANCED_MONITOR.resolve()) },
                    navigationIcon = {
                        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.advanced_back)) }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                Header(state, Modifier.padding(horizontal = 16.dp), onShell = { shellFrom = it; shellOpen = true })
                PrimaryTabRow(selectedTabIndex = tab, Modifier.padding(top = 12.dp)) {
                    listOf(R.string.advanced_this_tv, R.string.advanced_cpu, R.string.advanced_ram, R.string.advanced_network).forEachIndexed { i, label ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(label), maxLines = 1) })
                    }
                }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (snap == null) {
                        item {
                            Text(
                                if (offline) Copy.noStats(device.state).resolve() else stringResource(R.string.advanced_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    when (tab) {
                        0 -> thisTv(device, snap, ui.recent, staleSince, rows, ui.network, onCpu = { tab = 1 }, onApp = { sheet = it })
                        1 -> snap?.let { cpuTab(it, ui.recent, staleSince, rows, onApp = { n -> sheet = n }) }
                        2 -> snap?.let { ramTab(it, ui.recent, staleSince, rows, onApp = { n -> sheet = n }) }
                        else -> networkTab(snap, ui.recent, staleSince, rows, ui.network, onApp = { sheet = it })
                    }
                }
            }
        }
        rows.firstOrNull { it.name == sheet }?.let { AppSheet(it, onClose = { sheet = null }) }
        terminal?.let { t -> ShellOverlay(shellOpen, shellFrom, device, !offline, t, onClose = { shellOpen = false }) }
    }
}

/** One app on the CPU, RAM and Network tabs: its processes, connections and data used. */
private class AppRow(val name: String, val app: AppInfo?, val system: Boolean, val procs: List<Proc>, val sockets: List<Socket>, val traffic: AppTraffic?) {
    val cpu = procs.sumOf { it.cpuPercent }
    val ramMb = procs.sumOf { it.ramMb }
    val dataMb = traffic?.let { it.rxMb + it.txMb } ?: 0
    val running get() = procs.isNotEmpty()
}

/**
 * Every app that runs, holds a connection or has used data since boot, by name: the process list,
 * connections and data used each name an app the same way.
 */
private fun appRows(apps: List<AppInfo>, procs: List<Proc>, network: NetworkInfo?, androidSystem: String): List<AppRow> {
    val byPackage = apps.associateBy { it.packageName }
    val byLabel = apps.associateBy { it.label }
    val running = procs.groupBy { p -> p.packageName?.let { byPackage[it]?.label ?: AppNames.fallback(it) } ?: androidSystem }
    val sockets = network?.sockets.orEmpty().groupBy { it.app }
    val traffic = network?.traffic.orEmpty().associateBy { it.app }
    return (running.keys + sockets.keys + traffic.keys).distinct().map { name ->
        val ps = running[name].orEmpty()
        AppRow(name, ps.firstNotNullOfOrNull { it.packageName?.let(byPackage::get) } ?: byLabel[name], name == androidSystem, ps, sockets[name].orEmpty(), traffic[name])
    }
}

/**
 * Which TV this is: its cookie, its name on the network and the address Tellyscope reaches it at,
 * with the Shell button. [onShell] gets the button's centre in the root, where Shell grows from.
 */
@Composable
private fun Header(state: UiState, modifier: Modifier, onShell: (Offset) -> Unit) {
    val device = state.selected ?: return
    val c = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val address = stringResource(R.string.advanced_address, device.hit.ip, device.hit.port)
    var center by remember { mutableStateOf(Offset.Zero) }
    Card(modifier, colors = CardDefaults.cardColors(containerColor = c.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Cookie(device, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.hit.hostname ?: name(device), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), maxLines = 1)
                    IconButton(onClick = { clipboard.setText(AnnotatedString(address)) }, Modifier.size(32.dp)) {
                        Icon(CopyIcon, stringResource(R.string.advanced_copy_address), Modifier.size(16.dp), tint = c.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onShell(center) },
                modifier = Modifier.onGloballyPositioned { center = it.boundsInRoot().center },
            ) {
                Icon(TerminalIcon, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.shell_open))
            }
        }
    }
}

/** A summary card; greyed under an "Offline · as of" line when [staleSince] is set. */
@Composable
private fun SummaryCard(staleSince: Long?, at: Long, body: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (staleSince != null) {
                Text(
                    stringResource(R.string.advanced_offline_as_of, Copy.ago(at, staleSince).resolve()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.alpha(if (staleSince != null) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { body() }
        }
    }
}

// ---- This TV ----

private fun LazyListScope.thisTv(
    device: Device,
    snap: Snapshot?,
    recent: List<Snapshot>,
    staleSince: Long?,
    rows: List<AppRow>,
    network: NetworkInfo?,
    onCpu: () -> Unit,
    onApp: (String) -> Unit,
) {
    snap?.let { s -> item { Stats(s, recent, staleSince, rows, onCpu, onApp) } }
    item { Facts(device, snap, network) }
}

/** CPU, memory and storage as rings, the network's last minute, and what's busy when the CPU is. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Stats(s: Snapshot, recent: List<Snapshot>, staleSince: Long?, rows: List<AppRow>, onCpu: () -> Unit, onApp: (String) -> Unit) {
    val res = LocalContext.current.resources
    SummaryCard(staleSince, s.at) {
        Row(Modifier.fillMaxWidth()) {
            Gauge(stringResource(R.string.advanced_cpu), s.cpuPercent / 100f, chipLine(s), Modifier.weight(1f).clickable(onClick = onCpu))
            Gauge(stringResource(R.string.advanced_memory), fraction(s.ramUsedMb.toDouble(), s.ramTotalMb.toDouble()), Copy.gbOf(s.ramUsedMb / 1024.0, s.ramTotalMb / 1024.0).resolve(), Modifier.weight(1f))
            val used = s.storageUsedGb
            val total = s.storageTotalGb
            Gauge(
                stringResource(R.string.advanced_storage),
                if (used != null && total != null) fraction(used, total) else null,
                if (used != null && total != null) Copy.gbOf(used, total).resolve() else null,
                Modifier.weight(1f),
            )
        }
        Speeds(s, MaterialTheme.typography.titleSmall)
        Graph(recent.map { it.at }, recent.map { it.rxBytesPerSec?.toFloat() ?: 0f }, recent.map { it.txBytesPerSec?.toFloat() ?: 0f })
        // The busiest apps only when the CPU is busy enough for them to matter.
        val busiest = rows.filter { it.cpu >= 1 }.sortedByDescending { it.cpu }.take(3).takeIf { s.cpuPercent > 33 }.orEmpty()
        if (busiest.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(stringResource(R.string.advanced_busiest), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val max = busiest.maxOf { it.cpu }.coerceAtLeast(1.0)
            busiest.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { onApp(r.name) }, verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(r, 32.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Bar((r.cpu / max).toFloat(), Modifier.width(96.dp))
                    Text(res.getString(R.string.advanced_percent_whole, r.cpu), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/** `S905X4 · 4 cores`, either half left out when the TV doesn't say. */
@Composable
private fun chipLine(s: Snapshot): String? = listOfNotNull(
    s.chip,
    s.cores.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.advanced_cores, it, it) },
).joinToString(" · ").ifEmpty { null }

private fun fraction(used: Double, total: Double) = if (total > 0) (used / total).toFloat().coerceIn(0f, 1f) else 0f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Gauge(label: String, frac: Float?, under: String?, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    val shown by animateFloatAsState(frac ?: 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "gauge")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            CircularWavyProgressIndicator(
                progress = { shown },
                Modifier.size(80.dp),
                color = if ((frac ?: 0f) >= Copy.FULL) c.error else c.primary,
            )
            Text(
                frac?.let { stringResource(R.string.advanced_percent, (it * 100).toInt()) } ?: "—",
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(under ?: "", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `↓ 1.1 MB/s` and `↑ 96 KB/s`: at either end over a graph, side by side as a headline when [apart] is false. */
@Composable
private fun Speeds(s: Snapshot, style: TextStyle, apart: Boolean = true) {
    val down = stringResource(R.string.advanced_down, s.rxBytesPerSec?.let { Copy.speed(it).resolve() } ?: "—")
    val up = stringResource(R.string.advanced_up, s.txBytesPerSec?.let { Copy.speed(it).resolve() } ?: "—")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (apart) Arrangement.SpaceBetween else Arrangement.spacedBy(24.dp)) {
        Text(down, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        Text(up, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/** The facts under the card, two to a row; the MAC gets a row of its own so it's never cut off. */
@Composable
private fun Facts(device: Device, snap: Snapshot?, n: NetworkInfo?) {
    val dash = "—"
    val clipboard = LocalClipboardManager.current
    var dbm by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row {
            Fact(Icons2.Schedule, stringResource(R.string.advanced_on_for), snap?.let { Copy.upFor(it.uptimeSeconds).resolve() } ?: dash, Modifier.weight(1f))
            Fact(
                Icons2.Tv,
                stringResource(R.string.advanced_screen),
                snap?.screenOn?.let { stringResource(if (it) R.string.advanced_screen_on else R.string.advanced_screen_off) } ?: dash,
                Modifier.weight(1f),
            )
        }
        Row {
            Fact(Icons2.Android, stringResource(R.string.advanced_android), (device.android ?: device.lastKnown?.android)?.let { stringResource(R.string.tabs_android_version, it) } ?: dash, Modifier.weight(1f))
            Fact(Icons2.Update, stringResource(R.string.advanced_updated), (snap?.softwareMonth ?: device.lastKnown?.softwareMonth)?.let(Copy::shortMonth) ?: dash, Modifier.weight(1f))
        }
        val wifi = n?.iface?.takeIf { it.isNotEmpty() }?.let { it == "wifi" || it.startsWith("wlan") } ?: true
        Row {
            Fact(
                Icons2.Wifi,
                stringResource(if (wifi) R.string.advanced_wifi else R.string.advanced_connection),
                if (wifi) n?.ssid ?: dash else stringResource(R.string.advanced_ethernet),
                Modifier.weight(1f),
            )
            if (wifi) {
                // In words with the link speed; the dBm on a long press, for people who want it.
                val strength = n?.rssi?.let { stringResource(if (dbm) signalDbm(it) else signalWord(it), it) }
                val value = listOfNotNull(strength, n?.linkMbps?.let { stringResource(R.string.advanced_mbps, it) }).joinToString(" · ").ifEmpty { dash }
                Fact(Icons2.Signal, stringResource(R.string.advanced_signal), value, Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = { dbm = !dbm }))
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        n?.mac?.let { mac ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Fact(Icons2.Tag, stringResource(R.string.advanced_mac), mac, Modifier.weight(1f), mono = true)
                IconButton(onClick = { clipboard.setText(AnnotatedString(mac)) }) {
                    Icon(CopyIcon, stringResource(R.string.advanced_copy_mac), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = c.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.titleMedium.let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- CPU, RAM, Network ----

private fun LazyListScope.cpuTab(s: Snapshot, recent: List<Snapshot>, staleSince: Long?, rows: List<AppRow>, onApp: (String) -> Unit) {
    item { CpuCard(s, recent, staleSince) }
    appList(rows.filter { it.running }.sortedByDescending { it.cpu }, value = { it.cpu }, onApp) { r, res -> res.getString(R.string.advanced_percent_whole, r.cpu) }
}

private fun LazyListScope.ramTab(s: Snapshot, recent: List<Snapshot>, staleSince: Long?, rows: List<AppRow>, onApp: (String) -> Unit) {
    item { RamCard(s, recent, staleSince) }
    appList(rows.filter { it.running }.sortedByDescending { it.ramMb }, value = { it.ramMb.toDouble() }, onApp) { r, res -> Copy.mb(r.ramMb).resolve(res) }
}

private fun LazyListScope.networkTab(s: Snapshot?, recent: List<Snapshot>, staleSince: Long?, rows: List<AppRow>, network: NetworkInfo?, onApp: (String) -> Unit) {
    s?.let { item { NetworkCard(it, recent, staleSince, network) } }
    // Apps that have used data since boot, running or not, then the running ones that haven't.
    val shown = rows.filter { it.running || it.dataMb > 0 }.sortedByDescending { it.dataMb }
    appList(shown, value = { it.dataMb.toDouble() }, onApp, heading = false, under = { r ->
        if (r.running) pluralStringResource(R.plurals.advanced_connections, r.sockets.size, r.sockets.size) else stringResource(R.string.advanced_not_running)
    }) { r, res ->
        res.getString(R.string.advanced_down_up, Copy.mb(r.traffic?.rxMb ?: 0).resolve(res), Copy.mb(r.traffic?.txMb ?: 0).resolve(res))
    }
}

/** The apps under a summary card, each with a bar against the biggest; a tap opens its sheet. */
private fun LazyListScope.appList(
    rows: List<AppRow>,
    value: (AppRow) -> Double,
    onApp: (String) -> Unit,
    heading: Boolean = true,
    under: @Composable (AppRow) -> String = { r -> pluralStringResource(R.plurals.advanced_processes, r.procs.size, r.procs.size) },
    label: (AppRow, android.content.res.Resources) -> String,
) {
    if (rows.isEmpty()) return
    if (heading) {
        item {
            val n = rows.count { it.running }
            Text(pluralStringResource(R.plurals.advanced_apps_running, n, n), Modifier.padding(start = 8.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall)
        }
    }
    item {
        val res = LocalContext.current.resources
        val max = rows.maxOf(value).coerceAtLeast(1.0)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                rows.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onApp(r.name) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RowIcon(r, 48.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(under(r), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(label(r, res), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                            Spacer(Modifier.height(6.dp))
                            Bar((value(r) / max).toFloat(), Modifier.width(104.dp), fromEnd = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CpuCard(s: Snapshot, recent: List<Snapshot>, staleSince: Long?) {
    val c = MaterialTheme.colorScheme
    SummaryCard(staleSince, s.at) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.advanced_percent, s.cpuPercent), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                chipLine(s)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
            }
            // Where the time went: apps and Android add up to the big number; waiting on storage is on top.
            if (s.cpuApps != null) {
                Column(horizontalAlignment = Alignment.End) {
                    listOf(R.string.advanced_split_apps to s.cpuApps, R.string.advanced_split_system to s.cpuSystem, R.string.advanced_split_waiting to s.cpuWaiting).forEach { (label, v) ->
                        Row {
                            Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                            Text(v?.let { stringResource(R.string.advanced_percent, it) } ?: "—", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
        Graph(recent.map { it.at }, recent.map { it.cpuPercent.toFloat() }, max = 100f)
        s.cores.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                pair.forEachIndexed { i, pct ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row {
                            Text(stringResource(R.string.advanced_core, row * 2 + i + 1), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(stringResource(R.string.advanced_percent, pct), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                        }
                        Bar(pct / 100f, Modifier.fillMaxWidth())
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        s.clockMhz?.let { Text(stringResource(R.string.advanced_ghz, it / 1000.0), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
    }
}

@Composable
private fun RamCard(s: Snapshot, recent: List<Snapshot>, staleSince: Long?) {
    val c = MaterialTheme.colorScheme
    val res = LocalContext.current.resources
    val total = s.ramTotalMb.coerceAtLeast(1)
    SummaryCard(staleSince, s.at) {
        Column {
            Text(stringResource(R.string.advanced_percent, s.ramUsedMb * 100 / total), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
            Text(Copy.gbOf(s.ramUsedMb / 1024.0, s.ramTotalMb / 1024.0).resolve(), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
        Graph(recent.map { it.at }, recent.map { it.ramUsedMb * 100f / it.ramTotalMb.coerceAtLeast(1) }, max = 100f)
        // In use, then cache the TV gives back when asked, then memory holding nothing.
        val parts = listOf(
            Triple(R.string.advanced_in_use, s.ramUsedMb, c.primary),
            Triple(R.string.advanced_cached, s.ramCachedMb, c.secondary),
            Triple(R.string.advanced_free, s.ramFreeMb, c.surfaceContainerHighest),
        )
        Row(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(c.surfaceContainerHighest), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            parts.take(2).filter { it.second > 0 }.forEach { (_, mb, color) ->
                Box(Modifier.weight(mb.toFloat()).fillMaxHeight().background(color))
            }
            val rest = total - s.ramUsedMb - s.ramCachedMb
            if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            parts.forEach { (label, mb, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(Copy.mb(mb).resolve(res), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
        // Zram, only on a TV that has it.
        if (s.swapTotalMb > 0) {
            Text(
                stringResource(R.string.advanced_swap, Copy.mb(s.swapUsedMb).resolve(res), Copy.mb(s.swapTotalMb).resolve(res)),
                style = MaterialTheme.typography.bodyMedium,
                color = c.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetworkCard(s: Snapshot, recent: List<Snapshot>, staleSince: Long?, n: NetworkInfo?) {
    val c = MaterialTheme.colorScheme
    SummaryCard(staleSince, s.at) {
        Column {
            Speeds(s, MaterialTheme.typography.headlineSmall, apart = false)
            val wifi = n?.iface?.takeIf { it.isNotEmpty() }?.let { it == "wifi" || it.startsWith("wlan") }
            when {
                wifi == false -> stringResource(R.string.advanced_ethernet)
                n?.ssid != null -> stringResource(R.string.advanced_wifi_on, n.ssid)
                wifi == true -> stringResource(R.string.advanced_wifi)
                else -> null
            }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
        }
        Graph(recent.map { it.at }, recent.map { it.rxBytesPerSec?.toFloat() ?: 0f }, recent.map { it.txBytesPerSec?.toFloat() ?: 0f })
        val rx = s.rxTotalBytes
        val tx = s.txTotalBytes
        if (rx != null && tx != null) {
            val open = n?.sockets?.size
            val total = stringResource(R.string.advanced_since_boot, Copy.bytes(rx).resolve(), Copy.bytes(tx).resolve())
            Text(
                listOfNotNull(total, open?.let { pluralStringResource(R.plurals.advanced_open_connections, it, it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.onSurfaceVariant,
            )
        }
    }
}

/**
 * The last minute: [area] filled, [line] drawn over it, against [max] or the highest reading.
 * Each reading sits at its time in [at], the newest at "now", so a graph that just started grows
 * out of the right edge and readings taken further apart sit further apart.
 */
@Composable
private fun Graph(at: List<Long>, area: List<Float>, line: List<Float>? = null, max: Float? = null) {
    val c = MaterialTheme.colorScheme
    val fill = c.primary.copy(alpha = 0.25f)
    val edge = c.primary
    val second = c.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val top = max ?: ((area + line.orEmpty()).maxOrNull()?.takeIf { it > 0f } ?: 1f)
            val now = at.lastOrNull() ?: 0L
            fun points(v: List<Float>) = v.mapIndexed { i, y ->
                Offset(size.width * (1f - (now - at[i]).toFloat() / RECENT_MS), size.height * (1f - (y / top).coerceIn(0f, 1f)))
            }
            val a = points(area)
            if (a.size >= 2) {
                val edgePath = Path().apply { moveTo(a[0].x, a[0].y); a.drop(1).forEach { lineTo(it.x, it.y) } }
                val areaPath = Path().apply { addPath(edgePath); lineTo(a.last().x, size.height); lineTo(a[0].x, size.height); close() }
                drawPath(areaPath, fill)
                drawPath(edgePath, edge, style = Stroke(2.dp.toPx()))
            }
            val l = points(line.orEmpty())
            if (l.size >= 2) {
                drawPath(Path().apply { moveTo(l[0].x, l[0].y); l.drop(1).forEach { lineTo(it.x, it.y) } }, second, style = Stroke(1.5.dp.toPx()))
            }
        }
        Row {
            Text(stringResource(R.string.advanced_minute_ago), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            Text(stringResource(R.string.advanced_now), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
        }
    }
}

/** A rounded bar, red when nearly full; [fromEnd] grows it from the right, under a right-aligned number. */
@Composable
private fun Bar(frac: Float, modifier: Modifier, fromEnd: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val shown by animateFloatAsState(frac.coerceIn(0f, 1f), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "bar")
    Box(modifier.height(6.dp).clip(CircleShape).background(c.surfaceContainerHighest), contentAlignment = if (fromEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        // A sliver still shows, so an app at 0.4% isn't mistaken for one with no bar at all.
        if (shown > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceAtLeast(0.04f)).clip(CircleShape).background(if (frac >= Copy.FULL && !fromEnd) c.error else c.primary))
    }
}

/** The app's own icon, the Android robot for Android system, or a letter. */
@Composable
private fun RowIcon(r: AppRow, size: Dp) {
    val c = MaterialTheme.colorScheme
    when {
        r.app != null -> AppIcon(r.app, Modifier.size(size))
        else -> Box(Modifier.size(size).clip(MaterialTheme.shapes.medium).background(c.secondaryContainer), contentAlignment = Alignment.Center) {
            if (r.system) Icon(Icons2.Android, null, Modifier.size(size / 2), tint = c.onSecondaryContainer)
            else Text(r.name.take(1).uppercase(), color = c.onSecondaryContainer, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** One app's processes and connections, from any tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(r: AppRow, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val res = LocalContext.current.resources
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val number = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowIcon(r, 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(r.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                res.getString(R.string.advanced_cpu_share, r.cpu).takeIf { r.running },
                                Copy.mb(r.ramMb).resolve(res).takeIf { r.running },
                                r.traffic?.let { res.getString(R.string.advanced_since_boot_short, Copy.mb(it.rxMb + it.txMb).resolve(res)) },
                            ).ifEmpty { listOf(res.getString(R.string.advanced_not_running)) }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.onSurfaceVariant,
                        )
                    }
                }
            }
            if (r.procs.isNotEmpty()) {
                item { SheetHeading(stringResource(R.string.advanced_processes_n, r.procs.size)) }
                items(r.procs.sortedByDescending { it.cpuPercent }, key = { it.pid }) { p ->
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.pid.toString(), Modifier.width(56.dp), style = mono, color = c.onSurfaceVariant)
                        // The end of a long name tells processes apart: …youtube.tv:player.
                        Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        Text(res.getString(R.string.advanced_percent_whole, p.cpuPercent), Modifier.width(48.dp), style = number, textAlign = TextAlign.End)
                        Text(Copy.mb(p.ramMb).resolve(res), Modifier.width(72.dp), style = number, textAlign = TextAlign.End)
                    }
                }
            }
            if (r.sockets.isNotEmpty()) {
                item { SheetHeading(stringResource(R.string.advanced_connections_n, r.sockets.size)) }
                items(r.sockets) { s ->
                    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.remote, Modifier.weight(1f), style = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(tcpState(s.state)), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, Modifier.padding(top = 24.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@StringRes
private fun tcpState(state: String) = when (state) {
    "established" -> R.string.advanced_established
    "connecting" -> R.string.advanced_connecting
    else -> R.string.advanced_closing
}

/** The signal in words. */
@StringRes
private fun signalWord(rssi: Int) = when {
    rssi >= -55 -> R.string.advanced_signal_strong_word
    rssi >= -67 -> R.string.advanced_signal_good_word
    rssi >= -75 -> R.string.advanced_signal_weak_word
    else -> R.string.advanced_signal_very_weak_word
}

/** The same words with the dBm, as the resource's argument. */
@StringRes
private fun signalDbm(rssi: Int) = when {
    rssi >= -55 -> R.string.advanced_signal_strong
    rssi >= -67 -> R.string.advanced_signal_good
    rssi >= -75 -> R.string.advanced_signal_weak
    else -> R.string.advanced_signal_very_weak
}

/** Material Symbols the core icon set lacks. */
private object Icons2 {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.NonZero, fill = SolidColor(Color.Black))
        .build()

    val Schedule = icon(
        "Schedule",
        "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
    )
    val Tv = icon("Tv", "M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 1.99-.9 1.99-2L23 5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z")
    val Android = icon(
        "Android",
        "M17.6 9.48l1.84-3.18c.16-.31.04-.69-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24c-2.86-1.21-6.08-1.21-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48C3.3 11.25 1.28 14.44 1 18h22c-.28-3.56-2.3-6.75-5.4-8.52zM7 15.25c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25zm10 0c-.69 0-1.25-.56-1.25-1.25s.56-1.25 1.25-1.25 1.25.56 1.25 1.25-.56 1.25-1.25 1.25z",
    )
    val Update = icon(
        "SystemUpdate",
        "M17 1.01L7 1c-1.1 0-1.99.9-1.99 2v18c0 1.1.89 2 1.99 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14zm-1-6h-3V8h-2v5H8l4 4 4-4z",
    )
    val Wifi = icon(
        "Wifi",
        "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 2.93 7.08 2.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z",
    )
    val Signal = icon("NetworkWifi", "M12 3C6.95 3 3.15 4.85 0 7.23L12 22 24 7.25C20.85 4.87 17.05 3 12 3zm0 2c3.45 0 6.43 1.07 9.07 2.83L12 18.83 2.93 7.83C5.57 6.07 8.55 5 12 5z")
    val Tag = icon("Tag", "M20 10V8h-4V4h-2v4h-4V4H8v4H4v2h4v4H4v2h4v4h2v-4h4v4h2v-4h4v-2h-4v-4h4zm-6 4h-4v-4h4v4z")
}
