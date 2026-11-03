// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.adb.Protected
import app.tellyscope.adb.ProtectedReason
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Launchers that keep Home with their own accessibility service, for TVs whose Home button opens
 * their own launcher whatever the default is, so Make Home turns that service on, and Undo or a
 * switch away from it turns it off. Each with the version whose manifest was read. Built in, never
 * fetched: an accessibility service can read the screen, so only a list reviewed here may turn one on.
 */
val ACCESSIBILITY_LAUNCHERS = mapOf(
    "com.spocky.projengmenu" to "4.72",
    "com.overdevs.at4k" to "1.2",
    "com.spauldhaliwal.dispatch" to "1.0.175",
)

/**
 * A third-party launcher Tellyscope knows, from `launchers.json`. [accessibility] is whether it is
 * one of [ACCESSIBILITY_LAUNCHERS], never what the list says. [about] is the line Install a home
 * screen shows; a launcher without one isn't offered there.
 *
 * Installing it from an APK rather than the Play Store: [signers] are the SHA-256s of the only
 * certificates it may be signed with (empty: never from an APK), [maxBytes] is past any real copy of
 * it, and [releases] is its developer's GitHub releases page, the only place the GitHub build
 * downloads it from. [fireOs]: the launcher Fire OS installs, having no Play Store.
 */
data class KnownLauncher(
    val pkg: String,
    val name: String,
    val about: String? = null,
    val signers: Set<String> = emptySet(),
    val maxBytes: Long = DEFAULT_MAX_MB shl 20,
    val releases: String? = null,
    val fireOs: Boolean = false,
) {
    val accessibility get() = pkg in ACCESSIBILITY_LAUNCHERS

    /** It, signed only by its developer: [signers] are the SHA-256s of an APK's certificates. */
    fun accepts(packageName: String, signers: Set<String>) =
        packageName == pkg && signers.isNotEmpty() && this.signers.isNotEmpty() && this.signers.containsAll(signers)
}

private const val DEFAULT_MAX_MB = 64L
private const val MAX_MB = 256L
private val SIGNER = Regex("[0-9a-f]{64}")
// "." and ".." are not repo names; they would walk the URL out of the repo.
private val RELEASES = Regex("https://github\\.com/[A-Za-z0-9-]+/(?!\\.\\.?/)[A-Za-z0-9._-]+/releases")

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
            releases = l.optStringOrNull("releases")?.takeIf(RELEASES::matches),
            fireOs = l.optBoolean("fire_os"),
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
    /** The user's own list these came from ([DeviceLists.base]), or null for the default source. */
    val list: String? = null,
)

/**
 * The lists anyone can change without an app release: launchers, app names and streaming services
 * from `data/` ([DATA_BASE]), and the protected list and the Remote's logos from the list source next
 * to the debloat lists ([DeviceLists]), which may be the user's own. Each is the last cached copy at
 * launch, then the downloaded one; a list that can't be had keeps what was there. The app bundles
 * none, so before the first fetch they are empty; a build with `fixtures/` among its Java resources
 * (the tests) starts from those instead. The protected list goes on to [Protected.extra], on top of
 * the built-in one.
 */
object SharedLists {
    private val _state = MutableStateFlow(seed())
    val state: StateFlow<Shared> = _state.asStateFlow()
    val launchers get() = _state.value.launchers
    val labels get() = _state.value.labels
    val streaming get() = _state.value.streaming
    /** The user's own list the launchers came from, or null for the default: installing from it asks first. */
    val list get() = _state.value.list

    @Volatile private var data: DeviceLists? = null
    @Volatile private var source: DeviceLists? = null

    /** The cached lists from [data] and [lists], then downloaded; [fresh] drops the old protected list first, for a new source. */
    suspend fun load(data: DeviceLists, lists: DeviceLists, fresh: Boolean = false) {
        this.data = data
        source = lists
        if (fresh) reset(_state.value.copy(protected = emptyMap()))
        _state.update { it.copy(list = lists.custom()?.let { lists.base }) }
        apply(data::cachedShared, lists::cachedShared)
        refresh()
    }

    /** The lists again; one already downloaded is read from the cache, so this is cheap once online. */
    suspend fun refresh() {
        val data = data ?: return
        val source = source ?: return
        apply(data::shared, source::shared)
    }

    /** A logo's PNG, at four times its size in dp; null until it can be had. */
    suspend fun logo(name: String): ByteArray? = source?.logo(name)

    /** Launchers, labels and streaming from [data]; only the protected list from [source]. */
    private suspend fun apply(data: suspend (String) -> String?, source: suspend (String) -> String?) {
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
