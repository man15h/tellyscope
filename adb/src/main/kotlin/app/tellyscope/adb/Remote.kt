// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

/**
 * The TV's remote. Keys are a fixed list of Android key codes, so nothing the user does reaches
 * the shell line except text, which [typeCommand] quotes. [repeats] keys repeat while held.
 */
enum class RemoteKey(val code: Int, val repeats: Boolean = false) {
    UP(19, repeats = true),
    DOWN(20, repeats = true),
    LEFT(21, repeats = true),
    RIGHT(22, repeats = true),
    OK(23),
    BACK(4),
    HOME(3),
    MENU(82),
    PLAY_PAUSE(85),
    VOLUME_UP(24, repeats = true),
    VOLUME_DOWN(25, repeats = true),
    MUTE(164),
    /** Backspace in the focused text box. */
    DEL(67, repeats = true),
}

/** What [Remote.openUpdates] opened on the TV. */
enum class UpdateScreen {
    /** A screen with Check for updates: Android's update screen, or Fire OS's About. */
    UPDATES,
    /** Fire TV's Settings, where the user goes on to About. */
    FIRE_SETTINGS,
    /** Nothing on a Fire TV. */
    FIRE_NONE,
    /** Nothing. */
    NONE,
}

class Remote(private val session: AdbSession, private val keys: KeyHelper? = null) {

    /**
     * False when the TV answered with an error. Throws if the link is gone. Goes through [keys]
     * while its helper runs, and `input keyevent` when it can't.
     */
    suspend fun press(key: RemoteKey): Boolean =
        keys?.press(key.code) ?: (session.shell("input keyevent ${key.code}").exitCode == 0)

    /** [press] [times] over, stopping at the first key the TV didn't take. */
    suspend fun press(key: RemoteKey, times: Int): Boolean = (1..times).all { press(key) }

    /** The top of the TV's media volume, from `dumpsys audio`; null when it doesn't say. */
    suspend fun volumeMax(): Int? = musicMax(session.shell("dumpsys audio 2>/dev/null | grep -A12 '^- STREAM_MUSIC:'").output)

    /** Media plays through a fixed-volume output, so the volume keys can't reach the TV: see [musicFixed]. */
    suspend fun volumeFixed(): Boolean =
        musicFixed(session.shell("dumpsys audio 2>/dev/null | grep -e mFixedVolumeDevices -e '^- STREAM_MUSIC:' -A12").output)

    /**
     * Home that opens [launcher], the activity Android resolves Home to (see [Actions.currentHome]).
     * For Fire TV, whose Home key goes to Amazon's launcher whatever the default is. Null, a name
     * that isn't a plain component, or a TV that won't start it fall back to the Home key.
     */
    suspend fun home(launcher: String?): Boolean {
        if (launcher != null && COMPONENT.matches(launcher)) {
            val r = session.shell("${Commands.OPEN_HOME} '$launcher' 2>&1")
            if (r.exitCode == 0 && "Error" !in r.output) return true
        }
        return press(RemoteKey.HOME)
    }

    /**
     * Opens the TV's own system update screen. Fire OS has none behind Android's intent, so a
     * [fireOs] TV tries its About screen, which has Check for updates, then Fire TV's Settings.
     */
    suspend fun openUpdates(fireOs: Boolean): UpdateScreen = when {
        start("-a android.settings.SYSTEM_UPDATE_SETTINGS") -> UpdateScreen.UPDATES
        !fireOs -> UpdateScreen.NONE
        start("-a android.settings.DEVICE_INFO_SETTINGS") -> UpdateScreen.UPDATES
        FIRE_SETTINGS.any { start("-n $it") } -> UpdateScreen.FIRE_SETTINGS
        else -> UpdateScreen.FIRE_NONE
    }

    /**
     * `am start` with [args]. A build without the screen, or one that keeps it to Amazon's own
     * apps, says so in the output, and older `am` exits 0 even then, so both are checked.
     */
    private suspend fun start(args: String): Boolean {
        val r = session.shell("am start $args 2>&1")
        return r.exitCode == 0 && !r.output.contains("Error") && !r.output.contains("Exception")
    }

    /** Types [text] into whatever box has focus on the TV, through [keys] like [press]. Check [typeable] first. */
    suspend fun type(text: String): Boolean = keys?.type(text) ?: (session.shell(typeCommand(text)).exitCode == 0)

