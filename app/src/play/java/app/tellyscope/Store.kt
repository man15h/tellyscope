// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.content.Context
import app.tellyscope.data.AndroidLauncherPick
import app.tellyscope.data.LauncherPick
import app.tellyscope.data.Updates

// The Play build's side of app/src/github/…/Store.kt: no update check and no downloads. Play updates the
// app, and on Fire OS the user downloads the launcher in the browser and picks the file.

@Suppress("UNUSED_PARAMETER")
internal fun updates(context: Context, version: String): Updates? = null

/** Checks a launcher file the user picked; downloads nothing. */
internal fun launcherPick(context: Context): LauncherPick = AndroidLauncherPick(context)
