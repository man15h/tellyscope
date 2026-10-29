// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.time.YearMonth

/** How a TV was found: legacy network ADB on 5555, or Android 11+ wireless debugging (TLS + pairing code). */
enum class ScanKind { Classic, Wireless }

data class ScanHit(
    val ip: String,
    val port: Int,
    val kind: ScanKind,
    /** mDNS name, else reverse DNS, else null. */
    val hostname: String?,
    /** From the MAC's OUI; null when the MAC is randomised. */
    val maker: String?,
)

data class ScanProgress(val probed: Int, val total: Int, val subnet: String)

sealed interface ScanEvent {
    data class Found(val hit: ScanHit) : ScanEvent
    data class Progress(val progress: ScanProgress) : ScanEvent
    /** Not on Wi-Fi or Ethernet: only TVs that announce themselves (or add-by-IP) can be found. */
    data object NoLan : ScanEvent
    data object Done : ScanEvent
}

/**
 * ADB reports `unauthorized` both before the user answers and after they press
 * Deny, so there is no "denied" state: [NotAllowed] means "unauthorized past the
 * timeout", and only on a first connect. A TV that was online before and stalls
 * stays [Offline] and keeps retrying. [NeedsPairing] is a wireless-debugging TV
 * that doesn't know our key yet: show the 6-digit code field.
 */
enum class DeviceState { Found, Waiting, NeedsPairing, Online, Offline, NotAllowed }

data class Device(
    val id: String,
    val hit: ScanHit,
    val state: DeviceState,
    val model: String?,
    val manufacturer: String?,
    val android: String?,
    /** Epoch millis of the last successful poll. */
    val lastSeen: Long?,
    /** The name the user gave it; wins over the network name. */
    val nickname: String? = null,
    /** `ro.product.brand`, which can differ from [manufacturer]. */
    val brand: String? = null,
    /** What the TV last reported, kept until Forget; as of [lastSeen]. Null before it ever answered. */
    val lastKnown: LastKnown? = null,
    /**
     * Epoch millis the TV took back a key it had allowed: this phone is logged out and stops asking
     * (state stays [DeviceState.NotAllowed]). Null otherwise; cleared by a reconnect.
     */
    val loggedOutAt: Long? = null,
)

/**
 * The slow-changing facts from a TV's last good reading, saved on the phone so an offline TV
 * (even after a restart) still shows them. Live numbers (CPU, RAM, temp, network) are never kept.
 */
data class LastKnown(
    val manufacturer: String? = null,
    val brand: String? = null,
    val android: String? = null,
    val softwareMonth: YearMonth? = null,
    val storageUsedGb: Double? = null,
    val storageTotalGb: Double? = null,
)

sealed interface ConnectResult {
    data class Online(val device: Device) : ConnectResult
    data object NotAllowed : ConnectResult
    /** Wireless-debugging TV that doesn't know our key; ask for the pairing code. */
    data object NeedsPairing : ConnectResult
    data class WrongCode(val message: String) : ConnectResult
    data class Unreachable(val message: String) : ConnectResult
}

data class Proc(
    val pid: Int,
    val name: String,
    /** Owning package; null for native daemons (shown as "android system"). */
    val packageName: String?,
    val cpuPercent: Double,
    val ramMb: Int,
)

/** One poll of the fast tier. Null fields are stats the device doesn't report; the UI shows "—", never 0. */
data class Snapshot(
    val at: Long,
    val cpuPercent: Int,
    val ramUsedMb: Int,
    val ramTotalMb: Int,
    val storageUsedGb: Double?,
    val storageTotalGb: Double?,
    val tempC: Int?,
    val uptimeSeconds: Long,
    val screenOn: Boolean?,
    val procs: List<Proc>,
    /** Whole-TV network traffic, in and out. */
    val netBytesPerSec: Long? = null,
    /** When the TV's software was last updated. */
    val softwareMonth: YearMonth? = null,
)

