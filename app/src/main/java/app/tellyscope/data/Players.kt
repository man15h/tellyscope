// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.Progress

/**
 * The player Tellyscope offers when nothing on the TV plays a file: VLC, from the Play Store, or, in the GitHub
 * build, from VideoLAN's own downloads. Its APK must be signed with VideoLAN's certificate, the one in every build
 * on get.videolan.org (v1, v2 and v3 signatures alike, no rotation). VLC's mirrors are other people's servers, so
 * that check, not the address, is what makes a download VLC.
 */
val PLAYER = KnownLauncher(
    pkg = "org.videolan.vlc",
    name = "VLC",
    signers = setOf("c8768d2cea0c4b622e419b4b4715981946821e4ebc035fb41776cad395a7f68e"),
    maxBytes = 128L shl 20,
)

/** VideoLAN's downloads of [PLAYER]: `last/` lists the latest build for each CPU type, a folder per version the rest. */
const val PLAYER_DOWNLOADS = "https://get.videolan.org/vlc-android/"

/** What a file in the Files tab is, by its extension: only these get Open on TV. */
enum class MediaKind { Video, Audio, Image }

/** A file's [MediaKind] and the MIME type the TV is asked to open it as; null for anything else. */
fun media(name: String): Pair<MediaKind, String>? {
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = MEDIA[ext] ?: return null
    val kind = when (mime.substringBefore('/')) {
        "image" -> MediaKind.Image
        "audio" -> MediaKind.Audio
        else -> MediaKind.Video
    }
    return kind to mime
}

private val MEDIA = mapOf(
    "mp4" to "video/mp4", "m4v" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
    "mov" to "video/quicktime", "ts" to "video/mp2t", "m2ts" to "video/mp2t", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
    "wmv" to "video/x-ms-wmv", "flv" to "video/x-flv", "3gp" to "video/3gpp",
    "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac", "ogg" to "audio/ogg",
    "opus" to "audio/ogg", "wav" to "audio/x-wav", "wma" to "audio/x-ms-wma",
    "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
    "bmp" to "image/bmp", "heic" to "image/heic",
)

/**
 * Packages that answer VIEW for every type without opening anything: Google TV's stand-ins for apps the TV
 * leaves out. They never count.
 */
private val STUBS = setOf("com.google.android.tv.frameworkpackagestubs")

/**
 * Apps that open a video or a song but aren't made to play one: galleries and browsers. A player still goes first,
 * but a file only they open opens in them rather than offering one. Vendors add their own; this grows as TVs show them.
 */
private val NOT_PLAYERS = setOf(
    "com.android.gallery3d",
    "com.google.android.apps.photos",
    "com.internet.tvbrowser",
    "com.android.browser",
    "com.android.chrome",
    "com.amazon.cloud9",
    "org.mozilla.firefox",
    "org.mozilla.tv.firefox",
)

/** Who on the TV opens a file: [players] play it; [others] only open it. */
data class Handlers(val players: List<String>, val others: List<String>)

/**
 * [handlers], the TV's answer for a file of [kind] in its order, split into [Handlers]. For a picture, a gallery or a
 * browser is as good as anything, so only the stubs are left out.
 */
fun handlers(kind: MediaKind, handlers: List<String>): Handlers {
    val real = handlers.filter { it !in STUBS }
    return if (kind == MediaKind.Image) Handlers(real, emptyList()) else Handlers(real.filter { it !in NOT_PLAYERS }, real.filter { it in NOT_PLAYERS })
}

/** VideoLAN's latest build of VLC for one CPU type: [page] is the folder it's listed in, [sha256] its listed hash. */
data class PlayerRelease(val version: String, val abi: String, val page: String, val apk: String, val size: Long, val sha256: String)

/** The GitHub build's way to the player on a TV with no store, or Download directly beside the Play Store. Faked in tests. */
interface PlayerDownload {
    /** The latest build for the first of [abis] VideoLAN has one for. Throws when there's none or no network. */
    suspend fun latestPlayer(abis: List<String>): PlayerRelease

    /** Downloads [release], checks its hash and that VideoLAN signed it. Throws [WrongApk], or IOException on a failed download. */
    suspend fun fetchPlayer(release: PlayerRelease, onProgress: (Progress) -> Unit): PickedApp
}
