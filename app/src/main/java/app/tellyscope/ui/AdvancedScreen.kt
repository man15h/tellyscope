// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.AppInfo
import app.tellyscope.data.AppNames
import app.tellyscope.data.DeviceState
import app.tellyscope.data.NetworkInfo
import app.tellyscope.data.Proc
import app.tellyscope.data.Snapshot
import app.tellyscope.data.resolve
import app.tellyscope.ui.theme.SmallNumber
import app.tellyscope.ui.theme.StatNumber

/**
 * Everything with a number in it, kept off the TV's page: the full stats, CPU and RAM by app,
 * processes, network, and the TV's address details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedScreen(
    state: UiState,
    back: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    val device = state.selected ?: return
    val ui = state.device
    val snap = ui.snapshot
    val offline = device.state != DeviceState.Online
    val procs = snap?.procs.orEmpty()
    val androidSystem = Copy.ANDROID_SYSTEM.resolve()
    val byApp = remember(ui.apps, procs, androidSystem) {
        val labels = ui.apps.associate { it.packageName to it.label }
        procs.groupBy { p -> p.packageName?.let { labels[it] ?: AppNames.fallback(it) } ?: androidSystem }
    }
    val res = LocalContext.current.resources

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
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.advanced_this_tv), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        listOfNotNull(
                            stringResource(R.string.advanced_ip_address) to device.hit.ip,
                            stringResource(R.string.advanced_port) to device.hit.port.toString(),
                            (device.manufacturer ?: device.hit.maker)?.let { stringResource(R.string.advanced_maker) to it },
                            device.hit.hostname?.let { stringResource(R.string.advanced_network_name) to it },
                        ).forEach { (k, v) -> KeyValue(k, v) }
                    }
                }
            }
            if (snap != null) {
                // Offline: the last stats this phone saw, greyed and dated, rather than a grid of blanks.
                item { Stats(snap, if (offline) state.clock() else null) }
            } else {
                item {
                    Text(
                        if (offline) Copy.noStats(device.state).resolve() else stringResource(R.string.advanced_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A TV that lists no processes gets no charts: no bars beats an empty card.
            if (byApp.isNotEmpty()) {
                item { UsageCard(stringResource(R.string.advanced_cpu_by_app), byApp.map { (n, ps) -> n to ps.sumOf { it.cpuPercent } }, fmt = { res.getString(R.string.advanced_percent_whole, it) }) }
                item { UsageCard(stringResource(R.string.advanced_ram_by_app), byApp.map { (n, ps) -> n to ps.sumOf { it.ramMb }.toDouble() }, fmt = { Copy.mb(it.toInt()).resolve(res) }) }
                item { Block(stringResource(R.string.advanced_processes)) { ProcessTable(snap?.procs.orEmpty(), ui.apps) } }
            }
            ui.network?.let { n -> item { Block(stringResource(R.string.advanced_network)) { NetworkBlock(n) } } }
        }
    }
}

@Composable
private fun Block(title: String, body: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            body()
        }
    }
}

/**
 * Memory and storage as "75% used" with a bar, then the small stats. Missing stats show "—",
 * never 0. [staleSince] set means the TV is offline: these are its last numbers, greyed and dated.
 */
