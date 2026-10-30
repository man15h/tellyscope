// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import app.tellyscope.R

/** One app Reset TV changes, with the name it shows. */
data class ResetApp(val packageName: String, val label: String)

/**
 * Home's part of a reset: [to], the TV's own launcher, is Home again in place of [from], a launcher
 * the user added. [service]: [from] holds Home with an accessibility service, which goes off.
 * [serviceOnly]: Fire OS, where [to] stays the default and the service alone holds Home for [from].
 */
data class ResetHome(val from: String, val fromLabel: String, val to: String, val toLabel: String, val service: Boolean = false, val serviceOnly: Boolean = false)

/**
 * What Reset TV found on the TV before anything changes: preinstalled apps to [turnOn] and to
 * [bringBack], and the [home] change, if any. [shippedOff] apps the maker shipped switched off
 * stay off. [ownHome] names the TV's own launcher, for "nothing to reset".
 */
data class ResetPlan(
    val turnOn: List<ResetApp> = emptyList(),
    val bringBack: List<ResetApp> = emptyList(),
    val home: ResetHome? = null,
    val shippedOff: Int = 0,
    val ownHome: String? = null,
) {
    val changes get() = turnOn.size + bringBack.size + (if (home != null) 1 else 0)
}

/** The three parts of a reset, run in this order: apps first, so the TV's own launcher is on before it takes Home. */
enum class ResetPart { TurnOn, BringBack, Home }

/** How far a running reset is: [done] of [of] in [part]. */
data class ResetProgress(val part: ResetPart, val done: Int, val of: Int)

/** A step that didn't work: the [app] it was for (none for Home) and the TV's [reason]. */
data class ResetFailure(val part: ResetPart, val app: ResetApp?, val reason: UiText)

/** History's record of a reset: what Undo turns off, removes and switches back. */
internal object ResetRecord {
    const val ON = "on"
    const val BACK = "back"
    const val FROM = "from"
    const val TO = "to"
    const val SERVICE = "service"

    fun label(changes: Int) = UiText.plural(R.plurals.data_history_reset, changes)

    fun list(previous: Map<String, String?>?, key: String) = previous?.get(key)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    /** How many changes [previous] holds: each app, plus Home (on Fire OS, only its service). */
    fun changes(previous: Map<String, String?>?) = list(previous, ON).size + list(previous, BACK).size + (if (previous?.get(TO) != null || previous?.get(SERVICE) != null) 1 else 0)
}
