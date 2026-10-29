// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/**
 * The only changes Tellyscope makes. Undo for [STOP] is [OPEN] ("open again"); a real undo isn't
 * possible. [UNINSTALL] has no undo at all, so it only ever runs on apps the user installed.
 */
enum class Action { STOP, DISABLE, ENABLE, OPEN, UNINSTALL }

sealed interface ActionResult {
    data class Done(val undo: Action?) : ActionResult
    data class Refused(val reason: ProtectedReason) : ActionResult
    data class Failed(val output: String) : ActionResult
}

/**
 * What [Actions.makeHome] did. [from] and [to] are the old and new home activities, for Undo;
 * [from] is null when the TV had no default home and no launcher of its own to replace. [kept]:
 * the old launcher was one the user added, so it stayed on. Once any service change is done, the
 * caller hands the TV over with [Actions.handOff]. A [Failed.output] is one plain line;
 * [Failed.details] keeps what the TV printed, for a problem report.
 */
sealed interface HomeResult {
    data class Switched(val from: String?, val to: String, val kept: Boolean = false) : HomeResult
    data class Refused(val reason: ProtectedReason) : HomeResult
    data class Failed(val output: String, val details: String = output) : HomeResult
}

/** A step of [Actions.handOff] that didn't take: [reason] is one plain line, [output] what the TV printed. */
data class HandOffFailed(val reason: String, val output: String)

/**
 * [current] is Home; [others] are the launchers that can take over. With no default home, [current]
 * is the TV's own launcher, the one the others would replace, or null when none is on; [default]
 * is false then.
 */
data class HomeOptions(val current: String?, val others: Set<String>, val default: Boolean = true)

class Actions(private val shell: AdbShell) {

    suspend fun run(action: Action, pkg: String): ActionResult {
        require(PACKAGE.matches(pkg)) { "not a package name: $pkg" } // it goes into a shell line
        if (action != Action.ENABLE && action != Action.OPEN) {
            // No launcher answer (no `cmd` before Android 7, or an error): we can't tell what's safe.
            val home = homePackages() ?: return ActionResult.Refused(ProtectedReason.UNKNOWN_HOME)
            // Only the name guard asks whether the user installed it; the live Home is refused unasked.
            val why = Protected.reason(pkg, home)
                ?.let { if (it == ProtectedReason.HOME_SCREEN && pkg !in home) Protected.reason(pkg, home, userApp = pkg in userPackages()) else it }
            why?.let { return ActionResult.Refused(it) }
        }
        return when (action) {
            Action.STOP -> exec("am force-stop $pkg", Action.OPEN)
            Action.DISABLE -> exec("pm disable-user --user 0 $pkg", Action.ENABLE) { "disabled-user" in it }
            Action.ENABLE -> exec("pm enable --user 0 $pkg", Action.DISABLE) { "enabled" in it }
                .let { if (it is ActionResult.Failed && SHELL_REFUSED in it.output) ActionResult.Refused(ProtectedReason.DISABLED_BY_TV) else it }
            Action.OPEN -> open(pkg)
            Action.UNINSTALL -> uninstall(pkg)
        }
    }

    /**
     * Only for packages `pm list packages -3` lists, i.e. installed by the user. Plain
     * `pm uninstall`, never `--user 0`: that form removes system apps for a user, and Tellyscope
     * never does that.
     */
    private suspend fun uninstall(pkg: String): ActionResult {
        if (pkg !in userPackages()) return ActionResult.Refused(ProtectedReason.CAME_WITH_TV)
        return exec("pm uninstall $pkg", undo = null) { "Success" in it }
    }

    private suspend fun userPackages() = Parsers.packages(shell.shell(Commands.USER_PACKAGES).output).map { it.name }.toSet()

    /**
     * Launchers to protect: the default one, or with no default (then resolve-activity answers
     * with the system chooser, package `android`) every built-in one. A launcher the user installed
     * isn't locked then, unless it's the only launcher left: the TV must keep one. Null when the
     * TV didn't answer, so nothing is known to be safe.
     */
    suspend fun homePackages(): Set<String>? {
        val current = packagesIn(shell.shell(Commands.HOME_ACTIVITY).output) - "android"
        if (current.isNotEmpty()) return current
        val homes = shell.shell(Commands.HOME_ACTIVITIES).output
        val all = packagesIn(homes) - "android"
        if (all.isEmpty()) return null
        val user = userPackages()
        val last = launchersIn(homes).keys.takeIf { it.size == 1 && it.single() in user }.orEmpty()
        return all - user + last
    }

