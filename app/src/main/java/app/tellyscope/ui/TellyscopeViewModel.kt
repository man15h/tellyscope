// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.InstallKind
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.adb.Progress
import app.tellyscope.adb.Remote
import app.tellyscope.adb.RemoteKey
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
import app.tellyscope.data.HistoryEntry
import app.tellyscope.data.NetworkInfo
import app.tellyscope.data.NotAnApp
import app.tellyscope.data.PhoneStorage
import app.tellyscope.data.PickedApp
import app.tellyscope.data.ScanEvent
import app.tellyscope.data.ScanHit
import app.tellyscope.data.ScanKind
import app.tellyscope.data.ScanProgress
import app.tellyscope.data.Snapshot
import app.tellyscope.data.Suggestions
import app.tellyscope.data.TvSettings
import app.tellyscope.data.TvTweaks
import app.tellyscope.data.Update
import app.tellyscope.data.FoldBook
import app.tellyscope.data.UpdateCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
) {
    /** Off Wi-Fi with nothing found: the scan screen asks the user to join one instead of listing. */
    val offWifi get() = noLan && hits.isEmpty()
}

data class DeviceUi(
    val snapshot: Snapshot? = null,
    val apps: List<AppInfo> = emptyList(),
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
)

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
    /** The Apps tabs whose System section the user folded away. */
    val foldedSystem: Set<AppsTab> = emptySet(),
) {
    val selected: Device? get() = devices.firstOrNull { it.id == selectedId }
}

/** An action that went through on [deviceId]; the snackbar offers its undo. */
data class Done(val deviceId: String, val entry: HistoryEntry)

/** A restart in progress: [down] once the TV has dropped, so its coming back can be told apart from not having left. */
data class Restart(val since: Long, val down: Boolean = false)

/** [DeviceUi.busy] value while "Restore everything" runs. */
const val RESTORING = "restore"

/** [DeviceUi.busy] value while the ticked suggestions are being disabled. */
const val DISABLING = "lists"

/** [DeviceUi.busy] values for the TV tweaks; a setting change uses its [Tweak] name. */
const val CLEARING = "caches"
const val SCREENSHOT = "screenshot"

/** [DeviceUi.busy] value while the TV's connection prompt is being answered. */
const val ANSWERING = "request"

