// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.ui

import androidx.compose.runtime.Composable
import app.tellyscope.data.Update

// The Play build has no update check (Play updates it), so it never has an [Update] to offer. These
// stand in for app/src/github's dialog and What's new card, and draw nothing.

@Suppress("UNUSED_PARAMETER")
@Composable
fun UpdateDialog(update: Update, onDownload: () -> Unit, onRelease: () -> Unit, onCancel: () -> Unit) = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun UpdateCard(available: Update, installed: String, onUpdate: () -> Unit) = Unit

@Composable
internal fun NewerFailed() = Unit
