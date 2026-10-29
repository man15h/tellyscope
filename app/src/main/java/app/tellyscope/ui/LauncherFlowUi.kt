// SPDX-License-Identifier: GPL-3.0-only
// Download, ErrorOutline, InfoOutline and Code are the Material Icons "download", "error_outline", "info" (outlined)
// and "code" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.Device
import app.tellyscope.data.LauncherRelease
import app.tellyscope.data.PROJECTIVY
import app.tellyscope.data.PROJECTIVY_RELEASES
import app.tellyscope.data.isFireOs
import app.tellyscope.ui.theme.SmallNumber

/** The Play Store's package: a TV without it gets no card and no Tweaks row, unless it runs Fire OS ([fireInstall]). */
const val PLAY_STORE = "com.android.vending"

/**
 * Install a home screen is offered on the Apps tab while the TV has the Play Store and no launcher
 * was added, Home or not, and there's no second launcher, until Not now; Tweaks offers it whenever
 * the Store is there, launcher added or not, so there's always a way to try another.
 */
internal fun hasPlayStore(apps: List<AppInfo>) = apps.any { it.packageName == PLAY_STORE && it.enabled }

/** A launcher someone added is on the TV, Home or not: one on `launchers.json`, or one they installed that answers Home. */
internal fun hasAddedLauncher(apps: List<AppInfo>) = apps.any { it.listedLauncher || (it.removable && (it.home || it.answersHome)) }

/**
 * Fire OS has no Play Store, so Install a home screen offers only Projectivy there, which Tellyscope downloads
 * from its developer and installs itself. Only while the launcher list ([recommended]) has Projectivy; without it, nothing is offered.
 */
internal fun fireInstall(device: Device, apps: List<AppInfo>, recommended: List<Recommended> = RECOMMENDED) =
    isFireOs(device, apps) && recommended.any { it.pkg == PROJECTIVY }

/** [fire]: [fireInstall] holds, so the Play Store isn't needed. */
internal fun offerChoose(apps: List<AppInfo>, fire: Boolean = false) = (hasPlayStore(apps) || fire) && !hasAddedLauncher(apps)

internal fun offerLauncher(apps: List<AppInfo>, hidden: Boolean, fire: Boolean = false) =
    !hidden && offerChoose(apps, fire) && apps.none { it.homeInstead != null || it.homeBack != null }

/** Download directly is offered on [pkg] beside the Store: only launchers whose download and signer are pinned. */
internal fun downloadable(pkg: String) = pkg == PROJECTIVY

/** [pkg] on the TV and on, so the picker offers Make Home or Open on it rather than the Store. */
internal fun installedLauncher(apps: List<AppInfo>, pkg: String) = apps.firstOrNull { it.packageName == pkg && it.enabled }

/** The picker's On this TV: launchers someone added that Recommended doesn't list, the TV's own left out. */
internal fun otherLaunchers(apps: List<AppInfo>) =
    apps.filter { a -> a.enabled && a.answersHome && !a.builtInLauncher && RECOMMENDED.none { it.pkg == a.packageName } }

data class LauncherActions(
    val close: () -> Unit = {},
    /** Opens the Store on the TV at that launcher's page, and watches for the install. */
    val install: (String) -> Unit = {},
    /** Back to the picker with that launcher still chosen. */
    val chooseAnother: (String) -> Unit = {},
    /** Yes on the installed launcher, with its accessibility service or without. */
    val switch: (service: Boolean) -> Unit = {},
    /** Make Home in the picker, on a launcher already on the TV. */
    val makeHome: (pkg: String, service: Boolean) -> Unit = { _, _ -> },
    /** Open in the picker: shows that launcher on the TV. */
    val open: (String) -> Unit = {},
    val later: () -> Unit = {},
    /** Fire OS: Install Projectivy, which reads the latest release for the confirm dialog. Try again does the same. */
    val fireCheck: () -> Unit = {},
    /** Fire OS: Download and install on the confirm step. */
    val fireInstall: () -> Unit = {},
    /** Fire OS: X, Stop or OK on a step. */
    val fireDismiss: () -> Unit = {},
    /** Fire OS: Pick the downloaded file, after Couldn't download. */
    val firePick: () -> Unit = {},
)

