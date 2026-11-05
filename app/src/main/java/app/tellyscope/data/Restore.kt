// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.R
import app.tellyscope.adb.Tweak
import kotlinx.coroutines.CancellationException

/** An app as Restore everything sees it: on, off, or not installed for the user. */
enum class Was { On, Off, Gone }

/** One app Tellyscope changed: how it was before its first change, and how its last one left it. */
data class AppBefore(val before: Was, val last: Was)

/**
 * The rest of turning on a launcher a switch turned off: Home back to [from], the [service] that
 * switch turned on off, and the [fromService] it turned off on ([undoHomeSwitch]).
 */
data class SwitchUndo(val from: String?, val service: String?, val fromService: String?)

/**
 * Everything Tellyscope changed on one TV, each as it was before Tellyscope's first change to it:
 * what Restore everything puts back. However many times an app went off and on, it comes down to
 * one step, or none. Read from History alone; Restore skips whatever the TV already shows as it was.
 */
data class Restorable(
    /** Newest change first. */
    val apps: Map<String, AppBefore> = emptyMap(),
    /** The home activity before Tellyscope's first change to Home; null when it changed none, or replaced none. */
    val home: String? = null,
    /** Accessibility services a switch turned on or off, and whether each was on before. */
    val services: Map<String, Boolean> = emptyMap(),
    /** Each service as History last left it. */
    val servicesLast: Map<String, Boolean> = emptyMap(),
    /** Launchers whose newest open disable is a switch: turning one on undoes the rest of that switch too. */
    val switches: Map<String, SwitchUndo> = emptyMap(),
    /** Each setting Tellyscope changed, its keys as they were; null for a key that wasn't set. */
    val settings: Map<Tweak, Map<String, String?>> = emptyMap(),
) {
    /** What Restore does to the apps on a TV that shows them [now]. */
    fun plan(now: (String) -> Was): RestorePlan {
        val on = mutableListOf<String>()
        val off = mutableListOf<String>()
        val remove = mutableListOf<String>()
        val gone = mutableListOf<String>()
        for ((pkg, app) in apps) {
            val n = now(pkg)
            when {
                n == app.before -> Unit
                // Uninstalled since: nothing on the TV to turn on or off.
                n == Was.Gone -> gone += pkg
                app.before == Was.On -> on += pkg
                app.before == Was.Off -> off += pkg
                // Reset TV brought it back; before, it wasn't installed for the user.
                else -> remove += pkg
            }
        }
        return RestorePlan(on, off, remove, gone)
    }

    companion object {
        /**
         * Works out [Restorable] from [history], any order: the first change to each thing says how
         * it was, the last how it was left. A reset counts as turning on and bringing back each app
         * it did; once undone, it no longer counts for how they were left.
         */
        fun of(history: List<HistoryEntry>): Restorable {
            val before = mutableMapOf<String, Was>()
            val last = mutableMapOf<String, Was>()
            val newest = mutableMapOf<String, Long>()
            var home: String? = null
            var homeSeen = false
            val services = linkedMapOf<String, Boolean>()
            val serviceLast = mutableMapOf<String, Boolean>()
            val settings = linkedMapOf<Tweak, MutableMap<String, String?>>()
            val switches = mutableMapOf<String, SwitchUndo>()
            fun app(e: HistoryEntry, pkg: String, was: Was, now: Was) {
                before.putIfAbsent(pkg, was)
                if (e.undoneAt == null || e.action != Action.Reset) last[pkg] = now
                newest[pkg] = e.id
            }
            fun service(e: HistoryEntry, service: String, wasOn: Boolean) {
                services.putIfAbsent(service, wasOn)
                // An undone switch put its service back as it was.
                serviceLast[service] = if (e.undoneAt == null) !wasOn else wasOn
            }
            fun home(from: String?) {
                if (!homeSeen) home = from
                homeSeen = true
            }
            for (e in history.sortedBy { it.id }) {
                val p = e.previous.orEmpty()
                // As openDisable picks it: the newest disable nothing has undone.
                if (e.disables && e.undoneAt == null) {
                    if (e.action == Action.Home) switches[e.packageName] = SwitchUndo(p["from"], p["service"], p["fromService"])
                    else switches -= e.packageName
                }
                when (e.action) {
                    Action.Disable -> app(e, e.packageName, Was.On, Was.Off)
                    Action.Enable -> app(e, e.packageName, Was.Off, Was.On)
                    Action.Uninstall -> app(e, e.packageName, Was.On, Was.Gone)
                    Action.Home -> {
                        if (e.disables) app(e, e.packageName, Was.On, Was.Off)
                        if ("to" in p) home(p["from"])
                        p["service"]?.let { service(e, it, false) }
                        p["fromService"]?.let { service(e, it, true) }
                    }
                    Action.HomeBack -> {
                        if ("to" in p) home(p["from"])
                        p["service"]?.let { service(e, it, true) }
                    }
                    Action.Reset -> {
                        ResetRecord.list(p, ResetRecord.ON).forEach { app(e, it, Was.Off, Was.On) }
                        ResetRecord.list(p, ResetRecord.BACK).forEach { app(e, it, Was.Gone, Was.On) }
                        if (p[ResetRecord.TO] != null) home(p[ResetRecord.FROM])
                        p[ResetRecord.SERVICE]?.let { service(e, it, true) }
                    }
                    Action.Change -> Tweak.entries.firstOrNull { it.name == e.packageName }?.let { tweak ->
                        // Not putIfAbsent: it takes a key held as null, "wasn't set", for one not seen yet.
                        val first = settings.getOrPut(tweak) { mutableMapOf() }
                        e.previous?.forEach { (k, v) -> if (k !in first) first[k] = v }
                    }
                    Action.Stop, Action.Open, Action.ClearCaches -> Unit
                }
            }
            return Restorable(
                apps = before.keys.sortedByDescending { newest[it] }.associateWith { AppBefore(before.getValue(it), last[it] ?: before.getValue(it)) },
                home = home,
                services = services,
                servicesLast = serviceLast,
                switches = switches,
                settings = settings,
            )
        }
    }
}

