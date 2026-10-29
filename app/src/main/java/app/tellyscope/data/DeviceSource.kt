// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.StatsProfile
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import okio.Sink
import okio.Source
import java.io.File

/**
 * Everything the screens need from a TV. The UI only talks to this; an
 * adapter over the `:adb` module implements it for real devices, and
 * [FakeDeviceSource] stands in for screenshots and until that lands. Refresh tiers follow the slice-1 data list:
 * fast 2 s ([live]), medium 10 s ([network]), slow / on open ([apps]).
 */
interface DeviceSource {
    /** LAN sweep for 5555 plus mDNS for wireless debugging. Emits hits as they answer, then [ScanEvent.Done]. */
    fun scan(): Flow<ScanEvent>

    /** Emits whenever the phone is on a Wi-Fi or Ethernet network a [scan] can sweep. */
    fun lanUp(): Flow<Unit> = emptyFlow()

    /** Classic connect. Suspends while the TV shows the Allow prompt; the caller owns the timeout. */
    suspend fun connect(hit: ScanHit): ConnectResult

    /** Wireless-debugging pairing with the 6-digit code shown on the TV, then connect. */
    suspend fun pair(hit: ScanHit, code: String): ConnectResult

    /** Previously paired devices, including offline ones. */
    suspend fun known(): List<Device>

    /**
     * Try a known TV again after it stopped at "not allowed": the TV shows its Allow prompt. Clears
     * [Device.loggedOutAt]; a refusal now is a plain "not allowed", not another logout.
     */
    suspend fun reconnect(deviceId: String)

    /** Cancel on the Allow step: stops the attempt, and a TV that had logged this phone out goes back to that. */
    suspend fun cancelReconnect(deviceId: String)

    /** The user's own name for a TV; blank goes back to the network name. Kept per [historyKey]. */
    suspend fun rename(deviceId: String, name: String?)

    /** Stop reconnecting to a TV and drop it from the remembered list. */
    suspend fun forget(deviceId: String)

    /** Fast-tier stats. Completes when the device goes offline. */
    fun live(deviceId: String): Flow<Snapshot>

    /** Reads [deviceId] at its screen's [profile]; every other TV, or all of them for null, at the slowest. */
    fun showStats(deviceId: String?, profile: StatsProfile) {}

    /**
     * The app went to the background ([paused] true) or came back. While paused no TV's stats are
     * read and a dropped TV isn't retried; open connections stay, so a running copy or install goes on.
     */
    fun pause(paused: Boolean) {}

    suspend fun apps(deviceId: String): List<AppInfo>

    suspend fun network(deviceId: String): NetworkInfo