@Composable
private fun Stats(s: Snapshot, staleSince: Long?) {
    val cells = listOf(
        stringResource(R.string.advanced_cpu) to stringResource(R.string.advanced_percent, s.cpuPercent),
        stringResource(R.string.advanced_on_for) to Copy.uptime(s.uptimeSeconds).resolve(),
        stringResource(R.string.advanced_screen) to s.screenOn?.let { stringResource(if (it) R.string.advanced_screen_on else R.string.advanced_screen_off) },
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (staleSince != null) {
                Text(
                    stringResource(R.string.advanced_as_of, Copy.ago(s.at, staleSince).resolve()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.alpha(if (staleSince != null) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Meter(stringResource(R.string.advanced_memory), s.ramUsedMb / 1024.0, s.ramTotalMb / 1024.0)
                s.storageUsedGb?.let { u -> s.storageTotalGb?.let { t -> Meter(stringResource(R.string.advanced_storage), u, t) } }
                Row(Modifier.fillMaxWidth()) {
                    cells.forEach { (label, value) ->
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Meter(label: String, usedGb: Double, totalGb: Double) {
    val frac = if (totalGb > 0) (usedGb / totalGb).toFloat().coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.advanced_percent_used, (frac * 100).toInt()), style = StatNumber)
            }
            Text(Copy.gbOf(usedGb, totalGb).resolve(), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { frac },
            Modifier.fillMaxWidth(),
            color = if (frac >= Copy.FULL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

/** Grey bars, no chart library: top 6 by value, the rest summed as "other". */
@Composable
private fun UsageCard(title: String, rows: List<Pair<String, Double>>, fmt: (Double) -> String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Bars(rows, fmt)
        }
    }
}

@Composable
private fun Bars(rows: List<Pair<String, Double>>, fmt: (Double) -> String, top: Int = 6) {
    val sorted = rows.sortedByDescending { it.second }
    val rest = sorted.drop(top).sumOf { it.second }
    val other = stringResource(R.string.advanced_other)
    val shown = sorted.take(top) + if (rest > 0) listOf(other to rest) else emptyList()
    val max = shown.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    shown.forEach { (name, v) ->
        val frac by animateFloatAsState((v / max).toFloat(), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "bar")
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Long app names wrap to two lines rather than cut off; the value column only needs "1.2 GB".
            Text(name, Modifier.width(148.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f).height(12.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary))
            }
            Text(fmt(v), Modifier.width(60.dp), style = SmallNumber, textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun ProcessTable(procs: List<Proc>, apps: List<AppInfo>) {
    val labels = apps.associate { it.packageName to it.label }
    val groups = procs
        .groupBy { p -> p.packageName?.let { labels[it] ?: AppNames.fallback(it) } ?: Copy.ANDROID_SYSTEM.resolve() }
        .toList()
        .sortedByDescending { (_, ps) -> ps.sumOf { it.cpuPercent } }
    if (groups.isEmpty()) return EmptyLine("")
    Row {
        Text(stringResource(R.string.advanced_app), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.advanced_cpu), Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        Text(stringResource(R.string.advanced_ram), Modifier.width(72.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
    groups.forEach { (name, ps) ->
        var open by rememberSaveable(name) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp)) {
            Row {
                Text(
                    if (ps.size > 1) "$name  ×${ps.size}" else name,
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(stringResource(R.string.advanced_percent_whole, ps.sumOf { it.cpuPercent }), Modifier.width(56.dp), style = SmallNumber, textAlign = TextAlign.End)
                Text(Copy.mb(ps.sumOf { it.ramMb }).resolve(), Modifier.width(72.dp), style = SmallNumber, textAlign = TextAlign.End)
            }
            if (open) ps.forEach { p ->
                Row(Modifier.padding(start = 12.dp, top = 2.dp)) {
                    Text("${p.pid}  ${p.name}", Modifier.weight(1f), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.advanced_percent_tenths, p.cpuPercent), Modifier.width(56.dp), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                    Text(Copy.mb(p.ramMb).resolve(), Modifier.width(72.dp), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun NetworkBlock(n: NetworkInfo) {
    // Already the technical screen: every fact shows, nothing behind a Details tap.
    listOfNotNull(
        stringResource(R.string.advanced_connection) to listOfNotNull(stringResource(if (n.iface == "wifi" || n.iface.startsWith("wlan")) R.string.advanced_wifi else R.string.advanced_ethernet), n.ssid).joinToString(" · "),
        n.rssi?.let { stringResource(R.string.advanced_signal) to stringResource(signalWord(it), it) },
        stringResource(R.string.advanced_ip_address) to n.ip,
        n.mac?.let { stringResource(R.string.advanced_mac) to it },
        n.linkMbps?.let { stringResource(R.string.advanced_link_speed) to stringResource(R.string.advanced_mbps, it) },
    ).forEach { (k, v) -> KeyValue(k, v) }
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.advanced_data_used), style = MaterialTheme.typography.titleSmall)
    val res = LocalContext.current.resources
    Bars(n.traffic.map { it.app to (it.rxMb + it.txMb).toDouble() }, fmt = { Copy.mb(it.toInt()).resolve(res) })
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.advanced_open_connections), style = MaterialTheme.typography.titleSmall)
    if (n.sockets.isEmpty()) EmptyLine("")
    n.sockets.forEach { s ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text(s.app, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.remote, style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The signal in words, with its dBm as the resource's argument. */
@StringRes
private fun signalWord(rssi: Int) = when {
    rssi >= -55 -> R.string.advanced_signal_strong
    rssi >= -67 -> R.string.advanced_signal_good
    rssi >= -75 -> R.string.advanced_signal_weak
    else -> R.string.advanced_signal_very_weak
}

