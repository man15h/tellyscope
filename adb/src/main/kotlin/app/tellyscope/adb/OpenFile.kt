// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

/**
 * A file in one of the shared folders, opened on the TV in an app there. The shell's `am` sends a `file://`
 * address as it is: Android's check against them runs in the sending app, and `am` isn't one.
 */
class OpenFile(private val shell: AdbShell) {

    /** The packages that answer VIEW for [name] in [folder] as [mime], in the TV's order. */
    suspend fun handlers(folder: TvFolder, name: String, mime: String): List<String> {
        val out = shell.shell("cmd package query-activities --brief -a android.intent.action.VIEW ${view(folder, name, mime)} 2>/dev/null").output
        return out.lineSequence().map(String::trim).filter { Actions.ACTIVITY.matches(it) }.map { it.substringBefore('/') }.distinct().toList()
    }

    /**
     * Lets [pkg] read the shared folders without asking on the TV: the media permissions from Android 13, external
     * storage before it, and all files, for subtitles beside a video. A permission [pkg] doesn't ask for is refused
     * by the TV and skipped.
     */
    suspend fun allowReading(pkg: String) {
        require(Actions.PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        val grants = READ_PERMISSIONS.joinToString("; ") { "pm grant $pkg $it 2>/dev/null" }
        shell.shell("$grants; appops set $pkg MANAGE_EXTERNAL_STORAGE allow 2>/dev/null; true")
    }

    /** Opens [name] in [pkg], or in whatever the TV picks when null. `-W` waits, so a refusal prints an Error. */
    suspend fun open(folder: TvFolder, name: String, mime: String, pkg: String?): Boolean {
        require(pkg == null || Actions.PACKAGE.matches(pkg)) { "not a package name: $pkg" }
        val r = shell.shell("am start -W -a android.intent.action.VIEW ${view(folder, name, mime)}${pkg?.let { " -p $it" }.orEmpty()} 2>&1")
        return r.exitCode == 0 && "Error" !in r.output
    }

    /** The TV's CPU types, best first: which build of an app it runs. */
    suspend fun abis(): List<String> =
        shell.shell("getprop ro.product.cpu.abilist").output.trim().split(',').map(String::trim).filter { ABI.matches(it) }

    private fun view(folder: TvFolder, name: String, mime: String): String {
        require(Files.safeName(name) == name) { "not a plain file name: $name" }
        require(MIME.matches(mime)) { "not a MIME type: $mime" }
        return "-d ${shellQuote(fileUri(folder, name))} -t $mime"
    }

    companion object {
        private val READ_PERMISSIONS = listOf(
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_EXTERNAL_STORAGE",
        )
        private val MIME = Regex("[a-z]+/[a-z0-9.+-]+")
        private val ABI = Regex("[a-z0-9_-]+")

        /** `file://` and the path, each byte outside the unreserved set and `/` percent-encoded. */
        fun fileUri(folder: TvFolder, name: String): String =
            "file://" + "${folder.path}/$name".encodeToByteArray().joinToString("") { b ->
                val c = (b.toInt() and 0xff).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~/") c.toString() else "%%%02X".format(b.toInt() and 0xff)
            }
    }
}
