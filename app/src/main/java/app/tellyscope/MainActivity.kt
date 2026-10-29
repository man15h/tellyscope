// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.tellyscope.adb.AppFacts
import app.tellyscope.adb.Connector
import app.tellyscope.adb.StatsProfile
import app.tellyscope.data.AdbDeviceSource
import app.tellyscope.data.AndroidLauncherDownload
import app.tellyscope.data.AndroidPhoneStorage
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceState
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.FoldBook
import app.tellyscope.data.SeenBook
import app.tellyscope.data.KeyHaptics
import app.tellyscope.data.UpdateCheck
import app.tellyscope.ui.AccessActions
import app.tellyscope.ui.AccessScreen
import app.tellyscope.ui.AdvancedScreen
import app.tellyscope.ui.ConnectScreen
import app.tellyscope.ui.Copy
import app.tellyscope.ui.DebloatRunScreen
import app.tellyscope.ui.DebloatScreen
import app.tellyscope.ui.DeviceActions
import app.tellyscope.ui.DeviceTabsScreen
import app.tellyscope.ui.Done
import app.tellyscope.ui.HistoryScreen
import app.tellyscope.ui.ResetActions
import app.tellyscope.ui.ResetScreen
import app.tellyscope.ui.LauncherActions
import app.tellyscope.ui.LauncherScreen
import app.tellyscope.ui.LocalNow
import app.tellyscope.ui.LoggedOutScreen
import app.tellyscope.ui.PermissionScreen
import app.tellyscope.ui.RemoteActions
import app.tellyscope.ui.RemoteScreen
import app.tellyscope.ui.ReportKind
import app.tellyscope.ui.ReportScreen
import app.tellyscope.ui.RowState
import app.tellyscope.ui.ScanScreen
import app.tellyscope.ui.SettingsScreen
import app.tellyscope.ui.TellyscopeViewModel
import app.tellyscope.ui.TvTab
import app.tellyscope.ui.UpdateDialog
import app.tellyscope.ui.WelcomeScreen
import app.tellyscope.ui.brands
import app.tellyscope.ui.clock
import app.tellyscope.ui.connectTitle
import app.tellyscope.ui.name
import app.tellyscope.ui.theme.TellyscopeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Real TVs by default. Launch with `--es scenario fake|empty|noallow` to run on
        // made-up data: the full app, the empty-scan guide, or the Allow timeout.
        val scenario = when (intent.getStringExtra("scenario")) {
            "fake" -> FakeDeviceSource.Scenario.Normal
            "empty" -> FakeDeviceSource.Scenario.Empty
            "noallow" -> FakeDeviceSource.Scenario.NoAllow
            else -> null
        }
        // Existing installs with a saved TV never see the welcome; the marker keeps it to once.
        val welcomed = File(filesDir, "welcomed")
        val showWelcome = scenario == null && !welcomed.exists() && File(filesDir, "devices.tsv").length() == 0L
        setContent {
            TellyscopeTheme {
                var welcome by rememberSaveable { mutableStateOf(showWelcome) }
                if (welcome) {
                    val done = { welcomed.createNewFile(); welcome = false }
                    WelcomeScreen(askLocalNetwork = Build.VERSION.SDK_INT >= 37, onContinue = done, onSkip = done)
                } else {
                    // Straight from the welcome, Android's own dialog follows without a second screen in between.
                    LocalNetworkGate(skip = scenario != null, askNow = showWelcome) {
                        val vm: TellyscopeViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    TellyscopeViewModel(
                                        scenario?.let(::FakeDeviceSource) ?: AdbDeviceSource(applicationContext),
                                        AndroidPhoneStorage(applicationContext),
                                        updates = if (scenario != null) null else updateCheck(),
                                        folds = FoldBook(File(filesDir, "folds.properties")),
                                        keyHaptics = KeyHaptics(File(filesDir, "remote.properties")),
                                        gates = FoldBook(File(filesDir, "gates.properties")),
                                        historySeen = SeenBook(File(filesDir, "history-seen.properties")),
                                        appFacts = AppFacts(installedVersion(), "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"),
                                        launcherDownload = if (scenario != null) null else AndroidLauncherDownload(applicationContext),
                                    )
                                }
                            },
                        )
                        val now = vm.now.collectAsStateWithLifecycle()
                        CompositionLocalProvider(LocalNow provides now) { TellyscopeApp(vm) }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION") // the flags overload is API 33+
    private fun installedVersion() = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    private fun updateCheck(): UpdateCheck {
        val version = installedVersion()
        // Alpha builds install as app.tellyscope.alpha (app/build.gradle.kts).
        return UpdateCheck(File(filesDir, "updates.properties"), version, alpha = packageName.endsWith(".alpha"))
    }

    /**
     * Android 17 blocks LAN connections and mDNS for apps targeting 37 until the user grants
     * ACCESS_LOCAL_NETWORK, so nothing scans until then. The view model is created inside
     * [content], which keeps the first scan behind the grant.
     */
    @Composable
    private fun LocalNetworkGate(skip: Boolean, askNow: Boolean, content: @Composable () -> Unit) {
        // Not in Manifest.permission below API 37; the string is the same on every SDK.
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        fun granted() = skip || Build.VERSION.SDK_INT < 37 ||
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

        var ok by remember { mutableStateOf(granted()) }
        var denied by rememberSaveable { mutableStateOf(false) }
        var canAsk by rememberSaveable { mutableStateOf(true) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { yes ->
            ok = yes
            denied = !yes
            // No rationale after a refusal means Android won't show its dialog again: only Settings can grant it.
            canAsk = shouldShowRequestPermissionRationale(permission)
        }
        LaunchedEffect(Unit) { if (askNow && !ok && !denied) launcher.launch(permission) }
        // Coming back from Settings.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ok = granted() }

        if (ok) {
            content()
        } else {
            PermissionScreen(
                denied = denied,
                canAsk = canAsk,
                onAllow = { launcher.launch(permission) },
                onSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                },
            )
        }
    }
}

