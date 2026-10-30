// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.resolve

/** The launchers the Home screen card lists: the one on Home, then the ones the user added, then the TV's own. */
internal fun homeLaunchers(apps: List<AppInfo>): List<AppInfo> =
    apps.filter { it.answersHome || it.builtInLauncher }
        .sortedWith(compareBy({ !it.home }, { it.system }, { it.label.lowercase() }))

/**
 * What Make Home on [app] does, or null when it isn't offered. [current] is the launcher on Home,
 * null when the TV has no default. Leaving a launcher the user added for the TV's own goes back
 * ([Action.HomeBack]): that turns it back on if an earlier switch turned it off.
 */
internal fun homeMove(app: AppInfo, current: AppInfo?): Action? = when {
    app.home -> null
    app.builtInLauncher && current != null && !current.system -> Action.HomeBack
    !app.enabled -> null
    app.homeInstead != null || app.homeFirst -> Action.Home
    // No default: the TV's own launcher can be made the default too.
    current == null && app.answersHome -> Action.Home
    else -> null
}

/**
 * The Home screen card at the top of Tweaks: every launcher on the TV, which one Home opens, and
 * the one place to change it. Make Home asks first; only the TV's own launcher is ever turned off.
 * [fire]: Fire OS ([fireInstall]), where Get another launcher installs Projectivy without a Play Store.
 */
@Composable
fun HomeScreenBlock(ui: DeviceUi, actions: DeviceActions, fire: Boolean = false) {
    val launchers = homeLaunchers(ui.apps)
    val current = launchers.firstOrNull { it.home }
    val locked = ui.busy != null
    if (launchers.any { it.packageName == ui.busy }) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (current != null) {
        Text(Copy.opensOnHome(current.label).resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        NoDefaultBanner()
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        launchers.forEach { app ->
            LauncherRow(
                app, homeMove(app, current), locked,
                onMove = { actions.propose(app.packageName, it) },
                onUninstall = { actions.propose(app.packageName, Action.Uninstall) },
                onServiceOn = { actions.homeServiceOn(app.packageName) },
            )
        }
    }
    // Only before a launcher was added: after that, the rows above are the way to switch.
    if (launchers.none { !it.system } && (hasPlayStore(ui.apps) || fire)) {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Copy.GET_LAUNCHER.resolve(), style = MaterialTheme.typography.bodyLarge)
            Text((if (fire) Copy.GET_PROJECTIVY_TEXT else Copy.GET_LAUNCHER_TEXT).resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = actions.installLauncher, enabled = !locked) { Text(Copy.GET_LAUNCHER.resolve()) }
        }
    }
    val (pkg, action) = ui.pending ?: return
    val app = launchers.firstOrNull { it.packageName == pkg }
    when (action) {
        Action.Uninstall -> if (app != null) UninstallDialog(app, onUninstall = actions.confirm, onDismiss = actions.cancel, enabled = !locked)
        Action.Home, Action.HomeBack -> if (app != null && homeMove(app, current) == action) {
            MakeHomeDialog(app, action, current, ui.apps, locked, actions, fire)
        } else {
            // A switch whose target changed meanwhile (turned off in Debloat, say) has nothing left to ask.
            LaunchedEffect(pkg, action) { actions.cancel() }
        }
        else -> Unit
    }
}

