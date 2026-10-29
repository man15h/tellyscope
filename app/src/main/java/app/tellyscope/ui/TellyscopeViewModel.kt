// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.AppFacts
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.InstallKind
import app.tellyscope.adb.StatsProfile
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.adb.Peripheral
import app.tellyscope.adb.Progress
import app.tellyscope.adb.Remote
import app.tellyscope.adb.RemoteKey
import app.tellyscope.adb.Report
import app.tellyscope.adb.Reports
import app.tellyscope.adb.TvFacts
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.adb.Tweak
import app.tellyscope.adb.installKind
import app.tellyscope.data.Action
import app.tellyscope.data.AppInfo
import app.tellyscope.data.ConnectResult
import app.tellyscope.data.Device
import app.tellyscope.data.DeviceSource
import app.tellyscope.data.DeviceState
import app.tellyscope.data.Dock
import app.tellyscope.data.Docked
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.KeyHaptics
import app.tellyscope.data.LauncherDownload
import app.tellyscope.data.LauncherRelease
import app.tellyscope.data.NetworkInfo
import app.tellyscope.data.NotAnApp
import app.tellyscope.data.NotProjectivy
import app.tellyscope.data.PROJECTIVY
import app.tellyscope.data.PhoneStorage
import app.tellyscope.data.PickedApp
import app.tellyscope.data.RateLimited
import app.tellyscope.data.RemoteButtons
import app.tellyscope.data.ResetApp
import app.tellyscope.data.ResetFailure
import app.tellyscope.data.ResetPart
import app.tellyscope.data.ResetPlan
import app.tellyscope.data.ResetProgress
import app.tellyscope.data.ScanEvent
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.ScanProgress
import app.tellyscope.data.Snapshot
import app.tellyscope.data.StoreState
import app.tellyscope.data.Suggestions
import app.tellyscope.data.TvAccess
import app.tellyscope.data.TvSettings
import app.tellyscope.data.TvTweaks
import app.tellyscope.data.Update
import app.tellyscope.data.FoldBook
import app.tellyscope.data.SeenBook
import app.tellyscope.data.UpdateCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Per-row connect state on the scan screen. */
sealed interface RowState {
    data object Found : RowState
    data class Waiting(val since: Long) : RowState
    /** [error] is the plain sentence; [detail] is what the TV said, shown under Details. */
    data class Code(val input: String = "", val busy: Boolean = false, val error: String? = null, val detail: String? = null) : RowState
    data object NotAllowed : RowState
}

data class ScanUi(
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    /** Not on Wi-Fi or Ethernet, so there was no sweep. */
    val noLan: Boolean = false,
    val hits: List<ScanHit> = emptyList(),
    val rows: Map<String, RowState> = emptyMap(),
    /** Nothing found yet: when the next scan starts by itself. Null while a scan runs or once a TV turns up. */
    val nextScanAt: Long? = null,
) {
    /** Off Wi-Fi with nothing found: the scan screen asks the user to join one instead of listing. */
    val offWifi get() = noLan && hits.isEmpty()
}

data class DeviceUi(
    val snapshot: Snapshot? = null,
    val apps: List<AppInfo> = emptyList(),
    /** The first read of [apps] after opening the TV is still out; ends on success, empty or error. */
    val appsLoading: Boolean = false,
    val network: NetworkInfo? = null,
    val history: List<HistoryEntry> = emptyList(),
    /** Package whose action row is open, and the action awaiting confirmation. */
    val pending: Pair<String, Action>? = null,
    val busy: String? = null,
    /** The install dialog; null when it's closed. */
    val sideload: SideloadUi? = null,
    val files: FilesUi = FilesUi(),
    /** A file copy in progress; its dialog stays up until it finishes or Cancel is tapped. */
    val transfer: TransferUi? = null,
    /** The TV's debloat list; null while it loads. */
    val lists: Suggestions? = null,
    /** Private DNS and animation speed; null until read, or when the TV wouldn't say. */
    val tweaks: TvTweaks? = null,
    /** Another device asking the TV for debugging access; null when nothing is asking. */
    val request: ConnectionRequest? = null,
    /** The Remote's app shortcuts, in order. */
    val dock: List<Docked> = emptyList(),
    /** Who may use ADB on the TV and who is connected; null until first read. */
    val access: TvAccess? = null,
    /** What's plugged in or paired; null until first read. */
    val peripherals: List<Peripheral>? = null,
    /** "Remove all other access", while it runs; null otherwise. */
    val removal: Removal? = null,
    /** Install a home screen, while it's open; null otherwise. */
    val launcher: LauncherFlow? = null,
    /** Not now on the Apps tab's home-screen card, for this TV. True until read, so the card never flashes. */
    val launcherCardHidden: Boolean = true,
    /** Reset TV, while its screen is open; null otherwise. */
    val reset: ResetUi? = null,
    /** Debloat's run, from "Yes, disable them" until its screen closes; null otherwise. */
    val debloat: DebloatRun? = null,
)

/**
 * Debloat turning off the ticked [apps], in order. [at] counts the ones the TV has answered for,
 * and [failed] holds why each refused one is still on. [ran] once the last has answered; Try again
 * runs just the failed ones.
 */
data class DebloatRun(
    val apps: List<ResetApp>,
    val at: Int = 0,
    val failed: Map<String, String> = emptyMap(),
    val ran: Boolean = false,
)

/**
 * Reset TV's screen. [plan] is what the TV said, null while it's read ([error] if that failed).
 * While a run goes, [running] is what it's doing and [progress] how far. After one, [ran] is set
 * and [failed] holds the steps that didn't work; Try again runs just those, into History's [entry].
 */
data class ResetUi(
    val plan: ResetPlan? = null,
    val error: String? = null,
    val running: ResetPlan? = null,
    val progress: ResetProgress? = null,
    val ran: Boolean = false,
    val failed: List<ResetFailure> = emptyList(),
    val entry: Long? = null,
)

/**
 * Install a home screen: pick a launcher ([Step.Pick]), install it from the Play Store the TV
 * opens at its page ([Step.Waiting], or [Step.NoStore] when the Store didn't open and the user
 * searches by hand), then switch to it ([Step.Installed]). [Step.Left]: the Store closed first.
 */
data class LauncherFlow(val step: Step = Step.Pick, val pkg: String = RECOMMENDED.first().pkg, val fire: FireInstall? = null) {
    enum class Step { Pick, Waiting, NoStore, Left, Installed }

    /** Fire OS: Projectivy alone, with no Play Store; not Download directly beside the Store ([FireInstall.store]). */
    val fireOs get() = fire != null && !fire.store
}

/**
 * Install Projectivy on Fire OS ([fireInstall]), on [LauncherFlow.Step.Pick] in place of the picker: there's no
 * Play Store, so Tellyscope reads the latest release ([Step.Checking]), asks ([Step.Confirm]), downloads and
 * checks it ([Step.Downloading]), installs it over adb ([Step.Installing]), then moves to [LauncherFlow.Step.Installed].
 * A null [step] is the picker with nothing started. [error] is the TV's refusal, for [Step.Refused].
 * [store]: Download directly on a TV with the Play Store, from the picker ([downloadable]); its steps offer the
 * Store instead, and closing one goes back to the picker.
 * After [Step.NoDownload] the user can download it themselves and pick the file ([picked]): [Step.Reading] checks
 * it, then it installs the same way. [until]: GitHub refused the lookup until then, epoch millis, not the network.
 */
data class FireInstall(
    val step: Step? = null,
    val release: LauncherRelease? = null,
    val progress: Progress? = null,
    val error: String? = null,
    val store: Boolean = false,
    val picked: Boolean = false,
    val until: Long? = null,
    /** The APK's size once it's downloaded or picked, for the steps' first line. */
    val size: Long? = null,
) {
    enum class Step { Checking, Confirm, Downloading, Reading, Installing, NotProjectivy, NoDownload, Refused }
}

/**
 * "Remove all other access", one step at a time. The TV's keys are cleared ([Step.Revoking], or
 * by hand in Developer options when the call isn't available: [Step.Manual]); Tellyscope then
 * reconnects and the TV asks to allow this phone ([Step.Allow]). [Step.Done] only after a re-read
 * shows nobody else; [Step.StillConnected] names who is left, [Step.Unconfirmed] means the TV
 * wouldn't say. From there, [Step.Restarting] restarts the TV to end the connection, then checks again.
 */
data class Removal(
    val step: Step,
    /** This phone's fingerprint, to compare with the TV's prompt. */
    val fingerprint: String? = null,
    /** The TV said no to this phone (Cancel, or the prompt timed out): offer to ask again. */
    val refused: Boolean = false,
    /** Who is still there, for [Step.StillConnected]: "a connection from …", or a computer's name. */
    val still: String? = null,
    /** [still] is a connection left open, which only a restart ends; false when a computer was allowed again. */
    val open: Boolean = false,
) {
    enum class Step { Confirm, Revoking, Manual, Allow, Checking, Done, StillConnected, Restarting, Unconfirmed }
}

data class SideloadUi(
    val step: Step = Step.Reading,
    /** Null while the picked file is still being read. */
    val app: PickedApp? = null,
    val installed: InstalledVersion? = null,
    /** Set only by the user's own tick on the downgrade warning. */
    val allowDowngrade: Boolean = false,
    val progress: Progress? = null,
    val error: String? = null,
    /** The TV's own words for a refusal, shown small under the explanation. */
    val detail: String? = null,
) {
    enum class Step { Reading, Ready, Installing, Installed, Failed }

    val kind: InstallKind? get() = app?.let { installKind(it.info, installed) }
}

/** Report a problem goes to Tellyscope's tracker; Help add this TV to cleanyourtv's. */
enum class ReportKind { Problem, NewTv }

/** The report screen. The form itself (dropdowns, what the user writes) lives on the screen. */
data class ReportUi(
    val kind: ReportKind,
    val app: AppFacts,
    /** How the TV is connected, or COULD_NOT_CONNECT with no TV online. */
    val connection: Reports.Connection,
    /** The TV, read when the screen opened; null while reading, with no TV online, or if it went away. */
    val tv: TvFacts? = null,
    val reading: Boolean = false,
    /** What the entry point already knows, to start the user's details: the app that failed to install. */
    val details: String = "",
    /** This session's messages, then the TV's own words for a failed install. */
    val log: String? = null,
    /** Opened on GitHub with something cut: the screen offers to add it as a comment. */
    val posted: Report? = null,
)

