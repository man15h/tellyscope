// SPDX-License-Identifier: GPL-3.0-only
// CORE and CORE_OK follow cleanyourtv's tools/disable.sh (MIT, Copyright (c) 2026 Gathin), see NOTICE.
package app.tellyscope.adb

/** Why a package may not be stopped or disabled. The UI shows the matching copy. */
enum class ProtectedReason {
    HOME_SCREEN, SYSTEM, SETTINGS, PLAY_SERVICES, KEYBOARD, TV_INPUTS_REMOTE, SETUP_WIZARD, UPDATES,

    /** The TV didn't say which app is its home screen, so nothing is stopped or disabled. */
    UNKNOWN_HOME,

    /** Not installed by the user, so it can't be uninstalled; it can still be disabled. */
    CAME_WITH_TV,
}

/**
 * Packages Tellyscope never stops or disables. Over-matching is fine: a protected package just
 * stays on. Sources: cleanyourtv's core-package rule (a Mi TV Stick never booted again without
 * these, cleanyourtv#884), its "never disable what the TV needs" list (inputs/tuner, source menu,
 * remote and Bluetooth, keyboard, Play), and the package list of an Android TV 11 XGIMI projector.
 */
object Protected {
    /** Android can stop booting without these. */
    private val CORE = Regex(
        "android|com\\.android\\.systemui|com\\.android\\.shell|com\\.android\\.phone|" +
            "com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\.services|ext\\.shared|modulemetadata)|" +
            "com\\.google\\.android\\.overlay\\.modules.*|com\\.(google\\.)?android\\.providers\\..*",
    )
    /** Providers no TV feature uses; tested lists turn them off. */
    private val CORE_OK = Regex("com\\.android\\.providers\\.(calendar|contacts|userdictionary|blockednumber)")

    private val EXACT: Map<String, ProtectedReason> = mapOf(
        "com.android.settings" to ProtectedReason.SETTINGS,
        "com.android.tv.settings" to ProtectedReason.SETTINGS,
        "com.amazon.tv.settings.v2" to ProtectedReason.SETTINGS, // Fire TV
        "org.droidtv.settings" to ProtectedReason.SETTINGS, // Philips
        "com.android.vending" to ProtectedReason.PLAY_SERVICES,
        "com.google.android.gms" to ProtectedReason.PLAY_SERVICES,
        "com.google.android.gsf" to ProtectedReason.PLAY_SERVICES,
        // Every app that shows a web page needs the WebView provider.
        "com.google.android.webview" to ProtectedReason.SYSTEM,
        "com.android.webview" to ProtectedReason.SYSTEM,
    )

    /**
     * System updaters: `update`/`upgrade` anywhere, `ota` only as a whole name part or before
     * `update`, so `com.tcl.bootadservice` and `com.itwgame.mota` stay unprotected.
     */
    private val UPDATER = Regex("updat|upgrad|(^|\\.)(f|usb|remote|auth)?ota(\\.|$)|otaupdat")

    /** Name fragments, checked in order; manufacturer names vary, so these match loosely. */
    private val PATTERNS: List<Pair<String, ProtectedReason>> = listOf(
        "launcher" to ProtectedReason.HOME_SCREEN, // second guard behind the live home lookup
        "networkstack" to ProtectedReason.SYSTEM, // Wi-Fi and Ethernet setup; we'd cut our own link
        // Wi-Fi config, connectivity resources and their OEM overlays: same reason, and once Wi-Fi is
        // gone so is the ADB link an undo would need.
        "com.android.wifi" to ProtectedReason.SYSTEM,
        "com.google.android.wifi" to ProtectedReason.SYSTEM,
        "wifi.resources" to ProtectedReason.SYSTEM,
        "connectivity" to ProtectedReason.SYSTEM,
        // Chrome's shared library; WebView and Chrome stop loading without it.
        "trichromelibrary" to ProtectedReason.SYSTEM,
        "inputmethod" to ProtectedReason.KEYBOARD,
        "keyboard" to ProtectedReason.KEYBOARD,
        ".ime" to ProtectedReason.KEYBOARD,
        "inputdevices" to ProtectedReason.KEYBOARD,
        "tvinput" to ProtectedReason.TV_INPUTS_REMOTE,
        "tv.input" to ProtectedReason.TV_INPUTS_REMOTE,
        "hdmi" to ProtectedReason.TV_INPUTS_REMOTE,
        "tuner" to ProtectedReason.TV_INPUTS_REMOTE,
        "bluetooth" to ProtectedReason.TV_INPUTS_REMOTE,
        "remote" to ProtectedReason.TV_INPUTS_REMOTE,
        "rcu" to ProtectedReason.TV_INPUTS_REMOTE,
        "setupwizard" to ProtectedReason.SETUP_WIZARD,
        "setupwraith" to ProtectedReason.SETUP_WIZARD,
        "bootwizard" to ProtectedReason.SETUP_WIZARD,
        "provision" to ProtectedReason.SETUP_WIZARD,
    )

    /** [homePackages] are the device's launchers, read live; they are protected whatever their name. */
    fun reason(pkg: String, homePackages: Set<String> = emptySet()): ProtectedReason? {
        if (pkg in homePackages) return ProtectedReason.HOME_SCREEN
        EXACT[pkg]?.let { return it }
        if (CORE.matches(pkg) && !CORE_OK.matches(pkg)) return ProtectedReason.SYSTEM
        val lower = pkg.lowercase()
        PATTERNS.firstOrNull { (fragment, _) -> fragment in lower }?.let { return it.second }
        return if (UPDATER.containsMatchIn(lower)) ProtectedReason.UPDATES else null
    }
}