@Composable
fun TellyscopeApp(vm: TellyscopeViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val browser = LocalUriHandler.current
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var remote by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    // The TV's page comes back on the tab it was left on, from History or any screen its menu opens.
    var tvTab by rememberSaveable { mutableStateOf(TvTab.Overview) }
    var debloat by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var access by rememberSaveable { mutableStateOf(false) }
    // Connect again from the logged-out page: which TV, and since when, for its Allow screen.
    var rejoinId by rememberSaveable { mutableStateOf<String?>(null) }
    var rejoinSince by rememberSaveable { mutableStateOf(0L) }

    val pickApp = rememberLauncherForActivityResult(InDownloads.Multiple()) { vm.pickedApp(it) }
    val pickProjectivy = rememberLauncherForActivityResult(InDownloads.One()) { uri -> uri?.let(vm::pickedProjectivy) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::push) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::saveTo)
    val saveScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), vm::screenshotTo)

    state.update?.let { u ->
        UpdateDialog(u, onGet = { vm.updateLater(); runCatching { browser.openUri(u.url) } }, onLater = vm::updateLater, onSkip = vm::skipUpdate)
    }

    // On every return to the app, not just a cold start; the check itself enforces the interval.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.checkForUpdate() }
    // In the background nothing reads the TVs or sweeps the LAN; back on screen, everything picks up again.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.resume() }

    Snackbars(snackbar, vm.done, vm.notice, vm::doneShown, vm::noticeShown) { vm.undo(it.entry, it.deviceId) }
    LoggedOutSnackbars(snackbar, { state.devices }, { state.selectedId }, onView = vm::select)

    // A newly connected TV becomes the selection; leave the add flow then, not on tap.
    LaunchedEffect(state.selectedId) { adding = false; remote = false; advanced = false; history = false; resetting = false; debloat = false; settings = false; access = false; rejoinId = null }
    // Logged out mid-screen: the page takes over, and the TV comes back on Overview once allowed again.
    val loggedOutAt = state.selected?.loggedOutAt
    LaunchedEffect(loggedOutAt) { if (loggedOutAt != null) { remote = false; advanced = false; history = false; resetting = false; debloat = false; settings = false; access = false } }
    // The Allow screen while the TV is asked; allowed lands on Overview, a Deny there is a plain refusal (its card).
    val rejoining = state.selected?.takeIf { it.id == rejoinId && it.state == DeviceState.Waiting }
    LaunchedEffect(state.selected?.state, loggedOutAt) {
        val st = state.selected?.state
        if (st == DeviceState.Online || (st == DeviceState.NotAllowed && loggedOutAt == null)) rejoinId = null
    }

    LaunchedEffect(adding) { vm.addingTv(adding) }
    if (state.selected == null || adding) {
        BackHandler(enabled = adding) { adding = false }
        ScanScreen(
            scan = state.scan,
            now = state.clock(),
            onRescan = vm::rescan,
            onConnect = vm::connect,
            onCode = vm::codeChanged,
            onPair = vm::pair,
            onAddByIp = vm::addByIp,
            onBack = if (adding) ({ adding = false }) else null,
            onOpenWifi = { context.openWifiSettings() },
            phoneKey = remember { Connector.fingerprint() },
        )
    } else if (state.report != null) {
        BackHandler { vm.closeReport() }
        state.report?.let { ReportScreen(it, onOpened = vm::reportOpened, close = vm::closeReport) }
    } else if (rejoining != null) {
        val cancel = { vm.cancelReconnect(rejoining.id); rejoinId = null }
        BackHandler(onBack = cancel)
        ConnectScreen(
            hit = rejoining.hit,
            row = RowState.Waiting(rejoinSince),
            now = state.clock(),
            brand = brands.first(),
            phoneKey = remember { Connector.fingerprint() },
            onCode = {},
            onPair = {},
            onRetry = { vm.reconnect(rejoining.id) },
            onBack = cancel,
            title = connectTitle(rejoining),
        )
    } else if (state.selected != null && loggedOutAt != null) {
        val selected = state.selected!!
        // Back to TVs: another remembered TV, online ones first, else the scan list.
        val back = {
            val other = state.devices.filter { it.id != selected.id }
            val next = other.firstOrNull { it.state == DeviceState.Online } ?: other.firstOrNull()
            if (next != null) vm.select(next.id) else { adding = true; vm.rescan() }
        }
        BackHandler(onBack = back)
        LoggedOutScreen(
            device = selected,
            loggedOutAt = loggedOutAt,
            now = state.clock(),
            onConnect = { rejoinId = selected.id; rejoinSince = System.currentTimeMillis(); vm.reconnect(selected.id) },
            onBack = back,
        )
    } else if (advanced) {
        BackHandler { advanced = false }
        DisposableEffect(Unit) {
            vm.showStats(StatsProfile.ADVANCED)
            onDispose { vm.showStats(StatsProfile.OTHER) }
        }
        AdvancedScreen(state, back = { advanced = false }, snackbar = snackbar)
    } else if (history) {
        BackHandler { history = false }
        // Seen on the way in, and again on the way out for changes made here, so ⋮ has no dot for them.
        DisposableEffect(Unit) {
            vm.historyOpened()
            onDispose { vm.historyOpened() }
        }
        HistoryScreen(state, onUndo = vm::undo, onRestoreAll = vm::restoreAll, back = { history = false }, snackbar = snackbar)
    } else if (resetting && state.device.reset != null) {
        val close = { vm.closeReset(); resetting = false }
        // Leaving mid-run only closes the screen; the run goes on, and History has it when done.
        BackHandler(onBack = close)
        ResetScreen(
            state,
            ResetActions(reset = { vm.runReset() }, retry = { vm.runReset(retry = true) }, reload = vm::openReset, back = close),
        )
    } else if (access || state.device.removal != null) {
        // A removal started from the warning opens this screen too, and back steps out of it first.
        BackHandler { if (state.device.removal != null) vm.closeRemoval() else access = false }
        AccessScreen(state, accessActions(vm) { access = false }, snackbar = snackbar)
    } else if (settings) {
        BackHandler { settings = false }
        SettingsScreen(
            state.listSource,
            onListSource = vm::setListSource,
            checkUpdates = state.checkUpdates,
            alphaBuild = state.alphaBuild,
            onCheckUpdates = vm::setCheckUpdates,
            keyHaptics = state.keyHaptics,
            onKeyHaptics = vm::setKeyHaptics,
            onReport = { vm.openReport(ReportKind.Problem) },
            back = { settings = false },
            snackbar = snackbar,
        )
    } else if (state.device.launcher != null) {
        BackHandler { vm.closeLauncherFlow() }
        LauncherScreen(
            state.selected!!, state.device.launcher!!, state.device.apps,
            LauncherActions(
                close = vm::closeLauncherFlow,
                install = vm::installLauncher,
                chooseAnother = vm::pickLauncher,
                switch = vm::switchToLauncher,
                makeHome = vm::makeHome,
                open = vm::openLauncher,
                later = vm::launcherLater,
                fireCheck = vm::checkProjectivy,
                fireInstall = vm::installProjectivy,
                fireDismiss = vm::dismissProjectivy,
                firePick = { pickProjectivy.launch(arrayOf(InDownloads.APK)) },
            ),
            snackbar = snackbar,
            direct = vm.directDownload,
        )
    } else if (debloat && state.device.debloat != null) {
        // Leaving mid-run only closes the screen; the run goes on, and History has each app.
        DebloatRunScreen(state.device.debloat!!, back = { vm.closeDebloat(); debloat = false }, retry = vm::retryDebloat)
    } else if (debloat) {
        // Its own back handler closes the list search before leaving.
        DebloatScreen(
            state.device.lists,
            onPick = vm::pickList,
            onDisable = vm::disableSuggested,
            back = { debloat = false },
            snackbar = snackbar,
            source = state.listSource,
            tvMakers = listOfNotNull(state.selected?.brand, state.selected?.manufacturer).distinct(),
            tv = state.selected,
            tvApps = state.device.apps,
            busy = state.device.busy != null,
            onHelpAdd = { vm.openReport(ReportKind.NewTv) },
        )
    } else {
        DeviceTabsScreen(
            state = state,
            snackbar = snackbar,
            startTab = tvTab,
            onTab = { tvTab = it },
            actions = DeviceActions(
                select = vm::select,
                addDevice = { adding = true; vm.rescan() },
                settings = { settings = true },
                query = vm::query,
                propose = vm::propose,
                confirm = vm::confirm,
                confirmHome = vm::confirmHome,
                cancel = vm::cancel,
                homeServiceOn = vm::homeServiceOn,
                dismissServiceNote = vm::dismissServiceNote,
                installLauncher = vm::openLauncherFlow,
                hideLauncherCard = vm::hideLauncherCard,
                debloatGateOff = vm::debloatGateOff,
                undo = vm::undo,
                reconnect = { state.selectedId?.let(vm::reconnect) },
                forget = vm::forget,
                rename = vm::rename,
                restoreAll = vm::restoreAll,
                pickApp = { pickApp.launch(InDownloads.APPS) },
                allowDowngrade = vm::allowDowngrade,
                install = vm::install,
                closeSideload = vm::closeSideload,
                reportInstall = { s ->
                    vm.closeSideload()
                    vm.openReport(ReportKind.Problem, s.app?.let { "Tried to install ${it.label} ${it.info.versionName.orEmpty()}".trim() }.orEmpty(), s.detail)
                },
                openInstalled = vm::openInstalled,
                folder = vm::folder,
                pickFile = { folder -> vm.folder(folder); pickFile.launch(arrayOf("*/*")) },
                saveFile = { f -> vm.saving(f); saveFile.launch(f.name) },
                cancelTransfer = vm::cancelTransfer,
                remote = { remote = true },
                // A run that finished while Debloat was closed is done with.
                debloat = { vm.closeDebloat(); debloat = true },
                advanced = { advanced = true },
                history = { history = true },
                reset = { vm.openReset(); resetting = true },
                checkUpdates = vm::checkUpdates,
                setDns = vm::setDns,
                setFastAnimations = vm::setFastAnimations,
                clearCaches = vm::clearCaches,
                screenshot = { saveScreenshot.launch(Copy.screenshotName(System.currentTimeMillis())) },
                answerRequest = vm::answerRequest,
                dismissRequest = vm::dismissRequest,
                closeAlert = vm::closeAlert,
                foldSystem = vm::foldSystem,
                showStats = vm::showStats,
                whoHasAccess = { access = true },
                access = accessActions(vm) { access = false },
            ),
        )
        // A sheet over the TV's page, so the page stays where it was underneath; Back or a drag closes it.
        if (remote) {
            DisposableEffect(Unit) { onDispose { vm.closeRemote() } }
            RemoteScreen(
                state = state,
                snackbar = snackbar,
                actions = RemoteActions(
                    press = vm::press,
                    type = vm::type,
                    reboot = vm::reboot,
                    back = { remote = false },
                    retryRestart = vm::retryRestart,
                    stopWaiting = vm::stopWaiting,
                    launch = vm::launch,
                    setDock = vm::setDock,
                    screenOff = vm::setScreenOff,
                    screenOffTipSeen = vm::screenOffTipSeen,
                ),
            )
        }
    }
}

