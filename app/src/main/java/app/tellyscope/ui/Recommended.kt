// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import app.tellyscope.data.AppNames
import app.tellyscope.data.KnownLauncher
import app.tellyscope.data.SharedLists

/**
 * A launcher Install a home screen offers, from `launchers.json`: its name and a line
 * on what it's like.
 */
data class Recommended(
    val pkg: String,
    val name: String,
    val about: String,
    val accessibility: Boolean,
    /** Its whole entry, with what installing it from an APK takes. */
    val launcher: KnownLauncher = KnownLauncher(pkg, name, about),
)

/** In the picker's order: the launchers on `launchers.json` that have an `about` line, in file order. */
val RECOMMENDED: List<Recommended> get() =
    SharedLists.launchers.mapNotNull { l -> l.about?.let { Recommended(l.pkg, l.name, it, l.pkg in SharedLists.serviceLaunchers, l) } }

fun launcherName(pkg: String) = RECOMMENDED.firstOrNull { it.pkg == pkg }?.name ?: AppNames.fallback(pkg)

/** The launchers Fire OS offers, having no Play Store: the list's `fire_os` ones that say how to check the APK, in its order. */
fun fireLaunchers(recommended: List<Recommended> = RECOMMENDED) =
    recommended.filter { it.launcher.fireOs && it.launcher.signers.isNotEmpty() }

/** The first of [fireLaunchers], which Fire OS's picker opens on. */
fun fireLauncher(recommended: List<Recommended> = RECOMMENDED) = fireLaunchers(recommended).firstOrNull()
