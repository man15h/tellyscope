// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import app.tellyscope.data.AppNames
import app.tellyscope.data.SharedLists

/**
 * A launcher Install a home screen offers, from `launchers.json`: its name and a line
 * on what it's like.
 */
data class Recommended(val pkg: String, val name: String, val about: String, val accessibility: Boolean)

/** In the picker's order: the launchers on `launchers.json` that have an `about` line, in file order. */
val RECOMMENDED: List<Recommended> get() =
    SharedLists.launchers.mapNotNull { l -> l.about?.let { Recommended(l.pkg, l.name, it, l.accessibility) } }

fun launcherName(pkg: String) = RECOMMENDED.firstOrNull { it.pkg == pkg }?.name ?: AppNames.fallback(pkg)