/** The Apps tab's card: a launcher without ads, from the Play Store ([fire]: Projectivy, installed by Tellyscope). Not now puts it away on this TV. */
@Composable
fun LauncherCard(current: String?, onNotNow: () -> Unit, onChoose: () -> Unit, fire: Boolean = false) {
    HomeNoteCard(
        Icons.Outlined.Home, Copy.BETTER_HOME, Copy.betterHomeText(current, fire), Copy.NOT_NOW, onNotNow,
        if (fire) Copy.INSTALL_ONE else Copy.CHOOSE_ONE, onChoose,
    )
}

/**
 * Install a home screen, as one full screen: the picker, then the wait while the TV shows the
 * Play Store, then the switch, which ends in the same yes for the accessibility service as Set as
 * default. A launcher already on the TV skips the Store: the picker offers Make Home or Open on it.
 * A TV that didn't open the Store says so; a Store closed without installing goes back to the picker.
 * On Fire OS ([LauncherFlow.fireOs]) the picker is Projectivy alone, which Tellyscope downloads and installs.
 * [direct]: a launcher that is [downloadable] also offers Download directly, under the Store and after a warning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    device: Device,
    flow: LauncherFlow,
    apps: List<AppInfo>,
    actions: LauncherActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    direct: Boolean = false,
) {
    var chosen by rememberSaveable(flow.step, flow.pkg) { mutableStateOf(flow.pkg) }
    var askService by rememberSaveable(flow.step) { mutableStateOf(false) }
    val label = launcherName(flow.pkg)
    val picking = flow.step == LauncherFlow.Step.Pick
    val app = apps.firstOrNull { it.packageName == if (picking) chosen else flow.pkg }
    val current = app?.let { homeLabel(apps, it) }
    val switch = { service: Boolean -> if (picking) actions.makeHome(chosen, service) else actions.switch(service) }
    // Fire OS's install, from the start; Download directly once it has read the release. The picker gives way to it.
    val fire = when {
        !picking -> null
        flow.fireOs -> flow.fire ?: FireInstall()
        else -> flow.fire?.takeIf { it.step != null && it.step != FireInstall.Step.Checking }
    }
    var askStop by rememberSaveable { mutableStateOf(false) }
    if (askStop && fire?.step == FireInstall.Step.Downloading) {
        StopDialog(onKeep = { askStop = false }, onStop = { askStop = false; actions.fireDismiss() })
    }
    val close: () -> Unit = {
        when {
            fire?.step == FireInstall.Step.Downloading -> askStop = true
            fire != null && fire.store -> actions.fireDismiss()
            else -> actions.close()
        }
    }
    if (askService && app != null && current != null) {
        ServiceDialog(
            app.label, current,
            onTurnOn = { switch(true) },
            dismissLabel = Copy.SWITCH_WITHOUT, onOther = { switch(false) }, onDismiss = { askService = false },
        )
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            flow.fireOs -> Copy.INSTALL_PROJECTIVY
                            flow.step == LauncherFlow.Step.Pick -> Copy.CHOOSE_HOME
                            else -> Copy.INSTALL_HOME
                        },
                    )
                },
                // Dimmed while it installs: stopping halfway could leave the TV half done.
                navigationIcon = {
                    IconButton(onClick = close, enabled = fire?.step != FireInstall.Step.Installing) { Icon(Icons.Default.Close, contentDescription = "Close") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (flow.step) {
                    LauncherFlow.Step.Pick -> if (fire != null) {
                        FireButtons(fire, actions, Modifier.weight(1f))
                    } else {
                        val have = installedLauncher(apps, chosen)
                        when {
                            have == null -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val both = direct && downloadable(chosen)
                                if (both) {
                                    Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(6.dp))
                                        Text(Copy.STORE_RECOMMENDED, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                IconButtonText(OpenInNew, Copy.GET_FROM_STORE, Modifier.fillMaxWidth()) { actions.install(chosen) }
                                if (both) {
                                    OutlinedButton(onClick = actions.fireCheck, enabled = flow.fire == null, modifier = Modifier.fillMaxWidth()) { Text(Copy.DOWNLOAD_DIRECTLY) }
                                }
                            }
                            // Home now, or a TV that can't take it as Home: only Open.
                            !have.home && current != null -> {
                                TextButton(onClick = { actions.open(chosen) }) { Text(Copy.OPEN) }
                                IconButtonText(Icons.Outlined.Home, Copy.MAKE_HOME) { if (have.homeService) askService = true else switch(false) }
                            }
                            else -> IconButtonText(DeviceIcons.Tv, Copy.OPEN) { actions.open(chosen) }
                        }
                    }
                    LauncherFlow.Step.Waiting -> TextButton(onClick = { actions.install(flow.pkg) }) { Text(Copy.OPEN_STORE_AGAIN) }
                    LauncherFlow.Step.Installed -> {
                        TextButton(onClick = actions.later) { Text(Copy.NOT_NOW) }
                        Button(onClick = { if (app?.homeService == true) askService = true else actions.switch(false) }, enabled = current != null) {
                            Text(Copy.confirm(Action.Home))
                        }
                    }
                    LauncherFlow.Step.NoStore -> {
                        TextButton(onClick = { actions.chooseAnother(flow.pkg) }) { Text(Copy.CHOOSE_ANOTHER) }
                        Button(onClick = { actions.install(flow.pkg) }) { Text(Copy.TRY_AGAIN) }
                    }
                    LauncherFlow.Step.Left -> {
                        TextButton(onClick = { actions.chooseAnother(flow.pkg) }) { Text(Copy.CHOOSE_ANOTHER) }
                        Button(onClick = { actions.install(flow.pkg) }) { Text(Copy.OPEN_STORE_AGAIN) }
                    }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(DeviceIcons.Tv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(connectTitle(device), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val c = MaterialTheme.colorScheme
            when (flow.step) {
                LauncherFlow.Step.Pick -> if (fire != null) FireBody(fire, device, actions.firePick) else Picker(chosen, apps, direct) { chosen = it }
                LauncherFlow.Step.Waiting -> {
                    Hero(LauncherIcons.Download, c.secondaryContainer, c.onSecondaryContainer)
                    Headed(Copy.INSTALL_IT, bold(Copy.installItText(label), label, "Install"))
                    Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                                Text(Copy.storeOn(name(device)), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            }
                            WaitingLine()
                        }
                    }
                    Text(Copy.DOWNLOADS_TAKE, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                }
                LauncherFlow.Step.Installed -> {
                    Hero(Icons.Default.Check, c.secondaryContainer, c.onSecondaryContainer)
                    val explain = if (app != null && current != null && app.homeInstead != null) {
                        " " + if (flow.fire != null) Copy.explainFireHome(app.label, app.packageName, current)
                        else Copy.explainHome(app.label, app.packageName, current, app.homeInstead, app.homeService)
                    } else {
                        ""
                    }
                    Headed(Copy.MAKE_IT_HOME, AnnotatedString(Copy.isInstalled(label) + explain))
                }
                LauncherFlow.Step.NoStore -> {
                    Hero(LauncherIcons.ErrorOutline, c.errorContainer, c.onErrorContainer)
                    Headed(Copy.NO_STORE, bold(Copy.noStoreText(name(device), label), label))
                    Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.padding(16.dp)) { WaitingLine() }
                    }
                }
                LauncherFlow.Step.Left -> {
                    Hero(LauncherIcons.InfoOutline, c.surfaceContainerHighest, c.onSurfaceVariant)
                    Headed(Copy.notInstalled(label), AnnotatedString(Copy.NOT_INSTALLED_TEXT))
                }
            }
        }
    }
}

@Composable
private fun IconButtonText(icon: ImageVector, text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

/**
 * The recommended launchers as one segmented list, one chosen: rounded ends, small gaps, the chosen
 * one filled. Above them, On this TV lists any other launcher someone added, when there is one.
 * Rows never move: one on the TV carries an Installed or Home now chip where it stands.
 */
