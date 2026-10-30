// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.adb.StatsProfile
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.AppNames
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.Proc
import app.tellyscope.data.Snapshot
import app.tellyscope.data.resolve
import app.tellyscope.ui.theme.SmallNumber

data class DeviceActions(
    val select: (String) -> Unit = {},
    val addDevice: () -> Unit = {},
    val settings: () -> Unit = {},
    val query: (String) -> Unit = {},
    val propose: (String, Action) -> Unit = { _, _ -> },
    val confirm: () -> Unit = {},
    /** The confirmed switch to a launcher with an accessibility service, with the service or without it. */
    val confirmHome: (service: Boolean) -> Unit = {},
    val cancel: () -> Unit = {},
    /** Turns on the accessibility service of the launcher on Home, after a switch left it off. */
    val homeServiceOn: (String) -> Unit = {},
    /** Got it on the TV page's card about that service being off. */
    val dismissServiceNote: (String) -> Unit = {},
    /** Opens Install a home screen, from the Apps tab's card or Tweaks. */
    val installLauncher: () -> Unit = {},
    /** Not now on the Apps tab's card about a better home screen. */
    val hideLauncherCard: () -> Unit = {},
    /** Don't show again on the Debloat gate: Debloat goes straight to Apps from then on. */
    val debloatGateOff: () -> Unit = {},
    val undo: (HistoryEntry) -> Unit = {},
    val reconnect: () -> Unit = {},
    val forget: (String) -> Unit = {},
    val rename: (String, String?) -> Unit = { _, _ -> },
    val restoreAll: () -> Unit = {},
    /** Opens the system picker for an APK, split APKs or a bundle. */
    val pickApp: () -> Unit = {},
    val allowDowngrade: (Boolean) -> Unit = {},
    val install: () -> Unit = {},
    val closeSideload: () -> Unit = {},
    /** Report a problem from a failed install, with what the TV said. */
    val reportInstall: (SideloadUi) -> Unit = {},
    val openInstalled: () -> Unit = {},
    val folder: (TvFolder) -> Unit = {},
    /** Shows that folder, then opens the system picker for a file to send into it. */
    val pickFile: (TvFolder) -> Unit = {},
    /** Asks where to save [TvFile] on the phone, then copies it. */
    val saveFile: (TvFile) -> Unit = {},
    val cancelTransfer: () -> Unit = {},
    val remote: () -> Unit = {},
    /** Opens the Debloat flow: pick the TV's list, then review the apps it turns off. */
    val debloat: () -> Unit = {},
    /** Opens the Advanced monitor: every number, per-app CPU and RAM, processes and network. */
    val advanced: () -> Unit = {},
    /** Opens History: every change on this TV with its Undo, and Restore everything. */
    val history: () -> Unit = {},
    /** Opens Reset TV: what it would change back to how the TV shipped, before anything changes. */
    val reset: () -> Unit = {},
    /** Opens the TV's own system update screen. */
    val checkUpdates: () -> Unit = {},
    /** Ad-blocking Private DNS through the given hostname; null goes back to Automatic. */
    val setDns: (String?) -> Unit = {},
    val setFastAnimations: (Boolean) -> Unit = {},
    val clearCaches: () -> Unit = {},
    /** Asks where to save a screenshot of the TV on the phone, then takes it. */
    val screenshot: () -> Unit = {},
    /** Allow or Deny on another device's debugging prompt, and whether to always allow it. */
    val answerRequest: (allow: Boolean, always: Boolean) -> Unit = { _, _ -> },
    /** Hides that prompt on the phone without answering it; the TV keeps asking. */
    val dismissRequest: () -> Unit = {},
    /** Press OK on the TV, on Fire OS's remote alert over that prompt. */
    val closeAlert: () -> Unit = {},
    /** Folds an Apps tab's System section away, or opens it; remembered across restarts. */
    val foldSystem: (AppsTab, Boolean) -> Unit = { _, _ -> },
    /** The open tab's refresh rates; the TV's stats reads follow them. */
    val showStats: (StatsProfile) -> Unit = {},
    /** Opens Who has access. */
    val whoHasAccess: () -> Unit = {},
    /** The access warning's buttons, shared with Who has access. */
    val access: AccessActions = AccessActions(),
)