data class AppInfo(
    val packageName: String,
    /** Human label from the APK. Never the package id; the source falls back to a humanised id. */
    val label: String,
    /** ARGB of the letter avatar; null = neutral tile. Unused when [icon] is set. */
    val iconColor: Long?,
    val system: Boolean,
    val enabled: Boolean,
    /** Why this app can't be touched, in user words; null when actions are allowed. */
    val protectedReason: String?,
    val version: String,
    /** The app's own icon as a PNG, from the TV; null shows the letter avatar. */
    val icon: ByteArray? = null,
    /** ARGB of the letter on an [iconColor] avatar; null = white. A banner-only app's accent colour. */
    val iconAccent: Long? = null,
    /** Installed by the user (`pm list packages -3`), so Uninstall is offered. Never for apps that came with the TV. */
    val removable: Boolean = false,
    /** The launcher Home opens now. */
    val home: Boolean = false,
    /** On `launchers.json`: a launcher people add, never a TV's own. */
    val listedLauncher: Boolean = false,
    /** Another launcher that can replace the current home screen, [homeInstead]: Make Home is offered. */
    val homeInstead: String? = null,
    /** No default home and no launcher of the TV's own on: Make Home is offered with nothing to replace. */
    val homeFirst: Boolean = false,
    /** A launcher: it answers the Home button (an off app answers nothing). */
    val answersHome: Boolean = false,
    /** A launcher the TV came with, on or off: the Home screen card lists it, and it's the only kind a switch turns off. */
    val builtInLauncher: Boolean = false,
    /** On the home screen the user added: the name of the launcher going back from it returns to. */
    val homeBack: String? = null,
    /** A launcher that keeps Home with its accessibility service: the switch turns it on, the way back off. */
    val homeService: Boolean = false,
    /** The launcher on Home, with its accessibility service off: Home may still open the TV's own. The row offers Turn on. */
    val homeServiceOff: Boolean = false,
    /** [homeServiceOff], and the TV page's card about it hasn't been put away with Got it since the switch. */
    val homeServiceNote: Boolean = false,
    /** This TV's list says to keep it: Stop, Disable and Uninstall still run, after a warning. */
    val listKeep: Boolean = false,
    /** Has an icon on a launcher, so the Remote's dock can open it. */
    val launchable: Boolean = false,
    /** Has an icon on the TV's own home screen, not only a phone-style one. True when the TV can't say. */
    val tvHome: Boolean = true,
    /** Off, and the TV turned it off itself rather than the user: Android won't let ADB turn it back on. */
    val disabledByTv: Boolean = false,
)

/** Where an install from the Play Store stands, read every few seconds while the TV shows the Store. */
enum class StoreState { Installed, InStore, Elsewhere }

/** The app a Remote button opens. [label] is kept so it can be named after the app is gone. */
data class Docked(val packageName: String, val label: String)

data class Socket(val app: String, val remote: String, val state: String)
data class AppTraffic(val app: String, val rxMb: Int, val txMb: Int)

data class NetworkInfo(
    val iface: String,
    val ip: String,
    val mac: String?,
    val ssid: String?,
    val rssi: Int?,
    val linkMbps: Int?,
    val sockets: List<Socket>,
    val traffic: List<AppTraffic>,
)

/**
 * [Change] sets one [app.tellyscope.adb.Tweak]: its entry's packageName is the tweak's name and
 * [HistoryEntry.previous] holds what Undo writes back. [ClearCaches] has no undo. [Home] makes a
 * launcher the home screen and disables the old one: its entry's packageName is the old launcher,
 * the label the new one's, and [HistoryEntry.previous] holds both home activities ("from", "to").
 * Its undo is [Enable], which also makes the old launcher Home again. [HomeBack] makes the
 * launcher a switch replaced (or the TV's own) Home again, turning it on if it's off, and leaves
 * the added one on: its entry's packageName is the added launcher, the label the one now Home.
 * Its undo is [Home], which switches to the added launcher again. [Reset] is Reset TV, one entry
 * for all of it: [HistoryEntry.previous] holds what it changed ([ResetRecord]), and its undo, [Reset]
 * too, puts all of that back.
 */
enum class Action { Stop, Disable, Enable, Open, Uninstall, Change, ClearCaches, Home, HomeBack, Reset }

data class HistoryEntry(
    val id: Long,
    val deviceId: String,
    val at: Long,
    val action: Action,
    val packageName: String,
    val label: String,
    /** What the undo button runs; null when there is nothing to undo. */
    val undo: Action?,
    val undoneAt: Long?,
    /** For [Action.Change]: the settings as they were before, null for a key that wasn't set. */
    val previous: Map<String, String?>? = null,
)

/** The TV-wide settings shown on its page; null for one the TV doesn't report. */
data class TvTweaks(
    /** The Private DNS provider, when one is set and in use; null means Automatic or Off. */
    val dnsHost: String?,
    /** Window animation scale, e.g. "1.0"; null when unset (Android's 1×). */
    val animationScale: String?,
    /** Private DNS came in Android 9: older TVs accept the setting and ignore it. */
    val dnsSupported: Boolean = true,
)
