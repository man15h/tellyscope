// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/**
 * What Reset TV would change on the TV's own apps. [turnOn]: preinstalled apps someone turned off
 * by hand, from Settings, a phone or a tool; Android marks those "disabled by the user". [bringBack]:
 * preinstalled apps uninstalled for this user, which are still on the TV. [shippedOff]: preinstalled
 * apps the maker shipped switched off; Reset leaves them off.
 */
data class ResetScan(val turnOn: List<String>, val bringBack: List<String>, val shippedOff: List<String>)

/**
 * Reset TV's reads and changes, on preinstalled apps only. App data and sign-ins stay throughout:
 * nothing here clears data.
 */
class Reset(private val shell: AdbShell) {

    suspend fun scan(): ResetScan {
        val system = names("pm list packages -s")
        val off = names("pm list packages -s -d")
        val gone = names("pm list packages -s -u") - system
        val state = states((off + gone).sorted())
        return ResetScan(
            turnOn = off.filter { state[it]?.enabled == DISABLED_USER }.sorted(),
            // Android 10+ leaves some apps out for a type of user on purpose; those never shipped on.
            bringBack = gone.filter { state[it]?.let { s -> !s.installed && s.uninstallReason != USER_TYPE } == true }.sorted(),
            shippedOff = off.filter { state[it]?.enabled.let { e -> e != null && e != DISABLED_USER } }.sorted(),
        )
    }

    /** Turns [pkg] back on. Throws with the TV's answer when it refuses. */
    suspend fun turnOn(pkg: String) = exec("pm enable --user 0 ${checked(pkg)}") { "enabled" in it }

    /** Turns [pkg] off again, for Undo. */
    suspend fun turnOff(pkg: String) = exec("pm disable-user --user 0 ${checked(pkg)}") { "disabled-user" in it }

    /** Installs [pkg] again for this user from the copy still on the TV. */
    suspend fun bringBack(pkg: String) = exec("pm install-existing --user 0 ${checked(pkg)}") { "installed for user" in it }

    /**
     * Uninstalls [pkg] for this user again, keeping its data, for Undo of [bringBack] and for
     * Restore everything. The only `--user 0` uninstall Tellyscope runs, and only on apps Reset
     * itself brought back.
     */
    suspend fun remove(pkg: String) = exec("pm uninstall -k --user 0 ${checked(pkg)}") { "Success" in it }

    private suspend fun names(command: String) = Parsers.packages(shell.shell(command).output).map { it.name }.toSet()

    /** Each package's state for user 0, in one call: whether it's installed, how it's enabled, and why it was uninstalled. */
    private suspend fun states(pkgs: List<String>): Map<String, UserState> {
        val safe = pkgs.filter { Actions.PACKAGE.matches(it) }
        if (safe.isEmpty()) return emptyMap()
        val out = shell.shell(
            "for p in ${safe.joinToString(" ")}; do echo \"@@\$p\"; dumpsys package \"\$p\" | grep -E '^ *User 0:|uninstallReason='; done",
        ).output
        return out.split("@@").drop(1).mapNotNull { block ->
            val lines = block.lines()
            val pkg = lines.first().trim()
            // An updated system app lists itself twice; the first is the one that counts.
            val user = lines.firstOrNull { it.trim().startsWith("User 0:") } ?: return@mapNotNull null
            val reason = lines.dropWhile { it !== user }.firstNotNullOfOrNull { Regex("uninstallReason=(\\d+)").find(it) }
            pkg to UserState(
                installed = Regex("installed=(\\w+)").find(user)?.groupValues?.get(1) != "false",
                enabled = Regex("enabled=(\\d+)").find(user)?.groupValues?.get(1)?.toIntOrNull(),
                uninstallReason = reason?.groupValues?.get(1)?.toIntOrNull(),
            )
        }.toMap()
    }

    private suspend fun exec(command: String, ok: (String) -> Boolean) {
        val r = shell.shell("$command 2>&1")
        if (r.exitCode != 0 || !ok(r.output)) throw IllegalStateException(r.output.trim().ifEmpty { "the TV refused" })
    }

    private fun checked(pkg: String) = pkg.also { require(Actions.PACKAGE.matches(it)) { "not a package name: $it" } } // it goes into a shell line

    private data class UserState(val installed: Boolean, val enabled: Int?, val uninstallReason: Int?)

    private companion object {
        /** PackageManager's COMPONENT_ENABLED_STATE_DISABLED_USER: what `pm disable-user` and Settings' Disable set. */
        const val DISABLED_USER = 3
        /** PackageManager's UNINSTALL_REASON_USER_TYPE: never installed for this kind of user. */
        const val USER_TYPE = 1
    }
}