/**
 * The TV's vitals in one small card: internal storage as a full bar, since it decides whether an
 * install fits, then CPU, RAM and network speed as plain numbers.
 */
@Composable
internal fun SystemWidget(s: Snapshot) = SystemWidget(s.storageUsedGb, s.storageTotalGb, s)

/**
 * The same card for a TV that's away: storage as it was [asOf] the last visit, and the live
 * numbers as dashes, since old ones don't speak for a TV that isn't there.
 */
@Composable
internal fun SystemWidget(usedGb: Double?, totalGb: Double?, live: Snapshot?, asOf: String? = null) {
    val dash = "\u2014"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (asOf != null) {
                Text(stringResource(R.string.device_as_of, asOf), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val used = usedGb
            val total = totalGb?.takeIf { it > 0 }
            if (used != null && total != null) {
                Row {
                    Text(stringResource(R.string.device_internal_storage), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.device_gb_used, Copy.gbOf(used, total).resolve()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Copy.free(used, total)?.let { Text(it.resolve(), style = MaterialTheme.typography.titleLarge) }
                val full = used / total
                LinearProgressIndicator(
                    progress = { full.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp).height(8.dp),
                    color = if (full >= Copy.FULL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Row {
                Vital(stringResource(R.string.device_cpu), live?.let { stringResource(R.string.device_percent, it.cpuPercent) } ?: dash, Modifier.weight(1f))
                Vital(stringResource(R.string.device_ram), live?.takeIf { it.ramTotalMb > 0 }?.let { stringResource(R.string.device_percent, it.ramUsedMb * 100 / it.ramTotalMb) } ?: dash, Modifier.weight(1f), Alignment.CenterHorizontally)
                Vital(stringResource(R.string.device_network), live?.netBytesPerSec?.let { Copy.speed(it).resolve() } ?: dash, Modifier.weight(1f), Alignment.End)
            }
        }
    }
}

@Composable
private fun Vital(label: String, value: String, modifier: Modifier = Modifier, align: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, horizontalAlignment = align) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
    }
}

/** Undoing a Turn on disables the app; it names the app and asks like any other Disable. */
@Composable
fun UndoDisableDialog(entry: HistoryEntry, onDisable: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_disable_title, entry.label)) },
        text = { Text(Copy.explain(Action.Disable, entry.label, entry.packageName).resolve()) },
        confirmButton = { Button(onClick = onDisable) { Text(Copy.confirm(Action.Disable).resolve()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.device_cancel)) } },
    )
}

/** Restore only touches what Tellyscope turned off; the dialog says so before anything runs. */
@Composable
fun RestoreDialog(count: Int, onRestore: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.device_restore_title, count, count)) },
        text = {
            Text(stringResource(R.string.device_restore_text))
        },
        confirmButton = { Button(onClick = onRestore) { Text(stringResource(R.string.device_restore_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.device_cancel)) } },
    )
}

/** "XGIMI · Horizon Pro": many models already start with the maker's name, so it isn't said twice. */
internal fun made(maker: String?, model: String?): String {
    val m = if (maker != null && model != null && model.startsWith(maker, ignoreCase = true)) model.drop(maker.length).trim() else model
    return listOfNotNull(maker, m?.takeIf { it.isNotEmpty() }).joinToString(" · ")
}

/** The name the user gave the TV, else its model: a network id like "xgimi-projector" means little. */
internal fun name(d: Device) = d.nickname ?: d.model ?: networkName(d)

internal fun networkName(d: Device) = d.hit.hostname ?: d.model ?: d.hit.ip

/** Stored on this phone only, per TV; an empty name goes back to [fallback], the model or network name. */
@Composable
fun RenameDialog(current: String, fallback: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_rename_text))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(app.tellyscope.data.NameBook.MAX_LENGTH) },
                    singleLine = true,
                    placeholder = { Text(fallback) },
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(text) }) { Text(stringResource(R.string.device_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.device_cancel)) } },
    )
}

/** Forgetting only touches this phone; the TV keeps its own list of allowed computers. */
@Composable
fun ForgetDialog(name: String, onForget: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_forget_title, name)) },
        text = { Text(stringResource(R.string.device_forget_text)) },
        confirmButton = { Button(onClick = onForget) { Text(stringResource(R.string.device_forget)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.device_cancel)) } },
    )
}

