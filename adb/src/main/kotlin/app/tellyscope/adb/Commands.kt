// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/**
 * Shell snippets, batched so one tier costs one ADB round trip. Sections are split on [SEP].
 */
internal object Commands {
    const val SEP = "@@tellyscope@@"

    /** Every 2 s: CPU counters, memory and the byte counters for network speed. */
    val FAST = listOf("cat /proc/stat", "cat /proc/meminfo", "cat /proc/net/dev").batch()

    /** Every 60 s and on open: identity, storage, uptime, packages. */
    val SLOW = listOf(
        "getprop",
        "df -k /data",
        "cat /proc/uptime",
        PACKAGES,
    ).batch()

    /** Every 10 s on Overview and Apps: memory per process, without `top`'s CPU sampling. */
    const val LIGHT = "ps -A -o PID,USER,RSS,NAME 2>/dev/null"

    /** Every 10 s on Advanced: processes with CPU, and whether the screen is on. */
    val MEDIUM = listOf(
        "top -b -n 1 -o PID,USER,RES,%CPU,NAME",
        "dumpsys power | grep -E 'mWakefulness=|mScreenOn='",
    ).batch()

    /** Open TCP connections, IPv4 and IPv6. */
    const val SOCKETS = "cat /proc/net/tcp /proc/net/tcp6"

    /** Bytes per app since boot; xt_qtaguid answers on kernels from before Android 10's eBPF accounting. */
    val TRAFFIC = listOf("dumpsys netstats --uid", "cat /proc/net/xt_qtaguid/stats").batch()

    const val PACKAGES = "pm list packages -U"
    /** Apps the user installed: the only ones Tellyscope may uninstall. */
    const val USER_PACKAGES = "pm list packages -3"
    /** Each package's `Package [name]` line and its `User 0: … enabled=N` line, for [Parsers.disabledByTv]. */
    const val ENABLED_STATES = "dumpsys package packages | grep -e '^  Package \\[' -e '^    User 0:'"
    /** The apps the TV came with, disabled ones included. */
    const val SYSTEM_PACKAGES = "pm list packages -s"
    const val HOME_ACTIVITY = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    const val SET_HOME = "cmd package set-home-activity --user 0"
    /** Opens a launcher's home activity, named after `-n`; Fire TV's Home key won't reach anything but Amazon's. */
    const val OPEN_HOME = "am start -a android.intent.action.MAIN -c android.intent.category.HOME -n"
    const val HOME_ACTIVITIES = "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    /** Apps with an icon on a TV home screen. */
    const val TV_LAUNCHABLE = "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER"
    /** Apps with an icon on a TV home screen, then those with one on a phone's; some TVs list only one kind. */
    const val LAUNCHABLE =
        "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER 2>/dev/null; " +
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>/dev/null"
    const val A11Y_SERVICES = "cmd package query-services --brief -a android.accessibilityservice.AccessibilityService"
    const val A11Y_ON = "settings get secure enabled_accessibility_services"
    const val PLAY_STORE = "com.android.vending"
    /** Opens a `market://` link in the Play Store only; `-W` waits, so a refusal prints an Error. */
    const val OPEN_STORE = "am start -W -a android.intent.action.VIEW -p $PLAY_STORE -d"
    /** The app on screen: `mResumedActivity` up to Android 13, `topResumedActivity` from 14. */
    const val RESUMED = "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"

    private fun List<String>.batch() = joinToString("; echo $SEP; ") { "$it 2>/dev/null" }

    fun split(output: String, expected: Int): List<String> =
        output.split("$SEP\n", SEP).let { parts -> List(expected) { parts.getOrElse(it) { "" } } }
}
