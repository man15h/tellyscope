// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.YearMonth
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One reading for the stat strip. Null fields mean "this TV doesn't report it". */
data class Stats(
    val cpuPercent: Double?,
    val memory: Parsers.Memory?,
    val storage: Parsers.Storage? = null,
    val uptimeSeconds: Long? = null,
    val model: String? = null,
    val manufacturer: String? = null,
    /** The name on the box; the manufacturer can be the factory (Philips TVs say TPV). */
    val brand: String? = null,
    val androidVersion: String? = null,
    val sdk: Int? = null,
    /** Uid → packages, from the slow tier; empty where `pm` can't list uids. */
    val packages: Map<Int, List<String>> = emptyMap(),
    val processes: List<AppProcess> = emptyList(),
    val screenOn: Boolean? = null,
    /** Bytes in and out per second over the whole TV, between the last two fast readings. */
    val netBytesPerSec: Long? = null,
    val softwareMonth: YearMonth? = null,
)

/** One process and the package it belongs to; null for native daemons. */
data class AppProcess(val pid: Int, val name: String, val packageName: String?, val cpuPercent: Double?, val rssKb: Long)

/** Where [DeviceLink] gets readings from; [DeviceMonitor] in the app, a fake in tests. */
fun interface MonitorSource {
    fun stats(): Flow<Stats>
}

/** How much of the process list a screen needs. */
enum class ProcessLevel {
    /** Nothing shown: no process read; the last list is kept. */
    NONE,

    /** Memory per app (Overview, Apps): `ps`, no CPU and no screen state. */
    LIGHT,

    /** CPU per process and the screen state (Advanced): `top` and `dumpsys power`. */
    FULL,
}

/**
 * How often one screen refreshes what it shows: the stat strip (CPU, RAM, network) every [fastEvery],
 * the process list at [processes] every [processEvery].
 */
data class StatsProfile(val fastEvery: Duration, val processes: ProcessLevel, val processEvery: Duration) {
    companion object {
        val OVERVIEW = StatsProfile(2.seconds, ProcessLevel.LIGHT, 2.seconds)
        val APPS = StatsProfile(10.seconds, ProcessLevel.LIGHT, 10.seconds)
        val ADVANCED = StatsProfile(2.seconds, ProcessLevel.FULL, 2.seconds)

        /** Files, Tweaks, Remote, Settings, Debloat, and every TV that isn't on screen. */
        val OTHER = StatsProfile(10.seconds, ProcessLevel.NONE, 10.seconds)
    }
}

/**
 * What the app tells one TV's stats loop. [during] pauses it while a change runs, so a slow read
 * never competes with `pm`, an install or a Home switch; [profile] is the open screen's refresh
 * rates. Until a screen sets one, everything is read every 2 s. [paused] is the app in the
 * background: no stats are read and a dropped link doesn't retry until it's false again.
 * [changed] counts the ended changes to apps or storage, so what they touch is read again right after one.
 */
class StatsGate {
    private val running = MutableStateFlow(0)

    val profile = MutableStateFlow(StatsProfile.ADVANCED)

    val paused = MutableStateFlow(false)

    val changed = MutableStateFlow(0)

    val busy: Boolean get() = running.value > 0

    /**
     * [reread]: the change stops, starts, disables, installs or removes an app, or frees or fills storage,
     * so the process list, storage and the package list are read again once it ends.
     */
    suspend fun <T> during(reread: Boolean = false, block: suspend () -> T): T {
        running.update { it + 1 }
        try {
            return block()
        } finally {
            running.update { it - 1 }
            if (reread) changed.update { it + 1 }
        }
    }

    internal suspend fun awaitIdle() {
        running.first { it == 0 }
    }

    internal suspend fun awaitResumed() {
        paused.first { !it }
    }
}

/**
 * Live stats on three tiers, at the rates of [StatsGate.profile]: CPU/RAM/network speed, the process
 * list (read at once when its level changes), and identity/storage/uptime/packages every
 * [slow]. Reads run one after another, never overlapping: a tier slower than its interval just
 * reads back to back. A new profile takes effect at once, not after the old interval.
 * The first emission comes after one fast interval, since CPU % needs two samples.
 * A read slower than [readTimeout] skips that sample. Shell failures throw, and so does a slow read
 * the TV then doesn't follow with an `echo` answer within [pingTimeout], so the caller can mark it
 * offline instead of waiting out the socket timeout.
 */
