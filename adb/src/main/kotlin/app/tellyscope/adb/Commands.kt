// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/**
 * Shell snippets, batched so one tier costs one ADB round trip. Sections are split on [SEP].
 */
internal object Commands {
    const val SEP = "@@tellyscope@@"

    const val THERMAL =
        "for z in /sys/class/thermal/thermal_zone*; do echo \"$(cat \$z/type 2>/dev/null)=$(cat \$z/temp 2>/dev/null)\"; done"

    /** Every 2 s: CPU counters, memory and the byte counters for network speed. */
    val FAST = listOf("cat /proc/stat", "cat /proc/meminfo", "cat /proc/net/dev").batch()

    /** Every 60 s and on open: identity, storage, uptime, temperature. */
    val SLOW = listOf(
        "getprop",
        "df -k /data",
        "cat /proc/uptime",
        THERMAL,
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
    const val HOME_ACTIVITY = "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
    const val SET_HOME = "cmd package set-home-activity --user 0"
    const val HOME_ACTIVITIES = "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

    private fun List<String>.batch() = joinToString("; echo $SEP; ") { "$it 2>/dev/null" }

    fun split(output: String, expected: Int): List<String> =
        output.split("$SEP\n", SEP).let { parts -> List(expected) { parts.getOrElse(it) { "" } } }
}