    /**
     * Null when the TV didn't answer or has no launcher. With no default home, the TV's own
     * launcher stands as [HomeOptions.current]; with none of its own on, every launcher is offered.
     */
    suspend fun homeOptions(): HomeOptions? {
        currentHome()?.substringBefore('/')?.let { return HomeOptions(it, launchers().keys - it) }
        val launchers = launchers().keys.ifEmpty { return null }
        val stock = stockHome()?.substringBefore('/')
        return HomeOptions(stock, launchers - setOfNotNull(stock), default = false)
    }

    /**
     * Makes [pkg] the home screen in place of the current one, then disables the old launcher so
     * the TV can't switch back by itself. Only a launcher the TV came with is ever disabled: one
     * the user added stays on, and the switch only moves Home. Home is checked to open [pkg] before anything is
     * disabled; if it doesn't, the old home is set back and nothing is turned off. The one
     * exception: Android skips the default while a launcher with a higher Home priority is on, as
     * Android TV Home is. Such an old launcher is turned off first, and turned back on and made
     * Home again if Home still doesn't open [pkg].
     */
    suspend fun makeHome(pkg: String): HomeResult {
        require(PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        // With no default home, the TV's own launcher is the one being replaced; with none on, nothing is.
        val from = currentHome() ?: stockHome() ?: return firstHome(pkg)
        val old = from.substringBefore('/')
        // With no default home, the TV's own launcher can be made the default too.
        if (old == pkg) return if (currentHome() == null) firstHome(pkg) else HomeResult.Failed("it's already the home screen")
        val homes = shell.shell(Commands.HOME_ACTIVITIES).output
        val to = launchersIn(homes)[pkg] ?: return HomeResult.Failed("it has no home screen")
        // The old launcher is protected as the home screen and by name; once it isn't Home, that
        // guard has done its job. Any other reason still stands.
        Protected.reason(old)?.takeIf { it != ProtectedReason.HOME_SCREEN }?.let { return HomeResult.Refused(it) }
        if (old in userPackages()) {
            if (setHome(to)) return HomeResult.Switched(from, to, kept = true)
            setHome(from)
            return HomeResult.Failed("the TV kept the home screen it has")
        }
        val checked = setHome(to)
        val priority = prioritiesIn(homes)
        if (!checked && (priority[old] ?: 0) <= (priority[pkg] ?: 0)) {
            setHome(from)
            return HomeResult.Failed("the TV kept its own home screen, so nothing was turned off")
        }
        require(PACKAGE.matches(old)) { "not a package name: $old" } // read from the TV, and it goes into a shell line
        val off = exec("pm disable-user --user 0 $old", undo = null) { "disabled-user" in it }
        if (off is ActionResult.Failed) {
            setHome(from)
            return HomeResult.Failed("the TV wouldn't turn the old home screen off, so nothing changed", off.output)
        }
        if (checked || setHome(to)) return HomeResult.Switched(from, to)
        val on = exec("pm enable --user 0 $old", undo = null) { "enabled" in it }
        setHome(from)
        if (on is ActionResult.Failed) {
            return HomeResult.Failed("Home didn't open it, and the old home screen didn't turn back on. Turn it back on from Apps → Built-in", on.output)
        }
        return HomeResult.Failed("Home still didn't open it, so the old home screen was turned back on")
    }

    /**
     * Makes [pkg] Home again after another launcher took over: turns it on if it's off, then sets
     * [activity], the one the switch away from it saved, or else its own home activity. The
     * launcher that was Home stays on, so it can be made Home again or uninstalled.
     */
    suspend fun homeBack(pkg: String, activity: String? = null): HomeResult {
        require(PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        require(activity == null || (ACTIVITY.matches(activity) && activity.substringBefore('/') == pkg)) { "not $pkg's activity: $activity" }
        val from = currentHome() ?: return HomeResult.Refused(ProtectedReason.UNKNOWN_HOME)
        if (from.substringBefore('/') == pkg) return HomeResult.Failed("it's already the home screen")
        val on = exec("pm enable --user 0 $pkg", undo = null) { "enabled" in it }
        if (on is ActionResult.Failed) return HomeResult.Failed("the TV wouldn't turn it back on", on.output)
        val to = activity ?: launchers()[pkg] ?: return HomeResult.Failed("it has no home screen")
        if (!setHome(to)) {
            setHome(from)
            return HomeResult.Failed("the TV kept the home screen it has")
        }
        return HomeResult.Switched(from, to)
    }

    /** Makes [activity] (`package/class`) the default home, then checks that Home opens it. */
    suspend fun setHome(activity: String): Boolean {
        require(ACTIVITY.matches(activity)) { "not an activity: $activity" }
        shell.shell("${Commands.SET_HOME} '$activity' 2>&1") // a nested class has a $
        return currentHome()?.substringBefore('/') == activity.substringBefore('/')
    }

    /** Apps the TV turned off itself, which [run] can't turn back on ([ProtectedReason.DISABLED_BY_TV]). */
    suspend fun disabledByTv(): Set<String> = Parsers.disabledByTv(shell.shell(Commands.ENABLED_STATES).output)

    /** Packages the TV can open from a launcher icon; null when it didn't say (no `cmd` before Android 7). */
    suspend fun launchable(): Set<String>? =
        packagesIn(shell.shell(Commands.LAUNCHABLE).output).ifEmpty { null }

    /** The [launchable] packages with a TV home-screen icon; null when the TV lists none of that kind. */
    suspend fun tvLaunchable(): Set<String>? =
        packagesIn(shell.shell("${Commands.TV_LAUNCHABLE} 2>/dev/null").output).ifEmpty { null }

    /** [pkg]'s accessibility service as the TV has it installed; null when it has none. */
    suspend fun homeService(pkg: String): String? =
        activitiesIn(shell.shell(Commands.A11Y_SERVICES).output).firstOrNull { it.substringBefore('/') == pkg }

    suspend fun serviceOn(service: String): Boolean = HomeServices.has(shell.shell(Commands.A11Y_ON).output, service)

    /**
     * Turns accessibility [service] on or off, leaving the user's other services as they are, then
     * reads the setting back. True when it took.
     */
    suspend fun setService(service: String, on: Boolean): Boolean {
        require(ACTIVITY.matches(service)) { "not a service: $service" }
        val list = shell.shell(Commands.A11Y_ON).output
        val next = if (on) HomeServices.with(list, service) else HomeServices.without(list, service)
        // Every entry goes inside one quoted shell word; a quote of its own would end it.
        if (next.any { '\'' in it }) return false
        if (next.isEmpty()) shell.shell("settings delete secure enabled_accessibility_services 2>&1")
        else shell.shell("settings put secure enabled_accessibility_services '${next.joinToString(":")}' 2>&1")
        if (on) shell.shell("settings put secure accessibility_enabled 1 2>&1")
        return serviceOn(service) == on
    }

    /** Opens the Play Store on the TV at [pkg]'s page. False when it didn't: no Store, or it refused the link. */
    suspend fun openStore(pkg: String): Boolean {
        require(PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        val r = shell.shell("${Commands.OPEN_STORE} 'market://details?id=$pkg' 2>&1")
        return r.exitCode == 0 && "Error" !in r.output
    }

    /** `pm path` names an installed package's APK and prints nothing for one that isn't. */
    suspend fun installed(pkg: String): Boolean {
        require(PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        return shell.shell("pm path $pkg 2>/dev/null").output.lineSequence().any { it.startsWith("package:") }
    }

    /** Whether the Play Store is the app on screen. */
    suspend fun storeOnScreen(): Boolean =
        shell.shell(Commands.RESUMED).output.lineSequence().any { "ResumedActivity" in it && " ${Commands.PLAY_STORE}/" in it }

    /** The activity Home opens, or null when Android would ask (its chooser lives in `android`). */
    suspend fun currentHome(): String? =
        activitiesIn(shell.shell(Commands.HOME_ACTIVITY).output).singleOrNull()?.takeUnless { it.startsWith("android/") }

    /** No default home and no launcher of the TV's own on: sets [pkg] as Home, with nothing to turn off. */
    private suspend fun firstHome(pkg: String): HomeResult {
        val to = launchers()[pkg] ?: return HomeResult.Refused(ProtectedReason.UNKNOWN_HOME)
        if (!setHome(to)) return HomeResult.Failed("the TV still asks which home screen to use")
        return HomeResult.Switched(null, to)
    }

    /**
     * After a switch, so the TV shows the new home screen without a Home press: closes [old], the
     * launcher that was Home if it's still on, then opens [to]. Last, after any accessibility
     * service change: a service still on can pull Home straight back. Home has moved either way,
     * so a step that fails is only reported: what went wrong, or null when both took.
     */
    suspend fun handOff(old: String?, to: String): HandOffFailed? {
        require(old == null || PACKAGE.matches(old)) { "not a package name: $old" }
        require(ACTIVITY.matches(to)) { "not an activity: $to" }
        val stop = old?.takeIf { it != to.substringBefore('/') }?.let { exec("am force-stop $it", undo = null) }
        val open = shell.shell("${Commands.OPEN_HOME} '$to' 2>&1")
        return when {
            open.exitCode != 0 || "Error" in open.output -> HandOffFailed("it didn't open, so press Home on the remote to go there", open.output.trim())
            stop is ActionResult.Failed -> HandOffFailed("the old home screen didn't close", stop.output)
            else -> null
        }
    }

    /** With no default home, the TV's own launcher that is on: the built-in one Home ranks highest. */
    private suspend fun stockHome(): String? {
        val homes = shell.shell(Commands.HOME_ACTIVITIES).output
        val builtIn = launchersIn(homes).ifEmpty { return null } - userPackages()
        val priority = prioritiesIn(homes)
        return builtIn.maxByOrNull { priority[it.key] ?: 0 }?.value
    }

    /**
     * Every enabled launcher, package to its home activity. Apps protected for another reason
     * aren't launchers, though they may answer Home: TV Settings has a fallback home screen.
     */
    private suspend fun launchers(): Map<String, String> = launchersIn(shell.shell(Commands.HOME_ACTIVITIES).output)

    private fun launchersIn(brief: String): Map<String, String> =
        activitiesIn(brief).filterNot { it.startsWith("android/") }
            .associateBy { it.substringBefore('/') }
            .filterKeys { Protected.reason(it).let { r -> r == null || r == ProtectedReason.HOME_SCREEN } }

    /** Each package's Home priority, from the `priority=` line `--brief` prints above its activity. */
    private fun prioritiesIn(brief: String): Map<String, Int> {
        val priorities = mutableMapOf<String, Int>()
        var last: Int? = null
        for (line in brief.lineSequence().map(String::trim)) {
            if (line.startsWith("priority=")) last = line.removePrefix("priority=").substringBefore(' ').toIntOrNull()
            else if ('/' in line && ' ' !in line) {
                last?.let { priorities[line.substringBefore('/')] = it }
                last = null
            }
        }
        return priorities
    }

    private fun packagesIn(brief: String): Set<String> = activitiesIn(brief).map { it.substringBefore('/') }.toSet()

    private fun activitiesIn(brief: String): List<String> =
        brief.lineSequence().map(String::trim).filter { '/' in it && ' ' !in it }.distinct().toList()

    /**
     * TV apps register under LEANBACK_LAUNCHER only; phone-style apps under LAUNCHER. Try both, then
     * HOME, for a launcher with no icon of its own. `--pct-syskeys 0` because monkey aborts before
     * launching on a TV whose remote has no keys it counts as system keys (Fire TV, exit -5).
     */
    private suspend fun open(pkg: String): ActionResult {
        require(PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        var last = ""
        for (category in listOf("android.intent.category.LEANBACK_LAUNCHER", "android.intent.category.LAUNCHER", "android.intent.category.HOME")) {
            require(CATEGORY.matches(category)) { "not an intent category: $category" } // both go into the shell line
            val r = shell.shell("monkey -p $pkg -c $category --pct-syskeys 0 1 2>&1")
            if (r.exitCode == 0 && "No activities found" !in r.output && "aborted" !in r.output) {
                return ActionResult.Done(undo = null)
            }
            last = r.output
        }
        return ActionResult.Failed(last.trim())
    }

    private suspend fun exec(command: String, undo: Action?, ok: (String) -> Boolean = { true }): ActionResult {
        val r = shell.shell("$command 2>&1")
        return if (r.exitCode == 0 && ok(r.output)) ActionResult.Done(undo) else ActionResult.Failed(r.output.trim())
    }

    internal companion object {
        val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+|android")
        val ACTIVITY = Regex("(${PACKAGE.pattern})/[A-Za-z0-9_.$]+")
        private val CATEGORY = Regex("android\\.intent\\.category\\.[A-Z_]+")

        /**
         * Android's SecurityException when ADB tries to turn on an app the TV disabled itself:
         * shell may only move a package between default, enabled and disabled-user.
         */
        const val SHELL_REFUSED = "Shell cannot change component state"
    }
}
