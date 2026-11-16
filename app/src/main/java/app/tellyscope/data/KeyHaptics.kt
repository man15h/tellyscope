// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import java.io.File
import java.util.Properties

/**
 * The Remote's own settings, kept in [file]: whether a key press vibrates (on until the user turns
 * it off), whether the Screen off tip has been shown, and which [RemotePad] takes the D-pad's place.
 */
/** What sits in the D-pad's place on the Remote; [key] is how it's kept, none for the D-pad. */
enum class RemotePad(val key: String?) {
    DPAD(null),
    /** Swipes send arrows, a tap OK. */
    TOUCHPAD("touchpad"),
    /** Drag moves the TV's pointer through a virtual mouse. */
    POINTER("pointer"),
    /** The phone, sideways, as a game controller. */
    CONTROLLER("controller"),
}

class KeyHaptics(private val file: File) {
    @get:Synchronized @set:Synchronized
    var on: Boolean
        get() = props().getProperty("off") != "true"
        set(on) = store { if (on) remove("off") else setProperty("off", "true") }

    @get:Synchronized @set:Synchronized
    var screenOffTipSeen: Boolean
        get() = props().getProperty("screenOffTip") == "seen"
        set(seen) = store { if (seen) setProperty("screenOffTip", "seen") else remove("screenOffTip") }

    @get:Synchronized @set:Synchronized
    var pad: RemotePad
        get() = RemotePad.entries.firstOrNull { it.key != null && it.key == props().getProperty("pad") } ?: RemotePad.DPAD
        set(pad) = store { if (pad.key != null) setProperty("pad", pad.key) else remove("pad") }

    private fun store(change: Properties.() -> Unit) {
        val p = props().apply(change)
        runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { p.store(it, null) }
        }
    }

    private fun props() = Properties().apply {
        runCatching { file.takeIf { it.exists() }?.inputStream()?.use(::load) }
    }
}
