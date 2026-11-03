// SPDX-License-Identifier: GPL-3.0-only
// The update and offline icons are the Material Symbols "system_update" and "cloud_off" (Apache-2.0), see NOTICE.

package app.tellyscope.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tellyscope.R
import app.tellyscope.data.Update

// The GitHub build's update offer: this dialog and What's new's Update card. The Play build has empty
// stubs (app/src/play), so none of it is in that app.

/**
 * A newer release is out. Download APK opens the release's APK in the browser, which downloads it
 * for Android's installer; Check release opens its GitHub page.
 */
@Composable
fun UpdateDialog(update: Update, onDownload: () -> Unit, onRelease: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.update_title, update.version)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(boldSpans(dialogNotes(update.notes, stringResource(R.string.update_more_notes)).ifEmpty { stringResource(R.string.update_no_notes) }), style = MaterialTheme.typography.bodyMedium)
            }
        },
        // Three actions stack full width; without an APK this build can install, only its page is left.
        confirmButton = {
            Column(Modifier.fillMaxWidth()) {
                if (update.apk != null) {
                    Button(onClick = onDownload, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_download)) }
                    TextButton(onClick = onRelease, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_release)) }
                } else {
                    Button(onClick = onRelease, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_release)) }
                }
                TextButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_cancel)) }
            }
        },
    )
}

/**
 * release.yml's body as plain lines: the CHANGELOG part (prod) or the alpha line, without the
 * commit list, code ticks or the trailing PR links, capped at [max] lines, then [more]. `**` stays for [boldSpans].
 */
internal fun dialogNotes(body: String, more: String, max: Int = 12): String {
    val lines = body.substringBefore("### Commits").lines()
        .map { it.replace("`", "").replace(PR_LINK, "").trimEnd() }
        .filter { it.isNotBlank() }
    return (if (lines.size > max) lines.take(max) + more else lines).joinToString("\n")
}

private val PR_LINK = Regex("""\s*\[#\d+]\([^)]*\)\s*$""")

/** Each `**…**` pair becomes bold, stars removed; an unmatched `**` is dropped. */
internal fun boldSpans(text: String): AnnotatedString = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part ->
        // Odd parts sit inside a pair, unless the last one, whose opening `**` has no partner.
        if (i % 2 == 1 && i < parts.lastIndex) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}

/** "Tellyscope 0.14.0 is out · You have 0.13.0." Update opens the update check's dialog again. */
@Composable
internal fun UpdateCard(available: Update, installed: String, onUpdate: () -> Unit) {
    Card(Modifier.padding(top = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.whats_new_update_out, available.version), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(stringResource(R.string.whats_new_you_have, installed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = onUpdate) { Text(stringResource(R.string.whats_new_update)) }
        }
    }
}

/** An update is known, but its versions couldn't be read from GitHub. */
@Composable
internal fun NewerFailed() {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(CloudOff, contentDescription = null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.whats_new_newer_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val SystemUpdate = symbol(
    "SystemUpdate",
    "M280-40q-33 0-56.5-23.5T200-120v-720q0-33 23.5-56.5T280-920h400q33 0 56.5 23.5T760-840v124q18 7 29 22t11 34v80q0 19-11 34t-29 22v404q0 33-23.5 56.5T680-40H280Zm0-80h400v-720H280v720Zm0 0v-720 720Zm200-200 160-160-56-56-64 62v-166h-80v166l-64-62-56 56 160 160Z",
)

private val CloudOff = symbol(
    "CloudOff",
    "M792-56 686-160H260q-92 0-156-64T40-380q0-77 47.5-137T210-594q3-8 6-15.5t6-16.5L56-792l56-56 736 736-56 56ZM260-240h346L284-562q-2 11-3 21t-1 21h-20q-58 0-99 41t-41 99q0 58 41 99t99 41Zm185-161Zm419 191-58-56q17-14 25.5-32.5T840-340q0-42-29-71t-71-29h-60v-80q0-83-58.5-141.5T480-720q-27 0-52 6.5T380-693l-58-58q35-24 74.5-36.5T480-800q117 0 198.5 81.5T760-520q69 8 114.5 59.5T920-340q0 39-15 72.5T864-210ZM593-479Z",
)