    /**
     * Runs an action and records it. Uninstall runs only on apps the user installed, and has no
     * undo. [undoing] is the history entry this reverses, which gets marked undone.
     */
    suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long? = null): HistoryEntry

    /** [Action.Home], leaving the launcher's accessibility service off when [service] is false. */
    suspend fun makeHome(deviceId: String, packageName: String, service: Boolean): HistoryEntry = act(deviceId, Action.Home, packageName)

    /** Turns on the accessibility service of [packageName], the launcher on Home; Undo of the switch turns it off again. */
    suspend fun homeServiceOn(deviceId: String, packageName: String) {}

    /** Puts away the TV page's card about [packageName]'s service being off, until the next switch. */
    suspend fun dismissServiceNote(deviceId: String, packageName: String) {}

    /** Opens the Play Store on the TV at [packageName]'s page, a launcher from `launchers.json`; false when it didn't open. */
    suspend fun openStore(deviceId: String, packageName: String): Boolean = false

    /** Whether [packageName] is installed yet, and if not, whether the Store is still on screen. */
    suspend fun storeState(deviceId: String, packageName: String): StoreState = StoreState.Elsewhere

    /** Not now on the Apps tab's card about getting a better home screen, kept per TV. */
    suspend fun launcherCardHidden(deviceId: String): Boolean = false

    suspend fun hideLauncherCard(deviceId: String) {}

    /** Everything Tellyscope changed on this TV, newest first. Survives app restarts. */
    suspend fun history(deviceId: String): List<HistoryEntry>

    /** Turns back on every app Tellyscope disabled, and nothing that was off before. */
    suspend fun restoreAll(deviceId: String): List<HistoryEntry>

    /** What Reset TV would change, read from the TV itself, whoever changed it. Throws if it's gone. */
    suspend fun resetPlan(deviceId: String): ResetPlan = ResetPlan()

    /**
     * Runs [plan], reporting each step to [onProgress], and goes on past a step that fails. All of
     * it is one History entry; a retry ([into], that entry's id) adds to it. Returns the entry's id,
     * null when nothing changed, and what failed.
     */
    suspend fun reset(deviceId: String, plan: ResetPlan, into: Long? = null, onProgress: (ResetProgress) -> Unit = {}): Pair<Long?, List<ResetFailure>> = null to emptyList()

    /** The version of [packageName] on the TV, or null when it isn't installed. */
    suspend fun installed(deviceId: String, packageName: String): InstalledVersion?

    /** Installs [apks] (base first). Refuses a downgrade unless [allowDowngrade]; refusals come back, never throw. */
    suspend fun install(
        deviceId: String,
        apks: List<File>,
        apk: ApkInfo,
        allowDowngrade: Boolean,
        onProgress: (Progress) -> Unit,
    ): InstallResult

    /** Files in one of the shared folders, newest first. */
    suspend fun files(deviceId: String, folder: TvFolder): List<TvFile>

    /** Copies a phone file to the TV without overwriting anything; returns the name it got. */
    suspend fun push(deviceId: String, folder: TvFolder, name: String, size: Long?, source: Source, onProgress: (Progress) -> Unit): String

    suspend fun pull(deviceId: String, folder: TvFolder, file: TvFile, sink: Sink, onProgress: (Progress) -> Unit)

    /**
     * What the TV's list from tellyscope-devices suggests turning off. Never throws; offline it
     * uses the cached lists, or answers [Suggestions.Unavailable]. [apps] is the TV's app list,
     * asked for only once a list is found.
     */
    suspend fun suggestions(deviceId: String, apps: suspend () -> List<AppInfo>): Suggestions

    /** "Pick your TV": use list [listId] for this TV from now on. */
    suspend fun pickList(deviceId: String, listId: String)

    /** Where the debloat lists come from, as the user set it; null for tellyscope-devices. */
    suspend fun listSource(): String?

    /**
     * Reads the lists from [url] (the folder holding `index.json`) from now on, or from
     * tellyscope-devices again with null. Throws with the reason, changing nothing, when it
     * isn't HTTPS, can't be reached or isn't a Tellyscope list.
     */
    suspend fun setListSource(url: String?)

    /** One remote key. False when the TV answered with an error; throws when it's gone. */
    suspend fun press(deviceId: String, key: RemoteKey): Boolean

    /** The remote screen closed: stop anything [press] left running on the TVs. */
    fun closeRemote() {}

    /** Types into the focused box on the TV. The caller checks [app.tellyscope.adb.Remote.typeable] first. */
    suspend fun type(deviceId: String, text: String): Boolean

    /**
     * Turns the TV's picture off or back on; the TV stays awake and connected. False when this TV
     * can't (no key helper, or the TV refused). Throws when it's gone.
     */
    suspend fun screen(deviceId: String, on: Boolean): Boolean

    /** Restarts the TV. The link drops and reconnects on its own once the TV is back. False if the TV refused. */
    suspend fun reboot(deviceId: String): Boolean

    /** Opens [packageName] on the TV, the way its launcher icon would. False when it has nothing to open. Not history. */
    suspend fun launch(deviceId: String, packageName: String): Boolean

    /** The Remote's dock for this TV, kept on the phone so it shows while the TV is off. */
    suspend fun dock(deviceId: String): List<Docked>

    suspend fun setDock(deviceId: String, dock: List<Docked>)

    /** Opens the TV's system update screen on the TV. False when the TV has no such screen. */
    suspend fun openUpdates(deviceId: String): Boolean

    /** Private DNS and animation speed as the TV has them now. */
    suspend fun tweaks(deviceId: String): TvTweaks

    /**
     * Writes [values] for [tweak] and records the values from before, so Undo puts them back
     * exactly. [undoing] is the history entry this reverses. Throws with the reason when refused.
     */
    suspend fun change(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long? = null): HistoryEntry

    /**
     * Runs [block], several changes to apps, as one: the TV's stats wait for all of it, then are
     * read again once. Debloat's loop runs inside it.
     */
    suspend fun <T> batch(deviceId: String, block: suspend () -> T): T = block()

    /** Frees every app's cache, keeping app data and sign-ins, and records it. Returns KB freed, or null when the TV can't say. */
    suspend fun clearCaches(deviceId: String): Long?

    /** A PNG of what the TV shows right now, into [sink]. */
    suspend fun screenshot(deviceId: String, sink: Sink)

    /** Another device's "Allow debugging?" prompt showing on the TV now, or null. */
    suspend fun connectionRequest(deviceId: String): ConnectionRequest?

    /**
     * Presses Allow or Cancel on that prompt, ticking "Always allow" only when [always]. False
     * when the prompt was gone or showed another fingerprint by then.
     */
    suspend fun answerRequest(deviceId: String, request: ConnectionRequest, allow: Boolean, always: Boolean): Boolean

    /** Presses OK once on Fire OS's remote alert over that prompt ([ConnectionRequest.behindAlert]). False when nothing was sent. */
    suspend fun closeAlert(deviceId: String): Boolean

    /** What a report says about the TV: model, Android, the apps it came with. Throws if it's gone. */
    suspend fun facts(deviceId: String): TvFacts

    /** Who may use ADB on the TV and who is connected now. Throws if it's gone. */
    suspend fun access(deviceId: String): TvAccess = TvAccess()

    /** "It's mine": connections from [address] stop counting toward the warning on this TV. */
    suspend fun markMine(deviceId: String, address: String) {}

    /** "It's mine" on a new allowed computer: [fingerprint] counts as known on this TV from now on. */
    suspend fun markKeyMine(deviceId: String, fingerprint: String) {}

    /** Bluetooth, input, USB storage, HDMI-CEC and audio, for the Connected devices card. */
    suspend fun peripherals(deviceId: String): List<Peripheral> = emptyList()

    /**
     * Clears every allowed key on the TV, this phone's too. False when the TV refused, or its
     * Android isn't one the call is known for: the manual guide takes over.
     */
    suspend fun revokeAll(deviceId: String): Boolean = false

    /** Opens Developer options on the TV, for the manual guide. False when nothing opened. */
    suspend fun openDeveloperOptions(deviceId: String): Boolean = false

    /** This phone's key as the TV's Allow prompt shows it. */
    fun phoneFingerprint(): String? = null
}