/** Material 3 has no banner; a tonal surface stands in, as for the other notes. */
@Composable
private fun NoDefaultBanner() {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HubIcons.InfoOutline, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(Copy.NO_DEFAULT.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun LauncherRow(app: AppInfo, move: Action?, locked: Boolean, onMove: (Action) -> Unit, onUninstall: () -> Unit, onServiceOn: () -> Unit) {
    var turningOn by rememberSaveable(app.packageName) { mutableStateOf(false) }
    if (turningOn && app.homeServiceOff) {
        ServiceDialog(
            app.label, app.homeBack ?: Copy.TV_OWN_LAUNCHER.resolve(),
            onTurnOn = { turningOn = false; onServiceOn() },
            dismissLabel = Copy.NOT_NOW.resolve(), onOther = { turningOn = false }, onDismiss = { turningOn = false },
        )
    }
    Column {
        Row {
            AppIcon(app, Modifier.alpha(if (app.enabled) 1f else 0.4f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (app.system) Copy.builtIn(off = !app.enabled).resolve() else Copy.ADDED.resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.homeService) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(DeviceIcons.Accessibility, contentDescription = null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(if (app.homeServiceOff) Copy.SERVICE_OFF.resolve() else Copy.KEEPS_HOME.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (app.home) {
                Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(HubIcons.CheckCircleOutline, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text(Copy.CURRENT.resolve(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        // One action row for every launcher, so Uninstall lines up whether or not Make Home sits beside it.
        val serviceOn = app.home && app.homeServiceOff
        if (move != null || serviceOn || app.removable) {
            Row(Modifier.padding(start = 52.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (move != null) FilledTonalButton(onClick = { onMove(move) }, enabled = !locked) { Text(Copy.MAKE_HOME.resolve()) }
                if (serviceOn) FilledTonalButton(onClick = { turningOn = true }, enabled = !locked) { Text(Copy.TURN_ON.resolve()) }
                if (app.removable) TextButton(onClick = onUninstall, enabled = !locked) { Text(Copy.button(Action.Uninstall).resolve()) }
            }
        }
    }
}

/**
 * Make Home's confirm: what changes, what stays, and what Undo does. A launcher with an
 * accessibility service gets its own yes for that next, never as a side effect of this one.
 */
@Composable
private fun MakeHomeDialog(app: AppInfo, action: Action, current: AppInfo?, apps: List<AppInfo>, locked: Boolean, actions: DeviceActions, fire: Boolean) {
    val replaced = homeLabel(apps, app)
    val asks = action == Action.Home && app.homeService
    var askService by remember(app.packageName) { mutableStateOf(false) }
    if (askService) {
        ServiceDialog(
            app.label, current?.label ?: replaced ?: Copy.TV_OWN_LAUNCHER.resolve(),
            onTurnOn = { actions.confirmHome(true) },
            dismissLabel = Copy.SWITCH_WITHOUT.resolve(), onOther = { actions.confirmHome(false) }, onDismiss = { askService = false },
        )
        return
    }
    val currentService = current != null && current.homeService && !current.homeServiceOff
    val text = when {
        action == Action.HomeBack && current != null -> Copy.explainLeave(app.label, off = !app.enabled, current.label, currentService, service = false).resolve()
        fire && action == Action.Home && app.homeService && current != null -> Copy.explainFireHome(app.label, app.packageName, current.label).resolve()
        current != null && !current.system -> Copy.explainLeave(app.label, off = false, current.label, currentService, app.homeService).resolve()
        replaced != null && app.homeInstead != null -> Copy.explainReplace(app.label, replaced, app.homeInstead, app.homeService).resolve()
        else -> Copy.explainFirstHome(app.label, app.packageName, app.homeService).resolve()
    }
    AlertDialog(
        onDismissRequest = actions.cancel,
        title = { Text(Copy.makeHomeTitle(app.label).resolve()) },
        text = { Text(text) },
        confirmButton = { Button(onClick = if (asks) ({ askService = true }) else actions.confirm, enabled = !locked) { Text(Copy.MAKE_HOME.resolve()) } },
        dismissButton = { TextButton(onClick = actions.cancel) { Text(stringResource(R.string.screen_cancel)) } },
    )
}

private object HubIcons {
    // Absolute commands only: Compose's path parser misplaces a relative move after a close.
    private const val RING = "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2ZM12 4A8 8 0 1 1 12 20A8 8 0 1 1 12 4Z"
    val InfoOutline = icon("InfoOutline", RING + "M11 11H13V17H11ZM11 7H13V9H11Z")
    val CheckCircleOutline = icon("CheckCircleOutline", RING + "M16.59 7.58L10 14.17L6.41 10.59L5 12L10 17L18 9Z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .build()
}
