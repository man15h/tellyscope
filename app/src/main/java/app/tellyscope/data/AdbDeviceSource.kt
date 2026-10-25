// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import app.tellyscope.adb.ActionResult
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.Files
import app.tellyscope.adb.HomeOptions
import app.tellyscope.adb.HomeResult
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.Progress
import app.tellyscope.adb.Sideload
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.adb.Actions
import app.tellyscope.adb.ConnectionPrompt
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.Connector
import app.tellyscope.adb.DeviceLink
import app.tellyscope.adb.LabelHelper
import app.tellyscope.adb.LanScanner
import app.tellyscope.adb.NetworkStats
import app.tellyscope.adb.Parsers
import app.tellyscope.adb.Protected
import app.tellyscope.adb.ProtectedReason
import app.tellyscope.adb.Remote
import app.tellyscope.adb.RemoteKey
import app.tellyscope.adb.Stats
import app.tellyscope.adb.StatsProfile
import app.tellyscope.adb.PairResult
import app.tellyscope.adb.pairOnFreshPort
import app.tellyscope.adb.Tweak
import app.tellyscope.adb.Tweaks
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import okio.Sink
import okio.Source
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import app.tellyscope.adb.Action as AdbAction
import app.tellyscope.adb.ConnectResult as AdbConnectResult
import app.tellyscope.adb.DeviceState as AdbState
import app.tellyscope.adb.ScanHit as AdbHit

/**
 * [DeviceSource] on real TVs through `:adb`. Device id is the TV's IP. Wi-Fi name, signal, MAC and
 * link speed are empty for now. App names and icons come from the label helper through [AppNames].
 */
