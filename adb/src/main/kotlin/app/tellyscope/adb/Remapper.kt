// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a press during Remap TV remote's learn mode turned out to be. */
sealed interface Learned {
    /** A button that may be mapped, by its Android key name (`PROG_GREEN`, `BUTTON_4`). */
    data class Key(val name: String) : Learned

    /** A button that may not be (Home, Volume, the D-pad); [name] is null when the TV has no name for it either. */
    data class Refused(val name: String?) : Learned

    /** No press arrived in time. */
    data object NoPress : Learned

    /** No press arrived, but another app came to the front: the TV acted on the button before the script saw it. */
    data object NotSent : Learned
}

/** Starting the script: running, or why not, in the TV's own words. */
sealed interface RemapStart {
    data object Started : RemapStart
    data class Failed(val detail: String) : RemapStart
}

/**
 * Remap TV remote's script (`app/src/helper`, `Remap`) on one TV. Unlike the key helper it runs on
 * its own, detached, so the mappings keep working after the phone leaves; it ends when the TV
 * powers off fully, and Tellyscope starts it again on the next connect. Everything goes through
 * files in [DIR]: see the script's class comment. [dex] is the helper jar bundled in the app's assets.
 */
class Remapper(private val session: AdbSession, private val dex: ByteArray) {
    /** Whether the script is up: its pid file names a live process with its own name. */
    suspend fun running(): Boolean = session.shell("$ALIVE && echo on").output.trim() == "on"

    /**
     * Stops any script already running (an older one, from an earlier Tellyscope), then sends the
     * jar and [map] and starts it, and waits for its pid. [RemapStart.Failed] carries the end of its
     * log, for Details.
     */
    suspend fun start(map: String): RemapStart {
        session.shell("$ALIVE && kill \$p; rm -rf $DIR; mkdir -p $DIR")
        session.push(dex, "$DIR/remap.jar")
        session.push(map.toByteArray(), "$DIR/map")
        val out = session.shell(
            "cd $DIR && (CLASSPATH=$DIR/remap.jar \$(command -v setsid) nohup app_process / --nice-name=$NAME $MAIN $DIR " +
                "</dev/null >$DIR/log 2>&1 &); i=0; while [ \$i -lt 75 ] && [ ! -s pid ]; do sleep 0.2; i=\$((i+1)); done; " +
                "if [ -s pid ]; then echo started; else tail -n 20 log 2>/dev/null; fi",
        ).output.trim()
        return if (out == "started") RemapStart.Started else RemapStart.Failed(out)
    }

    /** Ends the script, which lets go of every remote; [clear] also removes its directory and mappings. */
    suspend fun stop(clear: Boolean) {
        session.shell("$ALIVE && kill \$p; " + if (clear) "rm -rf $DIR" else "rm -f $DIR/pid $DIR/learn $DIR/learned")
    }

    /** Hands the running script new mappings; it reads them within half a second. */
    suspend fun setMap(map: String) {
        session.push(map.toByteArray(), "$DIR/map.tmp")
        session.shell("mv -f $DIR/map.tmp $DIR/map")
    }

