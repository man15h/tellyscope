// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

/** Where releases are published: the app's own GitHub repo. */
const val RELEASES = "https://github.com/man15h/tellyscope/releases"

/** A newer Tellyscope than the one running. [apk] is its release's APK link; null when the release has none this build can install. */
data class Update(val version: String, val notes: String, val url: String, val apk: String? = null)

/**
 * The app's own update check: `UpdateCheck`, in the GitHub build only (app/src/github). The Play build
 * has none, since Play updates it.
 */
interface Updates {
    /** An alpha build, which looks at `-alpha` pre-releases too. */
    val alpha: Boolean
    var enabled: Boolean

    /** The newest release past the running one. Null when off, checked recently, or on any failure. */
    suspend fun check(): Update?

    /** The released CHANGELOG sections past the running one, newest first. Null on any failure. */
    suspend fun newer(): List<Changelog.Release>?
}