data class FilesUi(
    val folder: TvFolder = TvFolder.DOWNLOAD,
    /** Null while loading. */
    val list: List<TvFile>? = null,
    val error: String? = null,
)

data class TransferUi(val name: String, val toTv: Boolean, val folder: TvFolder, val progress: Progress? = null)

data class UiState(
    val scan: ScanUi = ScanUi(),
    val devices: List<Device> = emptyList(),
    val selectedId: String? = null,
    val device: DeviceUi = DeviceUi(),
    val query: String = "",
    /** When this state was made; it doesn't tick. Screens read the time with [clock]. */
    val now: Long = System.currentTimeMillis(),
    /** TVs asked to restart, by id. */
    val restarting: Map<String, Restart> = emptyMap(),
    /** The user's own debloat-list source; null for tellyscope-devices. */
    val listSource: String? = null,
    /** The every-30-minutes look at GitHub for a newer release; null when this build has none (screenshots, tests). */
    val checkUpdates: Boolean? = null,
    /** An alpha build, which checks every 5 minutes rather than every 30 minutes. */
    val alphaBuild: Boolean = false,
    /** A newer release to offer; cleared once the user answers. */
    val update: Update? = null,
    /** A Remote key press vibrates. */
    val keyHaptics: Boolean = true,
    /** The TVs whose picture the Remote turned off. */
    val screenOff: Set<String> = emptySet(),
    /** The Remote's Screen off button explains itself, the first time only. */
    val screenOffTip: Boolean = false,
    /** Debloat with nothing left on the list explains why it opens Apps, until Don't show again. */
    val debloatGate: Boolean = true,
    /** The Apps tabs whose System section the user folded away. */
    val foldedSystem: Set<AppsTab> = emptySet(),
    /** The report screen; null when it's closed. */
    val report: ReportUi? = null,
    /** When each TV's History was last opened, by id. */
    val historySeen: Map<String, Long> = emptyMap(),
) {
    /** The selected TV has a change since its History was last opened: ⋮ shows a dot. */
    val historyNew: Boolean get() = selectedId?.let { id -> device.history.any { it.at > (historySeen[id] ?: 0L) } } == true
    val selected: Device? get() = devices.firstOrNull { it.id == selectedId }
}

/** An action that went through on [deviceId]; the snackbar offers its undo. */
data class Done(val deviceId: String, val entry: HistoryEntry)

/**
 * A restart in progress: [down] once the TV has dropped, so its coming back can be told apart from
 * not having left. Each wait lasts [RESTART_WAIT_MS] from [waitFrom]: the first from [since], the
 * next from each Retry.
 */
data class Restart(val since: Long, val down: Boolean = false, val waitFrom: Long = since, val retried: Boolean = false) {
    enum class Phase { Waiting, Late, Lost }

    /**
     * Late once the first minute is up; Lost when a Retry's minute is up too. A TV that never
     * dropped stays Waiting: it ignored the restart, which [RESTART_GRACE_MS] reports instead.
     */
    fun phase(now: Long) = when {
        !down || now - waitFrom < RESTART_WAIT_MS -> Phase.Waiting
        retried -> Phase.Lost
        else -> Phase.Late
    }
}

/** The newest messages a report's log keeps. */
private const val LOG_LINES = 200

/** The gates book's key once the user said Don't show again on the Debloat gate. */
private const val DEBLOAT_GATE = "debloat"

/** Most TVs are back within this; after it the Remote asks whether to keep waiting. */
const val RESTART_WAIT_MS = 60_000L

/** [DeviceUi.busy] value while "Restore everything" runs. */
const val RESTORING = "restore"

/** [DeviceUi.busy] value while Reset TV runs. */
const val RESETTING = "reset"

/** [DeviceUi.busy] value while the ticked suggestions are being disabled. */
const val DISABLING = "lists"

/** [DeviceUi.busy] values for the TV tweaks; a setting change uses its [Tweak] name. */
const val CLEARING = "caches"
const val SCREENSHOT = "screenshot"

/** [DeviceUi.busy] value while the TV's connection prompt is being answered. */
const val ANSWERING = "request"

/** How long Fire OS takes to hand focus from its closed alert to the prompt under it. */
private const val ALERT_CLOSING_MS = 1_000L

/** How often Install a home screen asks the TV whether the launcher is in yet. */
const val STORE_POLL_MS = 3_000L

/** An empty scan starts again after this long, unless Rescan is tapped first. */
const val RESCAN_EVERY_MS = 10_000L

/** How long it keeps asking before it says the launcher didn't arrive. */
const val STORE_WAIT_MS = 10 * 60_000L

/** Where a removal ends, from the re-read after it: never Done on a guess. */
fun outcome(access: TvAccess?): Removal = when {
    access?.connected == null || access.allowed == null -> Removal(Removal.Step.Unconfirmed)
    access.onlyThisPhone -> Removal(Removal.Step.Done)
    else -> access.others.firstOrNull()?.let { Removal(Removal.Step.StillConnected, still = Copy.connectionFrom(it.address), open = true) }
        ?: Removal(Removal.Step.StillConnected, still = access.allowed.firstOrNull { !it.phone }?.let { it.name ?: Copy.UNNAMED_COMPUTER })
}

