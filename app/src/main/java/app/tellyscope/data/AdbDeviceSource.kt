// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import app.tellyscope.R
import app.tellyscope.adb.Access
import app.tellyscope.adb.ActionResult
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.BatchedShell
import app.tellyscope.adb.Files
import app.tellyscope.adb.HomeOptions
import app.tellyscope.adb.HomeResult
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.Progress
import app.tellyscope.adb.Sideload
import app.tellyscope.adb.TvFacts
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.adb.Actions
import app.tellyscope.adb.AdbSession
import app.tellyscope.adb.ConnectionPrompt
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.Connector
import app.tellyscope.adb.DeviceLink
import app.tellyscope.adb.KeyHelper
import app.tellyscope.adb.LabelHelper
import app.tellyscope.adb.LanScanner
import app.tellyscope.adb.NetworkStats
import app.tellyscope.adb.Parsers
import app.tellyscope.adb.Peripherals
import app.tellyscope.adb.Protected
import app.tellyscope.adb.ProtectedReason
import app.tellyscope.adb.Remote
import app.tellyscope.adb.RemoteKey
import app.tellyscope.adb.Reset as TvReset
import app.tellyscope.adb.Stats
import app.tellyscope.adb.StatsProfile
import app.tellyscope.adb.PairResult
import app.tellyscope.adb.pairOnFreshPort
import app.tellyscope.adb.Tweak
import app.tellyscope.adb.Tweaks
import app.tellyscope.adb.TvShell
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.Sink
import okio.Source
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import app.tellyscope.adb.Action as AdbAction
import app.tellyscope.adb.ConnectResult as AdbConnectResult
import app.tellyscope.adb.DeviceState as AdbState
import app.tellyscope.adb.ScanHit as AdbHit

/**
 * [DeviceSource] on real TVs through `:adb`. Device id is the TV's IP. Wi-Fi name, signal, MAC and
 * link speed are empty for now. App names and icons come from the label helper through [AppNames].
 */
