// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import app.tellyscope.adb.InstallKind
import app.tellyscope.adb.InstallRefusal
import app.tellyscope.adb.Peripheral
import app.tellyscope.adb.TvFolder
import app.tellyscope.data.Action
import app.tellyscope.data.AppNames
import app.tellyscope.data.DeviceState
import app.tellyscope.data.ResetPart
import app.tellyscope.data.ResetProgress
import app.tellyscope.data.Snapshot
import app.tellyscope.data.TvSettings
import app.tellyscope.data.sourceName
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
        Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack, Action.Reset -> error("$action has its own button")
    }

    fun confirm(action: Action) = when (action) {
        Action.Stop -> "Yes, stop it"
        Action.Disable -> "Yes, disable it"
        Action.Enable -> "Yes, turn it on"
        Action.Open -> "Yes, open it"
        Action.Uninstall -> "Yes, uninstall it"
        Action.Home -> "Yes, switch home screen"
        Action.HomeBack -> "Yes, switch back"
        Action.Change, Action.ClearCaches, Action.Reset -> error("$action isn't an app action")
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
            Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack, Action.Reset -> error("$action isn't a one-app action")
        }
    }

    fun done(action: Action, app: String) = when (action) {
        Action.Stop -> "Stopped $app"
        Action.Disable -> "Disabled $app"
        Action.Enable -> "Turned on $app"
        Action.Open -> "Opened $app"
        Action.Uninstall -> "Uninstalled $app"
        // A setting's label is the whole sentence: "Private DNS set to …", as is a reset's.
        Action.Change, Action.Reset -> app
        Action.ClearCaches -> "Cleared $app"
        Action.Home -> "Made $app Home"
        Action.HomeBack -> "Switched home back to $app"
    }

    /** Said in the confirm before Stop, Disable or Uninstall of an app the TV's list keeps. */
    const val KEEP_WARNING = "This TV's list says to keep this."

    /** Where the lists come from, for the Debloat screen and the snackbar after a change. */
    fun listSource(custom: String?) =
        if (custom == null) "Lists from tellyscope-devices, built from cleanyourtv" else "Lists from ${sourceName(custom)}"

    /** The Home screen card at the top of Tweaks: every launcher on the TV, and which one Home opens. */
    const val HOME_SCREEN = "Home screen"

    fun opensOnHome(app: String) = "$app opens when you press Home."

    const val NO_DEFAULT = "No default: pick one below."
    const val ADDED = "Added"

    fun builtIn(off: Boolean) = "Built into the TV" + if (off) " · turned off" else ""

    const val CURRENT = "Current"
    const val KEEPS_HOME = "Keeps Home with its accessibility service"
    const val MAKE_HOME = "Make Home"
    const val GET_LAUNCHER = "Get another launcher"
    const val GET_LAUNCHER_TEXT = "Install one from the Play Store, like Projectivy or FLauncher. Switch back any time."
    const val GET_PROJECTIVY_TEXT = "Install Projectivy. Switch back any time."

    /** The Apps tab's launcher rows: switching lives in the card, this opens it. */
    const val HOME_SETTINGS = "Home screen settings"

    fun makeHomeTitle(app: String) = "Make $app the home screen?"

    /** A launcher the user added in place of the TV's own, which is turned off: otherwise many TVs switch back by themselves. */
    fun explainReplace(app: String, current: String, currentPkg: String, service: Boolean) = buildString {
        append("Home opens $app. To stop the TV switching back by itself, Tellyscope turns off $current, the TV's built-in launcher.")
        append("\n\nThat's the only launcher it ever turns off. Launchers you added stay on, so you can switch between them any time.")
        append("\n\nUndo in History turns $current back on.")
        if (currentPkg in GOOGLE_HOMES) append("\n\nSome Google TV features, like the Apps row and the Live tab, are part of $current and go away with it.")
        if (service) append("\n\nNext, you choose whether to turn on its accessibility service.")
    }

    /**
     * Away from [current], a launcher the user added: it stays on, only its accessibility service
     * goes off when it has one on, since that would take Home straight back. [off]: [app] is the
     * TV's own launcher, turned off by an earlier switch.
     */
    fun explainLeave(app: String, off: Boolean, current: String, currentService: Boolean, service: Boolean) = buildString {
        append(if (off) "Tellyscope turns $app back on and makes it open when you press Home." else "Tellyscope makes $app open when you press Home.")
        append("\n\n")
        if (currentService) append("It also turns off $current's accessibility service, which would otherwise take Home straight back. ")
        append("$current stays installed and on, so you can make it Home again any time.")
        append("\n\nUndo in History makes $current Home again")
        append(if (currentService) " and turns its service back on." else ".")
        if (service) append("\n\nNext, you choose whether to turn on its accessibility service.")
    }

    /**
     * The home switch touches two apps, so it names both. The old launcher is disabled, not just
     * replaced: otherwise many TVs switch back to it by themselves.
     */
    fun explainHome(app: String, pkg: String, current: String, currentPkg: String, service: Boolean = false) = buildString {
        append("Makes $app ($pkg) open when you press Home, then disables $current ($currentPkg) so the TV can't switch back by itself. ")
        append("Undo in History turns $current back on and makes it Home again.")
        if (currentPkg in GOOGLE_HOMES) append("\n\nSome Google TV features, like the Apps row and the Live tab, are part of $current and go away with it.")
        if (service) append("\n\nNext, you choose whether to turn on its accessibility service.")
    }

    /** Fire OS: Amazon's launcher can't be turned off or replaced, so [app]'s accessibility service holds Home instead. */
    fun explainFireHome(app: String, pkg: String, current: String) =
        "Makes Home open $app ($pkg) through its accessibility service. Fire OS won't let $current be turned off or replaced, " +
            "so it stays on and nothing else changes. Undo in History turns the service off.\n\nNext, you choose whether to turn on its accessibility service."

    /** No default home and no launcher of the TV's own on: nothing is turned off, so Undo can only turn its service off. */
    fun explainFirstHome(app: String, pkg: String, service: Boolean = false) = buildString {
        append("Makes $app ($pkg) open when you press Home. The TV has no default home screen now, and nothing else is turned off.")
        if (service) append("\n\nNext, you choose whether to turn on its accessibility service.")
    }

    /** The separate yes for a launcher's accessibility service: what it does, what it can see, how it goes. */
    fun serviceTitle(app: String) = "Turn on $app's accessibility service?"

    fun explainService(app: String, other: String) =
        "It keeps the Home button opening $app. Without it, this TV's Home button may still open $other.\n\n" +
            "An accessibility service can see what's on screen. Turn it on only if you trust $app.\n\n" +
            "Undo in History, or making another launcher Home, turns it off."

    const val TURN_IT_ON = "Turn it on"
    const val SWITCH_WITHOUT = "Switch without it"
    const val NOT_NOW = "Not now"
    const val TURN_ON = "Turn on"
    const val GOT_IT = "Got it"
    const val SERVICE_OFF = "Accessibility service off"

    /** The TV page's card while the launcher on Home has its service off; [other] is the launcher Home may still open. */
    fun serviceOffTitle(other: String?) = "Home may open ${other ?: TV_OWN_LAUNCHER}"

    const val TV_OWN_LAUNCHER = "the TV's own launcher"

    /** Install a home screen: the Apps tab's card, the picker, the wait for the Store, and its dead ends. */
    const val BETTER_HOME = "Get a better home screen"

    /** [fire]: Fire OS, where there's no Play Store and Tellyscope installs it. */
    fun betterHomeText(current: String?, fire: Boolean = false) =
        "Swap ${current ?: "the TV's home screen"} for a launcher without ads" +
            if (fire) "; switch back any time." else ". Free on the Play Store; switch back any time."

    const val CHOOSE_ONE = "Choose one"
    const val INSTALL_ONE = "Install it"
    const val CHOOSE_HOME = "Choose a home screen"
    const val CHOOSE_HOME_TEXT = "Each one replaces the TV's home screen. Tellyscope opens it in the Play Store on the TV; you install it with the remote."
    /** After [CHOOSE_HOME_TEXT] when Download directly is offered. */
    const val CAN_DOWNLOAD = "Some can also be downloaded directly."
    const val NO_LAUNCHER_LIST = "Couldn't load the list of home screens. Check the phone's internet and try again."
    const val RECOMMENDED = "Recommended"
    /** The picker's section for launchers on the TV that Recommended doesn't list. */
    const val ON_THIS_TV = "On this TV"
    const val INSTALLED = "Installed"
    const val HOME_NOW = "Home now"
    /** Shows a launcher on the TV, from the picker. */
    const val OPEN = "Open"
    const val USES_SERVICE = "Uses an accessibility service"
    const val NO_SERVICE = "Open source · no accessibility service"
    /** Under the picker's list: any other launcher works too, and comes back as On this TV. */
    const val OTHER_LAUNCHER = "Prefer another launcher? Install it from the Play Store on your TV, then come back here. It shows up under $ON_THIS_TV."
    const val NOT_AFFILIATED = "Tellyscope isn't affiliated with any of these launchers or their developers."
    const val GET_FROM_STORE = "Get it from Play Store"
    const val STORE_RECOMMENDED = "Play Store recommended: it updates itself"
    const val DOWNLOAD_DIRECTLY = "Download directly"
    const val INSTALL_HOME = "Install a home screen"
    const val INSTALL_IT = "Install it on your TV"

    fun installItText(name: String) =
        "The Play Store is open on the TV, on $name's page. Use the remote to select Install. Tellyscope moves on by itself once it's installed."

    fun storeOn(tv: String) = "Play Store on $tv"

    const val WAITING_INSTALL = "Waiting for install…"
    const val DOWNLOADS_TAKE = "Downloads can take a minute. Tellyscope can't see the download, only when it's done."
    const val OPEN_STORE_AGAIN = "Open Play Store again"
    const val MAKE_IT_HOME = "Make it the home screen?"

    fun isInstalled(name: String) = "$name is installed."

    fun launcherLater(name: String) = "$name is installed. To use it later, choose $MAKE_HOME on it in Tweaks, under $HOME_SCREEN."

    fun launcherInstalled(name: String) = "$name is installed. Press Home on the remote to pick it."

    fun launcherIsHome(name: String) = "$name is already your home screen."

    const val NO_STORE = "The Play Store didn't open"

    fun noStoreText(tv: String, name: String) =
        "$tv didn't open the Play Store for Tellyscope. On the TV, open the Play Store yourself and search for $name. " +
            "Tellyscope keeps watching and moves on once it's installed."

    const val CHOOSE_ANOTHER = "Choose another"

    fun notInstalled(name: String) = "$name isn't installed"
    /** The Store closed without an install: back on the picker, still chosen. */
    fun wasntInstalled(name: String) = "$name wasn't installed"

    /** Install Projectivy on Fire OS: Tellyscope downloads it from its developer's GitHub and installs it over adb. */
    const val INSTALL_PROJECTIVY = "Install Projectivy"
    const val FIRE_INSTALL_TEXT = "A home screen without ads. Tellyscope installs it on the TV for you; there's nothing to do on the TV."

    fun downloadsFrom(name: String) = "Tellyscope downloads $name from the developer's own release page:"

    fun release(version: String, size: Long) = "Release $version · ${bytes(size)}"

    const val WHERE_FROM = "Where it comes from"
    const val SIGNED_CHECK =
        "Tellyscope checks it's signed by Projectivy's developer before installing it on the TV. Tellyscope isn't affiliated with Projectivy."
    const val DOWNLOAD_AND_INSTALL = "Download and install"

    /** Download directly on a TV with the Play Store: what Tellyscope checks, what it doesn't, and the safer way. */
    fun downloadDirectly(name: String) = "Download $name directly?"

    const val DIRECT_CHECK =
        "Tellyscope checks the app carries the developer's signature. It does not scan the app for malicious code, and the app won't update on its own."
    const val STORE_SAFER = "Getting it from Play Store is safer and keeps it updated."
    const val DOWNLOAD_ANYWAY = "Download anyway"
    const val STORE_INSTEAD = "Get it from the Play Store on the TV instead."

    /** Install Projectivy's steps, one panel: download (or the picked file), the check, the install. */
    fun installing(name: String) = "Installing $name"
    const val STEP_DOWNLOAD = "Download from GitHub"
    const val STEP_DOWNLOADED = "Downloaded"
    fun downloading(percent: Int, done: Long, total: Long) = "Downloading · $percent% · ${bytes(done)} of ${bytes(total)}"
    const val STEP_PICKED = "Picked a file"
    const val STEP_CHECK = "Check it's Projectivy"
    const val STEP_CHECKING = "Checking it's Projectivy…"
    const val STEP_CHECKED = "It's Projectivy"
    const val STEP_CHECKED_TEXT = "Signed by its developer"
    const val STEP_FAILED = "Not Projectivy"
    const val STEP_FAILED_TEXT = "Package name or signature didn't match"
    fun installOn(tv: String) = "Install on $tv"

    fun installingOn(tv: String) = "Installing on $tv…"

    /** X while it downloads: nothing is on the TV yet. */
    const val STOP_TITLE = "Stop the download?"
    const val STOP_TEXT = "Nothing is on the TV yet. You can start again any time."
    const val STOP = "Stop"
    const val KEEP_DOWNLOADING = "Keep downloading"

    const val NOT_PROJECTIVY = "That download isn't Projectivy"
    const val NOT_PROJECTIVY_TEXT =
        "The file from GitHub didn't match Projectivy's package name or its developer's signature, so Tellyscope installed nothing on the TV."
    const val NOT_PROJECTIVY_FILE = "That file isn't Projectivy"
    const val NOT_PROJECTIVY_FILE_TEXT =
        "It didn't match Projectivy's package name or its developer's signature, so Tellyscope installed nothing on the TV."
    const val CANT_READ_FILE = "Tellyscope couldn't read that file. Pick it again, or download it again."
    fun cantInstall(name: String) = "Couldn't install $name"

    /** Couldn't download: GitHub's limit for the network ([until] is a clock time), or no way to reach it. */
    const val NO_DOWNLOAD = "Couldn't download Projectivy"
    const val NO_DOWNLOAD_OFFLINE = "The phone can't reach GitHub. Check its internet, then try again."
    fun noDownloadLimited(until: String?) =
        "GitHub is limiting downloads from your network ${if (until != null) "until $until" else "for a while"}. Try again then, or download it yourself."
    const val OR_YOURSELF = "Or download it yourself"
    const val YOURSELF_PAGE = "Download the app file (.apk) from the release page in your browser."
    fun yourselfPick(file: String?) = "Come back and pick ${file ?: "it"}. Tellyscope checks it's Projectivy before installing it."
    const val LATEST_RELEASE = "Latest release"
    const val PICK_DOWNLOADED = "Pick the downloaded file"
    const val PICK_ANOTHER = "Pick another file"
    const val TRY_DOWNLOAD_AGAIN = "Try download again"

    const val NOT_INSTALLED_TEXT =
        "The Play Store closed on the TV before it was installed. If the Store said it isn't available for this TV, choose another launcher."

    /** The Tweaks row that reopens it after Not now on the card. */

    fun serviceOffText(app: String) = "$app's accessibility service is off, so the TV can take Home back."

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

    fun uninstallTitle(app: String) = "Uninstall $app?"

    /** The confirm for the launcher on Home: Home goes back first, and a failed step stops it. */
    fun uninstallHome(app: String, back: String, service: Boolean) = listOfNotNull(
        "$app is your home screen. Before removing it, Tellyscope:",
        "1. Makes $back the home screen again, turning it back on if it's off.",
        "2. Turns off $app's accessibility service.".takeIf { service },
        "${if (service) 3 else 2}. Uninstalls it, with everything saved in it. This can't be undone.",
        "If a step fails, Tellyscope stops there, says which one, and doesn't uninstall.",
    ).joinToString("\n\n")

    /** The Apps tabs' header over the apps the TV needs, and why they're apart. */
    const val SYSTEM = "System"
    const val SYSTEM_WHY = "Needed by the TV · can't be changed"

    /** Debloat with nothing left on the list: what's done, what's next, then Apps. */
    const val GATE_TITLE = "Everything on the list is off"
    const val GATE_DONE = "All the apps the cleanyourtv list suggests for this TV are already off."
    const val GATE_LAUNCHER = "To trim it further, switch to a minimal home screen. It replaces the TV's own, with its ads and suggestions."
    const val GATE_APPS = "You can still choose, app by app, what to keep or turn off in Apps."
    const val GO_TO_APPS = "Go to Apps"
    const val DONT_SHOW_AGAIN = "Don't show again"

    /** Apps, while the TV's app list is being read. */
    const val READING_APPS = "Reading the TV's apps…"
    const val READING_APPS_LINE = "This takes a few seconds."

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
    const val LOGGED_OUT = "You were logged out"
    const val LOGGED_OUT_WHY =
        "The TV removed this phone's access. Tellyscope disconnected and stopped asking, so nothing pops up on the TV until you connect again."
    val LOGGED_OUT_CAUSES = listOf(
        "Revoke USB debugging authorisations was chosen on the TV.",
        "Remove all other access was used from another phone, or from another Tellyscope build on this phone.",
    )
    fun loggedOutOf(name: String) = "Logged out of $name"

    /** When the TV took the access back, on the logged-out page. */
    fun loggedOutAt(at: Long, now: Long): String {
        // Calendar days, so 23:00 reads "yesterday" the next morning.
        val zone = ZoneId.systemDefault()
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        return when {
            now - at < 5 * 60_000 -> "Logged out just now"
            days == 0L -> "Logged out today, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
            days == 1L -> "Logged out yesterday"
            else -> "Logged out $days days ago"
        }
    }

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
    const val CANT_UNDO = "This version of Tellyscope can't undo that setting."
    const val FILES_LOST = "Couldn't read this folder. Check the TV is still connected."

    // Remote

    const val KEY_REFUSED = "The TV didn't take that key."
    const val TYPE_REFUSED = "The TV didn't take the text. Select a text box on the TV first, then send again."
    const val REMOTE_LOST = "Lost the TV. Check it's still on and connected."
    const val NO_UPDATE_SCREEN = "This TV has no update screen Tellyscope can open. Look in its Settings, under System or About."
    const val UPDATES_OPENED = "Opened the update screen on the TV. Look at the TV to check for updates."
    const val REBOOT_REFUSED = "The TV said no to restarting. Use its own remote or power button instead."
    const val REBOOT_IGNORED = "The TV didn't restart. Use its own remote or power button instead."
    const val REMOTE_OFFLINE_WAKE = "works again once it reconnects"
    const val TYPE_HINT = "Select a text box on the TV, like a search box, then type here."
    const val BACKSPACE = "Delete the last letter you typed on the TV"
    const val SCREEN_OFF = "Screen off"
    const val SCREEN_ON = "Screen on"
    const val SCREEN_OFF_WAKE = "tap any key to wake"
    const val SCREEN_OFF_TIP = "Turns off the TV's picture. The app stays connected; any key wakes it."
    const val SCREEN_OFF_REFUSED = "This TV can't turn its picture off from Tellyscope."
    const val SCREEN_ON_REFUSED = "The TV's picture didn't come back on. Try again, or restart the TV."

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

    const val REMOTE_RESTART = "Restart TV"
    const val REBOOT_TITLE = "Restart the TV?"
    const val REBOOT_TEXT = "The TV turns off and on again. Tellyscope reconnects by itself when it's back, usually within a minute."
    const val RESTARTING_TITLE = "Restarting…"
    const val RESTARTING_TEXT = "The TV is restarting. Tellyscope will reconnect by itself."
    const val RESTART_LATE_TITLE = "The TV hasn't come back yet"
    const val RESTART_LATE_STATUS = "Not back yet"
    const val RESTART_LATE_TEXT = "It's been a minute. Some TVs take longer to start; Retry waits another minute."
    const val RESTART_LOST_TEXT = "Use the TV's own remote to turn it on, then tap Retry."

    fun waitingFor(name: String, ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "Waiting for $name · ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun restartLost(name: String) = "Still can't reach $name"
    fun backOnline(name: String) = "Back online. $name reconnected."

    // Remote buttons

    const val REMOTE_BUTTONS = "Remote buttons"
    const val MORE = "More options"

    fun buttonsHint(cap: Int) = "Pick up to $cap. Drag to set the order. To pick another, untick one first."
    fun notOnTv(name: String) = "$name isn't on this TV. Install it from the Play Store on the TV."
    fun cantOpen(label: String) = "The TV couldn't open $label. If it's turned off, turn it on in Apps."

    fun leftDock(labels: List<String>) =
        if (labels.size == 1) "${labels[0]} was uninstalled, so its button left the Remote."
        else "${labels.size} apps were uninstalled, so their buttons left the Remote."

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
    /** A prompt under Fire OS's remote alert: it can't be read until the alert closes. */
    const val REQUEST_BEHIND_ALERT = "The TV's prompt is behind an Amazon alert, “Unable to Detect Your Remote”. Press OK on your TV remote to close it."
    const val REQUEST_THEN = "Then the prompt shows here, so you can check its fingerprint."
    const val REQUEST_WAITING = "Waiting for the TV's prompt"
    const val PRESS_OK = "Press OK on the TV"

    // Who has access
    const val WARNING_TITLE = "Another computer is connected to this TV"
    const val WARNING_TEXT = "It can do everything Tellyscope can: install or remove apps, read files and control the screen."
    const val ITS_MINE = "It's mine"
    const val REMOVE_ACCESS = "Remove all other access…"
    const val CONNECTED_NOW = "Connected now"
    const val WHO_HAS_ACCESS = "Who has access"

    /** The TV menu's rows for the screens it opens. */
    const val HISTORY = "History"
    const val ADVANCED_MONITOR = "Advanced monitor"

    /** In the TV menu and in Settings: opens Android's share sheet with the repo link. */
    const val SHARE = "Share Tellyscope"

    /** Reset TV: its row in the TV menu, its screen, and what it says before, during and after. */
    const val RESET_TV = "Reset TV to how it shipped"
    const val RESET_KEEPS = "Keeps apps' data and sign-ins"

    fun apps(n: Int) = if (n == 1) "1 app" else "$n apps"

    fun resetFound(n: Int) = "$n ${if (n == 1) "change" else "changes"} since this TV left the factory"
    const val RESET_FOUND_TEXT = "Your apps' data and sign-ins stay. Nothing has changed yet."
    fun turnOnApps(n: Int) = "Turn on ${apps(n)}"
    const val TURN_ON_TEXT = "Preinstalled apps turned off by hand, from any phone or tool"
    fun bringBackApps(n: Int) = "Bring back ${apps(n)}"
    const val BRING_BACK_TEXT = "Preinstalled apps that were uninstalled. They're still on the TV."
    fun resetHome(to: String) = "Make $to the home screen"
    fun resetHomeText(from: String, service: Boolean) =
        "Instead of $from" + (if (service) ", with its accessibility service turned off" else "") + ". $from stays installed."

    /** Under what Reset found: what it leaves alone. [maker] is the TV's brand, when known. */
    fun resetLeaves(shippedOff: Int, maker: String?) =
        (if (shippedOff > 0) "${apps(shippedOff)} ${maker ?: "the maker"} shipped switched off stay off. " else "") +
            "Apps and launchers you installed stay installed."

    const val NOTHING_TO_RESET = "Nothing to reset"
    fun nothingToReset(ownHome: String?, maker: String?) =
        "Every preinstalled app is on" + (ownHome?.let { ", and $it is the home screen" } ?: "") + ". " +
            "Apps ${maker ?: "the maker"} shipped switched off stay off."

    fun resetting(p: ResetProgress, home: String?) = when (p.part) {
        ResetPart.TurnOn -> "Turning on apps: ${p.done} of ${p.of}."
        ResetPart.BringBack -> "Bringing back apps: ${p.done} of ${p.of}."
        ResetPart.Home -> "Making ${home ?: "the TV's own launcher"} the home screen."
    } + " Keep the TV on and connected."
    const val RESETTING = "Resetting…"
    const val RESET_APPS_STALE = "Reset done, but the app list didn't refresh. Open the TV again to see it."
    const val RESET_BUTTON = "Reset TV"

    const val RESET_DONE = "TV reset to how it shipped"
    const val RESET_PARTLY = "Reset partly done"
    fun resetDoneText(apps: Int, home: String?) = listOfNotNull(
        when (apps) {
            0 -> null
            1 -> "1 preinstalled app is back"
            else -> "$apps preinstalled apps are back"
        },
        home?.let { "$it is the home screen" },
    ).joinToString(", and ") + ". Apps' data and sign-ins stayed."
    fun resetPartlyText(failed: Int) =
        "${if (failed == 1) "One step" else "$failed steps"} didn't work. Everything else is back to how it shipped. Nothing was lost."
    fun turnedOn(done: Int, of: Int) = if (done == of) "Turned on ${apps(of)}" else "Turned on $done of ${apps(of)}"
    fun broughtBack(done: Int, of: Int) = if (done == of) "Brought back ${apps(of)}" else "Brought back $done of ${apps(of)}"
    fun isHome(app: String) = "$app is the home screen"
    fun stillHome(app: String) = "$app is still the home screen"
    fun tryAgain(app: String?) = if (app != null) "Try $app again" else "Try again"

    /** Debloat while it turns the ticked apps off, then its result. */
    fun disablingApps(done: Int, of: Int) = "Turning off apps: $done of $of. Keep the TV on and connected."
    const val DISABLING = "Disabling…"
    const val DISABLING_NOTE = "Nothing is deleted. Turn any back on from History."
    const val DEBLOAT_DONE = "Debloat done"
    const val DEBLOAT_PARTLY = "Debloat partly done"
    const val DEBLOAT_RESULT_TEXT = "Nothing was deleted; undo any of them from History."
    fun turnedOff(done: Int, of: Int) = if (done == of) "Turned off ${apps(of)}" else "Turned off $done of ${apps(of)}"
    fun stillOn(app: String) = "$app is still on"

    /** Under History in the menu, and on top of the History screen: apps Restore everything turns back on. */
    fun stillOff(n: Int) = if (n == 1) "1 app still off" else "$n apps still off"
    fun stillOffTitle(n: Int) = if (n == 1) "1 app Tellyscope turned off is still off" else "$n apps Tellyscope turned off are still off"
    const val STILL_OFF_TEXT = "Restore everything turns them back on. Apps that were off before Tellyscope's first change stay off."
    fun othersConnected(n: Int) = if (n == 1) "1 other connected now" else "$n others connected now"
    fun connections(n: Int) = if (n == 1) "1 connection" else "$n connections"
    fun connectedFrom(address: String, n: Int) = "From $address · ${connections(n)}"
    const val ON_TV = "An app on this TV"
    const val ACCESS_INTRO = "Each computer allowed to use ADB can install or remove apps, read files and control the screen."
    const val THIS_PHONE = "This phone"
    const val TELLYSCOPE = "Tellyscope"
    const val NOT_THIS_PHONE = "Not this phone"
    const val YOURS = "Yours"
    const val WAITING_CHIP = "Waiting"
    const val WAITING_LINE = "Waiting for you to allow it on the TV"
    const val WAITING_TEXT = "An Allow prompt for it is on the TV now. It can't do anything until you choose Allow there. Not yours? Choose Deny."
    const val ANSWER = "Answer"
    const val NEW_CHIP = "New"
    const val NEW_KEY_TEXT = "Allowed on the TV while Tellyscope wasn't watching. Not yours? Remove all other access."
    const val ALLOWED_COMPUTERS = "Allowed computers"
    const val UNNAMED_COMPUTER = "Unnamed computer"
    const val CANT_CHECK_CONNECTED = "This TV doesn't say who is connected."
    const val CANT_CHECK_ALLOWED = "This TV doesn't list its allowed computers."
    const val NOBODY_ALLOWED = "No computer is allowed. The next one to connect has to be allowed on the TV."
    const val ACCESS_FOOT_LINK = "The TV doesn't say which allowed computer is behind a connection. If you don't recognise an address, remove all other access."
    const val ACCESS_FOOT_ALL = "Android can't remove one computer on its own: this clears every computer, then you allow Tellyscope again."
    /** Rough on purpose: the TV stamps a key when any connection uses it, not per session. */
    fun lastSeen(at: Long?, now: Long): String {
        if (at == null) return "Never seen"
        val days = ((now - at) / 86_400_000).coerceAtLeast(0)
        return when {
            now - at < 5 * 60_000 -> "Last seen just now"
            days == 0L -> "Last seen today, " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
            days == 1L -> "Last seen yesterday"
            days < 30 -> "Last seen $days days ago"
            else -> "Last seen over a month ago"
        }
    }
    /** Enough of a fingerprint to tell keys apart: the first three and last two pairs. */
    fun shortFingerprint(fp: String): String {
        val parts = fp.split(':')
        return if (parts.size <= 5) fp else (parts.take(3) + "…" + parts.takeLast(2)).joinToString(":")
    }

    // Remove all other access
    const val REMOVE_TITLE = "Remove all other access"
    const val CONFIRM_TITLE = "Remove all other access?"
    const val CONFIRM_TEXT = "Every computer this TV has allowed loses access, your own computers too: they'll need allowing again."
    const val CONFIRM_REMOTE = "Tellyscope reconnects straight away. Keep the TV remote handy: the TV asks you to allow this phone."
    const val CONFIRM_BUTTON = "Remove access"
    const val CANCEL = "Cancel"
    const val REVOKING = "Removing access…"
    const val ALLOW_TITLE = "Allow this phone on the TV"
    const val ALLOW_TEXT_A = "Other access is removed. The TV now asks “Allow USB debugging?”. Tick "
    const val ALLOW_ALWAYS = "Always allow from this computer"
    const val ALLOW_TEXT_B = ", then choose "
    const val ALLOW_BUTTON = "Allow"
    const val FINGERPRINT_LABEL = "The TV should show this fingerprint"
    const val FINGERPRINT_HINT = "If it shows a different one, choose Cancel on the TV."
    const val STOP_WAITING = "Stop waiting"
    const val REFUSED = "The TV didn't allow this phone. It may have timed out, or Cancel was chosen."
    const val ASK_AGAIN = "Ask the TV again"
    const val CHECKING = "Checking the TV…"
    const val DONE_TITLE = "Only this phone has access"
    const val DONE_TEXT = "Tellyscope checked the TV again: this phone is the only allowed computer, and nothing else is connected."
    const val TRUST_WARNING = "Only allow computers and apps you trust. Anything you allow can install apps, read files and control the TV."
    fun backTo(tv: String) = "Back to $tv"
    const val STILL_TITLE = "Another computer is still connected"
    fun stillText(who: String, tv: String) =
        "Tellyscope still sees $who on $tv. It may have connected again straight away. Turn it off or unplug it from the network, then try again."
    fun stillOpenText(who: String, tv: String) =
        "Tellyscope still sees $who on $tv. Its access is gone, but the TV keeps a connection open until it ends. Restart the TV to end it."
    const val CHECK_AGAIN = "Check again"
    const val RESTART_TV = "Restart the TV"
    const val RESTARTING_TO_CHECK = "Restarting the TV…"
    fun connectionFrom(address: String) = "a connection from $address"
    const val TRY_AGAIN = "Try again"
    const val UNCONFIRMED_TITLE = "Revoked on the TV"
    const val UNCONFIRMED_TEXT = "Tellyscope can't check the list on this TV. Look under Developer options to confirm."
    const val DONE = "Done"
    const val MANUAL_INTRO = "This TV didn't take Tellyscope's request, so it's two steps on the TV. Afterwards only this phone can use ADB on it; your own computers will need allowing again."
    const val MANUAL_STEP_1 = "Open Developer options on the TV"
    const val MANUAL_STEP_1_TEXT = "Tellyscope opens it for you. If nothing happens on the TV, go to Settings → Device Preferences → Developer options."
    const val OPEN_ON_TV = "Open on TV"
    const val MANUAL_STEP_2 = "Choose “Revoke USB debugging authorisations”"
    const val MANUAL_STEP_2_TEXT = "Then come back here, and Tellyscope asks the TV to allow this phone again."
    const val MANUAL_DONE = "I've revoked it"
    const val DEV_OPTIONS_FAILED = "Couldn't open Developer options on the TV. Go to Settings → Device Preferences → Developer options."

    // Connected devices
    const val DEVICES_TITLE = "Connected devices"
    fun showAll(n: Int) = "Show all ($n)"
    const val SHOW_LESS = "Show less"
    const val NO_DEVICES = "Nothing plugged in or paired."
    fun group(g: Peripheral.Group) = when (g) {
        Peripheral.Group.Bluetooth -> "Bluetooth"
        Peripheral.Group.Input -> "Input"
        Peripheral.Group.Storage -> "USB storage"
        Peripheral.Group.Hdmi -> "HDMI-CEC"
        Peripheral.Group.Audio -> "Audio output"
    }
    /** The row's right-hand words. Collapsed rows have no group label, so Bluetooth says so there. */
    fun peripheralStatus(p: Peripheral, grouped: Boolean): String {
        val status = p.free?.let { "${bytes(it).replace(".0 ", " ")} free" } ?: p.status
        return if (!grouped && p.group == Peripheral.Group.Bluetooth) "Bluetooth · ${status.lowercase()}" else status
    }
}