class DeviceMonitor(
    private val shell: AdbShell,
    private val slow: Duration = 60.seconds,
    private val readTimeout: Duration = 10.seconds,
    private val pingTimeout: Duration = 10.seconds,
    /** While a change runs, how often the TV is pinged instead of read. */
    private val pingEvery: Duration = 10.seconds,
    private val gate: StatsGate = StatsGate(),
    /** Times the tiers and network speed; tests pass the test scheduler's clock. */
    private val clock: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) : MonitorSource {
    override fun stats(): Flow<Stats> = flow {
        var slowStats = Stats(cpuPercent = null, memory = null)
        var lastSlow: TimeMark? = null
        var procStats: Pair<List<AppProcess>, Boolean?> = emptyList<AppProcess>() to null
        var lastProcs: TimeMark? = null
        var lastLevel = ProcessLevel.NONE
        var seenChanged = gate.changed.value
        var prev: Fast? = null

        while (true) {
            // Back from the background: CPU % and network speed start from fresh samples, not an average over the pause.
            if (gate.paused.value) {
                gate.awaitResumed()
                prev = null
            }
            val profile = gate.profile.value
            // Tiers are timed from the start of the round they ran in, so a 2 s tier runs every round.
            val started = clock.markNow()
            // A change just ended (an app stopped, installed, uninstalled; caches cleared): what it touched
            // can't wait out its interval. Storage and packages first, so a new app's processes are named.
            val fresh = gate.changed.value != seenChanged
            if (fresh) seenChanged = settled()
            // A skipped tier waits its whole interval again, so a TV that is always slow isn't asked every round.
            if (lastSlow.due(slow) || fresh) {
                readSlow()?.let { slowStats = it }
                lastSlow = started
            }
            val level = profile.processes
            if (level != ProcessLevel.NONE && (level != lastLevel || lastProcs.due(profile.processEvery) || fresh)) {
                readProcesses(level, slowStats.packages, procStats.second)?.let { procStats = it }
                lastProcs = started
                lastLevel = level
            }
            val now = readFast()
            val last = prev
            if (now != null && last != null) {
                val pct = if (last.cpu != null && now.cpu != null) Parsers.cpuPercent(last.cpu, now.cpu) else null
                emit(
                    slowStats.copy(
                        cpuPercent = pct,
                        memory = now.memory,
                        processes = procStats.first,
                        screenOn = procStats.second,
                        netBytesPerSec = rate(last, now),
                    ),
                )
            }
            if (now != null) prev = now
            val wait = profile.fastEvery - started.elapsedNow()
            if (wait.isPositive()) {
                withTimeoutOrNull(wait) {
                    combine(gate.profile, gate.changed) { p, c -> p != profile || c != seenChanged }.first { it }
                }
            }
        }
    }

    private fun TimeMark?.due(every: Duration) = this == null || elapsedNow() >= every

    /**
     * Waits until no change runs, and returns the count then: a Debloat, one change around one per
     * app, is read again once at the end rather than after each app.
     */
    private suspend fun settled(): Int {
        awaitIdle()
        return gate.changed.value
    }

    /** Pings while a change runs, so one that dies mid-change still ends instead of hanging. */
    private suspend fun awaitIdle() {
        while (withTimeoutOrNull(pingEvery) { gate.awaitIdle() } == null) ping()
    }

    /**
     * Null skips the sample: the read ran past [readTimeout] while a change was running, or the TV
     * still answered `echo` after it. While a change runs nothing is read, but the TV is pinged
     * every [pingEvery], so one that dies mid-change still ends the change instead of hanging it.
     */
    private suspend fun run(command: String): String? {
        awaitIdle()
        withTimeoutOrNull(readTimeout) { shell.shell(command).output }?.let { return it }
        if (!gate.busy) ping()
        return null
    }

    private suspend fun ping() {
        withTimeoutOrNull(pingTimeout) { shell.shell("echo") } ?: throw IOException("no answer to echo in $pingTimeout")
    }

    private class Fast(val cpu: Parsers.CpuTimes?, val memory: Parsers.Memory?, val netBytes: Long?, val at: ComparableTimeMark)

    private suspend fun readFast(): Fast? {
        val (stat, meminfo, netDev) = Commands.split(run(Commands.FAST) ?: return null, 3)
        return Fast(Parsers.cpuTimes(stat), Parsers.memory(meminfo), Parsers.netBytes(netDev), clock.markNow())
    }

    /** Null across a counter reset (an interface going down and up), never a negative speed. */
    private fun rate(prev: Fast, now: Fast): Long? {
        val bytes = (now.netBytes ?: return null) - (prev.netBytes ?: return null)
        val ms = (now.at - prev.at).inWholeMilliseconds
        return if (bytes < 0 || ms <= 0) null else bytes * 1000 / ms
    }

    /** The light read keeps [screenOn] from the last full one; only Advanced shows it. */
    private suspend fun readProcesses(level: ProcessLevel, packages: Map<Int, List<String>>, screenOn: Boolean?): Pair<List<AppProcess>, Boolean?>? {
        val (procs, screen) = if (level == ProcessLevel.FULL) {
            val (top, power) = Commands.split(run(Commands.MEDIUM) ?: return null, 2)
            Parsers.processes(top) to Parsers.screenOn(power)
        } else {
            (readPs() ?: return null) to screenOn
        }
        val known = packages.values.flatten().toSet()
        return procs.map { p -> AppProcess(p.pid, p.name, Parsers.owner(p, packages, known), p.cpuPercent, p.rssKb) } to screen
    }

    /** Set once `ps -o` answered without a single process row; plain `ps` from then on. */
    private var plainPs = false

    private suspend fun readPs(): List<Parsers.Process>? {
        if (!plainPs) {
            val out = run(Commands.LIGHT) ?: return null
            // A blank reply is a hiccup that skips the sample. Plain `ps` lists only the shell's own
            // processes on toybox, so it's only for a `ps` that answers but ignores `-o`.
            if (out.isBlank()) return null
            val procs = Parsers.psProcesses(out)
            if (procs.isNotEmpty()) return procs
            plainPs = true
        }
        return Parsers.psProcesses(run("ps") ?: return null)
    }

    private suspend fun readSlow(): Stats? {
        val (getprop, df, uptime, pm) = Commands.split(run(Commands.SLOW) ?: return null, 4)
        val props = Parsers.props(getprop)
        return Stats(
            cpuPercent = null,
            memory = null,
            storage = Parsers.storage(df),
            uptimeSeconds = Parsers.uptimeSeconds(uptime),
            model = props["ro.product.model"],
            manufacturer = props["ro.product.manufacturer"],
            brand = props["ro.product.brand"],
            androidVersion = props["ro.build.version.release"],
            sdk = props["ro.build.version.sdk"]?.toIntOrNull(),
            softwareMonth = Parsers.softwareMonth(props),
            packages = Parsers.byUid(Parsers.packages(pm)),
        )
    }
}