@Composable
internal fun StateDot(state: DeviceState) {
    val c = when (state) {
        DeviceState.Online -> MaterialTheme.colorScheme.primary
        DeviceState.Waiting, DeviceState.Found, DeviceState.NeedsPairing -> MaterialTheme.colorScheme.tertiary
        DeviceState.Offline -> MaterialTheme.colorScheme.outline
        DeviceState.NotAllowed -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
}

/** The TV refused (revoked access, a reset, or Deny); retrying stopped so the prompt doesn't nag. */
@Composable
internal fun NotAllowedCard(onConnect: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CardTitle(Icons.Default.Warning, stringResource(R.string.device_not_allowed_title))
            Text(
                stringResource(R.string.device_not_allowed_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onConnect) { Text(stringResource(R.string.device_connect_again)) }
            Text(
                stringResource(R.string.device_not_allowed_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun OfflineCard(device: Device, now: Long, onReconnect: () -> Unit) {
    // Material 3 has no warning role; tertiary is the standard stand-in, kept apart from error.
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle(Icons.Default.Info, stringResource(R.string.device_offline))
                Text(
                    stringResource(R.string.device_offline_text, device.lastSeen?.let { Copy.ago(it, now).resolve() } ?: stringResource(R.string.device_a_while_ago)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onReconnect) { Text(stringResource(R.string.device_reconnect)) }
        }
    }
}

/** App names by memory use, largest first, for the health line. */
internal fun heaviest(snap: Snapshot, apps: List<AppInfo>): List<String> {
    val labels = apps.associate { it.packageName to it.label }
    return snap.procs.filter { it.packageName != null }
        .groupBy { labels[it.packageName] ?: AppNames.fallback(it.packageName!!) }
        .mapValues { (_, ps) -> ps.sumOf { it.ramMb } }
        .entries.sortedByDescending { it.value }.map { it.key }
}

@Composable
internal fun EmptyLine(q: String) {
    Text(
        if (q.isEmpty()) stringResource(R.string.device_nothing_here) else stringResource(R.string.device_nothing_matches, q),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun AppIcon(app: AppInfo, modifier: Modifier = Modifier, word: Boolean = false) {
    val image = remember(app.icon) {
        // A 192 px icon is tens of KB; anything far bigger is a TV sending something odd.
        app.icon?.takeIf { it.size <= 256 * 1024 }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }
    if (image != null) {
        Image(image, contentDescription = null, modifier.size(40.dp).clip(MaterialTheme.shapes.medium))
        return
    }
    val bg = app.iconColor?.let { Color(it) } ?: MaterialTheme.colorScheme.secondaryContainer
    val fg = app.iconAccent?.let { Color(it) } ?: if (app.iconColor != null) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
    Box(modifier.size(40.dp).clip(MaterialTheme.shapes.medium).background(bg), contentAlignment = Alignment.Center) {
        // A tile with no name under it shows the name's first word, so two "P" apps aren't twins.
        if (word) Text(app.label.substringBefore(' '), color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(horizontal = 2.dp))
        else Text(app.label.take(1).uppercase(), color = fg, style = MaterialTheme.typography.titleMedium)
    }
}

/** The name of the home screen [app] would replace, or null when it isn't offered as one. */
internal fun homeLabel(apps: List<AppInfo>, app: AppInfo): String? =
    app.homeInstead?.let { pkg -> apps.firstOrNull { it.packageName == pkg }?.label }

/** Uninstall has no undo, so it asks in a dialog that names the app, not inline. */
@Composable
fun UninstallDialog(app: AppInfo, onUninstall: () -> Unit, onDismiss: () -> Unit, enabled: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(Copy.uninstallTitle(app.label).resolve()) },
        text = {
            // The launcher on Home: what happens before it goes, in order.
            val what = (app.homeBack?.let { Copy.uninstallHome(app.label, it, app.homeService) } ?: Copy.explain(Action.Uninstall, app.label, app.packageName)).resolve()
            Text(what + if (app.listKeep) "\n\n${Copy.KEEP_WARNING.resolve()}" else "")
        },
        confirmButton = {
            Button(
                onClick = onUninstall,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(Copy.confirm(Action.Uninstall).resolve()) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.device_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppRow(
    app: AppInfo,
    procs: List<Proc>,
    pending: Pair<String, Action>?,
    busy: Boolean,
    actions: DeviceActions,
    /** Something else is running on this TV: every button waits for it, like the tweaks do. */
    locked: Boolean = false,
    /** Opens the Home screen card in Tweaks, where launchers are switched. */
    toHomeScreen: () -> Unit = {},
    /** The TV is away: what the row last heard is stale, so it greys out, says nothing and won't open. */
    offline: Boolean = false,
) {
    var open by rememberSaveable(app.packageName) { mutableStateOf(false) }
    // A launcher can be protected and still open, for the way to the Home screen card.
    val launcher = app.answersHome || app.builtInLauncher
    val changeable = (app.protectedReason == null || launcher) && !offline
    val ram = procs.filter { it.packageName == app.packageName }.sumOf { it.ramMb }
    val status = stringResource(
        when {
            !app.enabled -> R.string.device_app_disabled
            ram > 0 -> R.string.device_app_running
            else -> R.string.device_app_not_running
        },
    )
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(enabled = changeable) { open = !open }.padding(vertical = 8.dp).alpha(if (offline) 0.38f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, Modifier.alpha(if (app.enabled || offline) 1f else 0.4f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!offline) {
                    Text(
                        app.protectedReason?.let { stringResource(R.string.device_app_protected, it.resolve()) } ?: status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (app.homeServiceOff) Text(Copy.SERVICE_OFF.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (busy && !offline) androidx.compose.material3.LoadingIndicator(Modifier.size(32.dp))
        }
        // Home switches are asked for in the Home screen card, not here.
        val proposed = pending?.takeIf { it.first == app.packageName }?.second?.takeUnless { it == Action.Home || it == Action.HomeBack }
        AnimatedVisibility((open || proposed != null) && changeable) {
            Column(Modifier.padding(start = 52.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The label is whatever the app calls itself; the package id is what an action touches.
                Text(app.packageName, style = SmallNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (proposed == null) {
                    if (app.protectedReason == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (app.enabled && ram > 0) OutlinedButton(onClick = { actions.propose(app.packageName, Action.Stop) }, enabled = !locked) { Text(Copy.button(Action.Stop).resolve()) }
                            if (app.enabled) OutlinedButton(onClick = { actions.propose(app.packageName, Action.Disable) }, enabled = !locked) { Text(Copy.button(Action.Disable).resolve()) }
                            else FilledTonalButton(onClick = { actions.propose(app.packageName, Action.Enable) }, enabled = !locked) { Text(Copy.button(Action.Enable).resolve()) }
                            if (app.removable) OutlinedButton(onClick = { actions.propose(app.packageName, Action.Uninstall) }, enabled = !locked) { Text(Copy.button(Action.Uninstall).resolve()) }
                        }
                    } else if (launcher && app.removable) {
                        // The launcher on Home: protected, but Uninstall still goes, Home moving first.
                        OutlinedButton(onClick = { actions.propose(app.packageName, Action.Uninstall) }, enabled = !locked) { Text(Copy.button(Action.Uninstall).resolve()) }
                    }
                    if (launcher) TextButton(onClick = toHomeScreen) {
                        Text(Copy.HOME_SETTINGS.resolve())
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, Modifier.size(18.dp))
                    }
                } else if (proposed == Action.Uninstall) {
                    UninstallDialog(app, onUninstall = actions.confirm, onDismiss = actions.cancel, enabled = !locked)
                } else {
                    Text(
                        if (app.listKeep && proposed != Action.Enable) Copy.explain(proposed, app.label, app.packageName).resolve() + "\n\n" + Copy.KEEP_WARNING.resolve()
                        else Copy.explain(proposed, app.label, app.packageName).resolve(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = actions.confirm, enabled = !locked) { Text(Copy.confirm(proposed).resolve()) }
                        TextButton(onClick = actions.cancel) { Text(stringResource(R.string.device_cancel)) }

                    }
                }
            }
        }
    }
}

