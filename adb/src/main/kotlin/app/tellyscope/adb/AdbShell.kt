// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import okio.BufferedSink
import okio.BufferedSource
import okio.Sink
import okio.Source
import java.io.File

/** The one thing collectors and actions need from a connection. Faked in tests. */
fun interface AdbShell {
    /** Runs [command] on the device and returns stdout. Throws [java.io.IOException] if the link is gone. */
    suspend fun shell(command: String): ShellResult
}

data class ShellResult(val output: String, val exitCode: Int)

/**
 * Kadb's install and sync calls, as they are: blocking, and throwing with the device's answer in
 * the message. [Sideload] and [Files] run them off the main thread. Faked in tests.
 */
interface AdbTransfer {
    fun install(source: Source, size: Long, vararg options: String)
    fun installMultiple(apks: List<File>, vararg options: String)
    fun push(source: Source, remotePath: String, mode: Int, lastModifiedMs: Long)
    fun pull(sink: Sink, remotePath: String)
    fun list(remoteDir: String): List<RemoteEntry>
}

data class RemoteEntry(val name: String, val mode: Int, val size: Long, val mtimeSec: Long)

/** An open connection to one device. */
interface AdbSession : AdbShell, AdbTransfer, AutoCloseable {
    /** Writes [bytes] to [remotePath] on the device, mode 0644. Throws [java.io.IOException] if the link is gone. */
    suspend fun push(bytes: ByteArray, remotePath: String)

    /** Asks adbd to restart the device (the `reboot:` service, what `adb reboot` uses). */
    suspend fun reboot()

    /** Starts [command] with its stdin and stdout left open, for a helper that stays up. Throws [java.io.IOException] if the link is gone. */
    suspend fun exec(command: String): AdbPipe

    /** A shell-v2 `sh` for [TvShell], or null when the TV has no shell v2. Throws [java.io.IOException] if the link is gone. */
    suspend fun openShell(): ShellChannel? = null
}

/** A running command's stdin and stdout, with no terminal in between. Closing it ends the command's stdin. */
interface AdbPipe : AutoCloseable {
    val stdin: BufferedSink
    val stdout: BufferedSource
}
