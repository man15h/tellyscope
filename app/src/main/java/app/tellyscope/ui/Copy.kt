// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import app.tellyscope.adb.InstallKind
import app.tellyscope.adb.InstallRefusal
import app.tellyscope.adb.TvFolder
import app.tellyscope.data.Action
import app.tellyscope.data.AppNames
import app.tellyscope.data.DeviceState
import app.tellyscope.data.Snapshot
import app.tellyscope.data.TvSettings
import app.tellyscope.data.sourceName
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * Every user-facing sentence about actions and time lives here so wording is
 * reviewed in one place: every button says what it does before it runs.
 */
object Copy {
    fun button(action: Action) = when (action) {
        Action.Stop -> "Stop"
        Action.Disable -> "Disable"
        Action.Enable -> "Turn on"
        Action.Open -> "Open"
        Action.Uninstall -> "Uninstall"
        Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack -> error("$action has its own button")
    }

    fun confirm(action: Action) = when (action) {
        Action.Stop -> "Yes, stop it"
        Action.Disable -> "Yes, disable it"
        Action.Enable -> "Yes, turn it on"
        Action.Open -> "Yes, open it"
        Action.Uninstall -> "Yes, uninstall it"
        Action.Home -> "Yes, switch home screen"
        Action.HomeBack -> "Yes, switch back"
        Action.Change, Action.ClearCaches -> error("$action isn't an app action")
    }

    /**
     * Names the package next to the label: the label comes from the app itself, and any app can
     * call itself "Settings". The package id is what actually gets changed.
     */
    fun explain(action: Action, app: String, pkg: String) = "$app ($pkg)".let { who ->
        when (action) {
            Action.Stop -> "Stops $who until you open it again."
            Action.Disable -> "Hides $who and stops it running. Nothing is deleted; you can turn it back on here."
            Action.Enable -> "Turns $who back on."
            Action.Open -> "Opens $who on the TV."
            Action.Uninstall -> "Removes $who and everything saved in it. This can't be undone; to get it back, install it again."
            Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack -> error("$action isn't a one-app action")
        }
    }

    fun done(action: Action, app: String) = when (action) {
        Action.Stop -> "Stopped $app"
        Action.Disable -> "Disabled $app"
        Action.Enable -> "Turned on $app"
        Action.Open -> "Opened $app"
        Action.Uninstall -> "Uninstalled $app"
        // A setting's label is the whole sentence: "Private DNS set to …".
        Action.Change -> app
        Action.ClearCaches -> "Cleared $app"
        Action.Home -> "Set $app as the default launcher"
        Action.HomeBack -> "Switched home back to $app"
    }

    /** Said in the confirm before Stop, Disable or Uninstall of an app the TV's list keeps. */
    const val KEEP_WARNING = "This TV's list says to keep this."

    fun homeBack(launcher: String) = "Switch home back to $launcher"

    /** The way back keeps the added launcher, so it can be made Home again or uninstalled. */
    fun explainHomeBack(added: String, back: String) =
        "Makes $back open when you press Home again, turning it back on if it's off. $added stays on. Undo in History makes $added Home again."

    /** Where the lists come from, for the Debloat screen and the snackbar after a change. */
    fun listSource(custom: String?) =
        if (custom == null) "Lists from tellyscope-devices, built from cleanyourtv" else "Lists from ${sourceName(custom)}"

    const val MAKE_HOME = "Set as default launcher"

    /**
     * The home switch touches two apps, so it names both. The old launcher is disabled, not just
     * replaced: otherwise many TVs switch back to it by themselves.
     */
    fun explainHome(app: String, pkg: String, current: String, currentPkg: String) = buildString {
        append("Makes $app ($pkg) open when you press Home, then disables $current ($currentPkg) so the TV can't switch back by itself. ")
        append("Undo in History turns $current back on and makes it Home again.")
        if (currentPkg in GOOGLE_HOMES) append("\n\nSome Google TV features, like the Apps row and the Live tab, are part of $current and go away with it.")
    }

    private val GOOGLE_HOMES = setOf("com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx")

    /**
     * Force-stop has no true undo, and undoing a Turn on disables the app; the button says what it
     * actually does.
     */
    fun undo(undo: Action) = when (undo) {
        Action.Open -> "Open again"
        Action.Disable -> button(Action.Disable)
        else -> "Undo"
    }