class AdbDeviceSource(
    context: Context,
    private val connector: suspend (String, Int) -> AdbConnectResult = { h, p -> Connector.connect(h, p) },
) : DeviceSource {
    private val appContext = context.applicationContext
    /** Our text in the app's language now, for what's saved: History labels, names stored with data. */
    private fun UiText.text(): String = this.resolve(appContext.resources)
    private val scope = CoroutineScope(SupervisorJob())
    private val links = ConcurrentHashMap<String, DeviceLink>()
    /** The TV on screen and its screen's refresh rates; every other TV reads at [StatsProfile.OTHER]. */
    @Volatile private var statsFor: Pair<String, StatsProfile>? = null
    /** The app is in the background; see [pause]. */
    @Volatile private var paused = false
    private val hits = ConcurrentHashMap<String, ScanHit>()
    private val store = DeviceStore(File(appContext.filesDir, "devices.tsv"))
    /** Launchers whose accessibility service a switch may turn on: only from a `data/` that passes its check. */
    private val serviceLaunchers get() = SharedLists.serviceLaunchers
    /** Launchers whose service may hold Home and is turned off on the way out: from `data/` signed or not. */
    private val listedServiceLaunchers get() = SharedLists.listedServiceLaunchers
    private val saved = ConcurrentHashMap<String, SavedDevice>()
    /** Remembered TVs that haven't answered since launch: shown offline, not "connecting". */
    private val restoring = ConcurrentHashMap.newKeySet<String>()
    private val watchers = ConcurrentHashMap<String, Job>()
    /** Address → [historyKey], so history follows a TV across DHCP changes. */
    private val keys = ConcurrentHashMap<String, String>()
    /** Wireless debugging advertises pairing and connect on different ports. */
    private val pairingPorts = ConcurrentHashMap<String, Int>()
    /** Address → a wireless-debugging TV's `_adb-tls-connect` instance name, see [SavedDevice.service]. */
    private val services = ConcurrentHashMap<String, String>()
    private val book = HistoryBook(File(appContext.filesDir, "history.tsv"))
    private val names = NameBook(File(appContext.filesDir, "names.tsv"))
    private val docks = DockBook(File(appContext.filesDir, "docks.tsv"))
    /** TVs whose Apps tab got Not now on the card about a better home screen. */
    private val launcherCards = FoldBook(File(appContext.filesDir, "launcher-card.properties"))
    private val mine = MineBook(File(appContext.filesDir, "mine.tsv"))
    /** The keys each TV was seen to allow, so one allowed behind Tellyscope's back shows as new. */
    private val knownKeys = MineBook(File(appContext.filesDir, "known-keys.tsv"))
    private val networkNames = NetworkNames()
    /** Last label seen per package, so history entries read like the app list. */
    private val labels = ConcurrentHashMap<String, String>()
    /** Per TV, the session [fireHome] read on and the launcher it found. */
    private val fireHomes = ConcurrentHashMap<String, Pair<AdbSession, String?>>()
    /** Per TV, the session [volumeKeys] read on and the keys per tap it worked out. */
    private val volumeSteps = ConcurrentHashMap<String, Pair<AdbSession, Int>>()
    /** The remote's key helper on each TV, while the remote is open. */
    private val keyHelpers = ConcurrentHashMap<String, KeyHelper>()
    private val helperDex by lazy { appContext.assets.open("label-helper.jar").use { it.readBytes() } }
    private val appNames = AppNames(File(appContext.filesDir, "app-names"))
    private val lists = DeviceLists(File(appContext.filesDir, "lists"))
    /** `data/`'s launcher, app name and streaming lists; never the user's source. */
    private val data = DeviceLists(File(appContext.filesDir, "data"), DATA_BASE, DATA_KEYS)
    /** [historyKey] → the keep entries of that TV's list, once it has been looked up. */
    private val keep = ConcurrentHashMap<String, Set<String>>()

    init {
        Connector.init(File(appContext.filesDir, "adbkey.pem"))
        scope.launch(Dispatchers.IO) { SharedLists.load(data, lists) }
    }

    /** Remembered TVs reconnect in the background from launch; [known] waits for the file read. */
    private val restored = scope.async(Dispatchers.IO) {
        store.load().forEach { d ->
            saved[d.host] = d
            d.historyKey?.let { keys[d.host] = it }
            // Saved before the service had a field of its own: a wireless TV's name was its instance name.
            (d.service ?: d.name?.takeIf { d.kind == ScanKind.Wireless && it.startsWith("adb-") })?.let { services[d.host] = it }
            hits[d.host] = ScanHit(d.host, d.port, d.kind, d.name, null)
            // A TV that logged this phone out stays quiet: reconnecting would prompt on it again.
            if (d.loggedOutAt == null) restoring += d.host
            link(d.host, d.port, first = null, knownGood = true, loggedOutAt = d.loggedOutAt)
        }
    }

    /**
     * Starts the reconnecting link for a TV and keeps its saved entry current as it comes and goes.
     * A TV [loggedOutAt] a time gets its link without a connect.
     *
     * A wireless-debugging TV is looked up by its instance name before each retry, as its port moves.
     * Whatever answers a retry must be the same TV ([sameTv]): another one that took the address or the
     * port is let go, and the saved TV stays offline.
     */
    private fun link(host: String, port: Int, first: AdbConnectResult?, knownGood: Boolean, loggedOutAt: Long? = null) {
        var pending = first
        // What the link was made for: a later scan can put a Classic hit on this address.
        val wireless = hits[host]?.kind == ScanKind.Wireless
        val link = DeviceLink(
            host, port, scope, knownGood = knownGood,
            open = { h, p ->
                // The user picked [first] off the scan: that one is the TV they meant.
                pending?.also { pending = null } ?: connector(h, p).let { r ->
                    if (!wireless) r else sameTv(r, keys[host]) { keyOf(it, host) }
                }
            },
            findPort = { services[host]?.let { LanScanner(KadbMdnsAndroid(appContext)).connectPort(host, it) } },
        )
        link.gate.profile.value = statsFor?.takeIf { it.first == host }?.second ?: StatsProfile.OTHER
        link.gate.paused.value = paused
        links.put(host, link)?.disconnect()
        watchers.put(host, scope.launch {
            // Save a fresh reading only when what's kept of it changed, not every 2 s.
            launch {
                link.stats.collect { st ->
                    val kept = saved[host]?.lastKnown
                    if (st != null && lastKnown(st, kept) != kept) remember(host)
                }
            }
            launch {
                link.loggedOutAt.collect { at ->
                    val d = saved[host] ?: return@collect
                    if (d.loggedOutAt != at) {
                        saved[host] = d.copy(loggedOutAt = at)
                        persist()
                    }
                }
            }
            link.state.collect { st ->
                if (st == AdbState.ONLINE) {
                    restoring -= host
                    // Read again on every connect: a new TV may have taken over a saved address.
                    launch { readKey(host) }
                }
                if (st == AdbState.ONLINE || st == AdbState.OFFLINE) remember(host)
            }
        })?.cancel()
        if (loggedOutAt != null) link.holdLoggedOut(loggedOutAt) else link.connect()
    }

    private fun remember(host: String) {
        val link = links[host] ?: return
        val hit = hits[host] ?: return
        val old = saved[host]
        // Another TV on a saved address starts from nothing, not from the old one's facts.
        val kept = old?.lastKnown?.takeIf { old.historyKey == null || old.historyKey == keys[host] }
        saved[host] = SavedDevice(
            host = host,
            port = link.port,
            kind = hit.kind,
            name = hit.hostname,
            model = link.stats.value?.model ?: old?.model,
            lastSeen = link.lastSeenMillis ?: old?.lastSeen ?: System.currentTimeMillis(),
            historyKey = keys[host] ?: old?.historyKey,
            lastKnown = link.stats.value?.let { lastKnown(it, kept) } ?: kept,
            loggedOutAt = link.loggedOutAt.value,
            service = services[host],
        )
        persist()
    }

    /**
     * What is kept of a reading; a fact this reading missed keeps its [old] value. Storage is
     * rounded to 0.1 GB so a few KB written doesn't count as new.
     */
    private fun lastKnown(s: Stats, old: LastKnown?) = LastKnown(
        manufacturer = s.manufacturer ?: old?.manufacturer,
        brand = s.brand ?: old?.brand,
        android = s.androidVersion ?: old?.android,
        softwareMonth = s.softwareMonth ?: old?.softwareMonth,
        storageUsedGb = s.storage?.let { tenths(it.usedKb) } ?: old?.storageUsedGb,
        storageTotalGb = s.storage?.let { tenths(it.totalKb) } ?: old?.storageTotalGb,
        fireOs = s.fireOsVersion ?: old?.fireOs,
    )

    private fun tenths(kb: Long) = Math.round(kb / 104_857.6) / 10.0

    /** Reads the map at write time, so whichever save runs last writes the newest list. */
    private fun persist() {
        scope.launch(Dispatchers.IO) { synchronized(store) { store.save(saved.values.sortedBy { it.host }) } }
    }

    override suspend fun reconnect(deviceId: String) {
        restored.await()
        links[deviceId]?.connect(asked = true)
    }

    override suspend fun cancelReconnect(deviceId: String) {
        restored.await()
        val link = links[deviceId] ?: return
        // Connect again after a logout goes back to it, with its time; any other attempt just stops.
        link.loggedOutAt.value?.let { link.holdLoggedOut(it) } ?: link.disconnect()
    }

    override suspend fun rename(deviceId: String, name: String?) {
        restored.await()
        names.set(key(deviceId), name)
    }

    override suspend fun forget(deviceId: String) {
        restored.await()
        watchers.remove(deviceId)?.cancel()
        keyHelpers.remove(deviceId)?.close()
        fireHomes -= deviceId
        volumeSteps -= deviceId
        links.remove(deviceId)?.disconnect()
        hits.remove(deviceId)
        services.remove(deviceId)
        restoring -= deviceId
        if (saved.remove(deviceId) != null) persist()
    }

    /**
     * The subnet of the active network if it's Wi-Fi or Ethernet. On mobile data or a VPN there is
     * no sweep, so we never probe a carrier's or VPN's neighbours; mDNS and add-by-IP still work.
     */
    private fun lanHosts(): List<String> {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val network = cm.activeNetwork ?: return emptyList()
        val caps = cm.getNetworkCapabilities(network) ?: return emptyList()
        val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return emptyList()
        return cm.getLinkProperties(network)?.linkAddresses.orEmpty()
            .filter { it.address is Inet4Address }
            .flatMap { LanScanner.subnetHosts(it.address as Inet4Address, it.prefixLength) }
            .distinct()
    }

    /** Unlike [lanHosts], a VPN on top doesn't count as off Wi-Fi: connect can still reach the TV. */
    override fun onLan(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        @Suppress("DEPRECATION") // the only synchronous list of every network, VPN's underlying one included
        return cm.allNetworks.any { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@any false
            // A VPN carries the transports it runs over, so it can still say Wi-Fi once Wi-Fi has gone;
            // the Wi-Fi under it is in the list in its own right.
            val local = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            // Wi-Fi on its way down can stay listed with no address left, and the TV is already out of reach.
            local && cm.getLinkProperties(n)?.linkAddresses.orEmpty().any { it.address is Inet4Address }
        }
    }

    private fun onVpn(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    override fun lanLost() = links.values.forEach(DeviceLink::lanLost)

    override fun lanUp(): Flow<Unit> = callbackFlow {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: run { close(); return@callbackFlow }
        val callback = object : ConnectivityManager.NetworkCallback() {
            // Capabilities can arrive before the address, so check again on each.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = check()
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = check()
            private fun check() { if (lanHosts().isNotEmpty()) trySend(Unit) }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }

    override suspend fun refreshListsOnNetwork() = callbackFlow {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: run { close(); return@callbackFlow }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            // Validated comes after onAvailable, so wait for it here; the network's other changes don't count.
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == last || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                last = network
                trySend(Unit)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.collect {
        // Paused, [pause] refreshes on the way back.
        if (!paused) withContext(Dispatchers.IO) { SharedLists.refresh() }
    }

    override fun scan(): Flow<ScanEvent> = flow {
        val hosts = lanHosts()
        if (hosts.isEmpty()) when {
            !onLan() -> emit(ScanEvent.NoLan)
            onVpn() -> emit(ScanEvent.Vpn)
        }
        LanScanner(KadbMdnsAndroid(appContext)).scan(hosts).collect { h ->
            if (h.kind == AdbHit.Kind.PAIRING) {
                pairingPorts[h.host] = h.port
                if (hits.containsKey(h.host)) return@collect
            }
            val hit = ScanHit(
                ip = h.host,
                port = h.port,
                kind = if (h.kind == AdbHit.Kind.CLASSIC) ScanKind.Classic else ScanKind.Wireless,
                hostname = h.name,
                maker = null, // Android 10+ apps can't read the ARP table
            )
            hits[h.host] = hit
            emit(ScanEvent.Found(hit))
        }
        emit(ScanEvent.Done)
    }

    override suspend fun connect(hit: ScanHit): ConnectResult = when (val r = connector(hit.ip, hit.port)) {
        is AdbConnectResult.Online -> {
            hits[hit.ip] = hit
            // Added by IP:port, a wireless-debugging TV has no instance name from a scan to follow its port by; it keeps one in a prop.
            val service = if (hit.kind != ScanKind.Wireless) null else hit.hostname ?: wifiGuid(r.session)
            service?.let { services[hit.ip] = it } ?: services.remove(hit.ip)
            restoring -= hit.ip
            // Hand the open session to a link, which reconnects on its own from here on.
            link(hit.ip, hit.port, first = r, knownGood = false)
            ConnectResult.Online(device(hit.ip))
        }
        AdbConnectResult.NotAllowed -> ConnectResult.NotAllowed
        AdbConnectResult.NeedsPairing -> ConnectResult.NeedsPairing
        is AdbConnectResult.Unreachable -> ConnectResult.Unreachable(r.reason)
    }

    /** The TV's wireless-debugging instance name, as mDNS shows it (`adb-<serial>-<tag>`); null when it has none. */
    private suspend fun wifiGuid(session: AdbSession): String? = try {
        session.shell("getprop persist.adb.wifi.guid").output.trim().takeIf { WIFI_GUID.matches(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    override suspend fun pair(hit: ScanHit, code: String): ConnectResult {
        // A port typed for add-by-IP, else the last one a scan saw; never the connect port.
        val hint = hit.port.takeIf { it != hits[hit.ip]?.port } ?: pairingPorts[hit.ip]
        val r = pairOnFreshPort(
            hint,
            find = { LanScanner(KadbMdnsAndroid(appContext)).pairingPort(hit.ip) },
            pair = { Connector.pair(hit.ip, it, code) },
        )
        when (r) {
            PairResult.Paired -> Unit
            is PairResult.WrongCode -> return ConnectResult.WrongCode(r.reason)
            is PairResult.Unreachable -> return ConnectResult.Unreachable(r.reason)
        }
        // After pairing, the connect port is the one mDNS advertised, not the pairing port.
        val connectHit = hits[hit.ip]?.takeIf { it.port != pairingPorts[hit.ip] } ?: hit
        return connect(connectHit)
    }

    override suspend fun known(): List<Device> {
        restored.await()
        return links.keys.map(::device)
    }

    override fun live(deviceId: String): Flow<Snapshot> {
        val link = links[deviceId] ?: return flow {}
        // Completes once the link drops (offline, not allowed, needs pairing), as the interface promises.
        return combine(link.state, link.stats) { state, stats -> state to stats }
            .transformWhile { (state, stats) ->
                if (state == AdbState.ONLINE && stats != null) emit(stats)
                state in setOf(AdbState.FOUND, AdbState.WAITING, AdbState.ONLINE)
            }
            .map { s ->
                val mem = s.memory
                Snapshot(
                    at = System.currentTimeMillis(),
                    cpuPercent = s.cpuPercent?.toInt() ?: 0,
                    ramUsedMb = mem?.let { ((it.totalKb - it.availableKb) / 1024).toInt() } ?: 0,
                    ramTotalMb = mem?.let { (it.totalKb / 1024).toInt() } ?: 0,
                    storageUsedGb = s.storage?.let { it.usedKb / 1_048_576.0 },
                    storageTotalGb = s.storage?.let { it.totalKb / 1_048_576.0 },
                    uptimeSeconds = s.uptimeSeconds ?: 0,
                    screenOn = s.screenOn,
                    onScreen = s.onScreen,
                    procs = s.processes.map { Proc(it.pid, it.name, it.packageName, it.cpuPercent ?: 0.0, (it.rssKb / 1024).toInt()) },
                    netBytesPerSec = s.netBytesPerSec,
                    softwareMonth = s.softwareMonth,
                    cores = s.cores.map { it.roundToInt() },
                    cpuApps = s.cpuSplit?.apps?.roundToInt(),
                    cpuSystem = s.cpuSplit?.system?.roundToInt(),
                    cpuWaiting = s.cpuSplit?.ioWait?.roundToInt(),
                    chip = s.chip,
                    clockMhz = s.clockKhz?.let { (it / 1000).toInt() },
                    ramCachedMb = mem?.let { ((it.availableKb - it.freeKb) / 1024).toInt() } ?: 0,
                    ramFreeMb = mem?.let { (it.freeKb / 1024).toInt() } ?: 0,
                    swapUsedMb = mem?.let { ((it.swapTotalKb - it.swapFreeKb) / 1024).toInt() } ?: 0,
                    swapTotalMb = mem?.let { (it.swapTotalKb / 1024).toInt() } ?: 0,
                    rxBytesPerSec = s.rxBytesPerSec,
                    txBytesPerSec = s.txBytesPerSec,
                    rxTotalBytes = s.netTotal?.rx,
                    txTotalBytes = s.netTotal?.tx,
                )
            }
    }

    override fun showStats(deviceId: String?, profile: StatsProfile) {
        statsFor = deviceId?.let { it to profile }
        links.forEach { (host, link) -> link.gate.profile.value = if (host == deviceId) profile else StatsProfile.OTHER }
    }

    override fun pause(paused: Boolean) {
        this.paused = paused
        links.values.forEach { it.gate.paused.value = paused }
        // Back in front: lists that couldn't be downloaded at launch get another go.
        if (!paused) scope.launch(Dispatchers.IO) { SharedLists.refresh() }
    }

    /**
     * Runs a change with that TV's stats paused, so a slow stats read can't compete with it or cut it off.
     * [reread]: it changes apps or storage, so the stats it touches are read again once it ends.
     */
    private suspend fun <T> busy(deviceId: String, reread: Boolean = false, block: suspend () -> T): T =
        links[deviceId]?.gate?.during(reread, block) ?: block()

    override suspend fun apps(deviceId: String): List<AppInfo> {
        val session = session(deviceId)
        val shell = BatchedShell.appList(session)
        val home = Actions(shell).homePackages()
        val homeOptions = Actions(shell).homeOptions()
        val system = Parsers.packages(shell.shell("pm list packages -s").output).map { it.name }.toSet()
        val disabled = Parsers.packages(shell.shell("pm list packages -d").output).map { it.name }.toSet()
        val byTv = Actions(shell).disabledByTv()
        val userInstalled = Parsers.packages(shell.shell("pm list packages -3").output).map { it.name }.toSet()
        // Before Android 7 there's no asking which apps have an icon; the ones the user installed do.
        val launchable = Actions(shell).launchable() ?: userInstalled
        val tvHome = Actions(shell).tvLaunchable() ?: launchable
        // Older pm rejects --show-versioncode and prints no packages; list again without it.
        val listed = Parsers.packages(shell.shell("pm list packages --show-versioncode").output)
            .ifEmpty { Parsers.packages(shell.shell("pm list packages").output) }
        // Same key as history: a placeholder serial must not give two boxes one cache.
        val names = appNames.names(key(deviceId), listed, LabelHelper(session, helperDex)::describe)
        val keep = keep[key(deviceId)].orEmpty()
        // Fire OS keeps Amazon's launcher the default; a launcher whose service is on holds Home instead.
        val held = if (fireOs(deviceId, FIRE_TV_LAUNCHER in system)) Actions(shell).fireServiceHolder(listedServiceLaunchers)?.first else null
        // On a launcher the user added, offer the way back to the one it replaced.
        val own = homeOptions?.let { builtIns(system, disabled, it) }.orEmpty()
        val back = homeOptions?.current?.takeIf { it !in system }
            ?.let { homeBackTarget(it, book.entries(key(deviceId)), own) }
        // Only launchers at their list's version or newer are offered the service.
        val offered = serviceLaunchers.filterTo(HashSet()) { pkg -> listed.any { it.name == pkg } && Actions(shell).mayTurnOn(pkg) }
        // Its service off, the TV's own launcher may still answer Home: the row offers Turn on.
        val serviceOff = back != null && homeOptions?.current?.takeIf { it in offered }
            ?.let { Actions(shell).homeService(it) }?.let { !Actions(shell).serviceOn(it) } == true
        // The card stays until Got it, which notes it on the switch; the next switch shows it again.
        val note = serviceOff && back?.reverses?.let { id -> book.entries(key(deviceId)).firstOrNull { it.id == id } }
            ?.let { it.previous?.get("note") != "dismissed" } == true
        return listed.map { p ->
            val name = names.getValue(p.name)
            labels[p.name] = name.label
            AppInfo(
                packageName = p.name,
                label = name.label,
                iconColor = name.tileColor,
                iconAccent = name.tileAccent,
                system = p.name in system,
                enabled = p.name !in disabled,
                disabledByTv = p.name in disabled && p.name in byTv,
                // Same rule as Actions.run: no known launcher, nothing can be stopped or disabled.
                // A disabled app stays unprotected so Enable (always allowed) still shows, even
                // for a built-in launcher that is off.
                protectedReason = when {
                    p.name in disabled -> null
                    home != null -> Protected.reason(p.name, home, userApp = p.name in userInstalled)
                    else -> ProtectedReason.UNKNOWN_HOME
                }?.let(::reasonText),
                version = "",
                icon = name.icon,
                removable = p.name in userInstalled,
                home = if (held != null) p.name == held else p.name == homeOptions?.takeIf { it.default }?.current,
                listedLauncher = SharedLists.launchers.any { it.pkg == p.name },
                homeInstead = homeOptions?.takeIf { p.name in it.others }?.current,
                homeFirst = homeOptions?.takeIf { it.current == null && p.name in it.others } != null,
                answersHome = homeOptions?.let { p.name == it.current || p.name in it.others } == true,
                builtInLauncher = p.name in system && (p.name == homeOptions?.current || p.name in own),
                homeBack = back?.takeIf { p.name == homeOptions?.current }?.let { names[it.pkg]?.label ?: AppNames.fallback(it.pkg) },
                homeService = p.name in offered,
                homeServiceOff = serviceOff && p.name == homeOptions?.current,
                homeServiceNote = note && p.name == homeOptions?.current,
                listKeep = p.name in keep,
                launchable = p.name in launchable,
                tvHome = p.name in tvHome,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** The last address and Wi-Fi read per TV: re-read only while Advanced shows it, since old TVs answer with all of `dumpsys wifi`. */
    private val addresses = ConcurrentHashMap<String, Pair<Parsers.Address?, Parsers.Wifi?>>()

    override suspend fun network(deviceId: String): NetworkInfo {
        val shell = session(deviceId)
        val cached = addresses[deviceId]?.takeIf { statsFor != (deviceId to StatsProfile.ADVANCED) }
        val (address, wifi) = cached ?: NetworkStats(shell).address().also { addresses[deviceId] = it }
        val packages = links[deviceId]?.stats?.value?.packages.orEmpty()
        return NetworkInfo(
            iface = address?.iface.orEmpty(),
            ip = address?.ip ?: deviceId,
            mac = address?.mac, ssid = wifi?.ssid, rssi = wifi?.rssi, linkMbps = wifi?.linkMbps,
            sockets = NetworkStats(shell).connections().map { Socket(uidLabel(it.uid, packages), it.remote, tcpState(it.state)) },
            traffic = traffic(deviceId, packages),
        )
    }

    /** `dumpsys netstats` is long, so data used is read at most once a minute per TV. */
    private val trafficCache = ConcurrentHashMap<String, Pair<Long, List<AppTraffic>>>()

    private suspend fun traffic(deviceId: String, packages: Map<Int, List<String>>): List<AppTraffic> {
        val now = System.currentTimeMillis()
        trafficCache[deviceId]?.takeIf { now - it.first < 60_000 }?.let { return it.second }
        val list = NetworkStats(session(deviceId)).traffic().filterKeys { it >= 0 }.entries
            .groupBy({ uidLabel(it.key, packages) }, { it.value })
            .map { (app, ts) -> AppTraffic(app, (ts.sumOf { it.rxBytes } shr 20).toInt(), (ts.sumOf { it.txBytes } shr 20).toInt()) }
            .filter { it.rxMb + it.txMb > 0 }
        trafficCache[deviceId] = now to list
        return list
    }

    /** An app's name for a uid; system uids and unknown ones read as Android system. */
    private fun uidLabel(uid: Int, packages: Map<Int, List<String>>): String {
        val pkg = packages[uid]?.takeIf { uid % 100_000 >= 10_000 }?.firstOrNull() ?: return AppNames.SYSTEM.text()
        return labels[pkg] ?: AppNames.fallback(pkg)
    }

    override suspend fun <T> batch(deviceId: String, block: suspend () -> T): T = busy(deviceId, reread = true, block)

    override suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry =
        busy(deviceId, reread = true) { actNow(deviceId, action, packageName, undoing) }

    override suspend fun makeHome(deviceId: String, packageName: String, service: Boolean): HistoryEntry =
        busy(deviceId, reread = true) { actNow(deviceId, Action.Home, packageName, null, service) }

    override suspend fun homeServiceOn(deviceId: String, packageName: String): Unit = busy(deviceId, reread = true) {
        val actions = Actions(session(deviceId))
        val label = labels[packageName] ?: AppNames.fallback(packageName)
        val service = packageName.takeIf { actions.mayTurnOn(it) }?.let { actions.homeService(it) }
            ?: throw UiTextException(UiText(R.string.data_no_service, label))
        if (!actions.grant(service)) throw UiTextException(serviceNotOn(label))
        fireHomes -= deviceId
        // Undo of the switch to it turns it off again, as if the switch had turned it on.
        openSwitch(deviceId, packageName)?.let { book.amend(it, mapOf("service" to service)) }
        Unit
    }

    override suspend fun dismissServiceNote(deviceId: String, packageName: String) {
        openSwitch(deviceId, packageName)?.let { book.amend(it, mapOf("note" to "dismissed")) }
    }

    /** The newest switch to [pkg] that nothing has undone: the change a later step belongs to. */
    private suspend fun openSwitch(deviceId: String, pkg: String): Long? = book.entries(key(deviceId))
        .firstOrNull { it.action == Action.Home && it.undoneAt == null && it.previous?.get("to")?.substringBefore('/') == pkg }?.id

    private suspend fun actNow(deviceId: String, action: Action, packageName: String, undoing: Long?, service: Boolean = true): HistoryEntry {
        fireHomes -= deviceId // undoing a home switch moves Home back
        val shell = session(deviceId)
        val key = key(deviceId)
        // The list's keep entries only warn, in the confirm; Actions.run holds the hard locks.
        if (action == Action.Home) {
            // Undoing a switch back turns the service on again only if that switch turned it off.
            val again = undoing?.let { id -> book.entries(key).firstOrNull { it.id == id } }?.let { it.previous?.get("service") != null }
            return switchHome(deviceId, packageName, undoing, again ?: service)
        }
        if (action == Action.HomeBack) return homeBack(deviceId, packageName, undoing)
        if (action == Action.Reset) return undoReset(deviceId, undoing ?: throw UiTextException(UiText(R.string.data_no_reset_to_undo)))
        undoing?.let { id -> book.entries(key).firstOrNull { it.id == id } }?.let { e ->
            e.firstHomeService?.let { return firstHomeServiceOff(deviceId, packageName, it, e.previous?.get("fromService"), undoing) }
        }
        if (action == Action.Uninstall && Actions(shell).homeOptions()?.current == packageName) return uninstallHome(deviceId, packageName)
        val r = Actions(shell).run(AdbAction.valueOf(action.name.uppercase()), packageName)
        return when (r) {
            is ActionResult.Done -> {
                // Undoing a home switch: the old launcher is on again, now make it Home again too.
                val switch = undoing?.let { id -> book.entries(key).firstOrNull { it.id == id && it.action == Action.Home } }
                val left = switch?.let { undoHomeSwitch(Actions(shell), it, ::labelOf) }
                book.record(
                    deviceId = key,
                    at = System.currentTimeMillis(),
                    action = action,
                    packageName = packageName,
                    label = labels[packageName] ?: AppNames.fallback(packageName),
                    // Disabling the home screen is refused anyway; offer no undo that can't work.
                    undo = if (switch != null) null else r.undo?.let { Action.valueOf(it.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    // A switch whose Undo didn't all take stays open, for Undo to try again.
                    undoing = undoing.takeIf { left == null },
                ).also { left?.let { throw UiTextException(it) } }
            }
            is ActionResult.Refused -> throw UiTextException(reasonText(r.reason))
            is ActionResult.Failed -> throw ActionFailed(failedText(action, labelOf(packageName)), r.output.ifEmpty { "(no output)" })
        }
    }

    /** [withService] false leaves the new launcher's accessibility service as it is: the user said no. */
    private suspend fun switchHome(deviceId: String, packageName: String, undoing: Long?, withService: Boolean): HistoryEntry {
        fireHomes -= deviceId
        val key = key(deviceId)
        val actions = Actions(session(deviceId))
        val offered = setOfNotNull(packageName.takeIf { actions.mayTurnOn(it) })
        if (packageName in offered && fireOs(deviceId, actions.installed(FIRE_TV_LAUNCHER))) {
            return fireServiceHome(actions, packageName, withService, listedServiceLaunchers, ::labelOf) { previous ->
                // Undo is for the service it turned on; one it only turned off another's for is left to Restore.
                book.record(key, System.currentTimeMillis(), Action.Home, packageName, labelOf(packageName), Action.Enable.takeIf { "service" in previous }, undoing, previous)
            }
        }
        return switchHome(actions, packageName, withService, offered, listedServiceLaunchers, ::labelOf, ::reasonText, { id, extra, undo -> book.amend(id, extra, undo = undo) }) { pkg, undo, previous ->
            book.record(key, System.currentTimeMillis(), Action.Home, pkg, labelOf(packageName), undo, undoing, previous)
        }
    }

    private fun labelOf(pkg: String) = labels[pkg] ?: AppNames.fallback(pkg)

    /** A Fire TV by its model, or any TV with Amazon's launcher: [amazonLauncher] says whether it has one. */
    private fun fireOs(deviceId: String, amazonLauncher: Boolean) = amazonLauncher || deviceKind(device(deviceId)) == DeviceKind.FireTv

    /**
     * Undo of a first home: [launcher] stays Home, as there's none to go back to; its [service] goes off, and
     * [fromService], another launcher's that the home turned off on Fire OS, goes back on.
     */
    private suspend fun firstHomeServiceOff(deviceId: String, launcher: String, service: String, fromService: String?, undoing: Long): HistoryEntry {
        val label = labelOf(launcher)
        val actions = Actions(session(deviceId))
        // The other's back on first, so a TV that refuses leaves everything as it was.
        fromService?.let { if (!actions.grant(it)) throw UiTextException(fireServiceNotOn(labelOf(it.substringBefore('/')))) }
        if (!actions.setService(service, false)) throw UiTextException(serviceStillOn(label))
        return book.record(
            deviceId = key(deviceId),
            at = System.currentTimeMillis(),
            // A sentence of its own, like a setting's: nothing here for Restore or a later Undo.
            action = Action.Change,
            packageName = launcher,
            label = UiText(R.string.data_history_service_off, label).text(),
            undo = null,
            undoing = undoing,
        )
    }

    /**
     * Uninstall of the launcher on Home: Home goes back to the TV's own first, its accessibility
     * service off with it, as going back does. Any step that fails stops it before the uninstall.
     */
    private suspend fun uninstallHome(deviceId: String, pkg: String): HistoryEntry =
        uninstallAfterHomeBack({ homeBack(deviceId, pkg, null) }, { actNow(deviceId, Action.Uninstall, pkg, null) }, book::dropUndo)

    /**
     * Leaves the launcher the user added, now Home, for one of the TV's own. [pkg] is either that
     * added launcher, going back to the one it replaced (the one the last switch saved, else the
     * TV's own), or the TV's own launcher to go to, from the Home screen card. The added launcher
     * stays on; the entry's undo makes it Home again.
     */
    private suspend fun homeBack(deviceId: String, pkg: String, undoing: Long?): HistoryEntry {
        fireHomes -= deviceId
        val key = key(deviceId)
        val shell = session(deviceId)
        val system = Parsers.packages(shell.shell("pm list packages -s").output).map { it.name }.toSet()
        val disabled = Parsers.packages(shell.shell("pm list packages -d").output).map { it.name }.toSet()
        val nothing = UiText(R.string.data_no_home_to_switch_back)
        if (fireOs(deviceId, FIRE_TV_LAUNCHER in system)) Actions(shell).fireServiceHolder(listedServiceLaunchers)?.let { held ->
            val (added, _) = held
            // Reverses the Make Home that turned the service on, so Restore leaves it alone.
            val home = undoing ?: book.entries(key).filter { it.action == Action.Home && it.undoneAt == null && it.firstHomeService != null && it.packageName == added }.maxByOrNull { it.id }?.id
            return fireHomeBack(Actions(shell), held, ::labelOf) { to, previous ->
                book.record(key, System.currentTimeMillis(), Action.HomeBack, added, to?.let(::labelOf) ?: labelOf(FIRE_TV_LAUNCHER), Action.Home, home, previous)
            }
        }
        val options = Actions(shell).homeOptions() ?: throw UiTextException(nothing)
        val added = options.current?.takeIf { it !in system } ?: throw UiTextException(nothing)
        val own = builtIns(system, disabled, options)
        val target = (if (pkg == added) homeBackTarget(added, book.entries(key), own) else pkg.takeIf { it in own }?.let { builtInTarget(it, book.entries(key)) })
            ?: throw UiTextException(nothing)
        return when (val r = Actions(shell).homeBack(target.pkg, target.activity)) {
            is HomeResult.Switched -> {
                // Off with Home: it would keep sending Home to [added]. Undo, a switch, turns it on again.
                val service = added.takeIf { it in listedServiceLaunchers }?.let { Actions(shell).homeService(it) }?.takeIf { Actions(shell).serviceOn(it) }
                val off = service?.let { Actions(shell).setService(it, false) }
                // Last: with its service still on, [added] could take Home straight back.
                val left = Actions(shell).handOff(added, r.to)
                book.record(
                    deviceId = key,
                    at = System.currentTimeMillis(),
                    action = Action.HomeBack,
                    packageName = added,
                    label = labels[target.pkg] ?: AppNames.fallback(target.pkg),
                    undo = Action.Home,
                    // The switch away from it is reversed now, so Restore leaves it alone.
                    undoing = undoing ?: target.reverses,
                    previous = listOfNotNull(r.from?.let { "from" to it }, "to" to r.to, service?.takeIf { off == true }?.let { "service" to it }).toMap(),
                ).also {
                    if (off == false) throw UiTextException(serviceNotOff(labels[added] ?: AppNames.fallback(added)))
                    left?.let { throw ActionFailed(notShown(labels[target.pkg] ?: AppNames.fallback(target.pkg), it.reason), it.output) }
                }
            }
            is HomeResult.Refused -> throw UiTextException(reasonText(r.reason))
            is HomeResult.Failed -> throw ActionFailed(tvSaid(r.output), r.details)
        }
    }

    /**
     * The TV's own launchers, the ones to go back to: those that are on, then any that are off
     * and named as one (an off app answers no Home query).
     */
    private fun builtIns(system: Set<String>, disabled: Set<String>, options: HomeOptions): List<String> =
        (options.others.filter { it in system }.sorted() +
            disabled.filter { it in system && Protected.reason(it) == ProtectedReason.HOME_SCREEN }.sorted()).distinct()

    override suspend fun history(deviceId: String): List<HistoryEntry> = book.entries(key(deviceId))

    override suspend fun restorable(deviceId: String): Restorable = book.restorable(key(deviceId))

    override suspend fun openStore(deviceId: String, packageName: String): Boolean {
        // Only a launcher the list names: the Store link is the one thing this opens.
        if (SharedLists.launchers.none { it.pkg == packageName }) return false
        return Actions(session(deviceId)).openStore(packageName)
    }

    override suspend fun storeState(deviceId: String, packageName: String): StoreState {
        val actions = Actions(session(deviceId))
        return when {
            actions.installed(packageName) -> StoreState.Installed
            actions.storeOnScreen() -> StoreState.InStore
            else -> StoreState.Elsewhere
        }
    }

    override suspend fun launcherCardHidden(deviceId: String): Boolean = key(deviceId) in launcherCards.folded()

    override suspend fun hideLauncherCard(deviceId: String) = launcherCards.set(key(deviceId), true)

    /**
     * Puts back, as it was before Tellyscope's first change to it, everything History says it
     * changed and the TV doesn't already show that way: apps on first, as turning on a launcher a
     * switch turned off makes it Home again; then Home and its services; then apps off; then apps
     * Reset TV brought back; then settings. A step the TV refuses doesn't stop the rest.
     */
    override suspend fun restoreAll(deviceId: String): RestoreResult = batch(deviceId) {
        val key = key(deviceId)
        val target = book.restorable(key)
        val shell = session(deviceId)
        val actions = Actions(shell)
        // `pm list packages` lists off apps too; an app it doesn't list isn't installed for the user.
        val listed = Parsers.packages(shell.shell("pm list packages").output).map { it.name }.toSet()
        val off = disabledPackages(deviceId)
        val plan = target.plan { if (it !in listed) Was.Gone else if (it in off) Was.Off else Was.On }
        val run = RestoreRun()
        fun app(pkg: String) = labelOf(pkg).asUiText()
        for (pkg in plan.turnOn) run.step(app(pkg)) { act(deviceId, Action.Enable, pkg, undoing = book.openDisable(key, pkg)) }
        // Home and its services, one step: turning a launcher on may have put them back already.
        val home = target.home?.takeIf { it.substringBefore('/') in listed }
        val homeNow = home?.let { actions.currentHome() }
        val moveTo = home?.takeIf { homeNow?.substringBefore('/') != it.substringBefore('/') }
        val services = target.services.filter { (service, wasOn) -> service.substringBefore('/') in listed && actions.serviceOn(service) != wasOn }
        if (moveTo != null || services.isNotEmpty()) run.step(UiText(R.string.data_restore_home)) {
            moveTo?.let { to ->
                if (!actions.setHome(to)) throw UiTextException(UiText(R.string.data_tv_refused))
                // Its Undo makes the launcher it left Home again, as a switch back's does.
                val left = homeNow?.substringBefore('/')
                book.record(key, System.currentTimeMillis(), Action.HomeBack, left ?: to.substringBefore('/'), labelOf(to.substringBefore('/')),
                    undo = left?.let { Action.Home }, previous = listOfNotNull(homeNow?.let { "from" to it }, "to" to to).toMap())
            }
            for ((service, wasOn) in services) {
                val pkg = service.substringBefore('/')
                if (!(if (wasOn) actions.grant(service) else actions.setService(service, false))) throw UiTextException(UiText(R.string.data_tv_refused))
                val label = UiText(if (wasOn) R.string.data_history_service_on else R.string.data_history_service_off, labelOf(pkg)).text()
                book.record(key, System.currentTimeMillis(), Action.Change, pkg, label, undo = null)
            }
        }
        for (pkg in plan.turnOff) run.step(app(pkg)) { act(deviceId, Action.Disable, pkg, undoing = book.openEnable(key, pkg)) }
        for (pkg in plan.remove) run.step(app(pkg)) {
            TvReset(shell).remove(pkg)
            book.record(key, System.currentTimeMillis(), Action.Uninstall, pkg, labelOf(pkg), undo = null)
        }
        val tweaks = Tweaks(shell)
        for ((tweak, before) in target.settings) {
            if (tweaks.read(tweak).filterKeys { it in before } == before) continue
            run.step(TvSettings.name(tweak)) { changeNow(deviceId, tweak, before, null) }
        }
        run.result()
    }

    override suspend fun resetPlan(deviceId: String): ResetPlan = busy(deviceId) {
        val shell = session(deviceId)
        val scan = TvReset(shell).scan()
        val options = Actions(shell).homeOptions()
        val system = Parsers.packages(shell.shell("pm list packages -s").output).map { it.name }.toSet()
        val disabled = Parsers.packages(shell.shell("pm list packages -d").output).map { it.name }.toSet()
        val own = options?.let { builtIns(system, disabled, it) }.orEmpty()
        // Home is a launcher the user added: the TV's own takes it back, the one a switch replaced first.
        val added = options?.takeIf { it.default }?.current?.takeIf { it !in system }
        val to = added?.let { homeBackTarget(it, book.entries(key(deviceId)), own) }?.pkg?.takeIf { it in system } ?: own.firstOrNull()
        // Fire OS keeps Amazon's launcher the default; a launcher whose service is on holds Home instead.
        val held = if (added == null && fireOs(deviceId, FIRE_TV_LAUNCHER in system)) Actions(shell).fireServiceHolder(listedServiceLaunchers)?.first else null
        val tweaks = Tweaks(shell)
        suspend fun offDefault(tweak: Tweak) = tweaks.read(tweak).takeIf { TvSettings.offDefault(tweak, it) }
        ResetPlan(
            turnOn = scan.turnOn.map { ResetApp(it, labelOf(it)) },
            bringBack = scan.bringBack.map { ResetApp(it, labelOf(it)) },
            home = when {
                added != null && to != null -> ResetHome(added, labelOf(added), to, labelOf(to), service = added in listedServiceLaunchers)
                held != null -> ResetHome(held, labelOf(held), FIRE_TV_LAUNCHER, labelOf(FIRE_TV_LAUNCHER), service = true, serviceOnly = true)
                else -> null
            },
            shippedOff = scan.shippedOff.size,
            ownHome = (options?.current?.takeIf { it in system } ?: to ?: own.firstOrNull())?.let(::labelOf),
            dns = offDefault(Tweak.PRIVATE_DNS),
            animations = offDefault(Tweak.ANIMATIONS),
        )
    }

    override suspend fun reset(deviceId: String, plan: ResetPlan, into: Long?, onProgress: (ResetProgress) -> Unit): Pair<Long?, List<ResetFailure>> = busy(deviceId) {
        fireHomes -= deviceId
        val key = key(deviceId)
        val shell = session(deviceId)
        val tv = TvReset(shell)
        val failed = mutableListOf<ResetFailure>()
        // Each step goes into the entry as soon as it's done, so a reset cancelled or cut off part-way
        // still leaves Undo and Restore what it changed. On Try again that's the entry it continues.
        var entry = into?.let { id -> book.entries(key).firstOrNull { it.id == id && it.undoneAt == null } }
        fun note(extra: Map<String, String?>) {
            val label = ResetRecord.label(ResetRecord.changes(entry?.previous.orEmpty() + extra)).text()
            entry = entry?.let { book.amend(it.id, extra, label) }
                ?: book.record(key, System.currentTimeMillis(), Action.Reset, RESET, label, undo = Action.Reset, previous = extra)
        }
        suspend fun each(part: ResetPart, field: String, apps: List<ResetApp>, change: suspend (String) -> Unit) = apps.forEachIndexed { i, app ->
            onProgress(ResetProgress(part, i, apps.size))
            try {
                change(app.packageName)
                note(mapOf(field to (ResetRecord.list(entry?.previous, field) + app.packageName).distinct().joinToString(",")))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A step that fails doesn't stop the rest: each one stands alone.
                failed += ResetFailure(part, app, e.why(R.string.data_tv_refused))
            }
        }
        each(ResetPart.TurnOn, ResetRecord.ON, plan.turnOn, tv::turnOn)
        each(ResetPart.BringBack, ResetRecord.BACK, plan.bringBack, tv::bringBack)
        plan.home?.let { h ->
            onProgress(ResetProgress(ResetPart.Home, 0, 1))
            try {
                // Home moved but the service stayed on, or the hand-off to it didn't finish: Undo still needs the move.
                resetHome(Actions(shell), h) { if (it.isNotEmpty()) note(it) }?.let { stuck ->
                    failed += ResetFailure(ResetPart.Home, ResetApp(h.from, h.fromLabel), stuck)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += ResetFailure(ResetPart.Home, null, e.why(R.string.data_tv_refused))
            }
        }
        val tweaks = Tweaks(shell)
        suspend fun setting(part: ResetPart, tweak: Tweak, planned: Map<String, String?>?) = planned?.let {
            onProgress(ResetProgress(part, 0, 1))
            try {
                resetSetting(tweaks, tweak)?.let(::note)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += ResetFailure(part, null, e.why(R.string.data_tv_refused))
            }
        }
        setting(ResetPart.Dns, Tweak.PRIVATE_DNS, plan.dns)
        setting(ResetPart.Animations, Tweak.ANIMATIONS, plan.animations)
        entry?.id to failed
    }

    /**
     * Home back to [home]'s TV's own launcher, the added one's accessibility service off with it:
     * it would keep sending Home there; then the TV is handed to it. Each change goes to [note], what
     * Undo needs, as soon as it's made. Returns why the service stayed on or the hand-off didn't
     * finish, if so; throws when Home didn't move. On Try again Home may have moved already: then only
     * the service and the hand-off are left to do, and the entry already holds the move.
     */
    private suspend fun resetHome(actions: Actions, home: ResetHome, note: (Map<String, String?>) -> Unit): UiText? {
        if (home.serviceOnly) {
            // On Try again the service may be off already: nothing left to record.
            val service = actions.homeService(home.from)?.takeIf { actions.serviceOn(it) } ?: return null
            if (!actions.setService(service, false)) throw UiTextException(fireServiceNotOff(home.fromLabel))
            note(mapOf(ResetRecord.SERVICE to service))
            return actions.currentHome()?.let { actions.handOff(home.from, it) }?.let { notShown(home.toLabel, it.reason) }
        }
        if (actions.currentHome()?.substringBefore('/') != home.to) when (val r = actions.homeBack(home.to)) {
            is HomeResult.Switched -> note(listOfNotNull(ResetRecord.FROM to r.from, ResetRecord.TO to r.to).toMap())
            is HomeResult.Refused -> throw UiTextException(reasonText(r.reason))
            is HomeResult.Failed -> throw UiTextException(tvSaid(r.output))
        }
        val service = home.from.takeIf { it in listedServiceLaunchers }?.let { actions.homeService(it) }?.takeIf { actions.serviceOn(it) }
        val off = service?.let { actions.setService(it, false) }
        // Recorded only once it's off: Undo turns back on what Reset turned off, nothing more.
        if (off == true) note(mapOf(ResetRecord.SERVICE to service))
        // Last, on Try again too: with its service still on, the added launcher could take Home straight back.
        val left = actions.currentHome()?.let { actions.handOff(home.from, it) }?.let { notShown(home.toLabel, it.reason) }
        return if (off == false) serviceNotOff(home.fromLabel) else left
    }

    /**
     * Undo of a reset: Home back to the launcher it replaced, its service on again, then the apps
     * it turned on go off and the ones it brought back are removed again. Goes on past a step that
     * fails; then the entry keeps only what is left, so Undo can try those again.
     */
    private suspend fun undoReset(deviceId: String, id: Long): HistoryEntry {
        val key = key(deviceId)
        val entry = book.entries(key).firstOrNull { it.id == id && it.undoneAt == null }
            ?: throw UiTextException(UiText(R.string.data_reset_already_undone))
        val p = entry.previous.orEmpty()
        val shell = session(deviceId)
        val tv = TvReset(shell)
        val actions = Actions(shell)
        val errors = mutableListOf<UiText>()
        var homeLeft = false
        p[ResetRecord.TO]?.let { to ->
            val from = p[ResetRecord.FROM]
            val name = labelOf(from?.substringBefore('/') ?: to.substringBefore('/'))
            // Home first: the TV's own launcher can't go off while it's still Home.
            if (from == null || !actions.setHome(from)) {
                homeLeft = true
                errors += UiText(R.string.data_reset_tv_kept_home, labelOf(to.substringBefore('/')))
            } else p[ResetRecord.SERVICE]?.let { if (!actions.grant(it)) errors += serviceNotOn(name) }
        }
        if (undoFireResetHome(actions, p) == false) errors += serviceNotOn(labelOf(p[ResetRecord.SERVICE].orEmpty().substringBefore('/')))
        val ownHome = p[ResetRecord.TO]?.substringBefore('/')?.takeIf { homeLeft }
        suspend fun each(pkgs: List<String>, change: suspend (String) -> Unit) = pkgs.filter { pkg ->
            if (pkg == ownHome) return@filter true
            try {
                change(pkg)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += UiText(R.string.data_app_reason, labelOf(pkg), e.why(R.string.data_tv_refused))
                true
            }
        }
        val on = each(ResetRecord.list(p, ResetRecord.ON), tv::turnOff)
        val back = each(ResetRecord.list(p, ResetRecord.BACK), tv::remove)
        val tweaks = Tweaks(shell)
        // Each setting back as it was; one the TV refuses stays marked, for Undo to try again.
        val settingsLeft = Tweak.entries.filterNot { tweak ->
            undoResetSetting(tweaks, tweak, p).also { put ->
                if (!put) errors += UiText(R.string.data_app_reason, TvSettings.name(tweak), UiText(R.string.data_tweak_refused))
            }
        }
        // Only the service left on: every change is back, so the reset is undone, with a warning.
        if (errors.isEmpty() || (on.isEmpty() && back.isEmpty() && !homeLeft && settingsLeft.isEmpty())) {
            val undone = book.record(key, System.currentTimeMillis(), Action.Change, RESET, UiText(R.string.data_history_reset_undone).text(), undo = null, undoing = id)
            if (errors.isEmpty()) return undone
            throw UiTextException(UiText(R.string.data_reset_undo_service_left, errors.first()))
        }
        val left = mapOf(ResetRecord.ON to on.joinToString(","), ResetRecord.BACK to back.joinToString(",")) +
            (if (homeLeft) emptyMap() else mapOf(ResetRecord.FROM to null, ResetRecord.TO to null, ResetRecord.SERVICE to null)) +
            (Tweak.entries - settingsLeft.toSet()).associate { ResetRecord.mark(it) to null }
        val before = ResetRecord.changes(p)
        val now = ResetRecord.changes(p + left)
        book.amend(id, left, ResetRecord.label(now).text())
        throw UiTextException(UiText.plural(R.plurals.data_reset_undo_partial, before, before - now, before, UiText.Capitalized(errors.first())))
    }

    override suspend fun suggestions(deviceId: String, apps: suspend () -> List<AppInfo>): Suggestions = try {
        val r = resolve(deviceId)
        when {
            r.index == null -> Suggestions.Unavailable
            r.match == ListMatch.None -> Suggestions.NoList(r.index.commit, r.index.devices)
            r.list == null -> Suggestions.Unavailable
            else -> Suggestions.Ready(
                r.index.commit, r.list, r.sdk, suggest(r.list, apps(), r.sdk), r.index.devices,
                byMaker = r.match is ListMatch.Maker,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Suggestions.Unavailable // the TV dropped mid-read
    }

    override suspend fun pickList(deviceId: String, listId: String) {
        val key = key(deviceId)
        lists.pick(key, listId)
        keep.remove(key)
    }

    override suspend fun listSource(): String? = lists.custom()

    override suspend fun setListSource(url: String?) {
        when (lists.setSource(url)) {
            SourceResult.Ok -> {
                keep.clear() // the old source's keep entries
                scope.launch(Dispatchers.IO) { SharedLists.load(data, lists, fresh = true) }
            }
            SourceResult.NotHttps -> throw UiTextException(UiText(R.string.data_lists_not_https))
            SourceResult.Unreachable -> throw UiTextException(UiText(R.string.data_lists_unreachable))
            SourceResult.NotAList -> throw UiTextException(NOT_A_LIST)
        }
    }

    private class Resolved(val index: ListIndex?, val match: ListMatch?, val list: DeviceList?, val sdk: Int?)

    /** This TV's list (picked, else matched, else its maker's), with its keep entries recorded for [apps] and [act]. */
    private suspend fun resolve(deviceId: String): Resolved {
        val index = lists.index() ?: return Resolved(null, null, null, null)
        val out = session(deviceId).shell(
            (MATCH_KEYS.map { "ro.product.$it" } + "ro.build.version.sdk").joinToString("; ") { "getprop $it" },
        ).output.lines().map { it.trim().ifEmpty { null } }
        val props = MATCH_KEYS.withIndex().associate { (i, k) -> k to out.getOrNull(i) }
        val key = key(deviceId)
        val match = lists.find(index, key, props)
        val list = when (match) {
            is ListMatch.Model -> lists.list(match.entry)
            // Fetched together, as a maker can have dozens. A list that fails its checksum is left out; the rest still count.
            is ListMatch.Maker -> coroutineScope { match.entries.map { async { lists.list(it) } }.awaitAll() }
                .filterNotNull().ifEmpty { null }?.let { merge(match.maker, it) }
            ListMatch.None -> null
        }
        keep[key] = list?.keep.orEmpty()
        return Resolved(index, match, list, out.getOrNull(MATCH_KEYS.size)?.toIntOrNull())
    }

    override suspend fun press(deviceId: String, key: RemoteKey) = busy(deviceId) {
        if (key == RemoteKey.HOME) remote(deviceId).home(fireHome(deviceId))
        else if (key == RemoteKey.VOLUME_UP || key == RemoteKey.VOLUME_DOWN) remote(deviceId).volume(key, volumeKeys(deviceId))
        else remote(deviceId).press(key)
    }

    /**
     * Keys per volume tap, so a tap moves about 1/[Remote.VOLUME_STEPS] of the range on any TV. Read
     * once per session; a reconnect reads it again.
     */
    private suspend fun volumeKeys(deviceId: String): Int {
        val session = session(deviceId)
        volumeSteps[deviceId]?.takeIf { it.first === session }?.let { return it.second }
        return Remote.volumeKeys(Remote(session).volumeMax()).also { volumeSteps[deviceId] = session to it }
    }

    /**
     * The launcher the Remote's Home opens on Fire OS ([fireRemoteHome]); null on any other TV, which
     * keeps the Home key. Read once per session so the key stays fast. A home switch, its undo or a
     * launcher's service going on drops it; a reconnect brings a new session, which reads it again.
     */
    private suspend fun fireHome(deviceId: String): String? {
        val session = session(deviceId)
        fireHomes[deviceId]?.takeIf { it.first === session }?.let { return it.second }
        val actions = Actions(session)
        val home = if (fireOs(deviceId, actions.installed(FIRE_TV_LAUNCHER))) actions.fireRemoteHome(listedServiceLaunchers) else null
        return home.also { fireHomes[deviceId] = session to it }
    }

    /** One helper per link: a reconnect brings a new session, and the old helper went with the old one. */
    private suspend fun remote(deviceId: String): Remote {
        val session = session(deviceId)
        val keys = keyHelpers.compute(deviceId) { _, k ->
            if (k?.session === session) k else KeyHelper(session, helperDex).also { k?.close() }
        }
        return Remote(session, keys)
    }

    override fun closeRemote() {
        keyHelpers.keys.toList().forEach { keyHelpers.remove(it)?.close() }
    }

    override suspend fun volumeFixed(deviceId: String) = Remote(session(deviceId)).volumeFixed()

    override suspend fun type(deviceId: String, text: String) = busy(deviceId) { remote(deviceId).type(text) }


    override suspend fun screen(deviceId: String, on: Boolean) = busy(deviceId) { remote(deviceId).screen(on) }

    override suspend fun reboot(deviceId: String) = Remote(session(deviceId)).reboot()

    // An offline TV is a lost link to the terminal, which prints that rather than an error.
    override fun shell(deviceId: String): TvShell {
        fun live() = links[deviceId]?.session ?: throw IOException("offline")
        return TvShell({ live().shell(it) }) { live().openShell() }
    }

    override suspend fun openUpdates(deviceId: String) =
        Remote(session(deviceId)).openUpdates(fireOs(deviceId, Actions(session(deviceId)).installed(FIRE_TV_LAUNCHER)))

    override suspend fun launch(deviceId: String, packageName: String) =
        busy(deviceId, reread = true) { Actions(session(deviceId)).run(AdbAction.OPEN, packageName) is ActionResult.Done }

    override suspend fun dock(deviceId: String): List<Docked> {
        restored.await()
        return docks[key(deviceId)]
    }

    override suspend fun setDock(deviceId: String, dock: List<Docked>) {
        restored.await()
        docks.set(key(deviceId), dock)
    }

    override suspend fun tweaks(deviceId: String): TvTweaks {
        val t = Tweaks(session(deviceId))
        val fire = fireOs(deviceId, Actions(session(deviceId)).installed(FIRE_TV_LAUNCHER))
        return TvSettings.tweaks(t.read(Tweak.PRIVATE_DNS), t.read(Tweak.ANIMATIONS), sdk(deviceId), fire)
    }

    private suspend fun sdk(deviceId: String) = session(deviceId).shell("getprop ro.build.version.sdk").output.trim().toIntOrNull()

    override suspend fun change(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long?): HistoryEntry =
        busy(deviceId) { changeNow(deviceId, tweak, values, undoing) }

    private suspend fun changeNow(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long?): HistoryEntry {
        val t = Tweaks(session(deviceId))
        val strictDns = tweak == Tweak.PRIVATE_DNS && values["private_dns_mode"] == "hostname"
        if (strictDns && (sdk(deviceId) ?: TvSettings.DNS_SDK) < TvSettings.DNS_SDK) throw UiTextException(TvSettings.DNS_TOO_OLD)
        return applyTweak(t, tweak, values, strictDns) { label, previous ->
            book.record(
                deviceId = key(deviceId),
                at = System.currentTimeMillis(),
                action = Action.Change,
                packageName = tweak.name,
                label = label.text(),
                undo = Action.Change,
                undoing = undoing,
                previous = previous,
            )
        }
    }

    override suspend fun clearCaches(deviceId: String): Long? {
        val freed = busy(deviceId, reread = true) { Tweaks(session(deviceId)).clearCaches() }
        book.record(key(deviceId), System.currentTimeMillis(), Action.ClearCaches, "", TvSettings.CACHES.text(), undo = null)
        return freed
    }

    override suspend fun screenshot(deviceId: String, sink: Sink) = Tweaks(liveSession(deviceId)).screenshot(sink)

    override suspend fun connectionRequest(deviceId: String) = ConnectionPrompt(session(deviceId)).pending()

    /**
     * Allow pressed from the app makes that key known, so it isn't flagged new. Only on a TV with a
     * known list: before the first read every key counts as known anyway.
     */
    override suspend fun answerRequest(deviceId: String, request: ConnectionRequest, allow: Boolean, always: Boolean): Boolean {
        val answered = ConnectionPrompt(session(deviceId)).answer(request, allow, always)
        val tv = key(deviceId)
        if (answered && allow && request.fingerprint.isNotEmpty() && knownKeys.has(tv)) knownKeys.add(tv, request.fingerprint)
        return answered
    }

    override suspend fun closeAlert(deviceId: String) = ConnectionPrompt(session(deviceId)).closeAlert()

    override suspend fun facts(deviceId: String) = TvFacts.read(session(deviceId))

    override suspend fun access(deviceId: String): TvAccess {
        val a = Access(session(deviceId))
        val port = links[deviceId]?.port ?: Access.CLASSIC_PORT
        val pending = runCatching { connectionRequest(deviceId)?.fingerprint }.getOrNull()
        val tv = key(deviceId)
        val access = TvAccess.of(
            a.clients(port), a.keys(), phoneAddresses(), deviceId, mine[tv], phoneFingerprint(),
            pending = pending, known = knownKeys[tv].takeIf { knownKeys.has(tv) },
        )
        access.allowed?.let { keys ->
            val known = knownKeys[tv] + keys.filterNot { it.new }.map { it.fingerprint }
            if (!knownKeys.has(tv) || known != knownKeys[tv]) knownKeys.set(tv, known)
        }
        // Other computers only: this phone and apps on the TV itself need no name.
        val lookup = access.connected.orEmpty().filter { it.who == AccessClient.Who.Other || it.who == AccessClient.Who.Mine }
        return access.named(networkNames.of(lookup.map { it.address }))
    }

    override suspend fun markMine(deviceId: String, address: String) {
        restored.await()
        mine.add(key(deviceId), address)
    }

    override suspend fun markKeyMine(deviceId: String, fingerprint: String) {
        restored.await()
        knownKeys.add(key(deviceId), fingerprint)
    }

    override suspend fun peripherals(deviceId: String) = Peripherals(session(deviceId)).read()

    /** Every key goes, so none counts as known after: a computer allowed again later shows as new. */
    override suspend fun revokeAll(deviceId: String) = Access(session(deviceId)).revokeAll().also { ok ->
        if (ok) knownKeys.set(key(deviceId), setOfNotNull(phoneFingerprint()))
    }

    override suspend fun openDeveloperOptions(deviceId: String) = Access(session(deviceId)).openDeveloperOptions()

    @Volatile private var fingerprint: String? = null

    override fun phoneFingerprint(): String? = fingerprint ?: Connector.fingerprint().also { fingerprint = it }

    /** Every address this phone has now, so its own connection to the TV is recognised. */
    private fun phoneAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }.toSet()
    }.getOrDefault(emptySet())

    /**
     * The history key for a TV, read from it once connected and saved with it. Until it has
     * answered (offline since install) the address stands in, and that history moves over when
     * the key is learned.
     */
    private suspend fun key(deviceId: String): String = keys[deviceId] ?: readKey(deviceId)

    private suspend fun readKey(deviceId: String): String {
        val shell = links[deviceId]?.session ?: return keys[deviceId] ?: deviceId
        val key = try {
            keyOf(shell, deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return keys[deviceId] ?: deviceId
        }
        if (keys.put(deviceId, key) != key) {
            book.rekey(deviceId, key)
            names.rekey(deviceId, key)
            docks.rekey(deviceId, key)
            mine.rekey(deviceId, key)
            knownKeys.rekey(deviceId, key)
            remember(deviceId)
        }
        return key
    }

    /** The [historyKey] of the TV on [shell], read from it; [deviceId] stands in when it has none. */
    private suspend fun keyOf(shell: AdbSession, deviceId: String): String {
        suspend fun prop(cmd: String) = shell.shell(cmd).takeIf { it.exitCode == 0 }?.output
        return historyKey(prop("getprop ro.serialno"), prop("settings get secure android_id"), deviceId)
    }

    private suspend fun disabledPackages(deviceId: String) =
        Parsers.packages(session(deviceId).shell("pm list packages -d").output).map { it.name }.toSet()

    override suspend fun installed(deviceId: String, packageName: String) = Sideload(liveSession(deviceId)).installed(packageName)

    override suspend fun install(
        deviceId: String,
        apks: List<File>,
        apk: ApkInfo,
        allowDowngrade: Boolean,
        onProgress: (Progress) -> Unit,
    ): InstallResult {
        val session = liveSession(deviceId)
        return busy(deviceId, reread = true) { Sideload(session).install(apks, apk, allowDowngrade, onProgress) }
    }

    override suspend fun files(deviceId: String, folder: TvFolder) = Files(session(deviceId)).list(folder)

    override suspend fun push(deviceId: String, folder: TvFolder, name: String, size: Long?, source: Source, onProgress: (Progress) -> Unit): String {
        val session = liveSession(deviceId)
        return busy(deviceId, reread = true) { Files(session).push(source, size, name, folder, onProgress) }
    }

    override suspend fun pull(deviceId: String, folder: TvFolder, file: TvFile, sink: Sink, onProgress: (Progress) -> Unit) {
        val session = liveSession(deviceId)
        busy(deviceId) { Files(session).pull(folder, file, sink, onProgress) }
    }

    private fun session(deviceId: String) =
        links[deviceId]?.session ?: throw UiTextException(UiText(R.string.data_offline, deviceId))

    /**
     * The session for a file the user just picked, or just picked a place to save to. While the system
     * picker is up the app is in the background, where Android cuts its network after a few seconds and
     * no stats read notices: back in the app, the old session is dead (#290, #296). Naming the file
     * keeps the picker up long enough. A quick shell call tells; a dead link starts
     * over and the transfer waits for the new session. The install's version check is the first call
     * after the picker, so it goes through here too.
     */
    private suspend fun liveSession(deviceId: String): AdbSession {
        val link = links[deviceId] ?: return session(deviceId)
        val old = link.session
        if (old != null && alive(old)) return old
        // Another caller may have started over already; its new session is the one to wait for.
        if (old != null && link.session === old) link.connect()
        // Polled: a reconnect from ONLINE stays ONLINE, so the state never says when the new session is in.
        return withTimeoutOrNull(RECONNECT_WAIT) {
            var next = link.session
            while (next == null || next === old) {
                delay(250)
                next = link.session
            }
            next
        } ?: session(deviceId)
    }

    private suspend fun alive(s: AdbSession) = try {
        withTimeout(PROBE_WAIT) { s.shell("echo") }
        true
    } catch (_: TimeoutCancellationException) {
        false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun device(id: String): Device {
        val link = links[id]
        val stats = link?.stats?.value
        val state = link?.state?.value
        return Device(
            id = id,
            hit = hits.getValue(id),
            // Still waking up after launch reads as offline; a refusal shows as itself.
            state = if (id in restoring && state in setOf(AdbState.FOUND, AdbState.WAITING, AdbState.OFFLINE)) {
                DeviceState.Offline
            } else when (state) {
                AdbState.WAITING -> DeviceState.Waiting
                AdbState.ONLINE -> DeviceState.Online
                AdbState.OFFLINE -> DeviceState.Offline
                AdbState.NOT_ALLOWED -> DeviceState.NotAllowed
                AdbState.NEEDS_PAIRING -> DeviceState.NeedsPairing
                AdbState.FOUND, null -> DeviceState.Found
            },
            model = stats?.model ?: saved[id]?.model,
            manufacturer = stats?.manufacturer ?: saved[id]?.lastKnown?.manufacturer,
            android = stats?.androidVersion ?: saved[id]?.lastKnown?.android,
            lastSeen = link?.lastSeenMillis ?: saved[id]?.lastSeen,
            nickname = names[keys[id] ?: id],
            brand = stats?.brand ?: saved[id]?.lastKnown?.brand,
            lastKnown = saved[id]?.lastKnown,
            // Read after state: the link writes it first, so a fresh refusal never shows an old logout.
            loggedOutAt = link?.loggedOutAt?.value?.takeIf { state == AdbState.NOT_ALLOWED },
            fireOs = stats?.fireOsVersion ?: saved[id]?.lastKnown?.fireOs,
        )
    }

    private fun tcpState(st: Int) = when (st) {
        Parsers.TcpSocket.ESTABLISHED -> "established"
        Parsers.TcpSocket.SYN_SENT, Parsers.TcpSocket.SYN_RECV -> "connecting"
        else -> "closing"
    }

    /** Copy from the protected-apps table in the UX copy doc. */
    private fun reasonText(r: ProtectedReason) = UiText(
        when (r) {
            ProtectedReason.HOME_SCREEN -> R.string.data_protected_home_screen
            ProtectedReason.SYSTEM -> R.string.data_protected_system
            ProtectedReason.SETTINGS -> R.string.data_protected_settings
            ProtectedReason.PLAY_SERVICES -> R.string.data_protected_play_services
            ProtectedReason.KEYBOARD -> R.string.data_protected_keyboard
            ProtectedReason.TV_INPUTS_REMOTE -> R.string.data_protected_tv_inputs_remote
            ProtectedReason.SETUP_WIZARD -> R.string.data_protected_setup_wizard
            ProtectedReason.UPDATES -> R.string.data_protected_updates
            ProtectedReason.UNKNOWN_HOME -> R.string.data_protected_unknown_home
            ProtectedReason.CAME_WITH_TV -> R.string.data_protected_came_with_tv
            ProtectedReason.DISABLED_BY_TV -> R.string.data_protected_disabled_by_tv
        },
    )

    /** One line for a change the TV refused; what it printed goes in [ActionFailed.details]. */
    private fun failedText(action: Action, label: String) = when (action) {
        Action.Stop -> UiText(R.string.data_failed_stop, label)
        Action.Disable -> UiText(R.string.data_failed_disable, label)
        Action.Enable -> UiText(R.string.data_failed_enable, label)
        Action.Open -> UiText(R.string.data_failed_open, label)
        Action.Uninstall -> UiText(R.string.data_failed_uninstall, label)
        else -> UiText(R.string.data_tv_refused)
    }
}

/**
 * Makes [pkg] Home and hands [record] the entry's package, its Undo and what Undo reverses. Only
 * a launcher the TV came with is ever turned off ([Actions.makeHome]). With [withService], a
 * launcher in [serviceLaunchers] gets its accessibility service on to hold Home. A launcher the
 * user added that was Home stays on, and its service goes off if it's in [listedServiceLaunchers],
 * or it would take Home straight back. The entry is recorded as soon as Home moves, and [amend]
 * adds each service step once it's taken, so a link that drops part-way still leaves Undo; a step
 * that didn't take throws after that.
 */
internal suspend fun switchHome(
    actions: Actions,
    pkg: String,
    withService: Boolean,
    serviceLaunchers: Set<String>,
    listedServiceLaunchers: Set<String>,
    label: (String) -> String,
    reason: (ProtectedReason) -> UiText,
    amend: (id: Long, extra: Map<String, String?>, undo: Action?) -> HistoryEntry?,
    record: (packageName: String, undo: Action?, previous: Map<String, String?>) -> HistoryEntry,
): HistoryEntry = when (val r = actions.makeHome(pkg)) {
    is HomeResult.Switched -> {
        val was = r.from?.substringBefore('/')?.takeIf { r.kept }
        // With no launcher to replace, nothing was turned off: Undo is only for a service it turns on.
        // "kept": the old launcher stayed on.
        var entry = record(
            r.from?.substringBefore('/') ?: pkg,
            Action.Enable.takeIf { r.from != null },
            listOfNotNull(r.from?.let { "from" to it }, "to" to r.to, ("kept" to "true").takeIf { r.kept }).toMap(),
        )
        // Where the TV sends Home to its own launcher, the new one holds it with this service.
        val service = pkg.takeIf { withService && it in serviceLaunchers }?.let { actions.homeService(it) }?.takeUnless { actions.serviceOn(it) }
        val on = service?.let { actions.grant(it) }
        // "service" only when this switch turned it on, and "fromService" only when it turned the
        // old one's off, so Undo reverses no more than that.
        if (on == true) entry = amend(entry.id, mapOf("service" to service), Action.Enable) ?: entry
        val old = was?.takeIf { it in listedServiceLaunchers }?.let { actions.homeService(it) }?.takeIf { actions.serviceOn(it) }
        val off = old?.let { actions.setService(it, false) }
        if (off == true) entry = amend(entry.id, mapOf("fromService" to old), null) ?: entry
        // Last, after both services: one still on could pull Home straight back. A launcher the
        // TV came with is off now, which closed it already.
        val left = actions.handOff(was, r.to)
        if (on == false) throw UiTextException(serviceNotOn(label(pkg)))
        if (off == false) throw UiTextException(serviceNotOff(label(was!!)))
        left?.let { throw ActionFailed(notShown(label(pkg), it.reason), it.output) }
        entry
    }
    is HomeResult.Refused -> throw UiTextException(reason(r.reason))
    is HomeResult.Failed -> throw ActionFailed(tvSaid(r.output), r.details)
}

/**
 * Fire OS's Home: it refuses to turn Amazon's launcher off and won't keep another default, but [pkg]'s
 * accessibility service takes the Home key, and opens of Amazon's launcher, on its own. So only the
 * service goes on and Amazon's launcher stays as it is, then [pkg] opens, so the TV shows it without
 * a Home press. Another of [listed] whose service is on would take the Home key too, so its service
 * goes off. Recorded as a home with nothing to replace: "service" when this turned it on, so Undo
 * turns it off, and "fromService" when it turned the other's off, so Undo turns that back on.
 */
internal suspend fun fireServiceHome(
    actions: Actions,
    pkg: String,
    withService: Boolean,
    listed: Collection<String>,
    label: (String) -> String,
    record: (previous: Map<String, String?>) -> HistoryEntry,
): HistoryEntry {
    if (!withService) throw UiTextException(fireNeedsService(label(pkg)))
    val service = actions.homeService(pkg) ?: throw UiTextException(UiText(R.string.data_no_service, label(pkg)))
    // Off first, so a TV that refuses leaves everything as it was.
    val old = actions.fireServiceHolder(listed.filter { it != pkg })
    if (old != null && !actions.setService(old.second, false)) throw UiTextException(fireServiceNotOff(label(old.first)))
    val on = !actions.serviceOn(service)
    if (on && !actions.grant(service)) {
        old?.let { actions.grant(it.second) }
        throw UiTextException(fireServiceNotOn(label(pkg)))
    }
    // Amazon's launcher isn't stopped: it stays the default, and the service sends Home on from it.
    val left = actions.homeActivity(pkg)?.let { actions.handOff(null, it) }
    val previous = listOfNotNull(("service" to service).takeIf { on }, old?.let { "fromService" to it.second }).toMap()
    return record(previous).also {
        left?.let { throw ActionFailed(notShown(label(pkg), it.reason), it.output) }
    }
}

/**
 * Undo of a reset's Home on Fire OS, where Home never moved and only the launcher's service went
 * off: [previous]'s service goes back on. Null when the reset recorded no such step.
 */
internal suspend fun undoFireResetHome(actions: Actions, previous: Map<String, String?>): Boolean? =
    previous[ResetRecord.SERVICE]?.takeIf { previous[ResetRecord.TO] == null }?.let { actions.grant(it) }

/**
 * On Fire OS, the launcher of [launchers] whose accessibility service is on, and that service: it
 * holds Home there while Amazon's launcher stays the default ([fireServiceHome]). Null when none is.
 */
internal suspend fun Actions.fireServiceHolder(launchers: Collection<String>): Pair<String, String>? {
    for (pkg in launchers) {
        val service = homeService(pkg) ?: continue
        if (serviceOn(service)) return pkg to service
    }
    return null
}

/**
 * On Fire OS, the launcher the user set, for the Remote's Home to open: Amazon's Home key reaches only
 * its own launcher. The one whose service holds Home ([fireServiceHolder]) first, else the default
 * Home resolves to, unless that is Amazon's. Null when neither is set, and Home stays the key.
 */
internal suspend fun Actions.fireRemoteHome(listed: Collection<String>): String? {
    fireServiceHolder(listed)?.let { (pkg, _) -> homeActivity(pkg)?.let { return it } }
    return currentHome()?.takeUnless { it.substringBefore('/') == FIRE_TV_LAUNCHER }
}

/**
 * Fire OS's way back to Amazon's launcher: [held]'s service, from [fireServiceHolder], goes off, so
 * Home opens the default again, and the TV shows it. [record] gets the home activity and "service";
 * its Undo, a Make Home, turns the service back on.
 */
internal suspend fun fireHomeBack(
    actions: Actions,
    held: Pair<String, String>,
    label: (String) -> String,
    record: (to: String?, previous: Map<String, String?>) -> HistoryEntry,
): HistoryEntry {
    val (pkg, service) = held
    if (!actions.setService(service, false)) throw UiTextException(fireServiceNotOff(label(pkg)))
    val to = actions.currentHome()
    val left = to?.let { actions.handOff(pkg, it) }
    return record(to?.substringBefore('/'), mapOf("service" to service)).also {
        left?.let { throw ActionFailed(notShown(label(to!!.substringBefore('/')), it.reason), it.output) }
    }
}

/**
 * Whether [pkg]'s accessibility service may go on: it's in [SharedLists.serviceLaunchers], and the TV
 * has its list version or newer ([hasServiceFrom]). Below that it isn't offered, and [grant] refuses.
 */
internal suspend fun Actions.mayTurnOn(pkg: String): Boolean =
    pkg in SharedLists.serviceLaunchers && SharedLists.serviceFrom(pkg)?.let { hasServiceFrom(it, versionName(pkg)) } == true

/**
 * Turns accessibility [service] on, only when [mayTurnOn] says so. Every path that turns one on comes
 * here, so neither an unsigned list, a saved record nor an older copy of the launcher can get one;
 * false for any other, as for a TV that refused.
 */
internal suspend fun Actions.grant(service: String): Boolean =
    mayTurnOn(service.substringBefore('/')) && setService(service, true)

/**
 * The rest of [switch]'s Undo once its old launcher is on again: Home back to that launcher, the
 * service the switch turned on off, and the old one's back on. Null when all of it took, else why
 * not, named by [label]: then the switch isn't undone yet, and Undo can try again.
 */
internal suspend fun undoHomeSwitch(actions: Actions, switch: HistoryEntry, label: (String) -> String): UiText? {
    val from = switch.previous?.get("from")
    val service = switch.previous?.get("service")
    val fromService = switch.previous?.get("fromService")
    val back = from?.let { actions.setHome(it) }
    val off = service?.let { actions.setService(it, false) }
    val on = fromService?.let { actions.grant(it) }
    return when {
        back == false -> homeNotBack(label(from!!.substringBefore('/')))
        off == false -> serviceStillOn(label(service!!.substringBefore('/')))
        on == false -> serviceNotOn(label(fromService!!.substringBefore('/')))
        else -> null
    }
}

/** A wireless-debugging instance name: it's in the mDNS lookup that finds the TV's port. */
private val WIFI_GUID = Regex("adb-[A-Za-z0-9._-]{1,60}")

/** How long a picked file's session gets to answer before it counts as dropped. */
private val PROBE_WAIT = 3.seconds

/** How long a picked file waits for a dropped link to come back: one retry interval plus a connect. */
private val RECONNECT_WAIT = 20.seconds

/** The package name a reset's History entries carry: it changes the TV, not one app. */
internal const val RESET = "reset"

private fun serviceNotOn(app: String) = UiText(R.string.data_service_not_on, app)

private fun serviceStillOn(app: String) = UiText(R.string.data_service_still_on, app)

private fun homeNotBack(app: String) = UiText(R.string.data_home_not_back, app)

/** Home moved, but the old launcher didn't close or [app] didn't open: [why] is [HandOffFailed.reason]. */
private fun notShown(app: String, why: String) = UiText(R.string.data_not_shown, app, why)

/** Fire OS, after No to the service: without it, Home can't open [app]. */
private fun fireNeedsService(app: String) = UiText(R.string.data_fire_needs_service, app)

private fun fireServiceNotOn(app: String) = UiText(R.string.data_fire_service_not_on, app)

private fun fireServiceNotOff(app: String) = UiText(R.string.data_fire_service_not_off, app)

private fun serviceNotOff(app: String) = UiText(R.string.data_service_not_off, app)

/** What the TV printed when it refused, else that it refused. */
private fun tvSaid(output: String) = output.ifEmpty { null }?.asUiText() ?: UiText(R.string.data_tv_refused)

/**
 * Writes [values] and hands [record] the label and the old values for History. Strict Private DNS
 * fails closed: a provider the network can't reach takes the TV offline, so it's put back on the
 * spot (ADB stays up, it's the LAN by address). If putting it back fails too, the change is still
 * recorded, so Undo in History can take the TV back.
 */
internal suspend fun applyTweak(
    t: Tweaks,
    tweak: Tweak,
    values: Map<String, String?>,
    strictDns: Boolean,
    record: suspend (label: UiText, previous: Map<String, String?>) -> HistoryEntry,
): HistoryEntry {
    // Only the keys being written: Undo must not touch the ones this change left alone.
    val before = t.read(tweak).filterKeys { it in values }
    if (!t.write(tweak, values)) throw UiTextException(UiText(R.string.data_tweak_refused))
    // Strict mode works once the TV shows the provider validated; a lookup only when it doesn't show that.
    val works = !strictDns || (values["private_dns_specifier"]?.let { t.dnsValidated(it) } ?: t.resolves())
    if (!works) {
        val restored = try {
            t.write(tweak, before)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (restored) throw UiTextException(TvSettings.dnsUnreachable(values["private_dns_specifier"]))
        record(TvSettings.label(tweak, values), before)
        throw UiTextException(TvSettings.dnsStuck(values["private_dns_specifier"]))
    }
    return record(TvSettings.label(tweak, t.read(tweak)), before)
}

/**
 * Reset TV's step for [tweak]: deleting its keys brings back the TV's own default. They're read
 * just before, so Undo writes back exactly what was there; returns that, marked, for the reset's
 * History entry, or null when [tweak] is at its default already and nothing changed. Throws
 * when the TV refuses.
 */
internal suspend fun resetSetting(t: Tweaks, tweak: Tweak): Map<String, String?>? {
    val was = t.read(tweak)
    if (!TvSettings.offDefault(tweak, was)) return null
    if (!t.write(tweak, tweak.keys.associateWith { null })) throw UiTextException(UiText(R.string.data_tweak_refused))
    return was + (ResetRecord.mark(tweak) to "1")
}

/**
 * Undo of [resetSetting]: [tweak]'s keys back as the reset's [previous] holds them. True when
 * they're back, or the reset didn't change [tweak]; false when the TV refused.
 */
internal suspend fun undoResetSetting(t: Tweaks, tweak: Tweak, previous: Map<String, String?>): Boolean {
    val was = ResetRecord.setting(previous, tweak) ?: return true
    return try {
        t.write(tweak, was)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/**
 * Runs [switchBack] (Home to the TV's own, the launcher's service off), and [uninstall] only once
 * it worked. A failure says which step stopped it. Once it's gone, the switch's Undo, which would
 * make it Home again, can't work: [dropUndo] takes it away.
 */
internal suspend fun uninstallAfterHomeBack(switchBack: suspend () -> HistoryEntry, uninstall: suspend () -> HistoryEntry, dropUndo: (Long) -> Unit): HistoryEntry {
    val back = try {
        switchBack()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val why = e.why(R.string.data_tv_refused)
        // "switched back, but …": Home moved, its service didn't turn off.
        val switchedBack = (why as? UiText.Res)?.id == R.string.data_service_not_off
        throw UiTextException(UiText(if (switchedBack) R.string.data_uninstall_after_service else R.string.data_uninstall_home_not_back, why))
    }
    return uninstall().also { dropUndo(back.id) }
}

/**
 * [r], unless it is a different TV than the one whose [historyKey] is [expected]: that one is let go
 * and counts as unreachable, so a saved TV is never re-pointed at another. A TV that can't be read
 * counts as different. Null [expected]: not known yet, so there is nothing to tell apart.
 */
internal suspend fun sameTv(r: AdbConnectResult, expected: String?, keyOf: suspend (AdbSession) -> String): AdbConnectResult {
    if (r !is AdbConnectResult.Online || expected == null) return r
    val key = try {
        keyOf(r.session)
    } catch (e: CancellationException) {
        r.session.close()
        throw e
    } catch (_: Exception) {
        null
    }
    if (key == expected) return r
    r.session.close()
    return AdbConnectResult.Unreachable("A different TV answers here")
}