@Composable
private fun Picker(chosen: String, apps: List<AppInfo>, direct: Boolean, onChoose: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val others = otherLaunchers(apps)
    val canDownload = direct && RECOMMENDED.any { downloadable(it.pkg) && installedLauncher(apps, it.pkg) == null }
    Text(if (canDownload) Copy.CHOOSE_HOME_TEXT + " " + Copy.CAN_DOWNLOAD else Copy.CHOOSE_HOME_TEXT, style = MaterialTheme.typography.bodyLarge)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (others.isNotEmpty()) {
            Text(Copy.ON_THIS_TV, style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.padding(bottom = 8.dp))
            Column(Modifier.selectableGroup().padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                others.forEachIndexed { i, a ->
                    PickRow(segment(i, others.lastIndex), a.packageName == chosen, a.label, a, vertical = 12.dp, onClick = { onChoose(a.packageName) })
                }
            }
        }
        Text(Copy.RECOMMENDED, style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.padding(bottom = 8.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RECOMMENDED.forEachIndexed { i, l ->
                PickRow(segment(i, RECOMMENDED.lastIndex), l.pkg == chosen, l.name, installedLauncher(apps, l.pkg), onClick = { onChoose(l.pkg) }) {
                    Text(l.about, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (l.accessibility) DeviceIcons.Accessibility else LauncherIcons.Code, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(if (l.accessibility) Copy.USES_SERVICE else Copy.NO_SERVICE, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                    }
                }
            }
        }
        Text(Copy.OTHER_LAUNCHER, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        Text(Copy.NOT_AFFILIATED, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Fire OS's picker: Projectivy alone, about it as the picker says it, and nothing to do on the TV. */
@Composable
private fun FirePick() {
    val c = MaterialTheme.colorScheme
    Text(Copy.FIRE_INSTALL_TEXT, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
    val l = RECOMMENDED.firstOrNull { it.pkg == PROJECTIVY } ?: return
    Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(l.name, style = MaterialTheme.typography.bodyLarge)
            Text(l.about, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (l.accessibility) DeviceIcons.Accessibility else LauncherIcons.Code, null, Modifier.size(14.dp), tint = c.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(if (l.accessibility) Copy.USES_SERVICE else Copy.NO_SERVICE, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
            }
        }
    }
}

/**
 * Fire OS's install as steps of this one screen, per [FireInstall.Step]: where it downloads from (with the
 * source row opening that release page on the phone), the download, the check and the install, then what
 * went wrong. Couldn't download offers the release page and Pick the downloaded file, checked the same way.
 * After Download directly ([FireInstall.store]) the confirm is a warning, and it and a wrong signature offer
 * the Play Store instead.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FireBody(f: FireInstall, device: Device, onPick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val label = launcherName(PROJECTIVY)
    val release = f.release
    val openPage = { uri.openUri(release?.page ?: "$PROJECTIVY_RELEASES/latest") }
    when (f.step) {
        null, FireInstall.Step.Checking, FireInstall.Step.Confirm -> if (f.store) {
            WarningBadge()
            Text(Copy.downloadDirectly(label), style = MaterialTheme.typography.headlineMedium)
            Text(Copy.downloadsFrom(label), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            release?.let { SourceRow(it, openPage) }
            Text(Copy.DIRECT_CHECK, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Text(Copy.STORE_SAFER, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        } else {
            FirePick()
            if (release != null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Copy.WHERE_FROM, style = MaterialTheme.typography.labelLarge, color = c.primary)
                    SourceRow(release, openPage)
                }
                Text(Copy.SIGNED_CHECK, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
        }
        FireInstall.Step.Downloading, FireInstall.Step.Reading, FireInstall.Step.Installing -> {
            Text(Copy.installing(label), style = MaterialTheme.typography.labelLarge, color = c.primary)
            FireSteps(f, device)
            Text(Copy.INSTALL_KEEP_OPEN, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        FireInstall.Step.NotProjectivy -> {
            Hero(LauncherIcons.ErrorOutline, c.errorContainer, c.onErrorContainer)
            Headed(
                if (f.picked) Copy.NOT_PROJECTIVY_FILE else Copy.NOT_PROJECTIVY,
                AnnotatedString(if (f.picked) Copy.NOT_PROJECTIVY_FILE_TEXT else Copy.NOT_PROJECTIVY_TEXT),
            )
            FireSteps(f, device)
            if (f.store) Text(Copy.STORE_INSTEAD, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
        FireInstall.Step.NoDownload -> {
            Hero(LauncherIcons.ErrorOutline, c.errorContainer, c.onErrorContainer)
            val text = if (f.until != null) Copy.noDownloadLimited(clockTime(f.until)) else Copy.NO_DOWNLOAD_OFFLINE
            Headed(Copy.NO_DOWNLOAD, AnnotatedString(text))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Copy.OR_YOURSELF, style = MaterialTheme.typography.labelLarge, color = c.primary)
                Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Numbered(1) {
                            Text(Copy.YOURSELF_PAGE, style = MaterialTheme.typography.bodyMedium)
                            SourceRow(release, openPage)
                        }
                        Numbered(2) {
                            Text(Copy.yourselfPick(release?.apk?.substringAfterLast('/')), style = MaterialTheme.typography.bodyMedium)
                            FilledTonalButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text(Copy.PICK_DOWNLOADED) }
                        }
                    }
                }
            }
        }
        FireInstall.Step.Refused -> {
            Hero(LauncherIcons.ErrorOutline, c.errorContainer, c.onErrorContainer)
            Headed(Copy.cantInstall(label), AnnotatedString(f.error.orEmpty()))
        }
    }
}

/** The bottom of each Fire OS step: full width, so the confirm reads the same as Download directly's. */
@Composable
private fun FireButtons(f: FireInstall, actions: LauncherActions, modifier: Modifier) {
    val store = { actions.install(PROJECTIVY) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val wide = Modifier.fillMaxWidth()
        when (f.step) {
            null, FireInstall.Step.Checking -> IconButtonText(LauncherIcons.Download, Copy.INSTALL_PROJECTIVY, wide, enabled = f.step == null, onClick = actions.fireCheck)
            // The safe way first and filled; the download only as the outlined second.
            FireInstall.Step.Confirm -> if (f.store) {
                IconButtonText(OpenInNew, Copy.GET_FROM_STORE, wide, onClick = store)
                OutlinedButton(onClick = actions.fireInstall, modifier = wide) { Text(Copy.DOWNLOAD_ANYWAY) }
            } else {
                IconButtonText(LauncherIcons.Download, Copy.DOWNLOAD_AND_INSTALL, wide, onClick = actions.fireInstall)
            }
            FireInstall.Step.Downloading, FireInstall.Step.Reading, FireInstall.Step.Installing -> Unit
            FireInstall.Step.NotProjectivy -> when {
                f.picked -> Row(wide, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = actions.firePick) { Text(Copy.PICK_ANOTHER) }
                    Button(onClick = actions.fireCheck) { Text(Copy.TRY_DOWNLOAD_AGAIN) }
                }
                f.store -> IconButtonText(OpenInNew, Copy.GET_FROM_STORE, wide, onClick = store)
                else -> Button(onClick = actions.fireCheck, modifier = wide) { Text(Copy.TRY_AGAIN) }
            }
            FireInstall.Step.NoDownload -> Row(wide, horizontalArrangement = Arrangement.End) {
                Button(onClick = actions.fireCheck) { Text(Copy.TRY_AGAIN) }
            }
            FireInstall.Step.Refused -> Row(wide, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (f.picked) TextButton(onClick = actions.firePick) { Text(Copy.PICK_ANOTHER) }
                Button(onClick = actions.fireDismiss) { Text("OK") }
            }
        }
    }
}

/** One numbered step of Or download it yourself. */
@Composable
private fun Numbered(n: Int, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).background(c.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, color = c.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

private enum class StepMark { Todo, Active, Done, Failed }

/** Download (or the picked file), the check, the install: each to do, under way, done, or failed. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FireSteps(f: FireInstall, device: Device) {
    val c = MaterialTheme.colorScheme
    val release = f.release
    val failed = f.step == FireInstall.Step.NotProjectivy
    val checked = f.step == FireInstall.Step.Installing
    Surface(shape = MaterialTheme.shapes.large, color = c.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when {
                f.picked -> StepLine(1, StepMark.Done, Copy.STEP_PICKED, f.size?.let(Copy::bytes))
                f.step == FireInstall.Step.Downloading -> {
                    val p = f.progress
                    val total = p?.total?.takeIf { it > 0 } ?: release?.size ?: 0L
                    val fraction = p?.fraction
                    StepLine(1, StepMark.Active, Copy.STEP_DOWNLOAD, Copy.downloading(((fraction ?: 0f) * 100).toInt(), p?.done ?: 0L, total)) {
                        if (fraction != null) LinearWavyProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
                        else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                else -> StepLine(1, StepMark.Done, Copy.STEP_DOWNLOADED, release?.let { Copy.release(it.version, it.size) })
            }
            when {
                failed -> StepLine(2, StepMark.Failed, Copy.STEP_FAILED, Copy.STEP_FAILED_TEXT)
                checked -> StepLine(2, StepMark.Done, Copy.STEP_CHECKED, Copy.STEP_CHECKED_TEXT)
                f.step == FireInstall.Step.Reading -> StepLine(2, StepMark.Active, Copy.STEP_CHECKING)
                else -> StepLine(2, StepMark.Todo, Copy.STEP_CHECK)
            }
            if (!failed) {
                if (checked) {
                    StepLine(3, StepMark.Active, Copy.installingOn(name(device))) {
                        // Past the last byte the TV is still unpacking: no bar stuck at 100%.
                        val p = f.progress?.fraction
                        if (p != null && p < 1f) LinearWavyProgressIndicator(progress = { p }, Modifier.fillMaxWidth())
                        else LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    }
                } else {
                    StepLine(3, StepMark.Todo, Copy.installOn(name(device)))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepLine(n: Int, mark: StepMark, title: String, text: String? = null, below: @Composable () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (mark) {
                StepMark.Todo -> Box(Modifier.size(22.dp).border(1.5.dp, c.outline, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$n", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
                StepMark.Active -> LoadingIndicator(Modifier.size(24.dp))
                StepMark.Done -> Box(Modifier.size(24.dp).background(c.primary, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = c.onPrimary)
                }
                StepMark.Failed -> Box(Modifier.size(24.dp).background(c.errorContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Close, null, Modifier.size(16.dp), tint = c.onErrorContainer)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (mark == StepMark.Todo) c.onSurfaceVariant else c.onSurface)
            text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) }
            below()
        }
    }
}

/** X while it downloads; Keep downloading is the filled one, as stopping throws the download away. */
@Composable
private fun StopDialog(onKeep: () -> Unit, onStop: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(Copy.STOP_TITLE) },
        text = { Text(Copy.STOP_TEXT) },
        confirmButton = { Button(onClick = onKeep) { Text(Copy.KEEP_DOWNLOADING) } },
        dismissButton = { TextButton(onClick = onStop) { Text(Copy.STOP) } },
    )
}

/** [millis] as the phone's clock shows it, like 18:44. */
private fun clockTime(millis: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))

/** Projectivy's repo, as the source row names it. */
private val PROJECTIVY_SOURCE = PROJECTIVY_RELEASES.removePrefix("https://").removeSuffix("/releases")

/**
 * Where the download comes from, as one tappable row: the repo, then the release and its size; it opens the
 * release page. With no [release] read, it says the latest one and opens that.
 */
@Composable
private fun SourceRow(release: LauncherRelease?, onOpen: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = c.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(LauncherIcons.Code, null, Modifier.size(20.dp), tint = c.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(PROJECTIVY_SOURCE, style = MaterialTheme.typography.bodyMedium, color = c.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(release?.let { Copy.release(it.version, it.size) } ?: Copy.LATEST_RELEASE, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(OpenInNew, null, Modifier.size(18.dp), tint = c.primary)
        }
    }
}

/** The warning and the failures: a filled warning in the error colour's cookie, so it reads at a glance. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WarningBadge() {
    val c = MaterialTheme.colorScheme
    Box(Modifier.size(56.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(c.errorContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Warning, null, Modifier.size(32.dp), tint = c.onErrorContainer)
    }
}

/** Row [i] of a segmented list ending at [last]: the ends rounded, the joins tight. */
private fun segment(i: Int, last: Int): RoundedCornerShape {
    val top = if (i == 0) 16.dp else 4.dp
    val bottom = if (i == last) 16.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** One launcher in the picker: its name, a chip when [have] is on the TV, then [details]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickRow(
    shape: RoundedCornerShape,
    on: Boolean,
    name: String,
    have: AppInfo?,
    vertical: Dp = 16.dp,
    onClick: () -> Unit,
    details: @Composable () -> Unit = {},
) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = shape,
        color = if (on) c.secondaryContainer else c.surfaceContainer,
        modifier = Modifier.fillMaxWidth().selectable(on, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, top = vertical, bottom = vertical, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                // The chip drops under the name rather than squeezing it at large font sizes.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    have?.let { InstalledChip(home = it.home) }
                }
                details()
            }
            RadioButton(selected = on, onClick = null)
        }
    }
}