/**
 * Shows each done and notice message once. They are collected rather than used as effect keys:
 * clearing a message changes the key, which would cancel its own snackbar before anyone saw it.
 */
@Composable
internal fun Snackbars(
    host: SnackbarHostState,
    done: StateFlow<Done?>,
    notice: StateFlow<String?>,
    doneShown: () -> Unit,
    noticeShown: () -> Unit,
    undo: (Done) -> Unit,
) {
    LaunchedEffect(host, done) {
        done.filterNotNull().collect { d ->
            doneShown()
            val result = host.showSnackbar(
                message = Copy.done(d.entry.action, d.entry.label),
                actionLabel = d.entry.undo?.let(Copy::undo),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) undo(d)
        }
    }
    LaunchedEffect(host, notice) {
        notice.filterNotNull().collect { message ->
            noticeShown()
            host.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }
}

/**
 * A TV logged out while another one is on screen gets one snackbar; View opens its logged-out page.
 * TVs already logged out at launch get none, their chip badge says it. Read through [devices] and
 * [selectedId] so the watch outlives each change instead of restarting and cancelling its snackbar.
 */
@Composable
internal fun LoggedOutSnackbars(host: SnackbarHostState, devices: () -> List<Device>, selectedId: () -> String?, onView: (String) -> Unit) {
    LaunchedEffect(host) {
        var seen: Set<String>? = null
        snapshotFlow { devices().filter { it.loggedOutAt != null } }.collect { out ->
            val ids = out.map { it.id }.toSet()
            val fresh = seen?.let { before -> out.filter { it.id !in before && it.id != selectedId() } }.orEmpty()
            seen = ids
            fresh.forEach { d ->
                launch {
                    val result = host.showSnackbar(Copy.loggedOutOf(name(d)), actionLabel = "View", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) onView(d.id)
                }
            }
        }
    }
}

/** The Wi-Fi panel over the app on Android 10+, else the full Wi-Fi settings. */
private fun Context.openWifiSettings() {
    val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
    runCatching { startActivity(Intent(panel)) }
        .recoverCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
}

private fun accessActions(vm: TellyscopeViewModel, back: () -> Unit) = AccessActions(
    back = back,
    markMine = vm::markMine,
    markKeyMine = vm::markKeyMine,
    showRequest = vm::showRequest,
    answerRequest = vm::answerRequest,
    dismissRequest = vm::dismissRequest,
    closeAlert = vm::closeAlert,
    askRemove = vm::askRemoveAccess,
    close = vm::closeRemoval,
    remove = vm::removeAccess,
    openDeveloperOptions = vm::openDeveloperOptions,
    revokedByHand = vm::revokedByHand,
    askAgain = vm::askTvAgain,
    retry = vm::retryRemoval,
    checkAgain = vm::checkAgain,
    restart = vm::restartToRemove,
)

/**
 * The system file picker, opened in Downloads, where a browser saves what it downloads. Most pickers
 * start there; some ignore it. It can't be kept there, or searched for the user.
 */
private object InDownloads {
    const val APK = "application/vnd.android.package-archive"

    /**
     * Apps and the bundle types file managers give .apks and .xapk files, which vary: zip, unknown
     * ("octet-stream"), or a type of their own. Photos, videos and documents are greyed out.
     */
    val APPS = arrayOf(APK, "application/zip", "application/x-zip-compressed", "application/octet-stream", "application/xapk-package-archive")

    private val DOWNLOADS: Uri = DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", "downloads")

    class One : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS)
    }

    class Multiple : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS)
    }
}