class TellyscopeViewModel(
    private val source: DeviceSource,
    private val storage: PhoneStorage? = null,
    private val allowTimeoutMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val updates: UpdateCheck? = null,
    private val folds: FoldBook? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState(now = clock()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot "done, undo?" messages for the snackbar, with the TV they happened on. */
    private val _done = MutableStateFlow<Done?>(null)
    val done: StateFlow<Done?> = _done.asStateFlow()

    /** One-shot snackbar lines: the TV refused, went away mid-action, or a restore or copy finished. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Last stats per TV, so an offline page shows where it stood instead of blanks. Lives as long as the app. */
    private val lastSnapshot = mutableMapOf<String, Snapshot>()

    /** Keys go out one at a time, in the order pressed. */
    private val keys = Mutex()

    private var scanJob: Job? = null
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
    private var updateJob: Job? = null
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
        viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(1_000)
                _state.update { it.copy(now = clock()) }
                if (++tick % 3 == 0) refreshDevices()
            }
        }
        updates?.let { u ->
            viewModelScope.launch(Dispatchers.IO) { _state.update { it.copy(checkUpdates = u.enabled, alphaBuild = u.alpha) } }
        }
        folds?.let { f ->
            viewModelScope.launch(Dispatchers.IO) {
                val folded = f.folded().mapNotNull { name -> AppsTab.entries.firstOrNull { it.name == name } }.toSet()
                _state.update { it.copy(foldedSystem = folded) }
            }
        }
        rescan()
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

    fun rescan() {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            // Empty scan with no TV open: the guide is on screen; keep looking so the user never has to
            // press anything. The retry stays in this job, as rescan() from here would see it still active.
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
                if (_state.value.let { it.scan.hits.isNotEmpty() || it.selected != null }) break
                delay(15_000)
            }
        }
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

    fun select(id: String?) {
        liveJob?.cancel()
        mediumJob?.cancel()
        listsJob?.cancel()
        requestJob?.cancel()
        // A copy still running on this TV keeps its dialog, after a reconnect or a look at another TV.
        val transfer = id?.let(transfers::get)
        _state.update { it.copy(selectedId = id, device = DeviceUi(snapshot = id?.let(lastSnapshot::get), transfer = transfer), query = "") }
        val device = _state.value.selected ?: return
        // History lives on the phone, so it shows (and undo is offered) even while the TV is off.
        viewModelScope.launch {
            val history = source.history(device.id)
            _state.update { if (it.selectedId == device.id) it.copy(device = it.device.copy(history = history)) else it }
        }
        if (device.state != DeviceState.Online) return
        liveJob = viewModelScope.launch {
            source.live(id!!).collect { snap ->
                lastSnapshot[id] = snap
                _state.update { it.copy(device = it.device.copy(snapshot = snap)) }
            }
        }
        // Its own job: the list download never holds up apps or stats.
        listsJob = viewModelScope.launch { loadLists(id!!) }
        requestJob = viewModelScope.launch { watchRequests(id!!) }
        mediumJob = viewModelScope.launch {
            // A TV that drops mid-read throws; the live flow ends and the offline card takes over.
            try {
                // Read first: update {} may run its block again, and must never call the TV.
                val apps = source.apps(id!!)
                updateDevice(id) { it.copy(apps = apps) }
                val tweaks = source.tweaks(id)
                updateDevice(id) { it.copy(tweaks = tweaks) }
                loadFiles()
                while (true) {
                    val net = source.network(id!!)
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

    /** Remembered TVs reconnect on their own; pick up their state and restart stats when one comes back. */
    private suspend fun refreshDevices() {
        val known = source.known()
        val before = _state.value.selected?.state
        _state.update { s -> s.copy(devices = known, restarting = restarts(s.restarting, known)) }
        val now = _state.value.selected ?: return
        if (now.state == DeviceState.Online && before != DeviceState.Online) select(now.id)
    }

    /** Reconnect button: a TV stopped at "not allowed" asks again; an offline one restarts its stats. */
    fun reconnect(id: String) {
        viewModelScope.launch {
            source.reconnect(id)
            refreshDevices()
            select(id)
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

    private fun run(pkg: String, action: Action, undoing: Long? = null, id: String? = _state.value.selectedId) {
        if (id == null) return
        updateDevice(id) { it.copy(pending = null, busy = pkg) }
        viewModelScope.launch {
            // The source throws with the user-facing reason when the TV refuses.
            val (entry, apps) = try {
                source.act(id, action, pkg, undoing) to source.apps(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else (a dropped socket) still must not crash the app.
                updateDevice(id) { it.copy(busy = null) }
                undoing?.let(undoInFlight::remove) // a failed Undo can be tried again
                _notice.value = e.message?.replaceFirstChar(Char::uppercase) ?: "That didn't work."
                return@launch
            }
            val history = source.history(id)
            updateDevice(id) { it.copy(apps = apps, busy = null, history = history) }
            if (undoing == null) _done.value = Done(id, entry)
        }
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
                    it.copy(sideload = ready.copy(step = SideloadUi.Step.Failed, error = Copy.installRefused(r.reason, app.label), detail = r.detail))
                }
            }
        }
    }

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

    private suspend fun loadLists(id: String) {
        val lists = source.suggestions(id)
        // Once the list is known its keep entries show as protected.
        val apps = if (lists is Suggestions.Ready) runCatching { source.apps(id) }.getOrNull() else null
        _state.update { s ->
            if (s.selectedId != id) s else s.copy(device = s.device.copy(lists = lists, apps = apps ?: s.device.apps))
        }
    }

    /**
     * Another device's "Allow debugging?" prompt, checked every few seconds while the TV's page
     * is open. A failed check just waits for the next one.
     */
    private suspend fun watchRequests(id: String) {
        while (true) {
            val request = try {
                source.connectionRequest(id)?.takeIf { it.fingerprint != dismissedRequest }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            updateDevice(id) { if (it.busy == ANSWERING) it else it.copy(request = request) }
            delay(5_000)
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
        }
    }

    /** "Pick your TV": remember the choice for this TV and show its list. */
    fun pickList(listId: String) {
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(device = it.device.copy(lists = null)) }
        listsJob?.cancel()
        listsJob = viewModelScope.launch {
            source.pickList(id, listId)
            loadLists(id)
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
            _state.update { it.copy(listSource = now, device = it.device.copy(lists = null)) }
            _notice.value = Copy.listSource(now)
            if (id != null) loadLists(id)
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

    /** Disables the ticked suggestions one by one, each recorded in History with its own undo. */
    fun disableSuggested(packages: List<String>) {
        val id = _state.value.selectedId ?: return
        _state.update { it.copy(device = it.device.copy(busy = DISABLING)) }
        viewModelScope.launch {
            var done = 0
            for (pkg in packages) {
                try {
                    source.act(id, Action.Disable, pkg)
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The TV refused this one or dropped; the count says so.
                }
            }
            _notice.value = Copy.disabledSuggested(done, packages.size)
            val apps = runCatching { source.apps(id) }.getOrNull()
            val history = source.history(id)
            updateDevice(id) { it.copy(apps = apps ?: it.apps, busy = null, history = history) }
            loadLists(id)
        }
    }

    /**
     * Sends one key and waits for the TV to take it; false when it didn't. The remote screen holds
     * a D-pad key by calling this in a loop, which stops at the first false, so a lost TV shows once.
     */
    suspend fun press(key: RemoteKey): Boolean {
        val id = _state.value.selectedId ?: return false
        return keys.withLock {
            try {
                source.press(id, key).also { if (!it) _notice.value = Copy.KEY_REFUSED }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = Copy.REMOTE_LOST
                false
            }
        }
    }

    /** Types on the TV. Returns why it can't, or null once it's on its way. */
    fun type(text: String): String? {
        if (!Remote.typeable(text)) return Copy.cantType(text)
        val id = _state.value.selectedId ?: return null
        viewModelScope.launch {
            keys.withLock {
                _notice.value = try {
                    if (source.type(id, text)) null else Copy.TYPE_REFUSED
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
        viewModelScope.launch {
            val ok = try {
                source.reboot(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _notice.value = Copy.REMOTE_LOST
                return@launch
            }
            if (ok) _state.update { it.copy(restarting = it.restarting + (id to Restart(clock()))) }
            else _notice.value = Copy.REBOOT_REFUSED
        }
    }

    /**
     * A restart ends when the TV is back online after dropping, or stops at a refusal. One still
     * online after [RESTART_GRACE_MS] never went down, so the TV ignored the request.
     */
    private fun restarts(current: Map<String, Restart>, known: List<Device>): Map<String, Restart> {
        val now = clock()
        return current.mapNotNull { (id, r) ->
            val state = known.firstOrNull { it.id == id }?.state ?: return@mapNotNull null
            when {
                state == DeviceState.Online && r.down -> null
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
