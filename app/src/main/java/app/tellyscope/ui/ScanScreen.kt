// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.ScanHit
import app.tellyscope.ui.theme.SmallNumber
import app.tellyscope.data.UiText
import app.tellyscope.data.resolve

/**
 * "Add your TV": pick the brand, follow its steps one at a time, then pick the TV the scan found.
 * The scan runs from the start, so a TV that turns up early skips the steps it has proved done.
 * Connect opens that TV's own [ConnectScreen]; [phoneKey] is shown there for the Allow box.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    scan: ScanUi,
    now: Long,
    onRescan: () -> Unit,
    onConnect: (ScanHit) -> Unit,
    onCode: (ScanHit, String) -> Unit,
    onPair: (ScanHit) -> Unit,
    onAddByIp: (String) -> UiText?,
    onBack: (() -> Unit)? = null,
    onOpenWifi: () -> Unit = {},
    phoneKey: String? = null,
    onLeave: (ScanHit) -> Unit = {},
) {
    var adding by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var brandName by rememberSaveable { mutableStateOf(brands.first().name) }
    val brand = brands.first { it.name == brandName }
    var step by rememberSaveable { mutableIntStateOf(if (scan.hits.isEmpty()) 0 else 2) }
    // The TV whose connect screen is open. A successful connect leaves this screen altogether.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(scan.hits.isNotEmpty()) { if (scan.hits.isNotEmpty()) step = 2 }

    val openHit = scan.hits.firstOrNull { it.ip == open }
    if (openHit != null) {
        BackHandler { onLeave(openHit); open = null }
        ConnectScreen(
            hit = openHit,
            row = scan.rows[openHit.ip] ?: RowState.Found,
            now = now,
            brand = brand,
            phoneKey = phoneKey,
            onCode = { onCode(openHit, it) },
            onPair = { onPair(openHit) },
            onRetry = { onConnect(openHit) },
            onBack = { onLeave(openHit); open = null },
            onOpenWifi = onOpenWifi,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.scan_back)) }
                },
            )
        },
    ) { pad ->
        if (scan.offWifi) {
            OffWifi(Modifier.padding(pad), onOpenWifi, onAddByIp = { adding = true })
        } else Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.headlineMedium)
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (scan.noLan) stringResource(R.string.scan_no_lan)
                        else stringResource(R.string.scan_same_wifi),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            BrandChips(brand, onPick = { brandName = it.name })
            GuideSteps(brand, step, onStep = { step = it }) {
                FoundTvs(scan, now, onRescan, onPick = { hit ->
                    val row = scan.rows[hit.ip] ?: RowState.Found
                    if (row == RowState.Found || row == RowState.NotAllowed || row is RowState.Unreachable) onConnect(hit)
                    open = hit.ip
                })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_add_by_ip))
                }
                TextButton(onClick = { help = true }) { Text(stringResource(R.string.scan_cant_find)) }
            }
        }
    }
    if (help) HelpDialog(brand, onAddByIp = { adding = true }, onDismiss = { help = false })
    if (adding) AddByIpDialog(
        subnet = scan.progress?.subnet,
        onDismiss = { adding = false },
        onSubmit = { text ->
            onAddByIp(text).also { e ->
                if (e == null) {
                    adding = false
                    open = text.trim().substringBefore(':')
                }
            }
        },
    )
}

/** Step 3: what the scan has found so far, each a tap away from its connect screen. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FoundTvs(scan: ScanUi, now: Long, onRescan: () -> Unit, onPick: (ScanHit) -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        scan.hits.forEach { hit ->
            val row = scan.rows[hit.ip] ?: RowState.Found
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(DeviceIcons.Tv, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(hit.hostname ?: hit.maker ?: stringResource(R.string.scan_android_device), style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(hit.maker, hit.ip).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = { onPick(hit) }) {
                        Text(
                            when (row) {
                                RowState.Found -> stringResource(R.string.scan_connect)
                                RowState.NotAllowed, is RowState.Unreachable -> stringResource(R.string.scan_try_again)
                                else -> stringResource(R.string.scan_continue)
                            },
                        )
                    }
                }
            }
        }
        val p = scan.progress
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                scan.scanning -> {
                    LoadingIndicator(Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(if (scan.hits.isEmpty()) R.string.scan_looking_first else R.string.scan_looking_more),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p != null) Text("${p.probed}/${p.total}", style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // An empty scan starts again by itself; the ring empties until it does.
                scan.hits.isEmpty() && scan.nextScanAt != null -> {
                    val left = (scan.nextScanAt - now).coerceIn(0L, RESCAN_EVERY_MS)
                    // The clock ticks once a second; the ring glides between ticks.
                    val ring by animateFloatAsState(left / RESCAN_EVERY_MS.toFloat(), tween(1_000, easing = LinearEasing), label = "next scan")
                    CircularProgressIndicator(progress = { ring }, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.scan_none_yet_in, ((left + 999) / 1_000).toInt().coerceAtLeast(1)),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                scan.hits.isEmpty() -> Text(
                    stringResource(R.string.scan_none_yet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> TextButton(onClick = onRescan) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan_again))
                }
            }
        }
        // Until a TV turns up, Rescan stays in one place; it waits out a scan rather than throw its probes away.
        if (scan.hits.isEmpty()) FilledTonalButton(onClick = onRescan, enabled = !scan.scanning) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.scan_rescan))
        }
    }
}

/** Off Wi-Fi with no TV announcing itself: one way forward instead of an empty list. */
@Composable
private fun OffWifi(modifier: Modifier, onOpenWifi: () -> Unit, onAddByIp: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(Modifier.size(112.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Icon(DeviceIcons.WifiOff, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.scan_off_wifi_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.scan_off_wifi_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onOpenWifi) { Text(stringResource(R.string.scan_open_wifi)) }
        // VPNs and odd setups can still reach a TV by its address.
        TextButton(onClick = onAddByIp) { Text(stringResource(R.string.scan_add_by_ip)) }
    }
}

@Composable
private fun AddByIpDialog(subnet: String?, onDismiss: () -> Unit, onSubmit: (String) -> UiText?) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<UiText?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_add_by_ip)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scan_ip_hint))
                // The scan screen says "your Wi-Fi"; the range is only useful here, while typing.
                if (subnet != null) Text(stringResource(R.string.scan_subnet, subnet), style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    isError = error != null,
                    supportingText = error?.let { { Text(it.resolve()) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            }
        },
        confirmButton = { Button(onClick = { error = onSubmit(text) }) { Text(stringResource(R.string.scan_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_cancel)) } },
    )
}
