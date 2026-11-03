// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import android.content.Context
import app.tellyscope.data.AndroidLauncherDownload
import app.tellyscope.data.LauncherPick
import app.tellyscope.data.UpdateCheck
import app.tellyscope.data.Updates
import java.io.File

// What the GitHub build has that the Play build doesn't (app/src/play/…/Store.kt): its own update check,
// and the Fire OS launcher downloaded from its developer's GitHub releases.

/** Every 30 minutes, whether GitHub has a newer release than [version]. Alpha builds install as app.tellyscope.alpha. */
internal fun updates(context: Context, version: String): Updates? =
    UpdateCheck(File(context.filesDir, "updates.properties"), version, alpha = context.packageName.endsWith(".alpha"))

/** Downloads a launcher, or checks a file the user picked. */
internal fun launcherPick(context: Context): LauncherPick = AndroidLauncherDownload(context)