/**
 * What Restore everything would do, by the last read of the TV's [apps] and [tweaks]: what
 * [RestoreSummary.count] says on the card and in the confirm, the same steps [restoreAll] counts.
 */
fun Restorable.summary(apps: List<AppInfo>, tweaks: TvTweaks?): RestoreSummary {
    val read = apps.associateBy { it.packageName }
    // Before the first read, as History left them.
    val plan = plan { pkg -> if (apps.isEmpty()) this.apps.getValue(pkg).last else read[pkg]?.let { if (it.enabled) Was.On else Was.Off } ?: Was.Gone }
    // Turning on a launcher a switch turned off undoes the rest of that switch too, newest first as Restore runs them.
    var homeNow = apps.firstOrNull { it.home }?.packageName
    val servicesNow = servicesLast.toMutableMap()
    for (pkg in plan.turnOn) switches[pkg]?.let { u ->
        u.from?.let { homeNow = it.substringBefore('/') }
        u.service?.let { servicesNow[it] = false }
        u.fromService?.let { servicesNow[it] = true }
    }
    val target = home?.substringBefore('/')?.takeIf { apps.isEmpty() || it in read }
    val installed = { service: String -> apps.isEmpty() || service.substringBefore('/') in read }
    return RestoreSummary(
        turnOn = plan.turnOn.size,
        turnOff = plan.turnOff.size,
        remove = plan.remove.size,
        home = (target != null && target != homeNow) || services.any { (s, on) -> installed(s) && (servicesNow[s] ?: on) != on },
        settings = settings.filter { (tweak, before) -> moved(tweak, before, tweaks) }.keys.toList(),
        gone = plan.gone.size,
    )
}

/** Whether the TV, showing [now], has [tweak] otherwise than [before]; not read yet counts as yes. */
private fun moved(tweak: Tweak, before: Map<String, String?>, now: TvTweaks?): Boolean {
    now ?: return true
    return when (tweak) {
        Tweak.PRIVATE_DNS -> {
            // A change to Automatic writes the mode alone: a key it left out is as the TV has it now.
            val hostname = if ("private_dns_mode" in before) before["private_dns_mode"] == "hostname" else now.dnsHost != null
            val host = if ("private_dns_specifier" in before) before["private_dns_specifier"] else now.dnsHost
            (if (hostname) host else null) != now.dnsHost
        }
        Tweak.ANIMATIONS -> "window_animation_scale" in before && scale(before["window_animation_scale"]) != scale(now.animationScale)
    }
}

/** An animation scale, unset being Android's 1×. */
private fun scale(v: String?) = v?.toFloatOrNull() ?: 1f

/**
 * Restore everything on one TV in the steps it counts: apps to [turnOn], to [turnOff] and to
 * [remove], [home] with its services as one, and each setting in [settings]. [gone] apps were
 * uninstalled since and can't come back, so they aren't counted.
 */
data class RestoreSummary(
    val turnOn: Int = 0,
    val turnOff: Int = 0,
    val remove: Int = 0,
    val home: Boolean = false,
    val settings: List<Tweak> = emptyList(),
    val gone: Int = 0,
) {
    val count get() = turnOn + turnOff + remove + (if (home) 1 else 0) + settings.size
}

/**
 * Restore everything's app steps on one TV, each newest change first: apps to [turnOn] and to
 * [turnOff] again, apps Reset TV brought back to [remove] again, and [gone] ones uninstalled since,
 * which can't come back.
 */
data class RestorePlan(
    val turnOn: List<String> = emptyList(),
    val turnOff: List<String> = emptyList(),
    val remove: List<String> = emptyList(),
    val gone: List<String> = emptyList(),
)

/** One thing Restore everything couldn't put back, and why. */
data class RestoreFailure(val what: UiText, val why: UiText)

/** How many steps Restore everything took, and the ones that didn't take. */
data class RestoreResult(val done: Int, val failed: List<RestoreFailure> = emptyList())

/**
 * Runs Restore everything's steps one at a time, on past any the TV refuses: the rest still go
 * back, and [result] names the ones that didn't. Those stay in History as they were, so the next
 * Restore tries them again.
 */
class RestoreRun {
    private var done = 0
    private val failed = mutableListOf<RestoreFailure>()

    suspend fun step(what: UiText, block: suspend () -> Unit) {
        try {
            block()
            done++
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dropped connection counts too: the user still needs to know what didn't go back.
            failed += RestoreFailure(what, e.why(R.string.data_tv_refused))
        }
    }

    fun result() = RestoreResult(done, failed.toList())
}
