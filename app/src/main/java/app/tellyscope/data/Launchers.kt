// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.Protected
import app.tellyscope.adb.ProtectedReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A third-party launcher Tellyscope knows, from `launchers.json`. [about] is the line Install a home
 * screen shows; a launcher without one isn't offered there.
 *
 * [accessibility]: it keeps Home with its own accessibility service, for TVs whose Home button opens
 * their own launcher whatever the default is, so Make Home turns that service on, and Undo or a switch
 * away from it turns it off. The value is the minimum version that has the service: an older copy, or
 * one whose versionName can't be read, isn't offered it ([hasServiceFrom]). An accessibility service
 * can read the screen, so only while `data/` passes its signature check may it go on
 * ([SharedLists.serviceLaunchers]); off works either way ([SharedLists.listedServiceLaunchers]).
 *
 * Installing it from an APK rather than the Play Store: [signers] are the SHA-256s of the only
 * certificates it may be signed with (empty: never from an APK), [maxBytes] is past any real copy of
 * it, and [releases] is its developer's GitHub releases page, the only place the GitHub build
 * downloads it from, and [apk] the asset to take from a release that carries more than one APK. [fireOs]: a
 * launcher Fire OS offers, having no Play Store.
 *
 * [icon] is an `https` address on its developer's own page ([LauncherImages]): its app icon. [screenshot] is an image
 * in `data/images/` and its sha256 ([SharedLists.screenshot]): its home screen as it first comes up, shown on the chosen
 * row. Its hash sits here rather than in the index, which covers it through this file's.
 * [credit] says where the screenshot comes from when it isn't ours, under it.
 */
data class KnownLauncher(
    val pkg: String,
    val name: String,
    val about: String? = null,
    val signers: Set<String> = emptySet(),
    val maxBytes: Long = DEFAULT_MAX_MB shl 20,
    val releases: String? = null,
    val fireOs: Boolean = false,
    val accessibility: String? = null,
    val apk: String? = null,
    val icon: String? = null,
    val screenshot: ListFile? = null,
    val credit: String? = null,
) {
    /** It, signed only by its developer: [signers] are the SHA-256s of an APK's certificates. */
    fun accepts(packageName: String, signers: Set<String>) =
        packageName == pkg && signers.isNotEmpty() && this.signers.isNotEmpty() && this.signers.containsAll(signers)
}

private const val DEFAULT_MAX_MB = 64L
private const val MAX_MB = 256L
private val SIGNER = Regex("[0-9a-f]{64}")
private val CHECKED = Regex("[0-9][0-9A-Za-z.+-]{0,31}")
// "." and ".." are not repo names; they would walk the URL out of the repo.
private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}\\.apk")
/** An image's name in `data/images/`, without `.png`: the launcher's name in lowercase, like `projectivy`. */
internal val IMAGE_NAME = Regex("[a-z0-9][a-z0-9-]{0,39}")
private const val IMAGE_URL_MAX = 500

/** [url] as an image address the app may fetch: `https`, with a host, and no user:password, else null. */
internal fun imageUrl(url: String?): String? {
    if (url == null || url.length > IMAGE_URL_MAX) return null
    val u = try {
        java.net.URI(url)
    } catch (_: java.net.URISyntaxException) {
        return null
    }
    return url.takeIf { u.scheme == "https" && !u.host.isNullOrEmpty() && u.rawUserInfo == null }
}
private val RELEASES_PAGE = Regex("https://github\\.com/[A-Za-z0-9-]+/(?!\\.\\.?/)[A-Za-z0-9._-]+/releases")

/**
 * Whether [installed], a launcher's versionName on the TV, is [minimum] or newer: [minimum] is its
 * [KnownLauncher.accessibility], the first version with the service. Compared by their leading
 * numbers, so `4.72-beta` counts as `4.72`; false when either has none, as for a TV that names no version.
 */
fun hasServiceFrom(minimum: String, installed: String?): Boolean {
    val need = versionParts(minimum) ?: return false
    val have = installed?.let(::versionParts) ?: return false
    for (i in 0 until maxOf(need.size, have.size)) {
        val c = have.getOrElse(i) { 0 }.compareTo(need.getOrElse(i) { 0 })
        if (c != 0) return c > 0
    }
    return true
}

private val VERSION_PARTS = Regex("^\\d+(?:\\.\\d+)*")

private fun versionParts(v: String): List<Long>? =
    VERSION_PARTS.find(v)?.value?.split('.')?.map { it.toLongOrNull() ?: return null }

