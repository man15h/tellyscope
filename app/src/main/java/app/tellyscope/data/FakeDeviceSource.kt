// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import android.content.res.Resources
import app.tellyscope.R
import app.tellyscope.adb.AdbClient
import app.tellyscope.adb.AllowedKey
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.adb.Peripheral
import app.tellyscope.adb.Progress
import app.tellyscope.adb.RemoteKey
import app.tellyscope.adb.TvFacts
import app.tellyscope.adb.TvFile
import app.tellyscope.adb.TvFolder
import app.tellyscope.adb.Tweak
import java.io.File
import java.io.IOException
import java.time.YearMonth
import kotlin.random.Random
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okio.Buffer
import okio.Sink
import okio.Source

/**
 * Stand-in [DeviceSource] for building the screens before the ADB module exists.
 * [Scenario] switches between the flows worth looking at. [resources] spell out History labels;
 * without them a label is the [UiText] as data, which no test reads.
 */
class FakeDeviceSource(
    private val scenario: Scenario = Scenario.Normal,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val resources: Resources? = null,
) : DeviceSource {

    private fun UiText.text(): String = resources?.let { this.resolve(it) } ?: toString()

    enum class Scenario {
        Normal,
        /** Scan finds nothing: the brand guide. */
        Empty,
        /** The TV never answers the Allow prompt. */
        NoAllow,
    }

    private val hits = listOf(
        ScanHit("192.168.1.23", 5555, ScanKind.Classic, "xgimi-projector", "XGIMI"),
        ScanHit("192.168.1.41", 5555, ScanKind.Classic, "android-7f3a", "Amlogic"),
        ScanHit("192.168.1.57", 37211, ScanKind.Wireless, "chromecast", "Google"),
        ScanHit("192.168.1.88", 5555, ScanKind.Classic, null, null),
    )

    private val models = mapOf(
        "XGIMI" to Triple("XGIMI Horizon Pro", "XGIMI", "11"),
        "Amlogic" to Triple("X96 Max", "Amlogic", "9"),
        "Google" to Triple("Chromecast with Google TV", "Google", "12"),
    )

    private val apps = mutableMapOf<String, MutableList<AppInfo>>()
    private val book = HistoryBook()
    private val tvFiles = mutableMapOf<TvFolder, MutableList<TvFile>>()

    override fun scan(): Flow<ScanEvent> = flow {
        val subnet = "192.168.1.0/24"
        val pending = if (scenario == Scenario.Empty) ArrayDeque() else ArrayDeque(hits)
        var probed = 0
        while (probed < 254) {
            delay(120)
            probed = minOf(254, probed + 23)
            emit(ScanEvent.Progress(ScanProgress(probed, 254, subnet)))
            if (pending.isNotEmpty() && random.nextFloat() < 0.45f) emit(ScanEvent.Found(pending.removeFirst()))
        }
        pending.forEach { emit(ScanEvent.Found(it)) }
        emit(ScanEvent.Done)
    }

    override suspend fun connect(hit: ScanHit): ConnectResult {
        if (scenario == Scenario.NoAllow) {
            delay(Long.MAX_VALUE)
        }
        if (hit.kind == ScanKind.Wireless) {
            delay(300)
            return ConnectResult.NeedsPairing
        }
        delay(3_200) // the user finding the remote and pressing Allow
        return ConnectResult.Online(device(hit))
    }

    override suspend fun pair(hit: ScanHit, code: String): ConnectResult {
        delay(800)
        if (!code.matches(Regex("\\d{6}"))) {
            return ConnectResult.WrongCode("that code didn't work. codes change each time the box opens; check the TV and try again.")
        }
        return ConnectResult.Online(device(hit))
    }

    /** Connected or remembered TVs, keyed by id; starts with one offline TV to show that state. */
    private val remembered = linkedMapOf<String, Device>().apply {
        listOf(
            Device(
                id = "192.168.1.64",
                hit = ScanHit("192.168.1.64", 5555, ScanKind.Classic, "bedroom-firetv", "Amazon"),
                state = DeviceState.Offline,
                model = "Fire TV Stick 4K",
                manufacturer = "Amazon",
                android = "9",
                lastSeen = clock() - 14 * 60_000,
                lastKnown = LastKnown("Amazon", "Amazon", "9", YearMonth.of(2024, 3), 5.1, 5.4),
            ),
        ).takeIf { scenario == Scenario.Normal }.orEmpty().forEach { put(it.id, it) }
    }

    /** TVs restarting, and when they answer again. */
    private val backAt = mutableMapOf<String, Long>()

    override suspend fun known(): List<Device> {
        backAt.entries.removeAll { (id, at) ->
            (at <= clock()).also { back -> if (back) remembered[id]?.let { remembered[id] = it.copy(state = DeviceState.Online, lastSeen = clock()) } }
        }
        return remembered.values.toList()
    }

    override suspend fun reconnect(deviceId: String) = Unit
    override suspend fun cancelReconnect(deviceId: String) = Unit

    override suspend fun rename(deviceId: String, name: String?) {
        remembered[deviceId]?.let { remembered[deviceId] = it.copy(nickname = name?.trim()?.ifEmpty { null }) }
    }

    override suspend fun forget(deviceId: String) {
        remembered.remove(deviceId)
    }

    private fun device(hit: ScanHit): Device {
        val (model, maker, android) = models[hit.maker] ?: Triple("Android TV box", hit.maker, "10")
        return Device(hit.ip, hit, DeviceState.Online, model, maker, android, clock()).also { remembered[it.id] = it }
    }

    override fun live(deviceId: String): Flow<Snapshot> = flow {
        while (currentCoroutineContext().isActive) {
            emit(snapshot(deviceId))
            delay(2_000)
        }
    }

    fun snapshot(deviceId: String): Snapshot {
        val enabled = appsFor(deviceId).filter { it.enabled }.map { it.packageName }.toSet()
        val procs = baseProcs
            .filter { it.packageName == null || it.packageName in enabled }
            .map {
                it.copy(
                    cpuPercent = (it.cpuPercent + (random.nextDouble() - 0.5) * 3).coerceAtLeast(0.0),
                    ramMb = (it.ramMb * (0.97 + random.nextDouble() * 0.06)).toInt(),
                )
            }
        return Snapshot(
            at = clock(),
            cpuPercent = procs.sumOf { it.cpuPercent }.toInt(),
            ramUsedMb = procs.sumOf { it.ramMb } + 260,
            ramTotalMb = 2048,
            storageUsedGb = 9.8,
            storageTotalGb = 16.0,
            uptimeSeconds = 3 * 86_400L + 4 * 3_600L,
            screenOn = true,
            procs = procs,
            netBytesPerSec = 2_400_000L + random.nextLong(-200_000, 200_000),
            softwareMonth = YearMonth.of(2023, 5),
        )
    }

    override suspend fun apps(deviceId: String): List<AppInfo> = appsFor(deviceId).toList()

    private fun appsFor(deviceId: String) = apps.getOrPut(deviceId) { baseApps.toMutableList() }

    override suspend fun network(deviceId: String) = NetworkInfo(
        iface = "wifi",
        ip = deviceId,
        mac = "58:d5:0a:3c:91:e2",
        ssid = "home-5g",
        rssi = -54 + random.nextInt(-3, 4),
        linkMbps = 433,
        sockets = listOf(
            Socket("YouTube", "youtube.com:443", "established"),
            Socket("Jellyfin", "192.168.1.10:8096", "established"),
            Socket("Google Play services", "googleapis.com:443", "established"),
            Socket("XGIMI Assistant", "xgimi.com:443", "closing"),
        ),
        traffic = listOf(
            AppTraffic("YouTube", 1840, 22),
            AppTraffic("Jellyfin", 960, 4),
            AppTraffic("Netflix", 410, 6),
            AppTraffic("Google Play services", 120, 31),
            AppTraffic("XGIMI Updates", 64, 1),
        ),
    )

    override suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long?): HistoryEntry {
        delay(400)
        val list = appsFor(deviceId)
        if (!book.hasBaseline(deviceId)) book.setBaseline(deviceId, list.filter { !it.enabled }.map { it.packageName }.toSet())
        if (action == Action.Reset) {
            val reset = book.entries(deviceId).first { it.id == undoing }
            val on = ResetRecord.list(reset.previous, ResetRecord.ON)
            list.replaceAll { if (it.packageName in on) it.copy(enabled = false) else it }
            return book.record(deviceId, clock(), Action.Change, packageName, UiText(R.string.data_history_reset_undone).text(), null, undoing)
        }
        val i = list.indexOfFirst { it.packageName == packageName }
        val app = list[i]
        when (action) {
            Action.Disable -> list[i] = app.copy(enabled = false)
            Action.Enable -> list[i] = app.copy(enabled = true)
            Action.Uninstall -> list.removeAt(i)
            Action.Stop, Action.Open, Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack, Action.Reset -> Unit
        }
        val undo = when (action) {
            Action.Stop -> Action.Open
            Action.Disable -> Action.Enable
            Action.Enable -> Action.Disable
            Action.Home -> Action.Enable
            Action.HomeBack -> Action.Home
            Action.Open, Action.Uninstall, Action.Change, Action.ClearCaches, Action.Reset -> null
        }
        return book.record(deviceId, clock(), action, packageName, app.label, undo, undoing)
    }

    override suspend fun history(deviceId: String): List<HistoryEntry> = book.entries(deviceId)

    override suspend fun restoreAll(deviceId: String): List<HistoryEntry> {
        val off = appsFor(deviceId).filter { !it.enabled }.map { it.packageName }.toSet()
        return restoreEach(book.restorable(deviceId).filter { it in off }) { pkg ->
            act(deviceId, Action.Enable, pkg, undoing = book.openDisable(deviceId, pkg))
        }
    }

    /** The demo TV's apps that are off and came with it, as if someone had turned them off by hand. */
    override suspend fun resetPlan(deviceId: String): ResetPlan {
        delay(400)
        val off = appsFor(deviceId).filter { it.system && !it.enabled }.map { ResetApp(it.packageName, it.label) }
        return ResetPlan(turnOn = off, ownHome = "Google TV Home")
    }

    override suspend fun reset(deviceId: String, plan: ResetPlan, into: Long?, onProgress: (ResetProgress) -> Unit): Pair<Long?, List<ResetFailure>> {
        val list = appsFor(deviceId)
        plan.turnOn.forEachIndexed { i, app ->
            onProgress(ResetProgress(ResetPart.TurnOn, i, plan.turnOn.size))
            delay(300)
            list.replaceAll { if (it.packageName == app.packageName) it.copy(enabled = true) else it }
        }
        if (plan.turnOn.isEmpty()) return into to emptyList()
        val on = plan.turnOn.joinToString(",") { it.packageName }
        val entry = book.record(deviceId, clock(), Action.Reset, "reset", ResetRecord.label(plan.changes).text(), Action.Reset, previous = mapOf(ResetRecord.ON to on))
        return entry.id to emptyList()
    }

    override suspend fun installed(deviceId: String, packageName: String): InstalledVersion? =
        appsFor(deviceId).firstOrNull { it.packageName == packageName }?.let { InstalledVersion(1, it.version) }

    override suspend fun install(
        deviceId: String,
        apks: List<File>,
        apk: ApkInfo,
        allowDowngrade: Boolean,
        onProgress: (Progress) -> Unit,
    ): InstallResult {
        val total = apks.sumOf { it.length() }
        for (i in 0..20) {
            onProgress(Progress(total * i / 20, total))
            delay(100)
        }
        delay(1_200) // the TV unpacking it
        return InstallResult.Done
    }

    override suspend fun files(deviceId: String, folder: TvFolder): List<TvFile> = tvFiles.getOrPut(folder) {
        if (folder == TvFolder.DOWNLOAD) baseFiles.toMutableList() else mutableListOf()
    }.sortedByDescending { it.modifiedMillis }

    override suspend fun push(deviceId: String, folder: TvFolder, name: String, size: Long?, source: Source, onProgress: (Progress) -> Unit): String {
        var done = 0L
        val buf = Buffer()
        while (true) {
            val n = source.read(buf, 256 * 1024)
            if (n < 0) break
            buf.clear()
            done += n
            onProgress(Progress(done, size))
            delay(50)
        }
        files(deviceId, folder)
        tvFiles.getValue(folder) += TvFile(name, done, clock())
        return name
    }

    override suspend fun pull(deviceId: String, folder: TvFolder, file: TvFile, sink: Sink, onProgress: (Progress) -> Unit) {
        val chunk = ByteArray(64 * 1024)
        var done = 0L
        while (done < file.size) {
            val n = minOf(chunk.size.toLong(), file.size - done)
            sink.write(Buffer().write(chunk, 0, n.toInt()), n)
            done += n
            onProgress(Progress(done, file.size))
            delay(30)
        }
        sink.flush()
    }

    private val baseFiles get() = listOf(
        TvFile("holiday-2025.mp4", 812_000_000, clock() - 3 * 86_400_000L),
        TvFile("projectivy-backup.plbackup", 48_200, clock() - 26 * 3_600_000L),
        TvFile("subtitles.srt", 61_000, clock() - 40 * 60_000L),
    )

    override suspend fun suggestions(deviceId: String, apps: suspend () -> List<AppInfo>): Suggestions {
        delay(600)
        val all = listOf(ListEntry(fakeList.id, fakeList.name, null, fakeList.sdk, "devices/${fakeList.id}.json", "0".repeat(64)))
        return Suggestions.Ready(null, fakeList, 30, suggest(fakeList, apps(), 30), all)
    }

    override suspend fun pickList(deviceId: String, listId: String) = Unit

    private var listSource: String? = null

    override suspend fun listSource() = listSource

    override suspend fun setListSource(url: String?) {
        listSource = url?.let { normalize(it) ?: throw UiTextException(UiText(R.string.data_lists_not_https)) }
    }

    override suspend fun press(deviceId: String, key: RemoteKey): Boolean {
        delay(60)
        return true
    }

    override suspend fun type(deviceId: String, text: String): Boolean {
        delay(150)
        return true
    }

    override suspend fun screen(deviceId: String, on: Boolean): Boolean {
        delay(100)
        return true
    }

    override suspend fun openUpdates(deviceId: String): Boolean {
        delay(300)
        return true
    }

    override suspend fun launch(deviceId: String, packageName: String): Boolean {
        delay(200)
        return appsFor(deviceId).any { it.packageName == packageName && it.launchable && it.enabled }
    }

    private val docks = DockBook()

    override suspend fun dock(deviceId: String) = docks[deviceId]

    override suspend fun setDock(deviceId: String, dock: List<Docked>) = docks.set(deviceId, dock)

    override suspend fun reboot(deviceId: String): Boolean {
        delay(300)
        remembered[deviceId]?.let { remembered[deviceId] = it.copy(state = DeviceState.Offline) }
        backAt[deviceId] = clock() + 20_000
        return true
    }

    /** Global settings per TV, for the tweaks card. */
    private val settings = mutableMapOf<String, MutableMap<String, String?>>()

    private fun settingsFor(deviceId: String) = settings.getOrPut(deviceId) { mutableMapOf("private_dns_mode" to "opportunistic") }

    override suspend fun tweaks(deviceId: String): TvTweaks {
        val s = settingsFor(deviceId)
        return TvSettings.tweaks(s, s, sdk = 30)
    }

    override suspend fun change(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long?): HistoryEntry {
        delay(300)
        val s = settingsFor(deviceId)
        val before = values.keys.associateWith { s[it] }
        s.putAll(values)
        return book.record(deviceId, clock(), Action.Change, tweak.name, TvSettings.label(tweak, s).text(), Action.Change, undoing, before)
    }

    override suspend fun clearCaches(deviceId: String): Long {
        delay(800)
        book.record(deviceId, clock(), Action.ClearCaches, "", TvSettings.CACHES.text(), undo = null)
        return 1_340_000
    }

    override suspend fun screenshot(deviceId: String, sink: Sink) {
        delay(500)
        throw IOException("the demo TV has no screen to capture")
    }

    override suspend fun connectionRequest(deviceId: String): ConnectionRequest? = null

    override suspend fun answerRequest(deviceId: String, request: ConnectionRequest, allow: Boolean, always: Boolean) = false

    override suspend fun closeAlert(deviceId: String) = false

    /** Addresses marked "It's mine", and TVs whose access was cleared, so the demo flow ends on Done. */
    private val mine = mutableSetOf<String>()
    private val revoked = mutableSetOf<String>()

    override suspend fun access(deviceId: String): TvAccess {
        val clients = listOf(AdbClient(PHONE, 40112)) +
            if (deviceId in revoked) emptyList() else listOf(AdbClient(OTHER, 51234), AdbClient(OTHER, 51240))
        val keys = listOf(AllowedKey(PHONE_FINGERPRINT, "tellyscope@phone", clock() - 60_000)) + if (deviceId in revoked) emptyList() else listOf(
            AllowedKey("9B:21:6E:F0:3A:7C:11:58:D4:02:BE:93:6F:A1:C8:E5", "alex@laptop", clock() - 2 * 3_600_000),
            AllowedKey("C4:0E:63:19:B2:7D:45:E8:0A:91:3C:F6:28:D7:A2:5B", "studio-pc", clock() - 3 * 86_400_000),
            AllowedKey("71:D5:08:AE:42:9F:C3:16:7B:E0:54:2A:D9:83:3C:E9", null, null),
        )
        return TvAccess.of(clients, keys, setOf(PHONE), deviceId, mine, PHONE_FINGERPRINT)
    }

    override suspend fun markMine(deviceId: String, address: String) {
        mine += address
    }

    override suspend fun peripherals(deviceId: String) = listOf(
        Peripheral("XGIMI remote", Peripheral.Group.Bluetooth, Peripheral.Kind.Remote, true, "Connected"),
        Peripheral("Sony WH-1000XM4", Peripheral.Group.Bluetooth, Peripheral.Kind.Headphones, false, "Paired"),
        Peripheral("Logitech K400", Peripheral.Group.Input, Peripheral.Kind.Keyboard, true, "USB"),
        Peripheral("SanDisk 64 GB", Peripheral.Group.Storage, Peripheral.Kind.Usb, true, "Connected", free = 41L shl 30),
        Peripheral("Sonos Beam", Peripheral.Group.Hdmi, Peripheral.Kind.Speaker, true, "On"),
        Peripheral("Sound output", Peripheral.Group.Audio, Peripheral.Kind.Sound, true, "HDMI ARC"),
    )

    override suspend fun revokeAll(deviceId: String): Boolean {
        delay(600)
        revoked += deviceId
        return true
    }

    override suspend fun openDeveloperOptions(deviceId: String) = true

    override fun phoneFingerprint() = PHONE_FINGERPRINT

    override suspend fun facts(deviceId: String) = TvFacts(
        manufacturer = "XGIMI", model = "Horizon Pro", device = "XK03H", androidVersion = "11", sdk = 30,
        build = "RTT1.230922.001", stockPackages = fakeStock, userApps = 7, identifiers = emptySet(),
    )

    private companion object {
        const val PHONE = "192.0.2.10"
        const val OTHER = "192.0.2.47"
        const val PHONE_FINGERPRINT = "3F:9A:1C:5D:E0:42:B7:19:8C:6A:F3:21:0D:94:7E:C2"

        /** Enough stock apps that a cleanyourtv report spills into "Add the rest". */
        val fakeStock = listOf(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.xgimi.ota",
            "com.xgimi.assistant", "com.xgimi.vcontrol", "com.xgimi.filemanager", "com.xgimi.gallery",
        ) + (1..240).map { "com.xgimi.service$it" } + (1..60).map { "com.android.component$it" }

        val fakeList = DeviceList(
            id = "xgimi-horizon-pro",
            name = "XGIMI Horizon Pro",
            testedOs = "Android TV 11",
            sdk = 30,
            keep = emptySet(),
            groups = listOf(
                ListGroup("Extras", listOf(
                    ListPackage("com.xgimi.assistant", "voice assistant", optional = false),
                    ListPackage("com.google.android.tvrecommendations", null, optional = false),
                    ListPackage("com.netflix.ninja", null, optional = true),
                )),
            ),
        )


        val baseApps = listOf(
            AppInfo("com.spocky.projengmenu", "Projectivy Launcher", 0xFF3D5AFE, false, true, UiText(R.string.data_protected_home_screen), "4.52", launchable = true),
            AppInfo("com.google.android.youtube.tv", "YouTube", 0xFFC4302B, false, true, null, "4.40.303", launchable = true),
            AppInfo("com.netflix.ninja", "Netflix", 0xFFB20710, false, true, null, "11.2.0", removable = true, launchable = true),
            AppInfo("org.jellyfin.androidtv", "Jellyfin", 0xFF7B5EA7, false, true, null, "0.17.4", removable = true, launchable = true),
            AppInfo("com.xgimi.assistant", "XGIMI Assistant", null, true, true, null, "2.1.8"),
            AppInfo("com.xgimi.ota", "XGIMI Updates", null, true, true, null, "1.4.0"),
            AppInfo("com.google.android.tvrecommendations", "TV Recommendations", null, true, false, null, "1.0"),
            AppInfo("com.android.systemui", "System UI", null, true, true, UiText.Raw("the TV needs this to draw the screen"), "11"),
            AppInfo("com.android.tv.settings", "Settings", null, true, true, UiText(R.string.data_protected_settings), "11", launchable = true),
            AppInfo("com.google.android.gms", "Google Play services", null, true, true, UiText(R.string.data_protected_play_services), "24.38"),
            AppInfo("com.google.android.inputmethod.latin", "Gboard", null, true, true, UiText(R.string.data_protected_keyboard), "14.1"),
        )

        val baseProcs = listOf(
            Proc(412, "surfaceflinger", null, 9.0, 38),
            Proc(688, "system_server", null, 6.0, 214),
            Proc(1502, "com.spocky.projengmenu", "com.spocky.projengmenu", 2.0, 312),
            Proc(2210, "com.google.android.youtube.tv", "com.google.android.youtube.tv", 4.0, 220),
            Proc(2241, "com.google.android.youtube.tv:service", "com.google.android.youtube.tv", 1.0, 41),
            Proc(1980, "com.google.android.gms.persistent", "com.google.android.gms", 1.0, 118),
            Proc(2077, "com.xgimi.assistant", "com.xgimi.assistant", 1.0, 96),
            Proc(2301, "org.jellyfin.androidtv", "org.jellyfin.androidtv", 0.5, 88),
            Proc(1440, "com.android.systemui", "com.android.systemui", 1.0, 102),
            Proc(530, "media.codec", null, 2.0, 22),
            Proc(2390, "com.xgimi.ota", "com.xgimi.ota", 0.0, 31),
        )
    }
}