    fun ago(at: Long, now: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 10 -> "just now"
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    fun uptime(s: Long): String {
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    /** Uptime for people: the largest unit or two, spelled out. */
    fun upFor(s: Long): String {
        fun n(v: Long, unit: String) = "$v $unit${if (v == 1L) "" else "s"}"
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 && h > 0 -> "${n(d, "day")}, ${n(h, "hour")}"
            d > 0 -> n(d, "day")
            h > 0 -> n(h, "hour")
            else -> n(m, "minute")
        }
    }

    /** "May 2023", in the phone's language. */
    fun month(m: YearMonth): String = m.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

    /** Network speed in the same units as [bytes], so a download's size and speed line up. */
    fun speed(bytesPerSec: Long) = bytes(bytesPerSec) + "/s"

    fun mb(v: Int) = if (v >= 1024) "%.1f GB".format(v / 1024.0) else "$v MB"

    fun disabledSuggested(done: Int, of: Int) = when {
        done == of -> "Disabled $done ${if (done == 1) "app" else "apps"}. Undo any of them from History."
        done == 0 -> "The TV didn't disable any of them."
        else -> "Disabled $done of $of apps; the TV refused the rest. Undo any of them from History."
    }

    fun uninstallTitle(app: String) = "Uninstall $app?"

    /** The Apps tabs' header over the apps the TV needs, and why they're apart. */
    const val SYSTEM = "System"
    const val SYSTEM_WHY = "Needed by the TV · can't be changed"

    /** Running, with no word from the TV. */
    const val TV_OFFLINE = "TV offline"
    const val RUNNING_OFFLINE = "Running apps show here once the TV is connected."

    const val ANDROID_SYSTEM = AppNames.SYSTEM

    /** The word next to the status dot, so the state never rests on colour alone. */
    fun state(s: DeviceState) = when (s) {
        DeviceState.Online -> "Connected"
        DeviceState.Offline -> "Offline"
        DeviceState.NotAllowed -> "Not allowed"
        DeviceState.Waiting -> "Connecting"
        DeviceState.NeedsPairing -> "Needs the pairing code"
        DeviceState.Found -> "Not connected"
    }

    /** Under 10 GB a whole number hides too much: a 1.5 GB TV isn't a 2 GB one. */
    fun gbOf(used: Double, total: Double) =
        if (total < 10) "%.1f of %.1f GB".format(used, total) else "%.1f of %.0f GB".format(used, total)

    /** Free storage in plain words, rounded down so it never promises room that isn't there; null if unknown. */
    fun free(s: Snapshot): String? = free(s.storageUsedGb, s.storageTotalGb)

    /** [free] from the two numbers, for a TV's saved facts as well as a live reading. */
    fun free(used: Double?, total: Double?): String? {
        val gb = used?.let { u -> total?.let { t -> (t - u).coerceAtLeast(0.0) } } ?: return null
        return if (gb < 10) "%.1f GB free".format(Math.floor(gb * 10) / 10) else "${gb.toInt()} GB free"
    }

    /** An offline TV that has never sent stats; a refusing TV is on, it just hasn't allowed us. */
    fun noStats(s: DeviceState) =
        if (s == DeviceState.NotAllowed) "Stats show up once the TV allows this phone."
        else "No stats yet. They show up once the TV is back on."

    const val WRONG_CODE = "That code didn't work. Check the 6 digits on the TV and try again."
    const val UNREACHABLE = "The TV didn't answer. Check it's on and on the same Wi-Fi as this phone, then try again."
    const val PAIR_INSTEAD = "The TV didn't let this phone in yet. Pair it once with the code from the TV."

    data class Health(val ok: Boolean, val text: String)

    /**
     * The one line at the top of a TV's page: the most pressing problem, or that all is well.
     * [heaviest] are app names by memory use, largest first; there is no per-app storage size yet.
     */
    fun health(s: Snapshot, heaviest: List<String>): Health {
        val storage = s.storageUsedGb?.let { u -> s.storageTotalGb?.takeIf { it > 0 }?.let { u / it } }
        val ram = if (s.ramTotalMb > 0) s.ramUsedMb.toDouble() / s.ramTotalMb else null
        val most = heaviest.take(2).takeIf { it.isNotEmpty() }?.let { ": ${it.joinToString(" and ")} use the most" }.orEmpty()
        return when {
            storage != null && storage >= FULL -> Health(false, "Storage almost full")
            ram != null && ram >= FULL -> Health(false, "Memory almost full$most")
            (s.tempC ?: 0) >= HOT_C -> Health(false, "Running hot")
            else -> Health(true, "Running fine")
        }
    }

    const val FULL = 0.9
    private const val HOT_C = 80

    fun bytes(v: Long): String = when {
        v >= 1L shl 30 -> "%.1f GB".format(v / (1L shl 30).toDouble())
        v >= 1L shl 20 -> "%.1f MB".format(v / (1L shl 20).toDouble())
        v >= 1L shl 10 -> "${v shr 10} KB"
        else -> "$v bytes"
    }

    fun folder(f: TvFolder) = when (f) {
        TvFolder.DOWNLOAD -> "Download"
        TvFolder.MOVIES -> "Movies"
    }

    // Sideload

    fun installButton(kind: InstallKind) = when (kind) {
        InstallKind.NEW -> "Install"
        InstallKind.UPDATE -> "Update"
        InstallKind.REINSTALL -> "Reinstall"
        InstallKind.DOWNGRADE -> "Install older version"
    }

    fun installKind(kind: InstallKind, onTv: String?, picked: String?) = when (kind) {
        InstallKind.NEW -> "Not on this TV yet. This installs it."
        InstallKind.UPDATE -> "Updates the TV's copy" + versions(onTv, picked) + ". Its settings stay."
        InstallKind.REINSTALL -> "The TV already has this version" + (onTv?.let { " ($it)" } ?: "") + ". This installs it again over itself."
        InstallKind.DOWNGRADE -> "This is older than the one on the TV" +
            (if (onTv != null && picked != null) " ($picked here, $onTv on the TV)" else "") +
            ". Android normally refuses that, and the app's saved data may not work with the older version."
    }

    private fun versions(onTv: String?, picked: String?) =
        if (onTv != null && picked != null) " from $onTv to $picked" else ""

    const val ALLOW_DOWNGRADE = "Install the older version anyway"
    const val INSTALL_KEEP_OPEN = "Keep Tellyscope open until it's done."

    fun installed(kind: InstallKind, app: String) = when (kind) {
        InstallKind.UPDATE -> "Updated $app."
        InstallKind.REINSTALL -> "Reinstalled $app."
        InstallKind.NEW, InstallKind.DOWNGRADE -> "Installed $app."
    }

    fun installRefused(reason: InstallRefusal, app: String) = when (reason) {
        InstallRefusal.SIGNATURE ->
            "The $app on the TV comes from a different developer, so Android won't replace it. If you're sure, uninstall it first: from Apps here if you installed it, or on the TV."
        InstallRefusal.DOWNGRADE -> "The TV has a newer $app and won't go back to an older one."
        InstallRefusal.NO_SPACE -> "The TV is out of space. Disable or uninstall something there, then try again."
        InstallRefusal.TV_SAID_NO ->
            "The TV blocked the install. Look in the TV's security settings for installing apps from unknown sources, or for app verification, then try again."
        InstallRefusal.TV_TOO_OLD -> "$app needs a newer version of Android than this TV has."
        InstallRefusal.WRONG_CPU ->
            "This $app is built for a different kind of processor. Look for a version made for this TV, often marked armeabi-v7a or arm64-v8a."
        InstallRefusal.APP_TOO_OLD -> "$app was made for a very old version of Android, and this TV won't install it."
        InstallRefusal.MISSING_FEATURE -> "$app needs something this TV doesn't have, like a touchscreen or a camera."
        InstallRefusal.MISSING_PARTS ->
            "$app comes in several parts and one is missing. Pick all of its .apk files together, or its .apks bundle."
        InstallRefusal.TEST_ONLY -> "This is a developer test build of $app, which the TV won't install."
        InstallRefusal.CONFLICT -> "Another app on the TV already uses something $app needs, so the TV refused it."
        InstallRefusal.BROKEN -> "This file isn't a working app. It may be damaged or only half downloaded; try getting it again."
        InstallRefusal.UNKNOWN -> "The TV didn't install $app."
    }

    const val NOT_AN_APP = "That file isn't an Android app. Pick an .apk, several split .apk files, or an .apks or .xapk bundle."
    const val SIDELOAD_LOST = "Lost the TV partway through. Check it's still on and connected, then try again."

    // Files

    fun sent(name: String, savedAs: String, f: TvFolder) =
        if (savedAs == name) "Sent $name to ${folder(f)}" else "Sent to ${folder(f)} as $savedAs, since $name was already there"

    fun saved(name: String) = "Saved $name to your phone"
    fun sendFailed(name: String) = "Couldn't send $name. Check the TV is still connected and has space."
    fun saveFailed(name: String) = "Couldn't save $name. Check the TV is still connected and the phone has space."

    const val CANCELLED_SEND = "Cancelled. Nothing was left on the TV."
    const val CANCELLED_SAVE = "Cancelled. Nothing was saved."
    const val CANT_READ = "Couldn't read that file on your phone."
    const val FILES_LOST = "Couldn't read this folder. Check the TV is still connected."

    // Remote

    const val KEY_REFUSED = "The TV didn't take that key."
    const val TYPE_REFUSED = "The TV didn't take the text. Select a text box on the TV first, then send again."
    const val REMOTE_LOST = "Lost the TV. Check it's still on and connected."
    const val NO_UPDATE_SCREEN = "This TV has no update screen Tellyscope can open. Look in its Settings, under System or About."
    const val UPDATES_OPENED = "Opened the update screen on the TV. Look at the TV to check for updates."
    const val REBOOT_REFUSED = "The TV said no to restarting. Use its own remote or power button instead."
    const val REBOOT_IGNORED = "The TV didn't restart. Use its own remote or power button instead."
    const val TYPE_HINT = "Select a text box on the TV, like a search box, then type here."

    fun cantType(text: String): String {
        val odd = text.firstOrNull { it !in ' '..'~' }
        return when {
            text.isEmpty() -> "Type something first."
            text.length > app.tellyscope.adb.Remote.MAX_TEXT -> "That's too long to send in one go. Send up to ${app.tellyscope.adb.Remote.MAX_TEXT} characters at a time."
            odd == '\n' || odd == '\t' -> "The TV can only take one line at a time."
            odd != null -> "The TV can only type plain letters, numbers and symbols, not \u201c$odd\u201d."
            else -> "The TV reads \u201c%s\u201d as a space, so it can't type that."
        }
    }

    const val REBOOT_TITLE = "Restart the TV?"
    const val REBOOT_TEXT = "Whatever is playing stops, and the TV takes a minute or two to come back. Tellyscope reconnects on its own when it's back."

    fun restarting(minutes: Long) =
        if (minutes < 3) "Tellyscope reconnects on its own when the TV is back, usually within a couple of minutes."
        else "Taking a while. Some TVs turn network debugging off when they restart. If it doesn't come back, turn it on again in the TV's Developer options."

    const val TWEAKS = "TV tweaks"
    const val DNS_TITLE = "Block ads with Private DNS"
    fun dnsState(host: String?) = host?.let { "On: $it" } ?: "Off. Turn on to block ads and trackers in every app on the TV."
    const val DNS_TOO_OLD = "Needs Android 9 or newer. This TV is older."
    const val DNS_DIALOG = "Block ads on the whole TV?"
    const val DNS_TEXT = "The TV will look up every address through this ad-blocking DNS provider. AdGuard is free and needs no account. " +
        "Tellyscope then checks the TV can still look addresses up, and puts it back if not. Undo is in History."
    const val DNS_FIELD = "DNS provider"
    const val DNS_BAD = "Enter a hostname like dns.adguard-dns.com. An IP address won't work here."
    const val DNS_CONFIRM = "Turn on"
    const val ANIMATIONS_TITLE = "Faster animations"
    fun animationsState(scale: String?) =
        if (scale == TvSettings.FAST) "On: menus move at twice the speed"
        else "Off. Turn on to make menus feel snappier."
    const val CACHES_TITLE = "Clear all app caches"
    const val CACHES_TEXT = "Frees space by clearing every app's temporary files. App data and sign-ins stay."
    const val CACHES_BUTTON = "Clear caches"
    fun cachesCleared(freedKb: Long?) =
        if (freedKb == null || freedKb <= 0) "Cleared app caches" else "Cleared app caches, freed ${bytes(freedKb * 1024)}"
    const val SCREENSHOT_TITLE = "Screenshot"
    const val SCREENSHOT_TEXT = "Saves what the TV shows to your phone. Protected video, like most streaming apps, shows as black."
    const val SCREENSHOT_BUTTON = "Take screenshot"
    fun screenshotName(at: Long) =
        "TV screenshot " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date(at)) + ".png"
    const val SCREENSHOT_SAVED = "Screenshot saved to your phone"
    const val SCREENSHOT_FAILED = "Couldn't take a screenshot. Check the TV is still connected."
    const val REQUEST_TITLE = "Another device wants to control this TV"
    const val REQUEST_TEXT = "The TV is asking whether to allow debugging from a device with this key fingerprint. " +
        "Allow it only if you started this yourself and the fingerprint matches the one that device shows."
    const val REQUEST_ALWAYS = "Always allow this device"
    const val REQUEST_ALLOW = "Allow"
    const val REQUEST_DENY = "Deny"
    const val REQUEST_ALLOWED = "Allowed. That device can control the TV now."
    const val REQUEST_DENIED = "Denied. That device can't connect."
    const val REQUEST_GONE = "The TV's prompt had closed or changed, so nothing was pressed."
}