/** Empty unless it is schema 1; entries that can't be read or aren't package names are dropped. */
fun parseLaunchers(text: String): List<KnownLauncher> = try {
    val o = jsonObject(text)
    if (o.optInt("schema") != 1) emptyList() else o.getJSONArray("launchers").objectsOrSkip { l ->
        KnownLauncher(
            pkg = l.getString("package"),
            name = plainText(l.getString("name"), NAME_MAX),
            about = l.optStringOrNull("about")?.let { plainText(it, NOTE_MAX) }?.ifEmpty { null },
            // A signer that isn't a SHA-256 drops them all: a half-read list must not pass a different APK.
            signers = l.optJSONArray("signers")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?.takeIf { s -> s.all(SIGNER::matches) }?.toSet().orEmpty(),
            maxBytes = l.optLong("max_mb", DEFAULT_MAX_MB).coerceIn(1, MAX_MB) shl 20,
            releases = l.optStringOrNull("releases")?.takeIf(RELEASES_PAGE::matches),
            fireOs = l.optBoolean("fire_os"),
            accessibility = (l.opt("accessibility") as? String)?.takeIf(CHECKED::matches),
            apk = l.optStringOrNull("apk")?.takeIf(APK_NAME::matches),
            icon = imageUrl(l.optStringOrNull("icon")),
            screenshot = l.optJSONObject("screenshot")?.let { ListFile(it.optString("file"), it.optString("sha256")) }
                ?.takeIf { IMAGE_FILE.matches(it.file) && SHA256.matches(it.sha256) },
            credit = l.optStringOrNull("credit")?.let { plainText(it, NAME_MAX) }?.ifEmpty { null },
        ).takeIf { PACKAGE.matches(it.pkg) && it.name.isNotEmpty() }
    }
} catch (_: org.json.JSONException) {
    emptyList()
}

/**
 * Package to display name, from `labels.json`; its groups are only there for
 * people reading the file. Empty unless it is schema 1; names under something that isn't a package
 * are dropped.
 */
fun parseLabels(text: String): Map<String, String> = try {
    val o = jsonObject(text)
    if (o.optInt("schema") != 1) emptyMap() else buildMap {
        o.getJSONArray("groups").objectsOrSkip { it.getJSONObject("labels") }.forEach { labels ->
            for (pkg in labels.keys()) {
                if (pkg != "android" && !PACKAGE.matches(pkg)) continue
                labels.optStringOrNull(pkg)?.let { plainText(it, NAME_MAX) }?.ifEmpty { null }?.let { put(pkg, it) }
            }
        }
    }
} catch (_: org.json.JSONException) {
    emptyMap()
}

/** The reasons `protected.json` may give; a list can't claim the home screen or the TV's own states. */
private val LIST_REASONS = listOf(
    ProtectedReason.SYSTEM, ProtectedReason.SETTINGS, ProtectedReason.PLAY_SERVICES, ProtectedReason.KEYBOARD,
    ProtectedReason.TV_INPUTS_REMOTE, ProtectedReason.SETUP_WIZARD, ProtectedReason.UPDATES,
).associateBy { it.name.lowercase() }

/**
 * Package to why it stays on, from `protected.json`; its groups (one per maker) are only there for
 * people reading the file. A reason this app doesn't know still protects, as [ProtectedReason.SYSTEM].
 * Empty unless it is schema 1; anything under a name that isn't a package is dropped.
 */
fun parseProtected(text: String): Map<String, ProtectedReason> = try {
    val o = jsonObject(text)
    if (o.optInt("schema") != 1) emptyMap() else buildMap {
        o.getJSONArray("groups").objectsOrSkip { it.getJSONObject("packages") }.forEach { packages ->
            for (pkg in packages.keys()) {
                if (PACKAGE.matches(pkg)) put(pkg, LIST_REASONS[packages.optString(pkg)] ?: ProtectedReason.SYSTEM)
            }
        }
    }
} catch (_: org.json.JSONException) {
    emptyMap()
}

/** The launcher, app name, streaming and protected lists, as [SharedLists] holds them. */
data class Shared(
    val launchers: List<KnownLauncher> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val streaming: List<StreamingService> = emptyList(),
    val protected: Map<String, ProtectedReason> = emptyMap(),
    /**
     * `data/`'s address ([DeviceLists.base]) while it fails its check ([DeviceLists.verified]): a bad or missing
     * signature, an older version, or a file that doesn't match the index. Null once it passes.
     */
    val unverified: String? = null,
)

/**
 * The lists anyone can change without an app release: launchers, app names and streaming services
 * from `data/` ([DATA_BASE]), and the protected list and the Remote's logos from the list source next
 * to the debloat lists ([DeviceLists]), which may be the user's own. Each is the last cached copy at
 * launch, then the downloaded one; a list that can't be had keeps what was there. The app bundles
 * none, so before the first fetch they are empty; a build with `fixtures/` among its Java resources
 * (the tests) starts from those instead. The protected list goes on to [Protected.extra], on top of
 * the built-in one. `data/` that fails its signature check is still used, but [unverified] says so, and
 * nothing installs from it without asking.
 */
