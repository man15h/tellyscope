// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import app.tellyscope.R
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
import app.tellyscope.data.UiText
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
    fun button(action: Action): UiText = when (action) {
        Action.Stop -> UiText(R.string.copy_button_stop)
        Action.Disable -> UiText(R.string.copy_button_disable)
        Action.Enable -> UiText(R.string.copy_button_enable)
        Action.Open -> UiText(R.string.copy_button_open)
        Action.Uninstall -> UiText(R.string.copy_button_uninstall)
        Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack, Action.Reset -> error("$action has its own button")
    }

    fun confirm(action: Action): UiText = when (action) {
        Action.Stop -> UiText(R.string.copy_confirm_stop)
        Action.Disable -> UiText(R.string.copy_confirm_disable)
        Action.Enable -> UiText(R.string.copy_confirm_enable)
        Action.Open -> UiText(R.string.copy_confirm_open)
        Action.Uninstall -> UiText(R.string.copy_confirm_uninstall)
        Action.Home -> UiText(R.string.copy_confirm_home)
        Action.HomeBack -> UiText(R.string.copy_confirm_home_back)
        Action.Change, Action.ClearCaches, Action.Reset -> error("$action isn't an app action")
    }

    /**
     * Names the package next to the label: the label comes from the app itself, and any app can
     * call itself "Settings". The package id is what actually gets changed.
     */
    fun explain(action: Action, app: String, pkg: String): UiText = when (action) {
        Action.Stop -> UiText(R.string.copy_explain_stop, app, pkg)
        Action.Disable -> UiText(R.string.copy_explain_disable, app, pkg)
        Action.Enable -> UiText(R.string.copy_explain_enable, app, pkg)
        Action.Open -> UiText(R.string.copy_explain_open, app, pkg)
        Action.Uninstall -> UiText(R.string.copy_explain_uninstall, app, pkg)
        Action.Change, Action.ClearCaches, Action.Home, Action.HomeBack, Action.Reset -> error("$action isn't a one-app action")
    }

    fun done(action: Action, app: String): UiText = when (action) {
        Action.Stop -> UiText(R.string.copy_done_stop, app)
        Action.Disable -> UiText(R.string.copy_done_disable, app)
        Action.Enable -> UiText(R.string.copy_done_enable, app)
        Action.Open -> UiText(R.string.copy_done_open, app)
        Action.Uninstall -> UiText(R.string.copy_done_uninstall, app)
        // A setting's label is the whole sentence: "Private DNS set to …", as is a reset's.
        Action.Change, Action.Reset -> UiText.Raw(app)
        Action.ClearCaches -> UiText(R.string.copy_done_clear_caches, app)
        Action.Home -> UiText(R.string.copy_done_home, app)
        Action.HomeBack -> UiText(R.string.copy_done_home_back, app)
    }

    /** Said in the confirm before Stop, Disable or Uninstall of an app the TV's list keeps. */
    val KEEP_WARNING = UiText(R.string.copy_keep_warning)

    /** Where the lists come from, for the Debloat screen and the snackbar after a change. */
    fun listSource(custom: String?): UiText =
        if (custom == null) UiText(R.string.copy_list_source_default) else UiText(R.string.copy_list_source_custom, sourceName(custom))

    /** The Home screen card at the top of Tweaks: every launcher on the TV, and which one Home opens. */
    val HOME_SCREEN = UiText(R.string.copy_home_screen)

    fun opensOnHome(app: String) = UiText(R.string.copy_opens_on_home, app)

    val NO_DEFAULT = UiText(R.string.copy_no_default)
    val ADDED = UiText(R.string.copy_added)

    fun builtIn(off: Boolean) = UiText(if (off) R.string.copy_built_in_off else R.string.copy_built_in)

    val CURRENT = UiText(R.string.copy_current)
    val KEEPS_HOME = UiText(R.string.copy_keeps_home)
    val MAKE_HOME = UiText(R.string.copy_make_home)
    val GET_LAUNCHER = UiText(R.string.copy_get_launcher)
    val GET_LAUNCHER_TEXT = UiText(R.string.copy_get_launcher_text)
    val GET_PROJECTIVY_TEXT = UiText(R.string.copy_get_projectivy_text)

    /** The Apps tab's launcher rows: switching lives in the card, this opens it. */
    val HOME_SETTINGS = UiText(R.string.copy_home_settings)

    fun makeHomeTitle(app: String) = UiText(R.string.copy_make_home_title, app)

    /** A launcher the user added in place of the TV's own, which is turned off: otherwise many TVs switch back by themselves. */
    fun explainReplace(app: String, current: String, currentPkg: String, service: Boolean): UiText = UiText.Join(
        listOfNotNull(
            UiText(R.string.copy_explain_replace_opens, app, current),
            UiText(R.string.copy_explain_replace_only),
            UiText(R.string.copy_explain_replace_undo, current),
            if (currentPkg in GOOGLE_HOMES) UiText(R.string.copy_google_features, current) else null,
            if (service) UiText(R.string.copy_next_service) else null,
        ),
        "\n\n",
    )

    /**
     * Away from [current], a launcher the user added: it stays on, only its accessibility service
     * goes off when it has one on, since that would take Home straight back. [off]: [app] is the
     * TV's own launcher, turned off by an earlier switch.
     */
    fun explainLeave(app: String, off: Boolean, current: String, currentService: Boolean, service: Boolean): UiText = UiText.Join(
        listOfNotNull(
            UiText(if (off) R.string.copy_explain_leave_turns_on else R.string.copy_explain_leave_makes, app),
            UiText.Join(
                listOfNotNull(
                    if (currentService) UiText(R.string.copy_explain_leave_service, current) else null,
                    UiText(R.string.copy_explain_leave_stays, current),
                ),
                " ",
            ),
            UiText(if (currentService) R.string.copy_explain_leave_undo_service else R.string.copy_explain_leave_undo, current),
            if (service) UiText(R.string.copy_next_service) else null,
        ),
        "\n\n",
    )

    /**
     * The home switch touches two apps, so it names both. The old launcher is disabled, not just
     * replaced: otherwise many TVs switch back to it by themselves.
     */
    fun explainHome(app: String, pkg: String, current: String, currentPkg: String, service: Boolean = false): UiText = UiText.Join(
        listOfNotNull(
            UiText(R.string.copy_explain_home, app, pkg, current, currentPkg),
            if (currentPkg in GOOGLE_HOMES) UiText(R.string.copy_google_features, current) else null,
            if (service) UiText(R.string.copy_next_service) else null,
        ),
        "\n\n",
    )

    /** Fire OS: Amazon's launcher can't be turned off or replaced, so [app]'s accessibility service holds Home instead. */
    fun explainFireHome(app: String, pkg: String, current: String): UiText = UiText.Join(
        listOf(UiText(R.string.copy_explain_fire_home, app, pkg, current), UiText(R.string.copy_next_service)),
        "\n\n",
    )

    /** No default home and no launcher of the TV's own on: nothing is turned off, so Undo can only turn its service off. */
    fun explainFirstHome(app: String, pkg: String, service: Boolean = false): UiText = UiText.Join(
        listOfNotNull(
            UiText(R.string.copy_explain_first_home, app, pkg),
            if (service) UiText(R.string.copy_next_service) else null,
        ),
        "\n\n",
    )

    /** The separate yes for a launcher's accessibility service: what it does, what it can see, how it goes. */
    fun serviceTitle(app: String) = UiText(R.string.copy_service_title, app)

    fun explainService(app: String, other: String) = UiText(R.string.copy_explain_service, app, other)

    val TURN_IT_ON = UiText(R.string.copy_turn_it_on)
    val SWITCH_WITHOUT = UiText(R.string.copy_switch_without)
    val NOT_NOW = UiText(R.string.copy_not_now)
    val TURN_ON = UiText(R.string.copy_turn_on)
    val GOT_IT = UiText(R.string.copy_got_it)
    val SERVICE_OFF = UiText(R.string.copy_service_off)

    /** The TV page's card while the launcher on Home has its service off; [other] is the launcher Home may still open. */
    fun serviceOffTitle(other: String?) = UiText(R.string.copy_service_off_title, other ?: TV_OWN_LAUNCHER)

    val TV_OWN_LAUNCHER = UiText(R.string.copy_tv_own_launcher)

    /** Install a home screen: the Apps tab's card, the picker, the wait for the Store, and its dead ends. */
    val BETTER_HOME = UiText(R.string.copy_better_home)

    /** [fire]: Fire OS, where there's no Play Store and Tellyscope installs it. */
    fun betterHomeText(current: String?, fire: Boolean = false) = UiText(
        if (fire) R.string.copy_better_home_text_fire else R.string.copy_better_home_text,
        current ?: UiText(R.string.copy_the_tvs_home_screen),
    )

    val CHOOSE_ONE = UiText(R.string.copy_choose_one)
    val INSTALL_ONE = UiText(R.string.copy_install_one)
    val CHOOSE_HOME = UiText(R.string.copy_choose_home)
    val CHOOSE_HOME_TEXT = UiText(R.string.copy_choose_home_text)
    /** After [CHOOSE_HOME_TEXT] when Download directly is offered. */
    val CAN_DOWNLOAD = UiText(R.string.copy_can_download)
    val NO_LAUNCHER_LIST = UiText(R.string.copy_no_launcher_list)
    val RECOMMENDED = UiText(R.string.copy_recommended)
    /** The picker's section for launchers on the TV that Recommended doesn't list. */
    val ON_THIS_TV = UiText(R.string.copy_on_this_tv)
    val INSTALLED = UiText(R.string.copy_installed)
    val HOME_NOW = UiText(R.string.copy_home_now)
    /** Shows a launcher on the TV, from the picker. */
    val OPEN = UiText(R.string.copy_open)
    val USES_SERVICE = UiText(R.string.copy_uses_service)
    val NO_SERVICE = UiText(R.string.copy_no_service)
    /** Under the picker's list: any other launcher works too, and comes back as On this TV. */
    val OTHER_LAUNCHER = UiText(R.string.copy_other_launcher, ON_THIS_TV)
    val NOT_AFFILIATED = UiText(R.string.copy_not_affiliated)
    val GET_FROM_STORE = UiText(R.string.copy_get_from_store)
    val STORE_RECOMMENDED = UiText(R.string.copy_store_recommended)
    val DOWNLOAD_DIRECTLY = UiText(R.string.copy_download_directly)
    val INSTALL_HOME = UiText(R.string.copy_install_home)
    val INSTALL_IT = UiText(R.string.copy_install_it)

    fun installItText(name: String) = UiText(R.string.copy_install_it_text, name)

    fun storeOn(tv: String) = UiText(R.string.copy_store_on, tv)

    val WAITING_INSTALL = UiText(R.string.copy_waiting_install)
    val DOWNLOADS_TAKE = UiText(R.string.copy_downloads_take)
    val OPEN_STORE_AGAIN = UiText(R.string.copy_open_store_again)
    val MAKE_IT_HOME = UiText(R.string.copy_make_it_home)

    fun isInstalled(name: String) = UiText(R.string.copy_is_installed, name)

    fun launcherLater(name: String) = UiText(R.string.copy_launcher_later, name, MAKE_HOME, HOME_SCREEN)

    fun launcherInstalled(name: String) = UiText(R.string.copy_launcher_installed, name)

    fun launcherIsHome(name: String) = UiText(R.string.copy_launcher_is_home, name)

    val NO_STORE = UiText(R.string.copy_no_store)

    fun noStoreText(tv: String, name: String) = UiText(R.string.copy_no_store_text, tv, name)

    val CHOOSE_ANOTHER = UiText(R.string.copy_choose_another)

    fun notInstalled(name: String) = UiText(R.string.copy_not_installed, name)
    /** The Store closed without an install: back on the picker, still chosen. */
    fun wasntInstalled(name: String) = UiText(R.string.copy_wasnt_installed, name)

    /** Install Projectivy on Fire OS: Tellyscope downloads it from its developer's GitHub and installs it over adb. */
    val INSTALL_PROJECTIVY = UiText(R.string.copy_install_projectivy)
    val FIRE_INSTALL_TEXT = UiText(R.string.copy_fire_install_text)

    fun downloadsFrom(name: String) = UiText(R.string.copy_downloads_from, name)

    fun release(version: String, size: Long) = UiText(R.string.copy_release, version, bytes(size))

    val WHERE_FROM = UiText(R.string.copy_where_from)
    val SIGNED_CHECK = UiText(R.string.copy_signed_check)
    val DOWNLOAD_AND_INSTALL = UiText(R.string.copy_download_and_install)

    /** Download directly on a TV with the Play Store: what Tellyscope checks, what it doesn't, and the safer way. */
    fun downloadDirectly(name: String) = UiText(R.string.copy_download_directly_title, name)

    val DIRECT_CHECK = UiText(R.string.copy_direct_check)
    val STORE_SAFER = UiText(R.string.copy_store_safer)
    val DOWNLOAD_ANYWAY = UiText(R.string.copy_download_anyway)
    val STORE_INSTEAD = UiText(R.string.copy_store_instead)

    /** Install Projectivy's steps, one panel: download (or the picked file), the check, the install. */
    fun installing(name: String) = UiText(R.string.copy_installing, name)
    val STEP_DOWNLOAD = UiText(R.string.copy_step_download)
    val STEP_DOWNLOADED = UiText(R.string.copy_step_downloaded)
    fun downloading(percent: Int, done: Long, total: Long) = UiText(R.string.copy_downloading, percent, bytes(done), bytes(total))
    val STEP_PICKED = UiText(R.string.copy_step_picked)
    val STEP_CHECK = UiText(R.string.copy_step_check)
    val STEP_CHECKING = UiText(R.string.copy_step_checking)
    val STEP_CHECKED = UiText(R.string.copy_step_checked)
    val STEP_CHECKED_TEXT = UiText(R.string.copy_step_checked_text)
    val STEP_FAILED = UiText(R.string.copy_step_failed)
    val STEP_FAILED_TEXT = UiText(R.string.copy_step_failed_text)
    fun installOn(tv: String) = UiText(R.string.copy_install_on, tv)

    fun installingOn(tv: String) = UiText(R.string.copy_installing_on, tv)

    /** X while it downloads: nothing is on the TV yet. */
    val STOP_TITLE = UiText(R.string.copy_stop_title)
    val STOP_TEXT = UiText(R.string.copy_stop_text)
    val STOP = UiText(R.string.copy_stop)
    val KEEP_DOWNLOADING = UiText(R.string.copy_keep_downloading)

    val NOT_PROJECTIVY = UiText(R.string.copy_not_projectivy)
    val NOT_PROJECTIVY_TEXT = UiText(R.string.copy_not_projectivy_text)
    val NOT_PROJECTIVY_FILE = UiText(R.string.copy_not_projectivy_file)
    val NOT_PROJECTIVY_FILE_TEXT = UiText(R.string.copy_not_projectivy_file_text)
    val CANT_READ_FILE = UiText(R.string.copy_cant_read_file)
    fun cantInstall(name: String) = UiText(R.string.copy_cant_install, name)

    /** Couldn't download: GitHub's limit for the network ([until] is a clock time), or no way to reach it. */
    val NO_DOWNLOAD = UiText(R.string.copy_no_download)
    val NO_DOWNLOAD_OFFLINE = UiText(R.string.copy_no_download_offline)
    fun noDownloadLimited(until: String?) =
        if (until != null) UiText(R.string.copy_no_download_limited_until, until) else UiText(R.string.copy_no_download_limited)
    val OR_YOURSELF = UiText(R.string.copy_or_yourself)
    val YOURSELF_PAGE = UiText(R.string.copy_yourself_page)
    fun yourselfPick(file: String?) = if (file != null) UiText(R.string.copy_yourself_pick_file, file) else UiText(R.string.copy_yourself_pick)
    val LATEST_RELEASE = UiText(R.string.copy_latest_release)
    val PICK_DOWNLOADED = UiText(R.string.copy_pick_downloaded)
    val PICK_ANOTHER = UiText(R.string.copy_pick_another)
    val TRY_DOWNLOAD_AGAIN = UiText(R.string.copy_try_download_again)

    val NOT_INSTALLED_TEXT = UiText(R.string.copy_not_installed_text)

    /** The Tweaks row that reopens it after Not now on the card. */

    fun serviceOffText(app: String) = UiText(R.string.copy_service_off_text, app)

    private val GOOGLE_HOMES = setOf("com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx")

    /**
     * Force-stop has no true undo, and undoing a Turn on disables the app; the button says what it
     * actually does.
     */
    fun undo(undo: Action): UiText = when (undo) {
        Action.Open -> UiText(R.string.copy_open_again)
        Action.Disable -> button(Action.Disable)
        else -> UiText(R.string.copy_undo)
    }

    fun ago(at: Long, now: Long): UiText {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 10 -> UiText(R.string.copy_just_now)
            s < 60 -> UiText.plural(R.plurals.copy_seconds_ago, s.toInt())
            s < 3600 -> UiText.plural(R.plurals.copy_minutes_ago, (s / 60).toInt())
            s < 86_400 -> UiText.plural(R.plurals.copy_hours_ago, (s / 3600).toInt())
            else -> UiText.plural(R.plurals.copy_days_ago, (s / 86_400).toInt())
        }
    }

    fun uptime(s: Long): UiText {
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> UiText(R.string.copy_uptime_days_hours, d, h)
            h > 0 -> UiText(R.string.copy_uptime_hours_minutes, h, m)
            else -> UiText(R.string.copy_uptime_minutes, m)
        }
    }

    /** Uptime for people: the largest unit or two, spelled out. */
    fun upFor(s: Long): UiText {
        val d = UiText.plural(R.plurals.copy_days, (s / 86_400).toInt())
        val h = UiText.plural(R.plurals.copy_hours, ((s % 86_400) / 3600).toInt())
        val m = UiText.plural(R.plurals.copy_minutes, ((s % 3600) / 60).toInt())
        return when {
            s / 86_400 > 0 && (s % 86_400) / 3600 > 0 -> UiText(R.string.copy_days_hours, d, h)
            s / 86_400 > 0 -> d
            (s % 86_400) / 3600 > 0 -> h
            else -> m
        }
    }

    /** "May 2023", in the phone's language. */
    fun month(m: YearMonth): String = m.format(DateTimeFormatter.ofPattern("MMMM yyyy"))

    /** Network speed in the same units as [bytes], so a download's size and speed line up. */
    fun speed(bytesPerSec: Long) = UiText(R.string.copy_speed, bytes(bytesPerSec))

    fun mb(v: Int) = if (v >= 1024) UiText(R.string.copy_size_gb, v / 1024.0) else UiText(R.string.copy_size_mb_whole, v)

    fun uninstallTitle(app: String) = UiText(R.string.copy_uninstall_title, app)

    /** The confirm for the launcher on Home: Home goes back first, and a failed step stops it. */
    fun uninstallHome(app: String, back: String, service: Boolean): UiText = UiText.Join(
        listOfNotNull(
            UiText(R.string.copy_uninstall_home_intro, app),
            UiText(R.string.copy_uninstall_home_back, back),
            if (service) UiText(R.string.copy_uninstall_home_service, app) else null,
            UiText(R.string.copy_uninstall_home_remove, if (service) 3 else 2),
            UiText(R.string.copy_uninstall_home_fails),
        ),
        "\n\n",
    )

    /** The Apps tabs' header over the apps the TV needs, and why they're apart. */
    val SYSTEM = UiText(R.string.copy_system)
    val SYSTEM_WHY = UiText(R.string.copy_system_why)

    /** Debloat with nothing left on the list: what's done, what's next, then Apps. */
    val GATE_TITLE = UiText(R.string.copy_gate_title)
    val GATE_DONE = UiText(R.string.copy_gate_done)
    val GATE_LAUNCHER = UiText(R.string.copy_gate_launcher)
    val GATE_APPS = UiText(R.string.copy_gate_apps)
    val GO_TO_APPS = UiText(R.string.copy_go_to_apps)
    val DONT_SHOW_AGAIN = UiText(R.string.copy_dont_show_again)

    /** Apps, while the TV's app list is being read. */
    val READING_APPS = UiText(R.string.copy_reading_apps)
    val READING_APPS_LINE = UiText(R.string.copy_reading_apps_line)

    /** Running, with no word from the TV. */
    val TV_OFFLINE = UiText(R.string.copy_tv_offline)
    val RUNNING_OFFLINE = UiText(R.string.copy_running_offline)

    val ANDROID_SYSTEM = AppNames.SYSTEM

    /** The word next to the status dot, so the state never rests on colour alone. */
    fun state(s: DeviceState): UiText = when (s) {
        DeviceState.Online -> UiText(R.string.copy_state_online)
        DeviceState.Offline -> UiText(R.string.copy_state_offline)
        DeviceState.NotAllowed -> UiText(R.string.copy_state_not_allowed)
        DeviceState.Waiting -> UiText(R.string.copy_state_waiting)
        DeviceState.NeedsPairing -> UiText(R.string.copy_state_needs_pairing)
        DeviceState.Found -> UiText(R.string.copy_state_found)
    }

    /** Under 10 GB a whole number hides too much: a 1.5 GB TV isn't a 2 GB one. */
    fun gbOf(used: Double, total: Double) =
        if (total < 10) UiText(R.string.copy_gb_of_small, used, total) else UiText(R.string.copy_gb_of, used, total)

    /** Free storage in plain words, rounded down so it never promises room that isn't there; null if unknown. */
    fun free(s: Snapshot): UiText? = free(s.storageUsedGb, s.storageTotalGb)

    /** [free] from the two numbers, for a TV's saved facts as well as a live reading. */
    fun free(used: Double?, total: Double?): UiText? {
        val gb = used?.let { u -> total?.let { t -> (t - u).coerceAtLeast(0.0) } } ?: return null
        return if (gb < 10) UiText(R.string.copy_free_gb_small, Math.floor(gb * 10) / 10) else UiText(R.string.copy_free_gb, gb.toInt())
    }

    /** An offline TV that has never sent stats; a refusing TV is on, it just hasn't allowed us. */
    fun noStats(s: DeviceState) =
        if (s == DeviceState.NotAllowed) UiText(R.string.copy_no_stats_not_allowed)
        else UiText(R.string.copy_no_stats)

    val WRONG_CODE = UiText(R.string.copy_wrong_code)
    val UNREACHABLE = UiText(R.string.copy_unreachable)
    val PAIR_INSTEAD = UiText(R.string.copy_pair_instead)
    val LOGGED_OUT = UiText(R.string.copy_logged_out)
    val LOGGED_OUT_WHY = UiText(R.string.copy_logged_out_why)
    val LOGGED_OUT_CAUSES: List<UiText> = listOf(
        UiText(R.string.copy_logged_out_cause_revoke),
        UiText(R.string.copy_logged_out_cause_remove),
    )
    fun loggedOutOf(name: String) = UiText(R.string.copy_logged_out_of, name)

    /** When the TV took the access back, on the logged-out page. */
    fun loggedOutAt(at: Long, now: Long): UiText {
        // Calendar days, so 23:00 reads "yesterday" the next morning.
        val zone = ZoneId.systemDefault()
        val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(at).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
        return when {
            now - at < 5 * 60_000 -> UiText(R.string.copy_logged_out_just_now)
            days == 0L -> UiText(R.string.copy_logged_out_today, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at)))
            days == 1L -> UiText(R.string.copy_logged_out_yesterday)
            else -> UiText.plural(R.plurals.copy_logged_out_days_ago, days.toInt())
        }
    }

    data class Health(val ok: Boolean, val text: UiText)

    /**
     * The one line at the top of a TV's page: the most pressing problem, or that all is well.
     * [heaviest] are app names by memory use, largest first; there is no per-app storage size yet.
     */
    fun health(s: Snapshot, heaviest: List<String>): Health {
        val storage = s.storageUsedGb?.let { u -> s.storageTotalGb?.takeIf { it > 0 }?.let { u / it } }
        val ram = if (s.ramTotalMb > 0) s.ramUsedMb.toDouble() / s.ramTotalMb else null
        val most = heaviest.take(2)
        return when {
            storage != null && storage >= FULL -> Health(false, UiText(R.string.copy_health_storage_full))
            ram != null && ram >= FULL -> Health(
                false,
                when (most.size) {
                    0 -> UiText(R.string.copy_health_memory_full)
                    1 -> UiText(R.string.copy_health_memory_full_one, most[0])
                    else -> UiText(R.string.copy_health_memory_full_two, most[0], most[1])
                },
            )
            else -> Health(true, UiText(R.string.copy_health_fine))
        }
    }

    const val FULL = 0.9

    fun bytes(v: Long): UiText = when {
        v >= 1L shl 30 -> UiText(R.string.copy_size_gb, v / (1L shl 30).toDouble())
        v >= 1L shl 20 -> UiText(R.string.copy_size_mb, v / (1L shl 20).toDouble())
        v >= 1L shl 10 -> UiText(R.string.copy_size_kb, v shr 10)
        else -> UiText.plural(R.plurals.copy_size_bytes, v.toInt(), v)
    }

    /** [bytes] without a trailing ".0": 12 GB rather than 12.0 GB. */
    private fun bytesWhole(v: Long): UiText {
        fun tenths(unit: Long) = Math.round(v * 10.0 / unit)
        return when {
            v >= 1L shl 30 && tenths(1L shl 30) % 10 == 0L -> UiText(R.string.copy_size_gb_whole, tenths(1L shl 30) / 10)
            v in (1L shl 20) until (1L shl 30) && tenths(1L shl 20) % 10 == 0L -> UiText(R.string.copy_size_mb_whole, tenths(1L shl 20) / 10)
            else -> bytes(v)
        }
    }

    fun folder(f: TvFolder): UiText = when (f) {
        TvFolder.DOWNLOAD -> UiText(R.string.copy_folder_download)
        TvFolder.MOVIES -> UiText(R.string.copy_folder_movies)
    }

    // Sideload

    fun installButton(kind: InstallKind): UiText = when (kind) {
        InstallKind.NEW -> UiText(R.string.copy_install_new)
        InstallKind.UPDATE -> UiText(R.string.copy_install_update)
        InstallKind.REINSTALL -> UiText(R.string.copy_install_reinstall)
        InstallKind.DOWNGRADE -> UiText(R.string.copy_install_downgrade)
    }

    fun installKind(kind: InstallKind, onTv: String?, picked: String?): UiText = when (kind) {
        InstallKind.NEW -> UiText(R.string.copy_install_kind_new)
        InstallKind.UPDATE ->
            if (onTv != null && picked != null) UiText(R.string.copy_install_kind_update_versions, onTv, picked)
            else UiText(R.string.copy_install_kind_update)
        InstallKind.REINSTALL ->
            onTv?.let { UiText(R.string.copy_install_kind_reinstall_version, it) } ?: UiText(R.string.copy_install_kind_reinstall)
        InstallKind.DOWNGRADE ->
            if (onTv != null && picked != null) UiText(R.string.copy_install_kind_downgrade_versions, picked, onTv)
            else UiText(R.string.copy_install_kind_downgrade)
    }

    val ALLOW_DOWNGRADE = UiText(R.string.copy_allow_downgrade)
    val INSTALL_KEEP_OPEN = UiText(R.string.copy_install_keep_open)

    fun installed(kind: InstallKind, app: String): UiText = when (kind) {
        InstallKind.UPDATE -> UiText(R.string.copy_installed_update, app)
        InstallKind.REINSTALL -> UiText(R.string.copy_installed_reinstall, app)
        InstallKind.NEW, InstallKind.DOWNGRADE -> UiText(R.string.copy_installed_new, app)
    }

    fun installRefused(reason: InstallRefusal, app: String): UiText = when (reason) {
        InstallRefusal.SIGNATURE -> UiText(R.string.copy_install_refused_signature, app)
        InstallRefusal.DOWNGRADE -> UiText(R.string.copy_install_refused_downgrade, app)
        InstallRefusal.NO_SPACE -> UiText(R.string.copy_install_refused_no_space)
        InstallRefusal.TV_SAID_NO -> UiText(R.string.copy_install_refused_tv_said_no)
        InstallRefusal.TV_TOO_OLD -> UiText(R.string.copy_install_refused_tv_too_old, app)
        InstallRefusal.WRONG_CPU -> UiText(R.string.copy_install_refused_wrong_cpu, app)
        InstallRefusal.APP_TOO_OLD -> UiText(R.string.copy_install_refused_app_too_old, app)
        InstallRefusal.MISSING_FEATURE -> UiText(R.string.copy_install_refused_missing_feature, app)
        InstallRefusal.MISSING_PARTS -> UiText(R.string.copy_install_refused_missing_parts, app)
        InstallRefusal.TEST_ONLY -> UiText(R.string.copy_install_refused_test_only, app)
        InstallRefusal.CONFLICT -> UiText(R.string.copy_install_refused_conflict, app)
        InstallRefusal.BROKEN -> UiText(R.string.copy_install_refused_broken)
        InstallRefusal.UNKNOWN -> UiText(R.string.copy_install_refused_unknown, app)
    }

    val NOT_AN_APP = UiText(R.string.copy_not_an_app)
    val SIDELOAD_LOST = UiText(R.string.copy_sideload_lost)

    // Files

    fun sent(name: String, savedAs: String, f: TvFolder) =
        if (savedAs == name) UiText(R.string.copy_sent, name, folder(f)) else UiText(R.string.copy_sent_renamed, folder(f), savedAs, name)

    fun saved(name: String) = UiText(R.string.copy_saved, name)
    fun sendFailed(name: String) = UiText(R.string.copy_send_failed, name)
    fun saveFailed(name: String) = UiText(R.string.copy_save_failed, name)

    val CANCELLED_SEND = UiText(R.string.copy_cancelled_send)
    val CANCELLED_SAVE = UiText(R.string.copy_cancelled_save)
    val CANT_READ = UiText(R.string.copy_cant_read)
    val CANT_UNDO = UiText(R.string.copy_cant_undo)
    val FILES_LOST = UiText(R.string.copy_files_lost)

    // Remote

    val KEY_REFUSED = UiText(R.string.copy_key_refused)
    val TYPE_REFUSED = UiText(R.string.copy_type_refused)
    val REMOTE_LOST = UiText(R.string.copy_remote_lost)
    val NO_UPDATE_SCREEN = UiText(R.string.copy_no_update_screen)
    val UPDATES_OPENED = UiText(R.string.copy_updates_opened)
    val REBOOT_REFUSED = UiText(R.string.copy_reboot_refused)
    val REBOOT_IGNORED = UiText(R.string.copy_reboot_ignored)
    val REMOTE_OFFLINE_WAKE = UiText(R.string.copy_remote_offline_wake)
    val TYPE_HINT = UiText(R.string.copy_type_hint)
    val BACKSPACE = UiText(R.string.copy_backspace)
    val SCREEN_OFF = UiText(R.string.copy_screen_off)
    val SCREEN_ON = UiText(R.string.copy_screen_on)
    val SCREEN_OFF_WAKE = UiText(R.string.copy_screen_off_wake)
    val SCREEN_OFF_TIP = UiText(R.string.copy_screen_off_tip)
    val SCREEN_OFF_REFUSED = UiText(R.string.copy_screen_off_refused)
    val SCREEN_ON_REFUSED = UiText(R.string.copy_screen_on_refused)

    fun cantType(text: String): UiText {
        val odd = text.firstOrNull { it !in ' '..'~' }
        return when {
            text.isEmpty() -> UiText(R.string.copy_cant_type_empty)
            text.length > app.tellyscope.adb.Remote.MAX_TEXT ->
                UiText.plural(R.plurals.copy_cant_type_too_long, app.tellyscope.adb.Remote.MAX_TEXT)
            odd == '\n' || odd == '\t' -> UiText(R.string.copy_cant_type_one_line)
            odd != null -> UiText(R.string.copy_cant_type_odd, odd.toString())
            else -> UiText(R.string.copy_cant_type_percent_s)
        }
    }

    val REMOTE_RESTART = UiText(R.string.copy_remote_restart)
    val REBOOT_TITLE = UiText(R.string.copy_reboot_title)
    val REBOOT_TEXT = UiText(R.string.copy_reboot_text)
    val RESTARTING_TITLE = UiText(R.string.copy_restarting_title)
    val RESTARTING_TEXT = UiText(R.string.copy_restarting_text)
    val RESTART_LATE_TITLE = UiText(R.string.copy_restart_late_title)
    val RESTART_LATE_STATUS = UiText(R.string.copy_restart_late_status)
    val RESTART_LATE_TEXT = UiText(R.string.copy_restart_late_text)
    val RESTART_LOST_TEXT = UiText(R.string.copy_restart_lost_text)

    fun waitingFor(name: String, ms: Long): UiText {
        val s = (ms / 1000).coerceAtLeast(0)
        return UiText(R.string.copy_waiting_for, name, s / 60, s % 60)
    }

    fun restartLost(name: String) = UiText(R.string.copy_restart_lost, name)
    fun backOnline(name: String) = UiText(R.string.copy_back_online, name)

    // Remote buttons

    val REMOTE_BUTTONS = UiText(R.string.copy_remote_buttons)
    val MORE = UiText(R.string.copy_more)

    fun buttonsHint(cap: Int) = UiText(R.string.copy_buttons_hint, cap)
    fun notOnTv(name: String) = UiText(R.string.copy_not_on_tv, name)
    fun cantOpen(label: String) = UiText(R.string.copy_cant_open, label)

    fun leftDock(labels: List<String>) =
        if (labels.size == 1) UiText(R.string.copy_left_dock_one, labels[0])
        else UiText.plural(R.plurals.copy_left_dock, labels.size)

    fun restarting(minutes: Long) =
        if (minutes < 3) UiText(R.string.copy_restarting_soon)
        else UiText(R.string.copy_restarting_long)

    val TWEAKS = UiText(R.string.copy_tweaks)
    val DNS_TITLE = UiText(R.string.copy_dns_title)
    fun dnsState(host: String?) = host?.let { UiText(R.string.copy_dns_state_on, it) } ?: UiText(R.string.copy_dns_state_off)
    val DNS_TOO_OLD = UiText(R.string.copy_dns_too_old)
    val DNS_DIALOG = UiText(R.string.copy_dns_dialog)
    val DNS_TEXT = UiText(R.string.copy_dns_text)
    val DNS_FIELD = UiText(R.string.copy_dns_field)
    val DNS_BAD = UiText(R.string.copy_dns_bad)
    val DNS_CONFIRM = UiText(R.string.copy_dns_confirm)
    val ANIMATIONS_TITLE = UiText(R.string.copy_animations_title)
    fun animationsState(scale: String?) =
        if (scale == TvSettings.FAST) UiText(R.string.copy_animations_state_on)
        else UiText(R.string.copy_animations_state_off)
    val CACHES_TITLE = UiText(R.string.copy_caches_title)
    val CACHES_TEXT = UiText(R.string.copy_caches_text)
    val CACHES_BUTTON = UiText(R.string.copy_caches_button)
    fun cachesCleared(freedKb: Long?) =
        if (freedKb == null || freedKb <= 0) UiText(R.string.copy_caches_cleared) else UiText(R.string.copy_caches_cleared_freed, bytes(freedKb * 1024))
    val SCREENSHOT_TITLE = UiText(R.string.copy_screenshot_title)
    val SCREENSHOT_TEXT = UiText(R.string.copy_screenshot_text)
    val SCREENSHOT_BUTTON = UiText(R.string.copy_screenshot_button)
    /** A file name on disk, so it stays as it is in every language. */
    fun screenshotName(at: Long) =
        "TV screenshot " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date(at)) + ".png"
    val SCREENSHOT_SAVED = UiText(R.string.copy_screenshot_saved)
    val SCREENSHOT_FAILED = UiText(R.string.copy_screenshot_failed)
    val REQUEST_TITLE = UiText(R.string.copy_request_title)
    val REQUEST_TEXT = UiText(R.string.copy_request_text)
    val REQUEST_ALWAYS = UiText(R.string.copy_request_always)
    val REQUEST_ALLOW = UiText(R.string.copy_request_allow)
    val REQUEST_DENY = UiText(R.string.copy_request_deny)
    val REQUEST_ALLOWED = UiText(R.string.copy_request_allowed)
    val REQUEST_DENIED = UiText(R.string.copy_request_denied)
    val REQUEST_GONE = UiText(R.string.copy_request_gone)
    /** A prompt under Fire OS's remote alert: it can't be read until the alert closes. */
    val REQUEST_BEHIND_ALERT = UiText(R.string.copy_request_behind_alert)
    val REQUEST_THEN = UiText(R.string.copy_request_then)
    val REQUEST_WAITING = UiText(R.string.copy_request_waiting)
    val PRESS_OK = UiText(R.string.copy_press_ok)

    // Who has access
    val WARNING_TITLE = UiText(R.string.copy_warning_title)
    val WARNING_TEXT = UiText(R.string.copy_warning_text)
    val ITS_MINE = UiText(R.string.copy_its_mine)
    val REMOVE_ACCESS = UiText(R.string.copy_remove_access)
    val CONNECTED_NOW = UiText(R.string.copy_connected_now)
    val WHO_HAS_ACCESS = UiText(R.string.copy_who_has_access)

    /** The TV menu's rows for the screens it opens. */
    val HISTORY = UiText(R.string.copy_history)
    val ADVANCED_MONITOR = UiText(R.string.copy_advanced_monitor)

    /** In the TV menu and in Settings: opens Android's share sheet with the repo link. */
    val SHARE = UiText(R.string.copy_share)

    /** Reset TV: its row in the TV menu, its screen, and what it says before, during and after. */
    val RESET_TV = UiText(R.string.copy_reset_tv)
    val RESET_KEEPS = UiText(R.string.copy_reset_keeps)

    fun apps(n: Int) = UiText.plural(R.plurals.copy_apps, n)

    fun resetFound(n: Int) = UiText.plural(R.plurals.copy_reset_found, n)
    val RESET_FOUND_TEXT = UiText(R.string.copy_reset_found_text)
    fun turnOnApps(n: Int) = UiText.plural(R.plurals.copy_turn_on_apps, n)
    val TURN_ON_TEXT = UiText(R.string.copy_turn_on_text)
    fun bringBackApps(n: Int) = UiText.plural(R.plurals.copy_bring_back_apps, n)
    val BRING_BACK_TEXT = UiText(R.string.copy_bring_back_text)
    fun resetHome(to: String) = UiText(R.string.copy_reset_home, to)
    fun resetHomeText(from: String, service: Boolean) =
        UiText(if (service) R.string.copy_reset_home_text_service else R.string.copy_reset_home_text, from)

    /** Under what Reset found: what it leaves alone. [maker] is the TV's brand, when known. */
    fun resetLeaves(shippedOff: Int, maker: String?): UiText = UiText.Join(
        listOfNotNull(
            if (shippedOff > 0) UiText.plural(R.plurals.copy_reset_leaves_off, shippedOff, shippedOff, maker ?: UiText(R.string.copy_the_maker)) else null,
            UiText(R.string.copy_reset_leaves),
        ),
        " ",
    )

    val NOTHING_TO_RESET = UiText(R.string.copy_nothing_to_reset_title)
    fun nothingToReset(ownHome: String?, maker: String?): UiText = UiText.Join(
        listOf(
            ownHome?.let { UiText(R.string.copy_nothing_to_reset_on_home, it) } ?: UiText(R.string.copy_nothing_to_reset_on),
            UiText(R.string.copy_nothing_to_reset_maker, maker ?: UiText(R.string.copy_the_maker)),
        ),
        " ",
    )

    fun resetting(p: ResetProgress, home: String?): UiText = UiText.Join(
        listOf(
            when (p.part) {
                ResetPart.TurnOn -> UiText(R.string.copy_resetting_turn_on, p.done, p.of)
                ResetPart.BringBack -> UiText(R.string.copy_resetting_bring_back, p.done, p.of)
                ResetPart.Home -> UiText(R.string.copy_resetting_home, home ?: TV_OWN_LAUNCHER)
            },
            UiText(R.string.copy_keep_tv_on),
        ),
        " ",
    )
    val RESETTING = UiText(R.string.copy_resetting)
    val RESET_APPS_STALE = UiText(R.string.copy_reset_apps_stale)
    val RESET_BUTTON = UiText(R.string.copy_reset_button)

    val RESET_DONE = UiText(R.string.copy_reset_done)
    val RESET_PARTLY = UiText(R.string.copy_reset_partly)
    fun resetDoneText(apps: Int, home: String?): UiText = when {
        apps != 0 && home != null -> UiText.plural(R.plurals.copy_reset_done_apps_home, apps, apps, home)
        apps != 0 -> UiText.plural(R.plurals.copy_reset_done_apps, apps)
        home != null -> UiText(R.string.copy_reset_done_home, home)
        else -> UiText(R.string.copy_reset_done_nothing)
    }
    fun resetPartlyText(failed: Int) = UiText.plural(R.plurals.copy_reset_partly_text, failed)
    fun turnedOn(done: Int, of: Int) =
        if (done == of) UiText.plural(R.plurals.copy_turned_on_all, of) else UiText.plural(R.plurals.copy_turned_on_some, of, done, of)
    fun broughtBack(done: Int, of: Int) =
        if (done == of) UiText.plural(R.plurals.copy_brought_back_all, of) else UiText.plural(R.plurals.copy_brought_back_some, of, done, of)
    fun isHome(app: String) = UiText(R.string.copy_is_home, app)
    fun stillHome(app: String) = UiText(R.string.copy_still_home, app)
    fun tryAgain(app: String?) = if (app != null) UiText(R.string.copy_try_app_again, app) else UiText(R.string.copy_try_again)

    /** Debloat while it turns the ticked apps off, then its result. */
    fun disablingApps(done: Int, of: Int) = UiText(R.string.copy_disabling_apps, done, of)
    val DISABLING = UiText(R.string.copy_disabling)
    val DISABLING_NOTE = UiText(R.string.copy_disabling_note)
    val DEBLOAT_DONE = UiText(R.string.copy_debloat_done)
    val DEBLOAT_PARTLY = UiText(R.string.copy_debloat_partly)
    val DEBLOAT_RESULT_TEXT = UiText(R.string.copy_debloat_result_text)
    fun turnedOff(done: Int, of: Int) =
        if (done == of) UiText.plural(R.plurals.copy_turned_off_all, of) else UiText.plural(R.plurals.copy_turned_off_some, of, done, of)
    fun stillOn(app: String) = UiText(R.string.copy_still_on, app)

    /** Under History in the menu, and on top of the History screen: apps Restore everything turns back on. */
    fun stillOff(n: Int) = UiText.plural(R.plurals.copy_still_off, n)
    fun stillOffTitle(n: Int) = UiText.plural(R.plurals.copy_still_off_title, n)
    val STILL_OFF_TEXT = UiText(R.string.copy_still_off_text)
    fun othersConnected(n: Int) = UiText.plural(R.plurals.copy_others_connected, n)
    fun connections(n: Int) = UiText.plural(R.plurals.copy_connections, n)
    fun connectedFrom(address: String, n: Int) = UiText.plural(R.plurals.copy_connected_from, n, address, n)
    val ON_TV = UiText(R.string.copy_on_tv)
    val ACCESS_INTRO = UiText(R.string.copy_access_intro)
    val THIS_PHONE = UiText(R.string.copy_this_phone)
    val TELLYSCOPE: UiText = UiText.Raw("Tellyscope")
    val NOT_THIS_PHONE = UiText(R.string.copy_not_this_phone)
    val YOURS = UiText(R.string.copy_yours)
    val WAITING_CHIP = UiText(R.string.copy_waiting_chip)
    val WAITING_LINE = UiText(R.string.copy_waiting_line)
    val WAITING_TEXT = UiText(R.string.copy_waiting_text)
    val ANSWER = UiText(R.string.copy_answer)
    val NEW_CHIP = UiText(R.string.copy_new_chip)
    val NEW_KEY_TEXT = UiText(R.string.copy_new_key_text)
    val ALLOWED_COMPUTERS = UiText(R.string.copy_allowed_computers)
    val UNNAMED_COMPUTER = UiText(R.string.copy_unnamed_computer)
    val CANT_CHECK_CONNECTED = UiText(R.string.copy_cant_check_connected)
    val CANT_CHECK_ALLOWED = UiText(R.string.copy_cant_check_allowed)
    val NOBODY_ALLOWED = UiText(R.string.copy_nobody_allowed)
    val ACCESS_FOOT_LINK = UiText(R.string.copy_access_foot_link)
    val ACCESS_FOOT_ALL = UiText(R.string.copy_access_foot_all)
    /** Rough on purpose: the TV stamps a key when any connection uses it, not per session. */
    fun lastSeen(at: Long?, now: Long): UiText {
        if (at == null) return UiText(R.string.copy_never_seen)
        val days = ((now - at) / 86_400_000).coerceAtLeast(0)
        return when {
            now - at < 5 * 60_000 -> UiText(R.string.copy_last_seen_just_now)
            days == 0L -> UiText(R.string.copy_last_seen_today, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at)))
            days == 1L -> UiText(R.string.copy_last_seen_yesterday)
            days < 30 -> UiText.plural(R.plurals.copy_last_seen_days_ago, days.toInt())
            else -> UiText(R.string.copy_last_seen_long_ago)
        }
    }
    /** Enough of a fingerprint to tell keys apart: the first three and last two pairs. */
    fun shortFingerprint(fp: String): String {
        val parts = fp.split(':')
        return if (parts.size <= 5) fp else (parts.take(3) + "…" + parts.takeLast(2)).joinToString(":")
    }

    // Remove all other access
    val REMOVE_TITLE = UiText(R.string.copy_remove_title)
    val CONFIRM_TITLE = UiText(R.string.copy_confirm_title)
    val CONFIRM_TEXT = UiText(R.string.copy_confirm_text)
    val CONFIRM_REMOTE = UiText(R.string.copy_confirm_remote)
    val CONFIRM_BUTTON = UiText(R.string.copy_confirm_button)
    val CANCEL = UiText(R.string.copy_cancel)
    val REVOKING = UiText(R.string.copy_revoking)
    val ALLOW_TITLE = UiText(R.string.copy_allow_title)
    val ALLOW_TEXT_A = UiText(R.string.copy_allow_text_a)
    val ALLOW_ALWAYS = UiText(R.string.copy_allow_always)
    val ALLOW_TEXT_B = UiText(R.string.copy_allow_text_b)
    val ALLOW_BUTTON = UiText(R.string.copy_allow_button)
    val FINGERPRINT_LABEL = UiText(R.string.copy_fingerprint_label)
    val FINGERPRINT_HINT = UiText(R.string.copy_fingerprint_hint)
    val STOP_WAITING = UiText(R.string.copy_stop_waiting)
    val REFUSED = UiText(R.string.copy_refused)
    val ASK_AGAIN = UiText(R.string.copy_ask_again)
    val CHECKING = UiText(R.string.copy_checking)
    val DONE_TITLE = UiText(R.string.copy_done_title)
    val DONE_TEXT = UiText(R.string.copy_done_text)
    val TRUST_WARNING = UiText(R.string.copy_trust_warning)
    fun backTo(tv: String) = UiText(R.string.copy_back_to, tv)
    val STILL_TITLE = UiText(R.string.copy_still_title)
    fun stillText(who: String, tv: String) = UiText(R.string.copy_still_text, who, tv)
    fun stillOpenText(who: String, tv: String) = UiText(R.string.copy_still_open_text, who, tv)
    val CHECK_AGAIN = UiText(R.string.copy_check_again)
    val RESTART_TV = UiText(R.string.copy_restart_tv)
    val RESTARTING_TO_CHECK = UiText(R.string.copy_restarting_to_check)
    fun connectionFrom(address: String) = UiText(R.string.copy_connection_from, address)
    val TRY_AGAIN = UiText(R.string.copy_try_again)
    val UNCONFIRMED_TITLE = UiText(R.string.copy_unconfirmed_title)
    val UNCONFIRMED_TEXT = UiText(R.string.copy_unconfirmed_text)
    val DONE = UiText(R.string.copy_done)
    val MANUAL_INTRO = UiText(R.string.copy_manual_intro)
    val MANUAL_STEP_1 = UiText(R.string.copy_manual_step_1)
    val MANUAL_STEP_1_TEXT = UiText(R.string.copy_manual_step_1_text)
    val OPEN_ON_TV = UiText(R.string.copy_open_on_tv)
    val MANUAL_STEP_2 = UiText(R.string.copy_manual_step_2)
    val MANUAL_STEP_2_TEXT = UiText(R.string.copy_manual_step_2_text)
    val MANUAL_DONE = UiText(R.string.copy_manual_done)
    val DEV_OPTIONS_FAILED = UiText(R.string.copy_dev_options_failed)

    // Connected devices
    val DEVICES_TITLE = UiText(R.string.copy_devices_title)
    fun showAll(n: Int) = UiText(R.string.copy_show_all, n)
    val SHOW_LESS = UiText(R.string.copy_show_less)
    val NO_DEVICES = UiText(R.string.copy_no_devices)
    fun group(g: Peripheral.Group): UiText = when (g) {
        Peripheral.Group.Bluetooth -> UiText.Raw("Bluetooth")
        Peripheral.Group.Input -> UiText(R.string.copy_group_input)
        Peripheral.Group.Storage -> UiText(R.string.copy_group_storage)
        Peripheral.Group.Hdmi -> UiText.Raw("HDMI-CEC")
        Peripheral.Group.Audio -> UiText(R.string.copy_group_audio)
    }
    /** The row's right-hand words. Collapsed rows have no group label, so Bluetooth says so there. */
    fun peripheralStatus(p: Peripheral, grouped: Boolean): UiText {
        val free = p.free?.let { UiText(R.string.copy_peripheral_free, bytesWhole(it)) }
        // p.status comes from the TV side in English; only that part is lowered.
        return if (!grouped && p.group == Peripheral.Group.Bluetooth) UiText(R.string.copy_peripheral_bluetooth, free ?: UiText.Raw(p.status.lowercase()))
        else free ?: UiText.Raw(p.status)
    }
}