/** Installed, outlined; Home now, filled, so it still stands out on the chosen row. */
@Composable
private fun InstalledChip(home: Boolean) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (home) c.tertiary else Color.Transparent,
        contentColor = if (home) c.onTertiary else c.onSurfaceVariant,
        border = if (home) null else BorderStroke(1.dp, c.outline),
    ) {
        Row(Modifier.height(24.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (home) Icons.Outlined.Home else Icons.Default.Check, null, Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (home) Copy.HOME_NOW else Copy.INSTALLED, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Hero(icon: ImageVector, bg: Color, fg: Color) {
    Box(Modifier.size(64.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(32.dp), tint = fg)
    }
}

@Composable
private fun Headed(title: String, text: AnnotatedString) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** No progress to show: the TV says nothing until the launcher is in. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WaitingLine() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(Copy.WAITING_INSTALL, style = MaterialTheme.typography.bodyMedium)
    }
}

/** [text] with each of [words] in medium weight, where it first appears. */
private fun bold(text: String, vararg words: String) = buildAnnotatedString {
    append(text)
    words.forEach { w -> text.indexOf(w).takeIf { it >= 0 }?.let { addStyle(SpanStyle(fontWeight = FontWeight.Medium), it, it + w.length) } }
}

private object LauncherIcons {
    // Absolute commands only: Compose's path parser misplaces a relative move after a close.
    val Download = icon("Download", "M5 20H19V18H5ZM19 9H15V3H9V9H5L12 16Z")
    val ErrorOutline = icon("ErrorOutline", "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2ZM12 4A8 8 0 1 1 12 20A8 8 0 1 1 12 4ZM11 7H13V13H11ZM11 15H13V17H11Z")
    val InfoOutline = icon("InfoOutline", "M12 2A10 10 0 1 0 12 22A10 10 0 1 0 12 2ZM12 4A8 8 0 1 1 12 20A8 8 0 1 1 12 4ZM11 11H13V17H11ZM11 7H13V9H11Z")
    val Code = icon("Code", "M9.4 16.6L4.8 12L9.4 7.4L8 6L2 12L8 18ZM14.6 16.6L19.2 12L14.6 7.4L16 6L22 12L16 18Z")

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
        .build()
}