class TellyscopeViewModel(
    private val source: DeviceSource,
    private val storage: PhoneStorage? = null,
    private val allowTimeoutMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val updates: UpdateCheck? = null,
    private val folds: FoldBook? = null,
    private val keyHaptics: KeyHaptics? = null,
    /** App-wide prompts the user turned off: [DEBLOAT_GATE]. */
    private val gates: FoldBook? = null,
    private val historySeen: SeenBook? = null,
    /** This build and phone, for reports. */
    private val appFacts: AppFacts = AppFacts("unknown", "unknown phone"),
    /** Projectivy from its developer's GitHub, for Fire OS. */
    private val launcherDownload: LauncherDownload? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState(now = clock()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The clock, every second. Apart from [state], so a tick redraws only what shows a time. */
    private val _now = MutableStateFlow(clock())
    val now: StateFlow<Long> = _now.asStateFlow()

    /** One-shot "done, undo?" messages for the snackbar, with the TV they happened on. */
    private val _done = MutableStateFlow<Done?>(null)
    val done: StateFlow<Done?> = _done.asStateFlow()

    /** One-shot snackbar lines: the TV refused, went away mid-action, or a restore or copy finished. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** This session's messages, newest last, for a report's log. Kept in memory only. */
    private val sessionLog = ArrayDeque<String>()

    /** Last stats per TV, so an offline page shows where it stood instead of blanks. Lives as long as the app. */
    private val lastSnapshot = mutableMapOf<String, Snapshot>()
    /** Each TV's app list and debloat list as last shown, so switching back shows them while fresh reads replace them. */
    private val lastApps = mutableMapOf<String, List<AppInfo>>()
    private val lastLists = mutableMapOf<String, Suggestions>()

    /** Keys go out one at a time, in the order pressed. */
    private val keys = Mutex()
    /**
     * Per TV, the letters the Remote typed that are still in the TV's text box, as far as the phone
     * knows. Backspace deletes only these: on an empty box Delete acts as Back, and YouTube closes.
     * Any other key may move the focus out of the box, and so may closing the Remote, so both forget them.
     */
    private val typed = mutableMapOf<String, Int>()

    private var scanJob: Job? = null
    /** Rescan tapped while an empty scan waits: cuts the wait short. */
    private val scanNow = Channel<Unit>(Channel.CONFLATED)
    /** The add flow is open over a selected TV, so an empty scan keeps looking there too. */
    private var addingTv = false
    private var sideloadJob: Job? = null
    /** File copies by TV: each dialog shows only on its own TV's page, and its Cancel stops only that copy. */
    private val transferJobs = mutableMapOf<String, Job>()
    private val transfers = ConcurrentHashMap<String, TransferUi>() // progress arrives off the main thread
    /** History entries being undone, so a second tap can't run the same Undo again. */
    private val undoInFlight = mutableSetOf<Long>()
    private var liveJob: Job? = null
    private var mediumJob: Job? = null
    private var listsJob: Job? = null
    private var requestJob: Job? = null
    private var accessJob: Job? = null
    /** Runs the removal across the reconnect it causes, so [select] doesn't stop it. */
    private var removalJob: Job? = null
    /** Watches the TV for a launcher's install from the Play Store. */
    private var launcherJob: Job? = null
    private var updateJob: Job? = null
    /** The clock and the device refresh, every second. */
    private var tickJob: Job? = null
    /** In the background since [pause]; [resume] starts everything again. */
    private var paused = false
    /** The scan was running, or asked for, while in the background, so [resume] starts it. */
    private var scanOnResume = false
    /** The fingerprint the user waved away on the phone; its prompt stays hidden while the TV shows it. */
    private var dismissedRequest: String? = null

    init {
        viewModelScope.launch {
            // Remembered TVs open straight to their page (offline until they answer).
            val known = source.known()
            val lists = runCatching { source.listSource() }.getOrNull()
            _state.update { it.copy(devices = known, listSource = lists) }
            if (_state.value.selectedId == null) known.firstOrNull()?.let { select(it.id) }
        }
        startTick()
        updates?.let { u ->
            viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(checkUpdates = u.enabled, alphaBuild = u.alpha) } }
        }
        keyHaptics?.let { k ->
            viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(keyHaptics = k.on, screenOffTip = !k.screenOffTipSeen) } }
        }
        gates?.let { g ->
            viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(debloatGate = DEBLOAT_GATE !in g.folded()) } }
        }
        historySeen?.let { h ->
            viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(historySeen = h.seen()) } }
        }
        folds?.let { f ->
            viewModelScope.launch(Dispatchers.IO) {
                val folded = f.folded().mapNotNull { name -> AppsTab.entries.firstOrNull { it.name == name } }.toSet()
                _state.update { it.copy(foldedSystem = folded) }
            }
        }
        rescan()
        viewModelScope.launch { notice.collect { it?.let(::log) } }
        viewModelScope.launch {
            // Off Wi-Fi a scan has nothing to sweep; start over the moment the phone joins one.
            source.lanUp().collect {
                if (_state.value.scan.noLan) {
                    scanJob?.cancel()
                    rescan()
                }
            }
        }
    }

    private fun startTick() {
        tickJob = viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(1_000)
                _now.value = clock()
                if (++tick % 3 == 0) refreshDevices()
            }
        }
    }

    /**
     * The app went to the background: nothing reads the TVs or sweeps the LAN until [resume].
     * What the user started goes on: a copy, an install, an action, the Play Store wait, a removal.
     */
    fun pause() {
        if (paused) return
        paused = true
        tickJob?.cancel()
        scanOnResume = scanJob?.isActive == true
        scanJob?.cancel()
        stopWatching()
        source.pause(true)
    }

    /** Back in the foreground: the TVs are read again and the page on screen picks up where it was, dialogs and all. */
    fun resume() {
        if (!paused) return
        paused = false
        source.pause(false)
        startTick()
        if (scanOnResume) rescan()
        viewModelScope.launch {
            if (!refreshDevices()) _state.value.selected?.let(::watch)
        }
    }

    fun rescan() {
        if (paused) {
            scanOnResume = true
            return
        }
        if (scanJob?.isActive == true) {
            if (_state.value.scan.nextScanAt != null) scanNow.trySend(Unit)
            return
        }
        scanJob = viewModelScope.launch {
            // Empty scan with the guide on screen: keep looking so the user never has to press anything.
            // The retry stays in this job, as rescan() from here would see it still active.
            val done = { _state.value.let { it.scan.hits.isNotEmpty() || (it.selected != null && !addingTv) } }
            while (true) {
                _state.update { it.copy(scan = it.scan.copy(scanning = true, noLan = false)) }
                source.scan().collect { e ->
                    when (e) {
                        is ScanEvent.Progress -> _state.update { it.copy(scan = it.scan.copy(progress = e.progress)) }
                        is ScanEvent.Found -> _state.update { s ->
                            val known = s.devices.any { it.hit.ip == e.hit.ip }
                            if (known || s.scan.hits.any { it.ip == e.hit.ip }) s
                            else s.copy(scan = s.scan.copy(
                                hits = (s.scan.hits + e.hit).sortedWith(compareBy({ it.maker == null }, { ipKey(it.ip) })),
                                rows = s.scan.rows + (e.hit.ip to RowState.Found),
                            ))
                        }
                        ScanEvent.NoLan -> _state.update { it.copy(scan = it.scan.copy(noLan = true)) }
                        ScanEvent.Done -> Unit
                    }
                }
                _state.update { it.copy(scan = it.scan.copy(scanning = false)) }
                if (done()) break
                scanNow.tryReceive() // drops a tap that raced the last wait's timeout
                _state.update { it.copy(scan = it.scan.copy(nextScanAt = clock() + RESCAN_EVERY_MS)) }
                try {
                    withTimeoutOrNull(RESCAN_EVERY_MS) { scanNow.receive() }
                } finally {
                    _state.update { it.copy(scan = it.scan.copy(nextScanAt = null)) }
                }
                if (done()) break
            }
        }
    }

    /** The add flow opened or closed over a selected TV. Closed, an empty scan stops waiting to look again. */
    fun addingTv(open: Boolean) {
        addingTv = open
        if (open) rescan()
        else if (_state.value.let { it.selected != null && it.scan.nextScanAt != null }) scanJob?.cancel()
    }

    fun connect(hit: ScanHit) {
        // Wireless-debugging TVs that already know our key connect straight away;
        // the rest answer NeedsPairing and get the code field.
        setRow(hit.ip, RowState.Waiting(clock()))
        viewModelScope.launch {
            val r = withTimeoutOrNull(allowTimeoutMs) { source.connect(hit) }
            when {
                r is ConnectResult.Online -> online(r.device)
                r == ConnectResult.NeedsPairing -> setRow(hit.ip, RowState.Code())
                // A wireless-debugging TV never shows the Allow box: whatever went wrong, the way in is the code.
                hit.kind == ScanKind.Wireless -> setRow(hit.ip, RowState.Code(
                    error = if (r is ConnectResult.Unreachable) Copy.UNREACHABLE else Copy.PAIR_INSTEAD,
                    detail = (r as? ConnectResult.Unreachable)?.message ?: (r as? ConnectResult.WrongCode)?.message,
                ))
                else -> setRow(hit.ip, RowState.NotAllowed)
            }
        }
    }

    fun codeChanged(hit: ScanHit, input: String) {
        setRow(hit.ip, RowState.Code(input.filter(Char::isDigit).take(6)))
    }

    fun pair(hit: ScanHit) {
        val row = _state.value.scan.rows[hit.ip] as? RowState.Code ?: return
        setRow(hit.ip, row.copy(busy = true, error = null, detail = null))
        viewModelScope.launch {
            when (val r = source.pair(hit, row.input)) {
                is ConnectResult.Online -> online(r.device)
                is ConnectResult.WrongCode -> setRow(hit.ip, row.copy(busy = false, error = Copy.WRONG_CODE, detail = r.message))
                is ConnectResult.Unreachable -> setRow(hit.ip, row.copy(busy = false, error = Copy.UNREACHABLE, detail = r.message))
                ConnectResult.NotAllowed -> setRow(hit.ip, row.copy(busy = false, error = Copy.PAIR_INSTEAD))
                ConnectResult.NeedsPairing -> setRow(hit.ip, row.copy(busy = false, error = "The TV still wants a code. Check the number on screen and try again."))
            }
        }
    }

    /** "Add by IP": `ip` or `ip:port`. Returns an error line, or null when the connect started. */
    fun addByIp(text: String): String? {
        val m = Regex("""^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$""").find(text.trim())
            ?: return "That doesn't look like an IP address. It should look like four numbers with dots, like the one in your TV's network settings."
        val port = m.groupValues[2].toIntOrNull() ?: 5555
        if (port !in 1..65535) return "The port after the colon should be a number from 1 to 65535, like 5555."
        val kind = if (port == 5555) app.tellyscope.data.ScanKind.Classic else app.tellyscope.data.ScanKind.Wireless
        val hit = ScanHit(m.groupValues[1], port, kind, null, null)
        _state.update { s ->
            if (s.scan.hits.any { it.ip == hit.ip }) s
            else s.copy(scan = s.scan.copy(hits = listOf(hit) + s.scan.hits))
        }
        connect(hit)
        return null
    }

    private fun online(device: Device) {
        _state.update { s ->
            s.copy(
                devices = s.devices.filterNot { it.id == device.id } + device,
                scan = s.scan.copy(hits = s.scan.hits.filterNot { it.ip == device.hit.ip }),
            )
        }
        // A TV that comes online mid-copy joins the list; switching pages would hide the copy's dialog and its Cancel.
        if (transferJobs[_state.value.selectedId]?.isActive != true) select(device.id)
    }

    /** The TV on screen refreshes at the open screen's rates; the others at the slowest. */
    fun showStats(profile: StatsProfile) = source.showStats(_state.value.selectedId, profile)

    fun select(id: String?) {
        stopWatching()
        launcherJob?.cancel()
        // A copy still running on this TV keeps its dialog, after a reconnect or a look at another TV.
        val transfer = id?.let(transfers::get)
        _state.value.let { s ->
            // A forgotten TV is no longer in the list, and keeps nothing.
            val old = s.selectedId?.takeIf { old -> s.devices.any { it.id == old } } ?: return@let
            if (s.device.apps.isNotEmpty()) lastApps[old] = s.device.apps
            s.device.lists?.let { lastLists[old] = it }
        }
        // Only an online TV gets its old lists back: the offline card stays as it was.
        val online = _state.value.devices.firstOrNull { it.id == id }?.state == DeviceState.Online
        // A removal survives the reconnect it causes; the TV coming back reselects it.
        _state.update {
            val removal = it.device.removal.takeIf { _ -> it.selectedId == id }
            val apps = if (online) id?.let(lastApps::get).orEmpty() else emptyList()
            val lists = if (online) id?.let(lastLists::get) else null
            it.copy(selectedId = id, device = DeviceUi(snapshot = id?.let(lastSnapshot::get), apps = apps, lists = lists, transfer = transfer, removal = removal), query = "")
        }
        val device = _state.value.selected ?: return
        // History lives on the phone, so it shows (and undo is offered) even while the TV is off.
        viewModelScope.launch {
            val history = source.history(device.id)
            val dock = source.dock(device.id)
            val hidden = source.launcherCardHidden(device.id)
            _state.update { if (it.selectedId == device.id) it.copy(device = it.device.copy(history = history, dock = dock, launcherCardHidden = hidden)) else it }
        }
        watch(device)
    }

    /** Stops the reads of the TV on screen that [watch] started. */
    private fun stopWatching() {
        liveJob?.cancel()
        mediumJob?.cancel()
        listsJob?.cancel()
        requestJob?.cancel()
        accessJob?.cancel()
    }

    /** Reads the TV on screen while it's online: stats, apps, tweaks, files, lists, prompts, access. Not in the background. */
    private fun watch(device: Device) {
        if (paused || device.state != DeviceState.Online) return
        val id = device.id
        // Only a page with no list yet says it's loading; back from the background, the list stays up.
        updateDevice(id) { it.copy(appsLoading = it.apps.isEmpty()) }
        liveJob = viewModelScope.launch {
            source.live(id).collect { snap ->
                lastSnapshot[id] = snap
                _state.update { it.copy(device = it.device.copy(snapshot = snap)) }
            }
        }
        // One read of the app list per open: Apps and the TV's list both use it. The list
        // download runs in its own job, so it never holds up apps or stats.
        val apps = CompletableDeferred<List<AppInfo>>()
        listsJob = viewModelScope.launch { loadLists(id) { apps.await() } }
        requestJob = viewModelScope.launch { watchRequests(id) }
        accessJob = viewModelScope.launch { watchAccess(id) }
        mediumJob = viewModelScope.launch {
            // A TV that drops mid-read throws; the live flow ends and the offline card takes over.
            try {
                // Read first: update {} may run its block again, and must never call the TV.
                val read = try {
                    source.apps(id)
                } catch (e: Exception) {
                    apps.completeExceptionally(e)
                    updateDevice(id) { it.copy(appsLoading = false) }
                    throw e
                }
                updateDevice(id) { it.copy(apps = read, appsLoading = false) }
                apps.complete(read)
                pruneDock(id, read)
                val tweaks = source.tweaks(id)
                updateDevice(id) { it.copy(tweaks = tweaks) }
                loadFiles()
                while (true) {
                    val net = source.network(id)
                    _state.update { it.copy(device = it.device.copy(network = net)) }
                    delay(10_000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nothing to show: the stats stop and the offline card explains.
            }
        }
    }

    /**
     * Remembered TVs reconnect on their own; pick up their state and restart stats when one comes back.
     * True when that reselected the TV on screen.
     */
    private suspend fun refreshDevices(): Boolean {
        val known = source.known()
        val before = _state.value.selected?.state
        _state.update { s -> s.copy(devices = known, restarting = restarts(s.restarting, known)) }
        val now = _state.value.selected ?: return false
        if (now.state != DeviceState.Online || before == DeviceState.Online) return false
        select(now.id)
        return true
    }

    /** Reconnect button: a TV stopped at "not allowed" asks again; an offline one restarts its stats. */
    fun reconnect(id: String) {
        viewModelScope.launch {
            source.reconnect(id)
            // A TV that came back is selected already; again would read it all twice.
            if (!refreshDevices() || _state.value.selectedId != id) select(id)
        }
    }

    /** Cancel on the Allow step after Connect again: a logged-out TV shows its logout page again. */
    fun cancelReconnect(id: String) {
        viewModelScope.launch {
            source.cancelReconnect(id)
            refreshDevices()
        }
    }

    fun rename(id: String, name: String?) {
        viewModelScope.launch {
            source.rename(id, name)
            _state.update { it.copy(devices = source.known()) }
        }
    }

    fun forget(id: String) {
        viewModelScope.launch {
            source.forget(id)
            lastApps.remove(id)
            lastLists.remove(id)
            val known = source.known()
            val lists = runCatching { source.listSource() }.getOrNull()
            _state.update { it.copy(devices = known, listSource = lists) }
            // Next TV, online ones first; none left means back to the scan screen.
            select((known.firstOrNull { it.state == DeviceState.Online } ?: known.firstOrNull())?.id)
        }
    }

    fun query(q: String) = _state.update { it.copy(query = q) }

    /** First tap on an action button: show what it will do. */
    fun propose(pkg: String, action: Action) =
        _state.update { it.copy(device = it.device.copy(pending = pkg to action)) }

    fun cancel() = _state.update { it.copy(device = it.device.copy(pending = null)) }

    fun confirm() {
        val (pkg, action) = _state.value.device.pending ?: return
        run(pkg, action)
    }

    /** A pending switch to a launcher with an accessibility service, answered in the dialog that asks about it. */
    fun confirmHome(service: Boolean) {
        val (pkg, action) = _state.value.device.pending?.takeIf { it.second == Action.Home } ?: return
        run(pkg, action) { id -> source.makeHome(id, pkg, service) }
    }

    /** Turns on the accessibility service of [pkg], the launcher on Home, after a switch left it off. */
    fun homeServiceOn(pkg: String) {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { it.copy(busy = pkg) }
        viewModelScope.launch {
            val apps = try {
                source.homeServiceOn(id, pkg)
                source.apps(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message?.replaceFirstChar(Char::uppercase) ?: "That didn't work."
                rereadAfterFailure(id)
                return@launch
            }
            val history = source.history(id)
            updateDevice(id) { it.copy(apps = apps, busy = null, history = history) }
        }
    }

    /** Got it on the TV page's card about [pkg]'s service being off: it stays away until the next switch. */
    fun dismissServiceNote(pkg: String) {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { d -> d.copy(apps = d.apps.map { if (it.packageName == pkg) it.copy(homeServiceNote = false) else it }) }
        viewModelScope.launch {
            try {
                source.dismissServiceNote(id, pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Only the card's memory: at worst it shows again next time.
            }
        }
    }

    /**
     * Undoes [entry] on the TV it happened on. The snackbar passes [deviceId] because the user may
     * have switched TVs since; History only lists the selected TV's entries.
     */
    fun undo(entry: HistoryEntry, deviceId: String? = _state.value.selectedId) {
        if (deviceId == null || entry.undo == null || !undoInFlight.add(entry.id)) return
        viewModelScope.launch {
            // Both the snackbar and History offer Undo: an entry already undone from one is done.
            val now = source.history(deviceId).firstOrNull { it.id == entry.id } ?: entry
            val undo = now.undo
            val tweak = Tweak.entries.firstOrNull { it.name == now.packageName }
            when {
                now.undoneAt != null || undo == null -> undoInFlight -= entry.id
                now.action != Action.Change -> run(now.packageName, undo, undoing = now.id, id = deviceId)
                tweak != null -> change(tweak, now.previous.orEmpty(), undoing = now.id, id = deviceId)
                else -> {
                    // A setting this version doesn't know, from a newer one's History.
                    undoInFlight -= entry.id
                    _notice.value = Copy.CANT_UNDO
                }
            }
        }
    }

    /** Opens Install a home screen on its picker; not before the launcher list has been downloaded once. */
    fun openLauncherFlow() {
        val id = _state.value.selectedId ?: return
        if (_state.value.selected?.let { fireInstall(it, _state.value.device.apps) } == true) {
            updateDevice(id) { it.copy(launcher = LauncherFlow(pkg = PROJECTIVY, fire = FireInstall())) }
            return
        }
        if (RECOMMENDED.isEmpty()) {
            _notice.value = Copy.NO_LAUNCHER_LIST
            return
        }
        updateDevice(id) { it.copy(launcher = LauncherFlow()) }
    }

    private fun setFire(id: String, f: (FireInstall) -> FireInstall) =
        updateDevice(id) { d -> d.copy(launcher = d.launcher?.let { l -> l.fire?.let { l.copy(fire = f(it)) } }) }

    /** Download directly is offered beside the Play Store: there's a way to download, as on Fire OS. */
    val directDownload get() = launcherDownload != null

    /**
     * Install Projectivy on Fire OS, or Download directly in the picker: reads the latest release, for the
     * confirm dialog to show where it comes from.
     */
    fun checkProjectivy() {
        val id = _state.value.selectedId ?: return
        val download = launcherDownload ?: return
        val store = _state.value.device.launcher?.let { it.fire?.store ?: true } ?: return
        launcherJob?.cancel()
        updateDevice(id) { d -> d.copy(launcher = d.launcher?.copy(pkg = PROJECTIVY, fire = FireInstall(FireInstall.Step.Checking, store = store))) }
        launcherJob = viewModelScope.launch {
            val release = try {
                download.latest()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RateLimited) {
                return@launch setFire(id) { FireInstall(FireInstall.Step.NoDownload, store = store, until = e.until) }
            } catch (_: Exception) {
                return@launch setFire(id) { FireInstall(FireInstall.Step.NoDownload, store = store) }
            }
            setFire(id) { FireInstall(FireInstall.Step.Confirm, release, store = store) }
        }
    }

    /**
     * Download and install: fetches the APK, which must be Projectivy signed by its developer, then installs it
     * over adb like any sideload. Nothing reaches the TV unless the check passes.
     */
    fun installProjectivy() {
        val id = _state.value.selectedId ?: return
        val download = launcherDownload ?: return
        val release = _state.value.device.launcher?.fire?.takeIf { it.step == FireInstall.Step.Confirm }?.release ?: return
        launcherJob?.cancel()
        setFire(id) { it.copy(step = FireInstall.Step.Downloading, progress = null, picked = false, size = null, until = null) }
        launcherJob = viewModelScope.launch {
            val app = try {
                download.fetch(release) { p -> setFire(id) { it.copy(progress = p) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: NotProjectivy) {
                return@launch setFire(id) { it.copy(step = FireInstall.Step.NotProjectivy) }
            } catch (_: Exception) {
                return@launch setFire(id) { it.copy(step = FireInstall.Step.NoDownload) }
            }
            installFetched(id, app)
        }
    }

    /**
     * Pick the downloaded file, after Couldn't download: the file the user picked, checked like the download,
     * then installed the same way. Nothing reaches the TV unless it's Projectivy signed by its developer.
     */
    fun pickedProjectivy(uri: Uri) {
        val id = _state.value.selectedId ?: return
        val download = launcherDownload ?: return
        // The picker answers on its own timing: only from a step that offers it, never over a running install.
        val step = _state.value.device.launcher?.fire?.step
        if (step != FireInstall.Step.NoDownload && step != FireInstall.Step.NotProjectivy && step != FireInstall.Step.Refused) return
        launcherJob?.cancel()
        setFire(id) { it.copy(step = FireInstall.Step.Reading, picked = true, progress = null, size = null) }
        launcherJob = viewModelScope.launch {
            val app = try {
                download.open(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: NotProjectivy) {
                return@launch setFire(id) { it.copy(step = FireInstall.Step.NotProjectivy) }
            } catch (_: Exception) {
                return@launch setFire(id) { it.copy(step = FireInstall.Step.Refused, error = Copy.CANT_READ_FILE) }
            }
            installFetched(id, app)
        }
    }

    /** Projectivy, downloaded or picked and checked, onto the TV over adb like any sideload. */
    private suspend fun installFetched(id: String, app: PickedApp) {
        setFire(id) { it.copy(step = FireInstall.Step.Installing, progress = null, size = app.sizeBytes) }
        val result = try {
            source.install(id, app.files, app.info, allowDowngrade = false) { p -> setFire(id) { it.copy(progress = p) } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return setFire(id) { it.copy(step = FireInstall.Step.Refused, error = Copy.SIDELOAD_LOST) }
        }
        when (result) {
            // The steps close with it: Installing would keep them up, and Close waits on that.
            InstallResult.Done -> launcherInstalled(id, PROJECTIVY) { step ->
                updateDevice(id) { it.copy(launcher = it.launcher?.let { l -> l.copy(step = step, fire = if (l.fire?.store == true) null else FireInstall()) }) }
            }
            is InstallResult.Refused -> setFire(id) {
                it.copy(step = FireInstall.Step.Refused, error = Copy.installRefused(result.reason, launcherName(PROJECTIVY)))
            }
        }
    }

    /** X or Stop on a Fire OS step: back to the start, or to the picker after Download directly. Not while the install runs. */
    fun dismissProjectivy() {
        val id = _state.value.selectedId ?: return
        if (_state.value.device.launcher?.fire?.step == FireInstall.Step.Installing) return
        launcherJob?.cancel()
        updateDevice(id) { d -> d.copy(launcher = d.launcher?.let { l -> l.copy(fire = if (l.fire?.store == true) null else FireInstall()) }) }
    }

    /** Not now on the Apps tab's card: it stays away on this TV, and Tweaks keeps the way in. */
    fun hideLauncherCard() {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { it.copy(launcherCardHidden = true) }
        viewModelScope.launch { runCatching { source.hideLauncherCard(id) } }
    }

    /** Back to the picker, [pkg] still chosen; Choose another from a dead end. */
    fun pickLauncher(pkg: String) {
        val id = _state.value.selectedId ?: return
        launcherJob?.cancel()
        updateDevice(id) { it.copy(launcher = LauncherFlow(LauncherFlow.Step.Pick, pkg)) }
    }

    fun closeLauncherFlow() {
        val id = _state.value.selectedId ?: return
        // An install under way finishes; cutting it off could leave the TV half done.
        if (_state.value.device.launcher?.fire?.step == FireInstall.Step.Installing) return
        launcherJob?.cancel()
        updateDevice(id) { it.copy(launcher = null) }
    }

    /**
     * Opens the Play Store on the TV at [pkg]'s page, then checks every few seconds whether it's
     * installed, or the Store has closed without it, which goes back to the picker. Where the Store
     * didn't open, it keeps checking while the user searches by hand. After [STORE_WAIT_MS] without it,
     * it stops at [LauncherFlow.Step.Left].
     *
     * Known: a Google sign-in or consent screen inside the Store runs as another app
     * (com.google.android.gms), so it can read as leaving the Store and end the wait early.
     * Open Store again recovers.
     */
    fun installLauncher(pkg: String) {
        val id = _state.value.selectedId ?: return
        launcherJob?.cancel()
        val step = { s: LauncherFlow.Step -> updateDevice(id) { it.copy(launcher = it.launcher?.let { _ -> LauncherFlow(s, pkg) }) } }
        // Already on the TV, installed since the picker was drawn (Open Store again after the wait): no Store detour.
        _state.value.device.apps.firstOrNull { it.packageName == pkg && it.enabled }?.let { have ->
            if (have.homeInstead != null) {
                step(LauncherFlow.Step.Installed)
            } else {
                updateDevice(id) { it.copy(launcher = null) }
                _notice.value = if (have.home) Copy.launcherIsHome(launcherName(pkg)) else Copy.launcherInstalled(launcherName(pkg))
            }
            return
        }
        step(LauncherFlow.Step.Waiting)
        launcherJob = viewModelScope.launch {
            val opened = try {
                source.openStore(id, pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!opened) step(LauncherFlow.Step.NoStore)
            var seen = opened
            repeat((STORE_WAIT_MS / STORE_POLL_MS).toInt()) {
                delay(STORE_POLL_MS)
                val now = try {
                    source.storeState(id, pkg)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@repeat // a dropped read: the next one tells
                }
                when (now) {
                    StoreState.Installed -> return@launch launcherInstalled(id, pkg, step)
                    StoreState.InStore -> seen = true
                    StoreState.Elsewhere -> if (seen) {
                        // The Store closed without it: back to the picker, still chosen, and the snackbar says so.
                        step(LauncherFlow.Step.Pick)
                        _notice.value = Copy.wasntInstalled(launcherName(pkg))
                        return@launch
                    }
                }
            }
            step(LauncherFlow.Step.Left)
        }
    }

    /** [pkg] is now on the TV: rereads the apps, then on to Make it the home screen. */
    private suspend fun launcherInstalled(id: String, pkg: String, step: (LauncherFlow.Step) -> Unit) {
        val apps = try {
            source.apps(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        apps?.let { a -> updateDevice(id) { it.copy(apps = a) } }
        // No way to offer it as Home (the TV asks each time): say where to find it instead.
        if (apps?.firstOrNull { it.packageName == pkg }?.homeInstead == null) {
            updateDevice(id) { it.copy(launcher = null) }
            _notice.value = Copy.launcherInstalled(launcherName(pkg))
        } else {
            step(LauncherFlow.Step.Installed)
        }
    }

    /** Yes on the installed launcher, with its accessibility service or without; the flow closes and the switch runs. */
    fun switchToLauncher(service: Boolean) {
        makeHome(_state.value.device.launcher?.pkg ?: return, service)
    }

    /** Make Home on [pkg], from the picker or after its install: the flow closes and the switch runs. */
    fun makeHome(pkg: String, service: Boolean) {
        val id = _state.value.selectedId ?: return
        launcherJob?.cancel()
        updateDevice(id) { it.copy(launcher = null) }
        run(pkg, Action.Home, id = id) { source.makeHome(it, pkg, service) }
    }

    /** Open in the picker: shows the launcher [pkg] on the TV. The picker stays. */
    fun openLauncher(pkg: String) {
        val label = _state.value.device.apps.firstOrNull { it.packageName == pkg }?.label ?: launcherName(pkg)
        open(pkg, label)
    }

    /** Not now on the installed launcher: where to switch later. */
    fun launcherLater() {
        val pkg = _state.value.device.launcher?.pkg ?: return
        closeLauncherFlow()
        _notice.value = Copy.launcherLater(launcherName(pkg))
    }

    /** Ad-blocking Private DNS through [host], or back to Automatic with null. */
    fun setDns(host: String?) = change(Tweak.PRIVATE_DNS, TvSettings.dns(host))

    fun setFastAnimations(fast: Boolean) = change(Tweak.ANIMATIONS, TvSettings.animations(fast))

    private fun change(tweak: Tweak, values: Map<String, String?>, undoing: Long? = null, id: String? = _state.value.selectedId) {
        if (id == null) return
        updateDevice(id) { it.copy(busy = tweak.name) }
        viewModelScope.launch {
            val (entry, tweaks) = try {
                source.change(id, tweak, values, undoing) to source.tweaks(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A change can fail after it was recorded (a rollback that didn't take), so History is reread.
                val history = source.history(id)
                updateDevice(id) { it.copy(busy = null, history = history) }
                undoing?.let(undoInFlight::remove) // a failed Undo can be tried again
                _notice.value = e.message?.replaceFirstChar(Char::uppercase) ?: "That didn't work."
                return@launch
            }
            val history = source.history(id)
            updateDevice(id) { it.copy(tweaks = tweaks, busy = null, history = history) }
            if (undoing == null) _done.value = Done(id, entry)
        }
    }

    fun clearCaches() {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { it.copy(busy = CLEARING) }
        viewModelScope.launch {
            _notice.value = try {
                Copy.cachesCleared(source.clearCaches(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message?.replaceFirstChar(Char::uppercase) ?: "That didn't work."
            }
            val history = source.history(id)
            updateDevice(id) { it.copy(busy = null, history = history) }
        }
    }

    /** The "save as" picker's answer for a screenshot; null when the user backed out. */
    fun screenshotTo(uri: Uri?) {
        val id = _state.value.selectedId ?: return
        val storage = storage ?: return
        if (uri == null) return
        updateDevice(id) { it.copy(busy = SCREENSHOT) }
        viewModelScope.launch {
            try {
                storage.create(uri).use { sink -> source.screenshot(id, sink) }
                _notice.value = Copy.SCREENSHOT_SAVED
            } catch (e: CancellationException) {
                storage.delete(uri)
                throw e
            } catch (_: Exception) {
                storage.delete(uri)
                _notice.value = Copy.SCREENSHOT_FAILED
            } finally {
                updateDevice(id) { it.copy(busy = null) }
            }
        }
    }

    /** Changes [id]'s page only while it is still the one shown; a result for another TV is dropped. */
    private fun updateDevice(id: String, f: (DeviceUi) -> DeviceUi) =
        _state.update { s -> if (s.selectedId == id) s.copy(device = f(s.device)) else s }

    private fun run(
        pkg: String,
        action: Action,
        undoing: Long? = null,
        id: String? = _state.value.selectedId,
        act: suspend (String) -> HistoryEntry = { source.act(it, action, pkg, undoing) },
    ) {
        if (id == null) return
        updateDevice(id) { it.copy(pending = null, busy = pkg) }
        viewModelScope.launch {
            // The source throws with the user-facing reason when the TV refuses.
            val (entry, apps) = try {
                act(id) to source.apps(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else (a dropped socket) still must not crash the app.
                undoing?.let(undoInFlight::remove) // a failed Undo can be tried again
                // A reset's Undo can get partway, so History shows where it ended.
                if (undoing != null) runCatching { source.history(id) }.getOrNull()?.let { h -> updateDevice(id) { it.copy(history = h) } }
                _notice.value = e.message?.replaceFirstChar(Char::uppercase) ?: "That didn't work."
                rereadAfterFailure(id)
                return@launch
            }
            val history = source.history(id)
            updateDevice(id) { it.copy(apps = apps, busy = null, history = history) }
            if (undoing == null) _done.value = Done(id, entry)
            if (action == Action.Uninstall) pruneDock(id, apps)
        }
    }

    /**
     * A change can fail halfway (a Home switch whose old launcher didn't come back, recorded first),
     * so the rows and History show what the TV has now. A TV that dropped keeps what was shown.
     */
    private suspend fun rereadAfterFailure(id: String) {
        val apps = runCatching { source.apps(id) }.getOrNull()
        val history = runCatching { source.history(id) }.getOrNull()
        updateDevice(id) { it.copy(apps = apps ?: it.apps, busy = null, history = history ?: it.history) }
    }

    /** The user picked one or more files to install. Reads them, then asks the TV what it has. */
    fun pickedApp(uris: List<Uri>) {
        val id = _state.value.selectedId ?: return
        val storage = storage ?: return
        if (uris.isEmpty()) return
        setSideload(SideloadUi())
        sideloadJob = viewModelScope.launch {
            val ui = try {
                val app = storage.readApp(uris)
                SideloadUi(SideloadUi.Step.Ready, app, source.installed(id, app.info.packageName))
            } catch (e: CancellationException) {
                throw e
            } catch (_: NotAnApp) {
                SideloadUi(SideloadUi.Step.Failed, error = Copy.NOT_AN_APP)
            } catch (_: Exception) {
                SideloadUi(SideloadUi.Step.Failed, error = Copy.SIDELOAD_LOST)
            }
            updateDevice(id) { it.copy(sideload = ui) }
        }
    }

    fun allowDowngrade(allow: Boolean) = updateSideload { it.copy(allowDowngrade = allow) }

    fun install() {
        val id = _state.value.selectedId ?: return
        val ready = _state.value.device.sideload?.takeIf { it.step == SideloadUi.Step.Ready } ?: return
        val app = ready.app ?: return
        if (ready.kind == InstallKind.DOWNGRADE && !ready.allowDowngrade) return
        setSideload(ready.copy(step = SideloadUi.Step.Installing))
        sideloadJob = viewModelScope.launch {
            val r = try {
                source.install(id, app.files, app.info, ready.allowDowngrade) { p -> updateSideload { it.copy(progress = p) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch updateDevice(id) { it.copy(sideload = ready.copy(step = SideloadUi.Step.Failed, error = Copy.SIDELOAD_LOST)) }
            }
            when (r) {
                InstallResult.Done -> {
                    updateDevice(id) { it.copy(sideload = ready.copy(step = SideloadUi.Step.Installed)) }
                    runCatching { source.apps(id) }.onSuccess { apps -> updateDevice(id) { it.copy(apps = apps) } }
                }
                is InstallResult.Refused -> updateDevice(id) {
                    log("Install ${app.info.packageName} ${app.info.versionName.orEmpty()}: ${r.detail ?: r.reason}")
                    it.copy(sideload = ready.copy(step = SideloadUi.Step.Failed, error = Copy.installRefused(r.reason, app.label), detail = r.detail))
                }
            }
        }
    }

    private fun log(line: String) = synchronized(sessionLog) {
        sessionLog.addLast(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(clock())) + " " + line)
        while (sessionLog.size > LOG_LINES) sessionLog.removeFirst()
    }

    /**
     * Opens the report screen about the selected TV, reading it if it's online. [details] starts what
     * the user writes; [said] is what the TV answered, added to the log.
     */
    fun openReport(kind: ReportKind, details: String = "", said: String? = null) {
        val tv = _state.value.selected?.takeIf { it.state == DeviceState.Online }
        val log = synchronized(sessionLog) { sessionLog.toList() } + listOfNotNull(said?.let { "The TV said: $it" })
        val connection = when (tv?.hit?.kind) {
            ScanKind.Classic -> Reports.Connection.PORT_5555
            ScanKind.Wireless -> Reports.Connection.WIRELESS_DEBUGGING
            null -> Reports.Connection.COULD_NOT_CONNECT
        }
        _state.update { it.copy(report = ReportUi(kind, appFacts, connection, reading = tv != null, details = details, log = log.joinToString("\n").ifEmpty { null })) }
        tv ?: return
        viewModelScope.launch {
            val facts = try { source.facts(tv.id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            _state.update { st -> st.copy(report = st.report?.copy(tv = facts, reading = false)) }
        }
    }

    /** The report went to GitHub: done, unless something was cut, which the screen then offers to add. */
    fun reportOpened(report: Report) {
        _state.update { st -> st.copy(report = if (report.rest == null) null else st.report?.copy(posted = report)) }
    }

    fun closeReport() = _state.update { it.copy(report = null) }

    /** Cancel or Done on the install dialog. Not while installing: the dialog has no button then. */
    fun closeSideload() {
        if (_state.value.device.sideload?.step == SideloadUi.Step.Installing) return
        sideloadJob?.cancel()
        storage?.clearApp()
        setSideload(null)
    }

    /** "Open on TV" after a successful install. */
    fun openInstalled() {
        val pkg = _state.value.device.sideload?.app?.info?.packageName ?: return
        closeSideload()
        run(pkg, Action.Open)
    }

    fun folder(folder: TvFolder) {
        _state.update { it.copy(device = it.device.copy(files = FilesUi(folder))) }
        viewModelScope.launch { loadFiles() }
    }

    private suspend fun loadFiles() {
        val id = _state.value.selectedId ?: return
        val folder = _state.value.device.files.folder
        val ui = try {
            FilesUi(folder, source.files(id, folder))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FilesUi(folder, emptyList(), Copy.FILES_LOST)
        }
        _state.update { s -> if (s.device.files.folder == folder) s.copy(device = s.device.copy(files = ui)) else s }
    }

    /** Copies a phone file into the folder on show. Never overwrites: a taken name gets " (1)". */
    fun push(uri: Uri) {
        val id = _state.value.selectedId ?: return
        val storage = storage ?: return
        val folder = _state.value.device.files.folder
        transferJobs[id] = viewModelScope.launch {
            val file = try {
                storage.open(uri)
            } catch (_: Exception) {
                _notice.value = Copy.CANT_READ
                return@launch
            }
            setTransfer(id, TransferUi(file.name, toTv = true, folder = folder))
            try {
                val saved = file.source.use { src ->
                    source.push(id, folder, file.name, file.size, src) { p -> updateTransfer(id) { it.copy(progress = p) } }
                }
                // Close the dialog before the reload: a Cancel tapped now would report a sent file as cancelled.
                setTransfer(id, null)
                _notice.value = Copy.sent(file.name, saved, folder)
                loadFiles()
            } catch (e: CancellationException) {
                _notice.value = Copy.CANCELLED_SEND
                throw e
            } catch (_: Exception) {
                _notice.value = Copy.sendFailed(file.name)
            } finally {
                setTransfer(id, null)
            }
        }
    }

    /** The file whose "save as" picker is open. Kept here so it survives the activity being recreated behind the picker. */
    private var saving: TvFile? = null

    fun saving(file: TvFile) {
        saving = file
    }

    /** The picker's answer: copies the file there, or does nothing if the user backed out. */
    fun saveTo(uri: Uri?) {
        val file = saving ?: return
        saving = null
        if (uri != null) pull(file, uri)
    }

    /** A cancelled or failed copy removes the partial file. */
    private fun pull(file: TvFile, uri: Uri) {
        val id = _state.value.selectedId ?: return
        val storage = storage ?: return
        val folder = _state.value.device.files.folder
        setTransfer(id, TransferUi(file.name, toTv = false, folder = folder, progress = Progress(0, file.size)))
        transferJobs[id] = viewModelScope.launch {
            try {
                storage.create(uri).use { sink ->
                    source.pull(id, folder, file, sink) { p -> updateTransfer(id) { it.copy(progress = p) } }
                }
                _notice.value = Copy.saved(file.name)
            } catch (e: CancellationException) {
                storage.delete(uri)
                _notice.value = Copy.CANCELLED_SAVE
                throw e
            } catch (_: Exception) {
                storage.delete(uri)
                _notice.value = Copy.saveFailed(file.name)
            } finally {
                setTransfer(id, null)
            }
        }
    }

    /** Only the dialog's Cancel button calls this. */
    fun cancelTransfer() {
        transferJobs[_state.value.selectedId]?.cancel()
    }

    private fun setSideload(ui: SideloadUi?) = _state.update { it.copy(device = it.device.copy(sideload = ui)) }

    private fun updateSideload(f: (SideloadUi) -> SideloadUi) =
        _state.update { s -> s.device.sideload?.let { s.copy(device = s.device.copy(sideload = f(it))) } ?: s }

    private fun setTransfer(id: String, ui: TransferUi?) {
        if (ui == null) transfers -= id else transfers[id] = ui
        updateDevice(id) { it.copy(transfer = ui) }
    }

    private fun updateTransfer(id: String, f: (TransferUi) -> TransferUi) {
        val ui = transfers[id]?.let(f) ?: return
        setTransfer(id, ui)
    }

    fun doneShown() {
        _done.value = null
    }

    fun noticeShown() {
        _notice.value = null
    }

    /** Turns back on everything Tellyscope disabled on the selected TV. */
    fun restoreAll() {
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(device = it.device.copy(busy = RESTORING)) }
        viewModelScope.launch {
            _notice.value = try {
                val n = source.restoreAll(id).size
                if (n == 0) "Nothing to turn back on." else "Turned $n ${if (n == 1) "app" else "apps"} back on."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message?.replaceFirstChar(Char::uppercase) ?: "That didn't work."
            }
            val apps = runCatching { source.apps(id) }.getOrNull()
            val history = source.history(id)
            updateDevice(id) { it.copy(apps = apps ?: it.apps, busy = null, history = history) }
        }
    }

    private suspend fun loadLists(id: String, apps: suspend () -> List<AppInfo>) {
        val lists = source.suggestions(id, apps)
        // Once the list is known its keep entries show as protected, without reading the TV again.
        val keep = (lists as? Suggestions.Ready)?.list?.keep.orEmpty()
        _state.update { s ->
            if (s.selectedId != id) s
            else s.copy(device = s.device.copy(lists = lists, apps = s.device.apps.map { it.copy(listKeep = it.packageName in keep) }))
        }
    }

    /** The app list on screen, or a fresh read when there's none yet. */
    private suspend fun shownApps(id: String): List<AppInfo> =
        _state.value.takeIf { it.selectedId == id }?.device?.apps?.ifEmpty { null } ?: source.apps(id)

    /**
     * Another device's "Allow debugging?" prompt, checked every few seconds while the TV's page
     * is open. A failed check just waits for the next one.
     */
    private suspend fun watchRequests(id: String) {
        while (true) {
            readRequest(id)
            delay(5_000)
        }
    }

    private suspend fun readRequest(id: String) {
        val request = try {
            source.connectionRequest(id).also {
                // A prompt under Amazon's alert has no fingerprint to hide it by: Close hides it until the alert is gone.
                if (it == null && dismissedRequest == "") dismissedRequest = null
            }?.takeIf { it.fingerprint != dismissedRequest }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        updateDevice(id) { if (it.busy == ANSWERING) it else it.copy(request = request) }
    }

    /**
     * Press OK on the TV, on a prompt under Fire OS's remote alert: one Back, sent only while the alert has focus.
     * Then a fresh read, so the prompt itself, with its fingerprint, takes over without waiting for the next check.
     */
    fun closeAlert() {
        val id = _state.value.selectedId ?: return
        if (_state.value.device.request?.behindAlert != true) return
        updateDevice(id) { it.copy(busy = ANSWERING) }
        viewModelScope.launch {
            try {
                source.closeAlert(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            delay(ALERT_CLOSING_MS)
            updateDevice(id) { it.copy(busy = null) }
            readRequest(id)
        }
    }

    fun dismissRequest() {
        val id = _state.value.selectedId ?: return
        dismissedRequest = _state.value.device.request?.fingerprint
        updateDevice(id) { it.copy(request = null) }
    }

    /** The user's answer to [DeviceUi.request]; nothing is ever answered without it. */
    fun answerRequest(allow: Boolean, always: Boolean) {
        val id = _state.value.selectedId ?: return
        val request = _state.value.device.request ?: return
        updateDevice(id) { it.copy(busy = ANSWERING) }
        viewModelScope.launch {
            val ok = try {
                source.answerRequest(id, request, allow, always)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            _notice.value = when {
                !ok -> Copy.REQUEST_GONE
                allow -> Copy.REQUEST_ALLOWED
                else -> Copy.REQUEST_DENIED
            }
            updateDevice(id) { it.copy(busy = null, request = null) }
            // An allowed device joins who has access now, not at the next 10 s read.
            if (ok) readAccess(id)
        }
    }

    /**
     * Who has access every 10 s while the TV's page is open. What's plugged in only when it opens
     * and every 2 minutes after: that read is five `dumpsys` calls, too heavy for every tick.
     * A failed read keeps the last answer; a removal in progress does its own reads.
     */
    private suspend fun watchAccess(id: String) {
        var tick = 0
        while (true) {
            if (_state.value.device.removal == null) readAccess(id)
            if (tick++ % 12 == 0) {
                val found = try { source.peripherals(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                found?.let { p -> updateDevice(id) { it.copy(peripherals = p) } }
            }
            delay(10_000)
        }
    }

    private suspend fun readAccess(id: String): TvAccess? {
        val access = try { source.access(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        access?.let { a -> updateDevice(id) { it.copy(access = a) } }
        return access
    }

    /** "It's mine": that address stops raising the warning on this TV. */
    fun markMine(address: String) {
        val id = _state.value.selectedId ?: return
        viewModelScope.launch {
            source.markMine(id, address)
            readAccess(id)
        }
    }

    /** "It's mine" on a new allowed computer: it stops showing as new on this TV. */
    fun markKeyMine(fingerprint: String) {
        val id = _state.value.selectedId ?: return
        viewModelScope.launch {
            source.markKeyMine(id, fingerprint)
            readAccess(id)
        }
    }

    /** Answer on a waiting connection: the TV's prompt again, even after it was dismissed. */
    fun showRequest() {
        val id = _state.value.selectedId ?: return
        dismissedRequest = null
        viewModelScope.launch { readRequest(id) }
    }

    private fun setRemoval(id: String, removal: Removal?) = updateDevice(id) { it.copy(removal = removal) }

    /** "Remove all other access…": asks first. */
    fun askRemoveAccess() {
        val id = _state.value.selectedId ?: return
        setRemoval(id, Removal(Removal.Step.Confirm))
    }

    /** Cancel, Stop waiting or Done: the flow closes. Keys already cleared stay cleared. */
    fun closeRemoval() {
        removalJob?.cancel()
        val id = _state.value.selectedId ?: return
        setRemoval(id, null)
        viewModelScope.launch { readAccess(id) }
    }

    /** Clears every key on the TV, or opens the manual guide when the TV won't take the call. */
    fun removeAccess() {
        val id = _state.value.selectedId ?: return
        removalJob?.cancel()
        removalJob = viewModelScope.launch {
            setRemoval(id, Removal(Removal.Step.Revoking))
            val ok = try { source.revokeAll(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (ok) relink(id) else setRemoval(id, Removal(Removal.Step.Manual))
        }
    }

    /** The manual guide's first step: Developer options open on the TV. */
    fun openDeveloperOptions() {
        val id = _state.value.selectedId ?: return
        viewModelScope.launch {
            val ok = try { source.openDeveloperOptions(id) } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            if (!ok) _notice.value = Copy.DEV_OPTIONS_FAILED
        }
    }

    /** The user revoked on the TV by hand: reconnect and allow this phone, as after the call. */
    fun revokedByHand() {
        val id = _state.value.selectedId ?: return
        removalJob?.cancel()
        removalJob = viewModelScope.launch { relink(id) }
    }

    /** The TV refused this phone: show its prompt again. */
    fun askTvAgain() {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { it.copy(removal = it.removal?.copy(refused = false)) }
        viewModelScope.launch { source.reconnect(id) }
    }

    /**
     * Reconnects so the TV asks to allow this phone, then checks on the new link. The link shows
     * online all through the reconnect, so only a read that works says the TV allowed this phone.
     */
    private suspend fun relink(id: String) {
        setRemoval(id, Removal(Removal.Step.Allow, fingerprint = source.phoneFingerprint()))
        source.reconnect(id)
        while (true) {
            val st = source.known().firstOrNull { it.id == id }?.state
            if (st == DeviceState.NotAllowed) updateDevice(id) { it.copy(removal = it.removal?.copy(refused = true)) }
            if (st == DeviceState.Online) readAccess(id)?.let { setRemoval(id, outcome(it)); return }
            delay(1_000)
        }
    }

    /** "Try again" after a computer was allowed again: clear again, no need to ask twice. */
    fun retryRemoval() = removeAccess()

    /** "Check again" after a connection was left open: reads the TV again, clears nothing. */
    fun checkAgain() {
        val id = _state.value.selectedId ?: return
        removalJob?.cancel()
        removalJob = viewModelScope.launch {
            setRemoval(id, Removal(Removal.Step.Checking))
            setRemoval(id, outcome(readAccess(id)))
        }
    }

    /**
     * "Restart the TV" after a connection was left open: a restart ends every connection.
     * Waits for the TV to come back, as the Remote does, then checks again. A refused restart
     * leaves the screen as it was, and the snackbar says why.
     */
    fun restartToRemove() {
        val id = _state.value.selectedId ?: return
        val before = _state.value.device.removal
        removalJob?.cancel()
        removalJob = viewModelScope.launch {
            setRemoval(id, Removal(Removal.Step.Restarting))
            if (!restart(id)) {
                setRemoval(id, before)
                return@launch
            }
            while (id in _state.value.restarting) delay(1_000)
            setRemoval(id, Removal(Removal.Step.Checking))
            setRemoval(id, outcome(readAccess(id)))
        }
    }

    /** "Pick your TV": remember the choice for this TV and show its list. */
    fun pickList(listId: String) {
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(device = it.device.copy(lists = null)) }
        listsJob?.cancel()
        listsJob = viewModelScope.launch {
            source.pickList(id, listId)
            loadLists(id) { shownApps(id) }
        }
    }

    /**
     * Reads the debloat lists from [url] from now on, or from tellyscope-devices with null, then
     * reloads this TV's list. A refused source changes nothing and says why.
     */
    fun setListSource(url: String?) {
        val id = _state.value.selectedId
        listsJob?.cancel()
        listsJob = viewModelScope.launch {
            try {
                source.setListSource(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message?.replaceFirstChar(Char::uppercase) ?: "That didn't work."
                return@launch
            }
            val now = source.listSource()
            // Every TV's list came from the old source.
            lastLists.clear()
            _state.update { it.copy(listSource = now, device = it.device.copy(lists = null)) }
            _notice.value = Copy.listSource(now)
            if (id != null) loadLists(id) { shownApps(id) }
        }
    }

    /**
     * Each time the app comes to the front: offers a newer release if the interval since the last
     * check has passed. One check at a time; an offer on screen stays until it is answered.
     */
    fun checkForUpdate() {
        val u = updates ?: return
        if (updateJob?.isActive == true || _state.value.update != null) return
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            // Null on any failure: no offer looks the same as no newer release.
            u.check()?.let { found -> _state.update { it.copy(update = found) } }
        }
    }

    /** "Get it" or "Later": the offer goes away until the next check. */
    fun updateLater() {
        _state.update { it.copy(update = null) }
    }

    /** "Skip this version": never offered again; a later release still is. */
    fun skipUpdate() {
        val version = _state.value.update?.version ?: return
        _state.update { it.copy(update = null) }
        viewModelScope.launch(Dispatchers.IO) { updates?.skip(version) }
    }

    /** Folds or opens [tab]'s System section, and keeps it that way across restarts. */
    fun foldSystem(tab: AppsTab, folded: Boolean) {
        _state.update { it.copy(foldedSystem = if (folded) it.foldedSystem + tab else it.foldedSystem - tab) }
        viewModelScope.launch(Dispatchers.IO) { folds?.set(tab.name, folded) }
    }

    fun setCheckUpdates(on: Boolean) {
        val u = updates ?: return
        _state.update { it.copy(checkUpdates = on) }
        viewModelScope.launch(Dispatchers.IO) { u.enabled = on }
    }

    fun setKeyHaptics(on: Boolean) {
        _state.update { it.copy(keyHaptics = on) }
        viewModelScope.launch(Dispatchers.IO) { keyHaptics?.on = on }
    }

    /** Disables the ticked suggestions one by one, each recorded in History with its own undo; Debloat shows how far. */
    fun disableSuggested(apps: List<ResetApp>) {
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(device = it.device.copy(busy = DISABLING, debloat = DebloatRun(apps))) }
        viewModelScope.launch {
            // One change for the whole list: the TV's stats are read again once, after the last app.
            source.batch(id) {
                for (app in apps) {
                    val why = try {
                        source.act(id, Action.Disable, app.packageName)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The TV refused this one or dropped; its row says why.
                        e.message ?: "the TV refused"
                    }
                    updateDevice(id) { d ->
                        d.copy(debloat = d.debloat?.let { r -> r.copy(at = r.at + 1, failed = if (why == null) r.failed else r.failed + (app.packageName to why)) })
                    }
                }
            }
            val read = runCatching { source.apps(id) }.getOrNull()
            val history = source.history(id)
            updateDevice(id) { it.copy(apps = read ?: it.apps, busy = null, history = history, debloat = it.debloat?.copy(ran = true)) }
            loadLists(id) { read ?: source.apps(id) }
        }
    }

    /** Try again on Debloat's result: just the apps the TV refused. */
    fun retryDebloat() {
        val run = _state.value.device.debloat ?: return
        if (_state.value.device.busy != null) return
        disableSuggested(run.apps.filter { it.packageName in run.failed })
    }

    /** Debloat closed: a finished run is forgotten, one still going shows again when it reopens. */
    fun closeDebloat() {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { if (it.busy == DISABLING) it else it.copy(debloat = null) }
    }

    /**
     * Sends one key and waits for the TV to take it; false when it didn't. The remote screen holds
     * a D-pad key by calling this in a loop, which stops at the first false, so a lost TV shows once.
     */
    suspend fun press(key: RemoteKey): Boolean {
        val id = _state.value.selectedId ?: return false
        return keys.withLock {
            try {
                // With the picture off, any key wakes it, the way a TV's own remote does, and does nothing else.
                if (id in _state.value.screenOff) return@withLock wake(id)
                if (key == RemoteKey.DEL) {
                    // Nothing typed from here to delete: the key does nothing, and a hold stops.
                    val left = typed[id] ?: 0
                    if (left == 0) return@withLock false
                    return@withLock source.press(id, key)
                        .also { if (it) typed[id] = left - 1 else _notice.value = Copy.KEY_REFUSED }
                }
                typed.remove(id)
                source.press(id, key).also { if (!it) _notice.value = Copy.KEY_REFUSED }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = Copy.REMOTE_LOST
                false
            }
        }
    }

    /** Turns the TV's picture off, or back on. The TV stays connected either way. */
    fun setScreenOff(off: Boolean) {
        val id = _state.value.selectedId ?: return
        viewModelScope.launch {
            keys.withLock {
                try {
                    if (!off) wake(id)
                    else if (source.screen(id, on = false)) _state.update { it.copy(screenOff = it.screenOff + id) }
                    else _notice.value = Copy.SCREEN_OFF_REFUSED
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _notice.value = Copy.REMOTE_LOST
                }
            }
        }
    }

    /** Under [keys]. True once the picture is back on. Throws when the TV is gone. */
    private suspend fun wake(id: String): Boolean {
        val ok = source.screen(id, on = true)
        if (ok) _state.update { it.copy(screenOff = it.screenOff - id) } else _notice.value = Copy.SCREEN_ON_REFUSED
        return ok
    }

    fun debloatGateOff() {
        _state.update { it.copy(debloatGate = false) }
        viewModelScope.launch(Dispatchers.IO) { gates?.set(DEBLOAT_GATE, true) }
    }

    /** Opens Reset TV and reads what it would change. */
    fun openReset() {
        val id = _state.value.selectedId ?: return
        // Back during a run only closed the screen; opening it again shows that run.
        if (_state.value.device.busy == RESETTING && _state.value.device.reset != null) return
        updateDevice(id) { it.copy(reset = ResetUi()) }
        viewModelScope.launch {
            val ui = try {
                ResetUi(plan = source.resetPlan(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResetUi(error = e.message?.replaceFirstChar(Char::uppercase) ?: "The TV didn't answer.")
            }
            updateDevice(id) { d -> d.copy(reset = d.reset?.let { ui }) }
        }
    }

    /** Reset TV on the selected TV: all of what it found, or with [retry] just the steps that failed. */
    fun runReset(retry: Boolean = false) {
        val id = _state.value.selectedId ?: return
        val ui = _state.value.device.reset ?: return
        val plan = ui.plan ?: return
        if (_state.value.device.busy != null) return
        val todo = if (!retry) plan else ResetPlan(
            turnOn = ui.failed.filter { it.part == ResetPart.TurnOn }.mapNotNull { it.app },
            bringBack = ui.failed.filter { it.part == ResetPart.BringBack }.mapNotNull { it.app },
            // A Home failure with an app is its service, still on: Try again does just that.
            home = plan.home?.takeIf { ui.failed.any { it.part == ResetPart.Home } },
        )
        updateDevice(id) { it.copy(busy = RESETTING, reset = ui.copy(running = todo, progress = null, failed = emptyList())) }
        viewModelScope.launch {
            val (entry, failed) = try {
                source.reset(id, todo, ui.entry) { p -> updateDevice(id) { d -> d.copy(reset = d.reset?.copy(progress = p)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The TV went away before any step could say: every step it was to run failed.
                val why = e.message ?: "the TV didn't answer"
                ui.entry to todo.turnOn.map { ResetFailure(ResetPart.TurnOn, it, why) } +
                    todo.bringBack.map { ResetFailure(ResetPart.BringBack, it, why) } +
                    listOfNotNull(todo.home?.let { ResetFailure(ResetPart.Home, null, why) })
            }
            // Read again what Reset changed, as opening the TV does: the apps, then the list from them,
            // so the Debloat tile counts what came back on. A failed read is tried once more, then said.
            val apps = runCatching { source.apps(id) }.recoverCatching { source.apps(id) }.getOrNull()
            if (apps == null) _notice.value = Copy.RESET_APPS_STALE
            val history = source.history(id)
            updateDevice(id) { d ->
                d.copy(
                    apps = apps ?: d.apps, history = history, busy = null,
                    reset = d.reset?.copy(running = null, progress = null, ran = true, failed = failed, entry = entry ?: ui.entry),
                )
            }
            if (apps != null) loadLists(id) { apps }
        }
    }

    fun closeReset() {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { if (it.busy == RESETTING) it else it.copy(reset = null) }
    }

    /** History opened: its newest change is seen, so ⋮ loses its dot until the next one. */
    fun historyOpened() {
        val s = _state.value
        val id = s.selectedId ?: return
        val at = s.device.history.maxOfOrNull { it.at } ?: return
        if (at <= (s.historySeen[id] ?: 0L)) return
        _state.update { it.copy(historySeen = it.historySeen + (id to at)) }
        viewModelScope.launch(Dispatchers.IO) { historySeen?.set(id, at) }
    }

    fun screenOffTipSeen() {
        _state.update { it.copy(screenOffTip = false) }
        viewModelScope.launch(Dispatchers.IO) { keyHaptics?.screenOffTipSeen = true }
    }

    /**
     * The Remote closed. A picture it turned off stays off without it, and only the Remote can turn
     * it back on, so that happens first.
     */
    fun closeRemote() {
        typed.clear()
        val off = _state.value.screenOff
        if (off.isEmpty()) return source.closeRemote()
        _state.update { it.copy(screenOff = emptySet()) }
        viewModelScope.launch {
            keys.withLock {
                for (id in off) {
                    try {
                        source.screen(id, on = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Gone: a restart or power cycle brings the picture back on its own.
                    }
                }
            }
            source.closeRemote()
        }
    }

    /** Types on the TV. Returns why it can't, or null once it's on its way. */
    fun type(text: String): String? {
        if (!Remote.typeable(text)) return Copy.cantType(text)
        val id = _state.value.selectedId ?: return null
        viewModelScope.launch {
            keys.withLock {
                _notice.value = try {
                    if (source.type(id, text)) {
                        typed[id] = (typed[id] ?: 0) + text.length
                        null
                    } else {
                        Copy.TYPE_REFUSED
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Copy.REMOTE_LOST
                }
            }
        }
        return null
    }

    /** The TV shows its own update screen; Tellyscope can't see what it finds, so it only says where to look. */
    fun checkUpdates() {
        val id = _state.value.selectedId ?: return
        viewModelScope.launch {
            val ok = try {
                source.openUpdates(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = Copy.REMOTE_LOST
                return@launch
            }
            _notice.value = if (ok) Copy.UPDATES_OPENED else Copy.NO_UPDATE_SCREEN
        }
    }

    fun reboot() {
        val id = _state.value.selectedId ?: return
        viewModelScope.launch { restart(id) }
    }

    /** Asks the TV to restart and starts the wait for it; false, with the snackbar saying why, when it won't. */
    private suspend fun restart(id: String): Boolean {
        val ok = try {
            source.reboot(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _notice.value = Copy.REMOTE_LOST
            return false
        }
        // A restart brings the picture back on by itself.
        if (ok) _state.update { it.copy(restarting = it.restarting + (id to Restart(clock())), screenOff = it.screenOff - id) }
        else _notice.value = Copy.REBOOT_REFUSED
        return ok
    }

    /** Retry once a restart runs late: the link tries the TV again, and the wait starts over for a minute. */
    fun retryRestart() {
        val id = _state.value.selectedId ?: return
        _state.update { s ->
            val r = s.restarting[id] ?: return@update s
            s.copy(restarting = s.restarting + (id to r.copy(waitFrom = clock(), retried = true)))
        }
        viewModelScope.launch { source.reconnect(id) }
    }

    /** Close on "Still can't reach": stop waiting, and the Remote shows the TV as offline. */
    fun stopWaiting() {
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(restarting = it.restarting - id) }
    }

    /** The dock's add, swap, remove and reorder all land here, saved per TV. */
    fun setDock(dock: List<Docked>) {
        val id = _state.value.selectedId ?: return
        updateDevice(id) { it.copy(dock = dock) }
        viewModelScope.launch { source.setDock(id, dock) }
    }

    /** A Remote button: opens the app on the TV, without a history entry. */
    fun launch(app: Docked) {
        // An empty list hasn't been read yet, so the TV gets to try.
        val apps = _state.value.device.apps
        if (apps.isNotEmpty() && apps.none { it.packageName == app.packageName }) {
            _notice.value = Copy.notOnTv(app.label)
            return
        }
        open(app.packageName, app.label)
    }

    private fun open(pkg: String, label: String) {
        val id = _state.value.selectedId ?: return
        viewModelScope.launch {
            val ok = try {
                source.launch(id, pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = Copy.REMOTE_LOST
                return@launch
            }
            if (!ok) _notice.value = Copy.cantOpen(label)
        }
    }

    /**
     * An app uninstalled, here or on the TV itself, takes its button off the Remote, and the snackbar
     * says so. Not with [RemoteButtons.ANY_SERVICE], where any service can have a button.
     */
    private suspend fun pruneDock(id: String, apps: List<AppInfo>) {
        if (RemoteButtons.ANY_SERVICE) return
        val (kept, gone) = Dock.prune(source.dock(id), apps)
        if (gone.isEmpty()) return
        source.setDock(id, kept)
        updateDevice(id) { it.copy(dock = kept) }
        _notice.value = Copy.leftDock(gone.map { it.label })
    }

    /**
     * A restart ends when the TV is back online after dropping, or stops at a refusal. One still
     * online after [RESTART_GRACE_MS] never went down, so the TV ignored the request.
     */
    private fun restarts(current: Map<String, Restart>, known: List<Device>): Map<String, Restart> {
        val now = clock()
        return current.mapNotNull { (id, r) ->
            val device = known.firstOrNull { it.id == id } ?: return@mapNotNull null
            val state = device.state
            when {
                state == DeviceState.Online && r.down -> {
                    _notice.value = Copy.backOnline(name(device))
                    null
                }
                state == DeviceState.Online && now - r.since > RESTART_GRACE_MS -> {
                    _notice.value = Copy.REBOOT_IGNORED
                    null
                }
                state == DeviceState.Online -> id to r
                state == DeviceState.NotAllowed || state == DeviceState.NeedsPairing -> null
                else -> id to r.copy(down = true)
            }
        }.toMap()
    }

    private fun setRow(ip: String, row: RowState) =
        _state.update { it.copy(scan = it.scan.copy(rows = it.scan.rows + (ip to row))) }

    private companion object {
        const val RESTART_GRACE_MS = 60_000L
    }

    private fun ipKey(ip: String) = ip.split('.').fold(0L) { acc, o -> acc * 256 + (o.toLongOrNull() ?: 0) }
}