    /**
     * Turns the TV's picture off or back on, leaving the TV awake so the link stays up. Only the
     * key helper can: false when it isn't running or the TV refused. Throws if the link is gone.
     */
    suspend fun screen(on: Boolean): Boolean = keys?.screen(on) ?: false

    /**
     * Restarts the TV. adbd's `reboot:` service works for the shell user on stock builds; where a
     * build refuses it, `svc power reboot` goes through the power service instead. Returns false
     * only when the TV said no. A link that drops on the way counts as restarting.
     */
    suspend fun reboot(): Boolean {
        try {
            session.reboot()
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Refused, or the link went: the fallback tells which.
        }
        val r = try {
            session.shell("svc power reboot 2>&1")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return true
        }
        return r.exitCode == 0 && r.output.isBlank()
    }

    companion object {
        const val MAX_TEXT = 200

        /** Fire TV's Settings, from Amazon's launcher: Fire OS 6 and later, then Fire OS 5. */
        val FIRE_SETTINGS = listOf("com.amazon.tv.launcher/.ui.MainSettingsActivity", "com.amazon.tv.launcher/.ui.SettingsActivity")

        /** A volume tap moves about this share of the range, whatever the TV's own step count. */
        const val VOLUME_STEPS = 15

        /** `Max: 15` in the STREAM_MUSIC block of `dumpsys audio`. */
        fun musicMax(dump: String): Int? {
            val block = dump.substringAfter("- STREAM_MUSIC:", "").ifEmpty { return null }
            return Regex("""^\s*Max:\s*(\d+)""", RegexOption.MULTILINE).find(block)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
        }

        /**
         * Music plays through a fixed-volume output: `Devices: hdmi` in the STREAM_MUSIC block, whose
         * `400 (hdmi)` code is in `mFixedVolumeDevices`. A box on HDMI (a Fire TV stick) keeps its own
         * volume at full there and ignores the volume keys. Android 11 lists the fixed devices
         * (`0x1000,0x400`); 10 and older print one bitmask (`0x2c400`), so a device's bits are tested.
         */
        fun musicFixed(dump: String): Boolean {
            val block = dump.substringAfter("- STREAM_MUSIC:", "").ifEmpty { return false }
            val line = Regex("""mFixedVolumeDevices=(.*)""").find(dump)?.groupValues?.get(1).orEmpty()
            val fixed = Regex("""0x([0-9a-fA-F]+)""").findAll(line).map { it.groupValues[1].toLong(16) }.toSet()
            val devices = Regex("""^\s*Devices:\s*(.+)$""", RegexOption.MULTILINE).find(block)?.groupValues?.get(1).orEmpty()
                .split(Regex("""[\s,]+""")).filter { it.isNotEmpty() }
            val codes = Regex("""([0-9a-fA-F]+) \(([^)]+)\)""").findAll(block).associate { it.groupValues[2] to it.groupValues[1].toLong(16) }
            // A list names each device exactly; a lone value is the old bitmask, tested bit by bit.
            val listed = fixed.size > 1
            return devices.isNotEmpty() && devices.all { d ->
                codes[d]?.let { c -> c != 0L && fixed.any { if (listed) it == c else (it and c) == c } } == true
            }
        }

        /** Volume keys per tap: 1 on a 15-step TV, 3 on 50, 7 on 100. Never under 1. */
        fun volumeKeys(max: Int?): Int = ((max ?: VOLUME_STEPS) / VOLUME_STEPS.toDouble()).roundToInt().coerceAtLeast(1)

        /** `pkg/.Activity` or `pkg/pkg.Activity`; a nested class has a `$`, which the single quotes keep. */
        private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

        /**
         * What `input text` can type: printable ASCII only (it has no key for anything else), and
         * never `%s`, which it reads as a space.
         */
        fun typeable(text: String): Boolean =
            text.isNotEmpty() && text.length <= MAX_TEXT && text.all { it in ' '..'~' } && "%s" !in text

        /** `input text` reads `%s` as a space, and the whole thing is single-quoted for the shell. */
        fun typeCommand(text: String): String {
            require(typeable(text)) { "input text can't type this" }
            return "input text " + shellQuote(text.replace(" ", "%s"))
        }
    }
}
