// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.tellyscope.adb.Connector
import app.tellyscope.data.AdbDeviceSource
import app.tellyscope.data.AndroidPhoneStorage
import app.tellyscope.data.FakeDeviceSource
import app.tellyscope.data.FoldBook
import app.tellyscope.data.UpdateCheck
import app.tellyscope.ui.AdvancedScreen
import app.tellyscope.ui.Copy
import app.tellyscope.ui.DebloatScreen
import app.tellyscope.ui.DeviceActions
import app.tellyscope.ui.DeviceTabsScreen
import app.tellyscope.ui.Done
import app.tellyscope.ui.PermissionScreen
import app.tellyscope.ui.RemoteActions
import app.tellyscope.ui.RemoteScreen
import app.tellyscope.ui.ScanScreen
import app.tellyscope.ui.SettingsScreen
import app.tellyscope.ui.TellyscopeViewModel
import app.tellyscope.ui.UpdateDialog
import app.tellyscope.ui.WelcomeScreen
import app.tellyscope.ui.theme.TellyscopeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
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
                                    )
                                }
                            },
                        )
                        TellyscopeApp(vm)
                    }
                }
            }
        }
    }

    private fun updateCheck(): UpdateCheck {
        @Suppress("DEPRECATION") // the flags overload is API 33+
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
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
    var debloat by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }

    // "*/*": file managers label .apks and .xapk bundles inconsistently, so a narrower type would grey them out.
    val pickApp = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.pickedApp(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::push) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::saveTo)
    val saveScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"), vm::screenshotTo)

    state.update?.let { u ->
        UpdateDialog(u, onGet = { vm.updateLater(); runCatching { browser.openUri(u.url) } }, onLater = vm::updateLater, onSkip = vm::skipUpdate)
    }

    // On every return to the app, not just a cold start; the check itself enforces the interval.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.checkForUpdate() }

    Snackbars(snackbar, vm.done, vm.notice, vm::doneShown, vm::noticeShown) { vm.undo(it.entry, it.deviceId) }

    // A newly connected TV becomes the selection; leave the add flow then, not on tap.
    LaunchedEffect(state.selectedId) { adding = false; remote = false; advanced = false; debloat = false; settings = false }

    if (state.selected == null || adding) {
        BackHandler(enabled = adding) { adding = false }
        ScanScreen(
            scan = state.scan,
            now = state.now,
            onRescan = vm::rescan,
            onConnect = vm::connect,
            onCode = vm::codeChanged,
            onPair = vm::pair,
            onAddByIp = vm::addByIp,
            onBack = if (adding) ({ adding = false }) else null,
            onOpenWifi = { context.openWifiSettings() },
            phoneKey = remember { Connector.fingerprint() },
        )
    } else if (remote) {
        BackHandler { remote = false }
        RemoteScreen(
            state = state,
            snackbar = snackbar,
            actions = RemoteActions(
                press = vm::press,
                type = vm::type,
                reboot = vm::reboot,
                back = { remote = false },
            ),
        )
    } else if (advanced) {
        BackHandler { advanced = false }
        AdvancedScreen(state, back = { advanced = false }, snackbar = snackbar)
    } else if (settings) {
        BackHandler { settings = false }
        SettingsScreen(
            state.listSource,
            onListSource = vm::setListSource,
            checkUpdates = state.checkUpdates,
            alphaBuild = state.alphaBuild,
            onCheckUpdates = vm::setCheckUpdates,
            back = { settings = false },
            snackbar = snackbar,
        )
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
        )
    } else {
        DeviceTabsScreen(
            state = state,
            snackbar = snackbar,
            actions = DeviceActions(
                select = vm::select,
                addDevice = { adding = true; vm.rescan() },
                settings = { settings = true },
                query = vm::query,
                propose = vm::propose,
                confirm = vm::confirm,
                cancel = vm::cancel,
                undo = vm::undo,
                reconnect = { state.selectedId?.let(vm::reconnect) },
                forget = vm::forget,
                rename = vm::rename,
                restoreAll = vm::restoreAll,
                pickApp = { pickApp.launch(arrayOf("*/*")) },
                allowDowngrade = vm::allowDowngrade,
                install = vm::install,
                closeSideload = vm::closeSideload,
                openInstalled = vm::openInstalled,
                folder = vm::folder,
                pickFile = { folder -> vm.folder(folder); pickFile.launch(arrayOf("*/*")) },
                saveFile = { f -> vm.saving(f); saveFile.launch(f.name) },
                cancelTransfer = vm::cancelTransfer,
                remote = { remote = true },
                debloat = { debloat = true },
                advanced = { advanced = true },
                checkUpdates = vm::checkUpdates,
                setDns = vm::setDns,
                setFastAnimations = vm::setFastAnimations,
                clearCaches = vm::clearCaches,
                screenshot = { saveScreenshot.launch(Copy.screenshotName(System.currentTimeMillis())) },
                answerRequest = vm::answerRequest,
                dismissRequest = vm::dismissRequest,
                foldSystem = vm::foldSystem,
            ),
        )
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

/** The Wi-Fi panel over the app on Android 10+, else the full Wi-Fi settings. */
private fun Context.openWifiSettings() {
    val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
    runCatching { startActivity(Intent(panel)) }
        .recoverCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
}