object SharedLists {
    private val _state = MutableStateFlow(seed())
    val state: StateFlow<Shared> = _state.asStateFlow()
    val launchers get() = _state.value.launchers
    val labels get() = _state.value.labels
    val streaming get() = _state.value.streaming
    /** `data/`'s address while it fails its check, or null: installing a launcher from it asks first. */
    val unverified get() = _state.value.unverified
    /** Launchers whose accessibility service may go on ([KnownLauncher.accessibility]): none while `data/` fails its check. */
    val serviceLaunchers: Set<String> get() = if (unverified != null) emptySet() else listedServiceLaunchers
    /**
     * Every launcher with [KnownLauncher.accessibility], signed or not: for finding the one whose service holds Home and
     * turning that service off. Unsigned, the worst a list can do with it is turn off the service of a launcher that was Home.
     */
    val listedServiceLaunchers: Set<String> get() = launchers.filter { it.accessibility != null }.mapTo(HashSet()) { it.pkg }
    /** [pkg]'s [KnownLauncher.accessibility], the minimum version that has the service; null when it has none. */
    fun serviceFrom(pkg: String): String? = launchers.firstOrNull { it.pkg == pkg }?.accessibility

    @Volatile private var data: DeviceLists? = null
    @Volatile private var source: DeviceLists? = null

    /** The cached lists from [data] and [lists], then downloaded; [fresh] drops the old protected list first, for a new source. */
    suspend fun load(data: DeviceLists, lists: DeviceLists, fresh: Boolean = false) {
        this.data = data
        source = lists
        if (fresh) reset(_state.value.copy(protected = emptyMap()))
        apply(data.cachedVerified(), data::cachedShared, lists::cachedShared)
        refresh()
    }

    /** The lists again; one already downloaded is read from the cache, so this is cheap once online. */
    suspend fun refresh() {
        val data = data ?: return
        val source = source ?: return
        apply(data.verified(), data::shared, source::shared)
    }

    /** A logo's PNG, at four times its size in dp; null until it can be had. */
    suspend fun logo(name: String): ByteArray? = source?.logo(name)

    /** Where launchers' icons are cached; set once by the app, null in tests that don't. */
    @Volatile var images: LauncherImages? = null

    /**
     * A launcher's icon from its developer's page ([KnownLauncher.icon]); null until it can be had, and
     * while `data/` fails its check ([unverified]), so a list that isn't ours can't send the phone to any host it names.
     */
    suspend fun image(url: String): ByteArray? = if (unverified != null) null else images?.get(url)

    /** A launcher's screenshot from `data/images/` ([KnownLauncher.screenshot]); null until it can be had. */
    suspend fun screenshot(file: ListFile): ByteArray? = data?.image(file)

    /**
     * Launchers, labels and streaming from [data]; only the protected list from [source]. [verified] is worked out
     * first and set in the same update, so new launchers never sit behind an old [unverified].
     */
    private suspend fun apply(verified: Boolean, data: suspend (String) -> String?, source: suspend (String) -> String?) {
        val base = this.data?.base
        val launchers = data("launchers")?.let(::parseLaunchers)
        val labels = data("labels")?.let(::parseLabels)
        val streaming = data("streaming")?.let(::parseStreaming)
        val protected = source("protected")?.let(::parseProtected)
        _state.update { now ->
            now.copy(
                launchers = launchers?.ifEmpty { null } ?: now.launchers,
                labels = labels?.ifEmpty { null } ?: now.labels,
                streaming = streaming?.ifEmpty { null } ?: now.streaming,
                protected = protected?.ifEmpty { null } ?: now.protected,
                unverified = if (verified) null else base,
            )
        }
        Protected.extra = _state.value.protected
    }

    /** Back to the lists it starts with, and no list sources; for tests. */
    internal fun reseed() {
        data = null
        source = null
        reset(seed())
    }

    private fun reset(shared: Shared) {
        _state.value = shared
        Protected.extra = shared.protected
    }

    private fun seed() = Shared(
        launchers = resource("/fixtures/launchers.json")?.let { parseLaunchers(it.decodeToString()) }.orEmpty(),
        labels = resource("/fixtures/labels.json")?.let { parseLabels(it.decodeToString()) }.orEmpty(),
        streaming = resource("/fixtures/streaming.json")?.let { parseStreaming(it.decodeToString()) }.orEmpty(),
    )

    private fun resource(path: String): ByteArray? =
        SharedLists::class.java.getResourceAsStream(path)?.use { it.readBytes() }
}
