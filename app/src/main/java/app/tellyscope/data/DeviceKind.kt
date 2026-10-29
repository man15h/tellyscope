// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

/** What sort of box a TV is, for its icon. A guess from what the TV and the network report. */
enum class DeviceKind { Tv, Projector, FireTv, Stick }

/** Makers whose Android devices are all projectors. */
private val PROJECTOR_MAKERS = setOf("xgimi", "nebula", "dangbei", "formovie")

/** Fire TV models all start with AFT (AFTMM, AFTKA); a network name may just say so. */
private val FIRE_TV = Regex("^aft|fire[ -]?tv", RegexOption.IGNORE_CASE)

/** Other streaming sticks and boxes, by model or network name. */
private val STICK = Regex("chromecast|stick|streamer|shield", RegexOption.IGNORE_CASE)

/** Projector makers or "projector" in a name, then Amazon, then other sticks and boxes; anything else is a TV. */
fun deviceKind(d: Device): DeviceKind = kindOf(listOfNotNull(d.manufacturer, d.hit.maker), listOfNotNull(d.model, d.hit.hostname))

/** Amazon's launcher, on every Fire OS device: Fire TV Edition TVs too, whose maker may not say Amazon. */
const val FIRE_TV_LAUNCHER = "com.amazon.tv.launcher"

/** Fire OS: a Fire TV by name, or any TV with Amazon's launcher. No Play Store there. */
fun isFireOs(d: Device, apps: List<AppInfo>) = deviceKind(d) == DeviceKind.FireTv || apps.any { it.packageName == FIRE_TV_LAUNCHER }

/** The same guess for a debloat list, from its maker (see [listMakers]) and model name. */
fun listKind(e: ListEntry, maker: String): DeviceKind = kindOf(listOf(maker), listOf(e.name))

private fun kindOf(makers: List<String>, names: List<String>): DeviceKind {
    val words = makers.flatMap { it.lowercase().split(Regex("[^a-z]+")) }
    return when {
        words.any { it in PROJECTOR_MAKERS } || names.any { "projector" in it.lowercase() } -> DeviceKind.Projector
        "amazon" in words || names.any { FIRE_TV.containsMatchIn(it) } -> DeviceKind.FireTv
        names.any { STICK.containsMatchIn(it) } -> DeviceKind.Stick
        else -> DeviceKind.Tv
    }
}