class AdbDeviceSource(context: Context) : DeviceSource {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob())
    private val links = ConcurrentHashMap<String, DeviceLink>()
    /** The TV on screen and its screen's refresh rates; every other TV reads at [StatsProfile.OTHER]. */
    @Volatile private var statsFor: Pair<String, StatsProfile>? = null
    private val hits = ConcurrentHashMap<String, ScanHit>()
    private val store = DeviceStore(File(appContext.filesDir, "devices.tsv"))
    private val saved = ConcurrentHashMap<String, SavedDevice>()
    /** Remembered TVs that haven't answered since launch: shown offline, not "connecting". */
    private val restoring = ConcurrentHashMap.newKeySet<String>()
    private val watchers = ConcurrentHashMap<String, Job>()
    /** Address → [historyKey], so history follows a TV across DHCP changes. */
    private val keys = ConcurrentHashMap<String, String>()
    /** Wireless debugging advertises pairing and connect on different ports. */
    private val pairingPorts = ConcurrentHashMap<String, Int>()
    private val book = HistoryBook(File(appContext.filesDir, "history.tsv"))
    private val names = NameBook(File(appContext.filesDir, "names.tsv"))
    /** Last label seen per package, so history entries read like the app list. */
    private val labels = ConcurrentHashMap<String, String>()
    private val helperDex by lazy { appContext.assets.open("label-helper.jar").use { it.readBytes() } }
    private val appNames = AppNames(File(appContext.filesDir, "app-names"))
    private val lists = DeviceLists(File(appContext.filesDir, "lists"))
    /** [historyKey] → the keep entries of that TV's list, once it has been looked up. */
    private val keep = ConcurrentHashMap<String, Set<String>>()

    init {
        Connector.init(File(appContext.filesDir, "adbkey.pem"))
    }

    /** Remembered TVs reconnect in the background from launch; [known] waits for the file read. */
    private val restored = scope.async(Dispatchers.IO) {
        store.load().forEach { d ->
            saved[d.host] = d
            d.historyKey?.let { keys[d.host] = it }
            hits[d.host] = ScanHit(d.host, d.port, d.kind, d.name, null)
            restoring += d.host
            link(d.host, d.port, first = null, knownGood = true)
        }
    }

    /** Starts the reconnecting link for a TV and keeps its saved entry current as it comes and goes. */
    private fun link(host: String, port: Int, first: AdbConnectResult?, knownGood: Boolean) {
        var pending = first
        val link = DeviceLink(host, port, scope, knownGood = knownGood, open = { h, p ->
            pending?.also { pending = null } ?: Connector.connect(h, p)
        })
        link.gate.profile.value = statsFor?.takeIf { it.first == host }?.second ?: StatsProfile.OTHER
        links.put(host, link)?.disconnect()
        watchers.put(host, scope.launch {
            // Save a fresh reading only when what's kept of it changed, not every 2 s.
            launch {
                link.stats.collect { st ->
                    val kept = saved[host]?.lastKnown
                    if (st != null && lastKnown(st, kept) != kept) remember(host)
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
        link.connect()
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
    )

    private fun tenths(kb: Long) = Math.round(kb / 104_857.6) / 10.0

    /** Reads the map at write time, so whichever save runs last writes the newest list. */
    private fun persist() {
        scope.launch(Dispatchers.IO) { synchronized(store) { store.save(saved.values.sortedBy { it.host }) } }
    }

    override suspend fun reconnect(deviceId: String) {
        restored.await()
        links[deviceId]?.connect()
    }

    override suspend fun rename(deviceId: String, name: String?) {
        restored.await()
        names.set(key(deviceId), name)
    }

    override suspend fun forget(deviceId: String) {
        restored.await()
        watchers.remove(deviceId)?.cancel()
        links.remove(deviceId)?.disconnect()
        hits.remove(deviceId)
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

    override fun scan(): Flow<ScanEvent> = flow {
        val hosts = lanHosts()
        if (hosts.isEmpty()) emit(ScanEvent.NoLan)
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

    override suspend fun connect(hit: ScanHit): ConnectResult = when (val r = Connector.connect(hit.ip, hit.port)) {
        is AdbConnectResult.Online -> {
            hits[hit.ip] = hit
            restoring -= hit.ip
            // Hand the open session to a link, which reconnects on its own from here on.
            link(hit.ip, hit.port, first = r, knownGood = false)
            ConnectResult.Online(device(hit.ip))
        }
        AdbConnectResult.NotAllowed -> ConnectResult.NotAllowed
        AdbConnectResult.NeedsPairing -> ConnectResult.NeedsPairing
        is AdbConnectResult.Unreachable -> ConnectResult.Unreachable(r.reason)
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
                    tempC = s.temperatureC?.toInt(),
                    uptimeSeconds = s.uptimeSeconds ?: 0,
                    screenOn = s.screenOn,
                    procs = s.processes.map { Proc(it.pid, it.name, it.packageName, it.cpuPercent ?: 0.0, (it.rssKb / 1024).toInt()) },
                    netBytesPerSec = s.netBytesPerSec,
                    softwareMonth = s.softwareMonth,
                )
            }
    }

    override fun showStats(deviceId: String?, profile: StatsProfile) {
        statsFor = deviceId?.let { it to profile }
        links.forEach { (host, link) -> link.gate.profile.value = if (host == deviceId) profile else StatsProfile.OTHER }
    }

    /** Runs a change with that TV's stats paused, so a slow stats read can't compete with it or cut it off. */
    private suspend fun <T> busy(deviceId: String, block: suspend () -> T): T =
        links[deviceId]?.gate?.during(block) ?: block()

    override suspend fun apps(deviceId: String): List<AppInfo> {
        val shell = session(deviceId)
        val home = Actions(shell).homePackages()
        val homeOptions = Actions(shell).homeOptions()
        val system = Parsers.packages(shell.shell("pm list packages -s").output).map { it.name }.toSet()
        val disabled = Parsers.packages(shell.shell("pm list packages -d").output).map { it.name }.toSet()
        val userInstalled = Parsers.packages(shell.shell("pm list packages -3").output).map { it.name }.toSet()
        // Older pm rejects --show-versioncode and prints no packages; list again without it.
        val listed = Parsers.packages(shell.shell("pm list packages --show-versioncode").output)
            .ifEmpty { Parsers.packages(shell.shell("pm list packages").output) }
        // Same key as history: a placeholder serial must not give two boxes one cache.
        val names = appNames.names(key(deviceId), listed, LabelHelper(shell, helperDex)::describe)
        val keep = keep[key(deviceId)].orEmpty()
        // On a launcher the user added, offer the way back to the one it replaced.
        val back = homeOptions?.current?.takeIf { it !in system }
            ?.let { homeBackTarget(it, book.entries(key(deviceId)), builtIns(system, disabled, homeOptions)) }
        return listed.map { p ->
            val name = names.getValue(p.name)
            labels[p.name] = name.label
            AppInfo(
                packageName = p.name,
                label = name.label,
                iconColor = null,
                system = p.name in system,
                enabled = p.name !in disabled,
                // Same rule as Actions.run: no known launcher, nothing can be stopped or disabled.
                // A disabled app stays unprotected so Enable (always allowed) still shows, even
                // for a built-in launcher that is off.
                protectedReason = when {
                    p.name in disabled -> null
                    home.isNotEmpty() -> Protected.reason(p.name, home)
                    else -> ProtectedReason.UNKNOWN_HOME
                }?.let(::reasonText),
                version = "",
                icon = name.icon,
                removable = p.name in userInstalled,
                homeInstead = homeOptions?.takeIf { p.name in it.others }?.current,
                homeBack = back?.takeIf { p.name == homeOptions?.current }?.let { names[it.pkg]?.label ?: AppNames.fallback(it.pkg) },
                listKeep = p.name in keep,
            )
        }.sortedBy { it.label.lowercase() }
    }

    override suspend fun network(deviceId: String): NetworkInfo {
        val shell = session(deviceId)
        val out = shell.shell("ip -o -4 addr show").output
        val line = out.lineSequence().firstOrNull { "inet " in it && " lo " !in it }
        val f = line?.trim()?.split(Regex("\\s+")).orEmpty()
        val packages = links[deviceId]?.stats?.value?.packages.orEmpty()
        return NetworkInfo(
            iface = f.getOrElse(1) { "" },
            ip = f.getOrNull(3)?.substringBefore('/') ?: deviceId,
            mac = null, ssid = null, rssi = null, linkMbps = null,
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
        val pkg = packages[uid]?.takeIf { uid % 100_000 >= 10_000 }?.firstOrNull() ?: return AppNames.SYSTEM
        return labels[pkg] ?: AppNames.fallback(pkg)
    }

    override suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry =
        busy(deviceId) { actNow(deviceId, action, packageName, undoing) }

    private suspend fun actNow(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry {
        val shell = session(deviceId)
        // Before the first change on a TV, note what was already off: Restore must leave those alone.
        val key = key(deviceId)
        if (!book.hasBaseline(key)) book.setBaseline(key, disabledPackages(deviceId))
        // The list's keep entries only warn, in the confirm; Actions.run holds the hard locks.
        if (action == Action.Home) return makeHome(deviceId, packageName, undoing)
        if (action == Action.HomeBack) return homeBack(deviceId, packageName, undoing)
        val r = Actions(shell).run(AdbAction.valueOf(action.name.uppercase()), packageName)
        return when (r) {
            is ActionResult.Done -> {
                // Undoing a home switch: the old launcher is on again, now make it Home again too.
                val switch = undoing?.let { id -> book.entries(key).firstOrNull { it.id == id && it.action == Action.Home } }
                val back = switch?.previous?.get("from")?.let { Actions(shell).setHome(it) }
                book.record(
                    deviceId = key,
                    at = System.currentTimeMillis(),
                    action = action,
                    packageName = packageName,
                    label = labels[packageName] ?: AppNames.fallback(packageName),
                    // Disabling the home screen is refused anyway; offer no undo that can't work.
                    undo = if (switch != null) null else r.undo?.let { Action.valueOf(it.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    undoing = undoing,
                ).also { if (back == false) throw IllegalStateException(homeNotBack(it.label)) }
            }
            is ActionResult.Refused -> throw IllegalStateException(reasonText(r.reason))
            is ActionResult.Failed -> throw IllegalStateException(r.output.ifEmpty { "the TV refused" })
        }
    }

    private suspend fun makeHome(deviceId: String, packageName: String, undoing: Long?): HistoryEntry {
        val key = key(deviceId)
        return when (val r = Actions(session(deviceId)).makeHome(packageName)) {
            is HomeResult.Switched -> book.record(
                deviceId = key,
                at = System.currentTimeMillis(),
                action = Action.Home,
                packageName = r.from.substringBefore('/'),
                label = labels[packageName] ?: AppNames.fallback(packageName),
                undo = Action.Enable,
                undoing = undoing,
                previous = mapOf("from" to r.from, "to" to r.to),
            )
            is HomeResult.Refused -> throw IllegalStateException(reasonText(r.reason))
            is HomeResult.Failed -> throw IllegalStateException(r.output.ifEmpty { "the TV refused" })
        }
    }

    /**
     * Makes Home again the launcher that [added], now Home, replaced: the one the last switch saved,
     * else the TV's own. [added] stays on; the entry's undo makes it Home again.
     */
    private suspend fun homeBack(deviceId: String, added: String, undoing: Long?): HistoryEntry {
        val key = key(deviceId)
        val shell = session(deviceId)
        val system = Parsers.packages(shell.shell("pm list packages -s").output).map { it.name }.toSet()
        val disabled = Parsers.packages(shell.shell("pm list packages -d").output).map { it.name }.toSet()
        val options = Actions(shell).homeOptions()
        val target = options?.current?.takeIf { it == added && it !in system }
            ?.let { homeBackTarget(it, book.entries(key), builtIns(system, disabled, options)) }
            ?: throw IllegalStateException("there's no home screen to switch back to")
        return when (val r = Actions(shell).homeBack(target.pkg, target.activity)) {
            is HomeResult.Switched -> book.record(
                deviceId = key,
                at = System.currentTimeMillis(),
                action = Action.HomeBack,
                packageName = added,
                label = labels[target.pkg] ?: AppNames.fallback(target.pkg),
                undo = Action.Home,
                // The switch away from it is reversed now, so Restore leaves it alone.
                undoing = undoing ?: target.reverses,
                previous = mapOf("from" to r.from, "to" to r.to),
            )
            is HomeResult.Refused -> throw IllegalStateException(reasonText(r.reason))
            is HomeResult.Failed -> throw IllegalStateException(r.output.ifEmpty { "the TV refused" })
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

    override suspend fun restoreAll(deviceId: String): List<HistoryEntry> {
        // Only what is still off now: the user may have turned some back on from the TV itself.
        val off = disabledPackages(deviceId)
        val key = key(deviceId)
        return restoreEach(book.restorable(key).filter { it in off }) { pkg ->
            act(deviceId, Action.Enable, pkg, undoing = book.openDisable(key, pkg))
        }
    }

    override suspend fun suggestions(deviceId: String): Suggestions = try {
        val r = resolve(deviceId)
        when {
            r.index == null -> Suggestions.Unavailable
            r.match == ListMatch.None -> Suggestions.NoList(r.index.commit, r.index.devices)
            r.list == null -> Suggestions.Unavailable
            else -> Suggestions.Ready(
                r.index.commit, r.list, r.sdk, suggest(r.list, apps(deviceId), r.sdk), r.index.devices,
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
            SourceResult.Ok -> keep.clear() // the old source's keep entries
            SourceResult.NotHttps -> throw IllegalStateException("the address has to start with https://")
            SourceResult.Unreachable -> throw IllegalStateException("couldn't reach that address. Check it and the phone's internet")
            SourceResult.NotAList -> throw IllegalStateException(NOT_A_LIST)
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

    override suspend fun press(deviceId: String, key: RemoteKey) = busy(deviceId) { Remote(session(deviceId)).press(key) }

    override suspend fun type(deviceId: String, text: String) = busy(deviceId) { Remote(session(deviceId)).type(text) }

    override suspend fun reboot(deviceId: String) = Remote(session(deviceId)).reboot()

    override suspend fun openUpdates(deviceId: String) = Remote(session(deviceId)).openUpdates()

    override suspend fun tweaks(deviceId: String): TvTweaks {
        val t = Tweaks(session(deviceId))
        return TvSettings.tweaks(t.read(Tweak.PRIVATE_DNS), t.read(Tweak.ANIMATIONS), sdk(deviceId))
    }

    private suspend fun sdk(deviceId: String) = session(deviceId).shell("getprop ro.build.version.sdk").output.trim().toIntOrNull()

    override suspend fun change(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long?): HistoryEntry =
        busy(deviceId) { changeNow(deviceId, tweak, values, undoing) }

    private suspend fun changeNow(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long?): HistoryEntry {
        val t = Tweaks(session(deviceId))
        val strictDns = tweak == Tweak.PRIVATE_DNS && values["private_dns_mode"] == "hostname"
        if (strictDns && (sdk(deviceId) ?: TvSettings.DNS_SDK) < TvSettings.DNS_SDK) throw IllegalStateException(TvSettings.DNS_TOO_OLD)
        return applyTweak(t, tweak, values, strictDns) { label, previous ->
            book.record(
                deviceId = key(deviceId),
                at = System.currentTimeMillis(),
                action = Action.Change,
                packageName = tweak.name,
                label = label,
                undo = Action.Change,
                undoing = undoing,
                previous = previous,
            )
        }
    }

    override suspend fun clearCaches(deviceId: String): Long? {
        val freed = busy(deviceId) { Tweaks(session(deviceId)).clearCaches() }
        book.record(key(deviceId), System.currentTimeMillis(), Action.ClearCaches, "", TvSettings.CACHES, undo = null)
        return freed
    }

    override suspend fun screenshot(deviceId: String, sink: Sink) = Tweaks(session(deviceId)).screenshot(sink)

    override suspend fun connectionRequest(deviceId: String) = ConnectionPrompt(session(deviceId)).pending()

    override suspend fun answerRequest(deviceId: String, request: ConnectionRequest, allow: Boolean, always: Boolean) =
        ConnectionPrompt(session(deviceId)).answer(request, allow, always)

    /**
     * The history key for a TV, read from it once connected and saved with it. Until it has
     * answered (offline since install) the address stands in, and that history moves over when
     * the key is learned.
     */
    private suspend fun key(deviceId: String): String = keys[deviceId] ?: readKey(deviceId)

    private suspend fun readKey(deviceId: String): String {
        val shell = links[deviceId]?.session ?: return keys[deviceId] ?: deviceId
        suspend fun prop(cmd: String) = shell.shell(cmd).takeIf { it.exitCode == 0 }?.output
        val key = try {
            historyKey(prop("getprop ro.serialno"), prop("settings get secure android_id"), deviceId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return keys[deviceId] ?: deviceId
        }
        if (keys.put(deviceId, key) != key) {
            book.rekey(deviceId, key)
            names.rekey(deviceId, key)
            remember(deviceId)
        }
        return key
    }

    private suspend fun disabledPackages(deviceId: String) =
        Parsers.packages(session(deviceId).shell("pm list packages -d").output).map { it.name }.toSet()

    override suspend fun installed(deviceId: String, packageName: String) = Sideload(session(deviceId)).installed(packageName)

    override suspend fun install(
        deviceId: String,
        apks: List<File>,
        apk: ApkInfo,
        allowDowngrade: Boolean,
        onProgress: (Progress) -> Unit,
    ): InstallResult = busy(deviceId) { Sideload(session(deviceId)).install(apks, apk, allowDowngrade, onProgress) }

    override suspend fun files(deviceId: String, folder: TvFolder) = Files(session(deviceId)).list(folder)

    override suspend fun push(deviceId: String, folder: TvFolder, name: String, size: Long?, source: Source, onProgress: (Progress) -> Unit) =
        busy(deviceId) { Files(session(deviceId)).push(source, size, name, folder, onProgress) }

    override suspend fun pull(deviceId: String, folder: TvFolder, file: TvFile, sink: Sink, onProgress: (Progress) -> Unit) =
        busy(deviceId) { Files(session(deviceId)).pull(folder, file, sink, onProgress) }

    private fun session(deviceId: String) =
        links[deviceId]?.session ?: throw IllegalStateException("$deviceId is offline")

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
        )
    }

    private fun tcpState(st: Int) = when (st) {
        Parsers.TcpSocket.ESTABLISHED -> "established"
        Parsers.TcpSocket.SYN_SENT, Parsers.TcpSocket.SYN_RECV -> "connecting"
        else -> "closing"
    }

    private fun homeNotBack(app: String) =
        "turned $app back on, but the TV kept the other home screen. Press Home on the remote to pick $app"

    /** Copy from the protected-apps table in the UX copy doc. */
    private fun reasonText(r: ProtectedReason) = when (r) {
        ProtectedReason.HOME_SCREEN -> "this is your home screen"
        ProtectedReason.SYSTEM -> "the TV needs this to work"
        ProtectedReason.SETTINGS -> "you need this to change TV settings"
        ProtectedReason.PLAY_SERVICES -> "most apps stop working without it"
        ProtectedReason.KEYBOARD -> "you'd lose the on-screen keyboard"
        ProtectedReason.TV_INPUTS_REMOTE -> "your remote or HDMI inputs could stop working"
        ProtectedReason.SETUP_WIZARD -> "the TV may get stuck on restart without it"
        ProtectedReason.UPDATES -> "the TV gets its updates through this"
        ProtectedReason.UNKNOWN_HOME -> "this TV didn't say which app is its home screen, so Tellyscope won't change anything"
        ProtectedReason.CAME_WITH_TV -> "it came with the TV, so it can only be disabled"
    }
}

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
    record: suspend (label: String, previous: Map<String, String?>) -> HistoryEntry,
): HistoryEntry {
    // Only the keys being written: Undo must not touch the ones this change left alone.
    val before = t.read(tweak).filterKeys { it in values }
    if (!t.write(tweak, values)) throw IllegalStateException("the TV didn't accept the change")
    if (strictDns && !t.resolves()) {
        val restored = try {
            t.write(tweak, before)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (restored) throw IllegalStateException(TvSettings.dnsUnreachable(values["private_dns_specifier"]))
        record(TvSettings.label(tweak, values), before)
        throw IllegalStateException(TvSettings.dnsStuck(values["private_dns_specifier"]))
    }
    return record(TvSettings.label(tweak, t.read(tweak)), before)
}
