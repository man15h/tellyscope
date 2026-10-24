// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import okio.Sink
import okio.Source

/** The two shared folders Tellyscope reads and writes. Nothing outside them. */
enum class TvFolder(val path: String) {
    DOWNLOAD("/sdcard/Download"),
    MOVIES("/sdcard/Movies"),
}

data class TvFile(val name: String, val size: Long, val modifiedMillis: Long)

/** Phone ↔ TV file copies over the ADB sync service. */
class Files(private val session: AdbSession) {

    /** Plain files in [folder], newest first. A missing folder is an empty one. */
    suspend fun list(folder: TvFolder): List<TvFile> = runTransfer { _ ->
        session.list(folder.path)
            .filter { it.mode and S_IFMT == S_IFREG && !it.name.startsWith(".") }
            .map { TvFile(it.name, it.size, it.mtimeSec * 1000) }
            .sortedByDescending { it.modifiedMillis }
    }

    /**
     * Copies [source] into [folder] as [name], or "name (1).ext" if that's taken, so nothing on the
     * TV is ever overwritten. Returns the name it was saved under. A cancelled or failed copy deletes
     * the partial file, so a retry doesn't land as "name (1)" beside a truncated one.
     */
    suspend fun push(
        source: Source,
        size: Long?,
        name: String,
        folder: TvFolder,
        onProgress: (Progress) -> Unit = {},
    ): String {
        // Every entry counts as taken, hidden files and folders too, not just what [list] shows.
        val taken = runTransfer { _ -> session.list(folder.path).map { it.name }.toSet() }
        val target = uniqueName(safeName(name), taken)
        val path = "${folder.path}/$target"
        runTransfer(cleanUp = { runCatching { session.shell("rm -f ${shellQuote(path)}") } }) { stopped ->
            session.push(source.counting(size, stopped, onProgress), path, FILE_MODE, System.currentTimeMillis())
        }
        return target
    }

    /** Copies [file] from [folder] into [sink]. On cancel the caller deletes what it was writing to. */
    suspend fun pull(folder: TvFolder, file: TvFile, sink: Sink, onProgress: (Progress) -> Unit = {}) {
        require(safeName(file.name) == file.name) { "not a plain file name: ${file.name}" }
        runTransfer { stopped -> session.pull(sink.counting(file.size, stopped, onProgress), "${folder.path}/${file.name}") }
    }

    companion object {
        private const val S_IFMT = 0xF000
        private const val S_IFREG = 0x8000
        private const val FILE_MODE = 420 // 0644

        /** One path segment: no separators, no control characters, never "." or "..". */
        fun safeName(name: String): String {
            val cleaned = name.map { if (it == '/' || it == '\\' || it.code < 0x20) '_' else it }.joinToString("").trim()
            return cleaned.takeUnless { it.isEmpty() || it.all { c -> c == '.' } } ?: "file"
        }

        /** "clip.mp4" → "clip (1).mp4" → "clip (2).mp4" until it's free. */
        fun uniqueName(name: String, taken: Set<String>): String {
            if (name !in taken) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.substring(0, dot)
            val ext = name.substring(dot)
            return generateSequence(1) { it + 1 }.map { "$stem ($it)$ext" }.first { it !in taken }
        }
    }
}
