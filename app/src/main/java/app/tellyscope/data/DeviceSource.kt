// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.StatsProfile
import app.tellyscope.adb.ApkInfo
import app.tellyscope.adb.ConnectionRequest
import app.tellyscope.adb.InstallResult
import app.tellyscope.adb.InstalledVersion
import app.tellyscope.adb.Progress
import app.tellyscope.adb.RemoteKey
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

    /** Try a known TV again after it stopped at "not allowed": the TV shows its Allow prompt. */
    suspend fun reconnect(deviceId: String)

    /** The user's own name for a TV; blank goes back to the network name. Kept per [historyKey]. */
    suspend fun rename(deviceId: String, name: String?)

    /** Stop reconnecting to a TV and drop it from the remembered list. */
    suspend fun forget(deviceId: String)

    /** Fast-tier stats. Completes when the device goes offline. */
    fun live(deviceId: String): Flow<Snapshot>

    /** Reads [deviceId] at its screen's [profile]; every other TV, or all of them for null, at the slowest. */
    fun showStats(deviceId: String?, profile: StatsProfile) {}

    suspend fun apps(deviceId: String): List<AppInfo>

    suspend fun network(deviceId: String): NetworkInfo

    /**
     * Runs an action and records it. Uninstall runs only on apps the user installed, and has no
     * undo. [undoing] is the history entry this reverses, which gets marked undone.
     */
    suspend fun act(deviceId: String, action: Action, packageName: String, undoing: Long? = null): HistoryEntry

    /** Everything Tellyscope changed on this TV, newest first. Survives app restarts. */
    suspend fun history(deviceId: String): List<HistoryEntry>

    /** Turns back on every app Tellyscope disabled, and nothing that was off before. */
    suspend fun restoreAll(deviceId: String): List<HistoryEntry>

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
     * uses the cached lists, or answers [Suggestions.Unavailable].
     */
    suspend fun suggestions(deviceId: String): Suggestions

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

    /** Types into the focused box on the TV. The caller checks [app.tellyscope.adb.Remote.typeable] first. */
    suspend fun type(deviceId: String, text: String): Boolean

    /** Restarts the TV. The link drops and reconnects on its own once the TV is back. False if the TV refused. */
    suspend fun reboot(deviceId: String): Boolean

    /** Opens the TV's system update screen on the TV. False when the TV has no such screen. */
    suspend fun openUpdates(deviceId: String): Boolean

    /** Private DNS and animation speed as the TV has them now. */
    suspend fun tweaks(deviceId: String): TvTweaks

    /**
     * Writes [values] for [tweak] and records the values from before, so Undo puts them back
     * exactly. [undoing] is the history entry this reverses. Throws with the reason when refused.
     */
    suspend fun change(deviceId: String, tweak: Tweak, values: Map<String, String?>, undoing: Long? = null): HistoryEntry

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
}