    /**
     * Waits up to [seconds] for the next button pressed on any remote, through the running script.
     * Cancelling stops the wait on the TV too.
     */
    suspend fun learn(seconds: Int): Learned {
        val ticks = seconds * 10
        return try {
            RemapKeys.learned(
                session.shell(
                    "cd $DIR && rm -f learned && a=\$($FRONT); : > learn; i=0; " +
                        "while [ \$i -lt $ticks ] && [ ! -s learned ]; do sleep 0.1; i=\$((i+1)); done; rm -f learn; " +
                        "if [ -s learned ]; then cat learned; else b=\$($FRONT); [ \"\$a\" = \"\$b\" ] && echo none || echo moved; fi",
                ).output,
            )
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { session.shell("rm -f $DIR/learn") } }
            throw e
        }
    }

    /**
     * What the script runs to open [pkg]: its TV launcher activity, else its phone one, as
     * `package/class`; or `@package` when neither resolves, which the script opens with `monkey`.
     */
    suspend fun target(pkg: String): String {
        require(Actions.PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        for (category in listOf("LEANBACK_LAUNCHER", "LAUNCHER")) {
            val out = session.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.$category $pkg 2>/dev/null").output
            out.lineSequence().map { it.trim() }.lastOrNull { Actions.ACTIVITY.matches(it) }
                ?.takeIf { it.substringBefore('/') == pkg }?.let { return it }
        }
        return "@$pkg"
    }

    companion object {
        const val DIR = "/data/local/tmp/tellyscope-remap"
        const val NAME = "tellyscope-remap"
        const val MAIN = "app.tellyscope.helper.Remap"
        /** Learn mode's wait, the 15 s ring on the phone. */
        const val LEARN_SECONDS = 15
        /** Sets `p` to the script's pid; true only when that process is the script, not a pid reused after a restart. */
        private const val ALIVE = "p=\$(cat $DIR/pid 2>/dev/null); [ -n \"\$p\" ] && grep -q $NAME /proc/\$p/cmdline 2>/dev/null"
        /** The app in front, for telling a button the TV kept from no press at all. */
        private const val FRONT = "dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|ResumedActivity'"
    }
}

/** Remap TV remote's rules: which buttons may be mapped, what to call them, and the script's files. */
object RemapKeys {
    val COLOURS = listOf("PROG_RED", "PROG_GREEN", "PROG_YELLOW", "PROG_BLUE")

    /**
     * Keep in step with the script's `KeyLayout.ALLOWED`; a test checks they match. Never Home,
     * Power, Volume, Mute, the D-pad, OK, Back, the mic, Settings or Input: those stay the TV's.
     */
    val ALLOWED: Set<String> = (COLOURS + "AVR_POWER" + (1..16).map { "BUTTON_$it" } + (1..4).map { "APP_$it" }).toSet()

    /** Fire TV's app buttons: on the list, but not yet seen working on a Fire TV. */
    val FIRE_APP_BUTTONS = (1..4).map { "APP_$it" }.toSet()

    fun allowed(name: String) = name in ALLOWED

    /**
     * The streaming service printed on an app button, where the remote is known: Sony's Netflix,
     * YouTube, Prime Video and YouTube Music buttons, and Fire TV's. Null where it isn't, which
     * shows the key's own name. Google's remotes are left out: sources disagree on theirs.
     */
    fun brand(name: String, sony: Boolean, fire: Boolean): String? = when {
        fire -> mapOf("APP_1" to "Netflix", "APP_2" to "Prime Video", "APP_4" to "YouTube")[name]
        sony -> mapOf("BUTTON_4" to "Netflix", "BUTTON_14" to "YouTube", "BUTTON_2" to "Prime Video", "BUTTON_15" to "YouTube Music")[name]
        else -> null
    }

    /**
     * The script's `map` file: one `NAME\tACTION` line per mapped button, [actions] already
     * resolved ([Remapper.target], or `-` for Do nothing). Anything off [ALLOWED] is left out.
     */
    fun map(actions: Map<String, String>): String =
        actions.filterKeys(::allowed).entries.sortedBy { it.key }.joinToString("") { (k, v) -> "$k\t$v\n" }

    /** [Remapper.learn]'s output: a name, `!name` for a refused one, `none` or `moved`. */
    fun learned(output: String): Learned {
        val line = output.trim().lineSequence().lastOrNull()?.trim().orEmpty()
        return when {
            line == "moved" -> Learned.NotSent
            line.startsWith("!") -> Learned.Refused(line.drop(1).takeIf { NAME.matches(it) })
            NAME.matches(line) && allowed(line) -> Learned.Key(line)
            NAME.matches(line) -> Learned.Refused(line)
            else -> Learned.NoPress
        }
    }

    private val NAME = Regex("[A-Z0-9_]+")
}
